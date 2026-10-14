"""
Minimal LLM clients for the retrieval-augmented engine, using only the standard library.

Gemini is the default: the project already uses it, and the published Nagamese
retrieval result (WMT26) used Gemini 2.5 Flash. Gemini 2.5 models now serve only
projects that used them before, so the default is the current stable Flash model.
The OpenAI-compatible client covers OpenAI, OpenRouter, Groq and local servers
such as Ollama or LM Studio.

Free Gemini keys get a small daily allowance per model (20 requests a day for
gemini-3.8-flash in October 2026). When a model's allowance runs out, the client
rests that model until Google says it resets and answers with the next model in
its list, which has an allowance of its own.
"""

from __future__ import annotations

import json
import re
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Protocol

from ..engine_api import EngineError, EngineUnavailableError

DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"
DEFAULT_GEMINI_FALLBACKS = ("gemini-3.5-flash-lite",)
GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"


class RateLimitedError(EngineUnavailableError):
    """The provider refused for quota reasons. retry_after is in seconds, when the provider says."""

    def __init__(self, message: str, retry_after: float | None = None, daily: bool = False) -> None:
        super().__init__(message)
        self.retry_after = retry_after
        self.daily = daily


def _quota_details(detail: str) -> tuple[float | None, bool]:
    """Seconds until the limit resets, and whether it is a daily limit, from a Google error body."""
    try:
        details = json.loads(detail).get("error", {}).get("details", [])
    except (ValueError, AttributeError):
        return None, False
    retry_after, daily = None, False
    for item in details:
        delay = re.fullmatch(r"([0-9.]+)s", str(item.get("retryDelay", "")))
        if delay:
            retry_after = float(delay.group(1))
        for violation in item.get("violations", []):
            daily = daily or "PerDay" in str(violation.get("quotaId", ""))
    return retry_after, daily


def _hours(seconds: float) -> str:
    if seconds < 90:
        return "a minute"
    if seconds < 5400:
        return f"{round(seconds / 60)} minutes"
    return f"{round(seconds / 3600)} hours"


class LLMClient(Protocol):
    provider: str
    model: str

    def complete(self, system: str, prompt: str) -> str: ...


def _post_json(url: str, payload: dict, headers: dict[str, str], timeout: float) -> dict:
    body = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json", **headers})
    for attempt in (1, 2):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            full = exc.read().decode("utf-8", "replace")
            detail = full[:300]
            retry_after, daily = _quota_details(full) if exc.code == 429 else (None, False)
            # Retry once after a short pause, unless the provider says the wait is long.
            if exc.code in (429, 500, 502, 503, 504) and attempt == 1 and (retry_after or 0) <= 3:
                time.sleep(1.5)
                continue
            if exc.code == 429:
                if daily:
                    reset = f" It resets in about {_hours(retry_after)}." if retry_after else ""
                    raise RateLimitedError(f"The free daily limit for this model is used up.{reset}", retry_after, True) from exc
                raise RateLimitedError("The LLM provider is rate-limiting requests. Try again shortly.", retry_after) from exc
            if exc.code in (401, 403) or "API_KEY_INVALID" in detail:
                raise EngineError(f"The LLM provider rejected the API key (HTTP {exc.code}). Check GEMINI_API_KEY or LIKHIBI_LLM_API_KEY.") from exc
            raise EngineError(f"LLM provider error HTTP {exc.code}: {detail}") from exc
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            if attempt == 1:
                time.sleep(1.0)
                continue
            raise EngineUnavailableError(f"Cannot reach the LLM provider: {exc}") from exc
    raise EngineUnavailableError("The LLM provider did not answer.")


@dataclass
class GeminiClient:
    api_key: str
    model: str = DEFAULT_GEMINI_MODEL
    timeout: float = 45.0
    # Gemini 3 models think before answering, and thinking tokens count against the output
    # limit. "low" is the lowest level every current Flash model accepts; "" keeps the default.
    thinking_level: str = "low"
    # Tried in order when the models before them are out of quota.
    fallback_models: tuple[str, ...] = DEFAULT_GEMINI_FALLBACKS
    last_model: str | None = field(default=None, init=False)
    _resting: dict[str, float] = field(default_factory=dict, init=False, repr=False)
    base_url: str = GEMINI_BASE_URL
    provider: str = "gemini"

    def note(self) -> str | None:
        """Which models are resting, for the engine's status line."""
        now = time.monotonic()
        resting = [m for m in (self.model, *self.fallback_models) if self._resting.get(m, 0) > now]
        if not resting:
            return None
        awake = [m for m in (self.model, *self.fallback_models) if m not in resting]
        if not awake:
            return "Every Gemini model has used its free limit; the other engines answer meanwhile"
        return f"Free limit used up for {', '.join(resting)}, so {awake[0]} answers"

    def complete(self, system: str, prompt: str) -> str:
        now = time.monotonic()
        models = [m for m in dict.fromkeys((self.model, *self.fallback_models)) if self._resting.get(m, 0) <= now]
        if not models:
            soonest = min(self._resting.values()) - now
            raise RateLimitedError(
                f"Gemini's free limits are used up for now; the first resets in about {_hours(soonest)}.", soonest, True
            )
        error: RateLimitedError | None = None
        for model in models:
            try:
                text = self._complete_with(model, system, prompt)
            except RateLimitedError as exc:
                # Rest the model until Google's reset time, so no request is wasted on it.
                self._resting[model] = time.monotonic() + (exc.retry_after or 60)
                error = exc
                continue
            self.last_model = model
            return text
        assert error is not None
        raise error

    def _complete_with(self, model: str, system: str, prompt: str) -> str:
        # Temperature stays at Google's default: Gemini 3 models can loop when it is lowered.
        config: dict = {"maxOutputTokens": 8192}
        if self.thinking_level:
            config["thinkingConfig"] = {"thinkingLevel": self.thinking_level}
        payload = {
            "systemInstruction": {"parts": [{"text": system}]},
            "contents": [{"role": "user", "parts": [{"text": prompt}]}],
            "generationConfig": config,
        }
        url = f"{self.base_url}/models/{model}:generateContent"
        # The key goes in a header, not the URL, so it never lands in proxy or server logs.
        headers = {"x-goog-api-key": self.api_key}
        try:
            data = _post_json(url, payload, headers, self.timeout)
        except EngineError as exc:
            # Older models such as Gemini 2.5 reject thinking levels; ask again with the model's default.
            if "thinkingConfig" not in config or "thinking" not in str(exc).lower():
                raise
            del config["thinkingConfig"]
            data = _post_json(url, payload, headers, self.timeout)
        candidates = data.get("candidates") or []
        if not candidates:
            reason = (data.get("promptFeedback") or {}).get("blockReason", "no candidates returned")
            raise EngineError(f"Gemini returned no translation ({reason}).")
        parts = (candidates[0].get("content") or {}).get("parts") or []
        text = "".join(part.get("text", "") for part in parts if not part.get("thought"))
        if not text.strip():
            raise EngineError(f"Gemini returned an empty answer ({candidates[0].get('finishReason', 'unknown')}).")
        return text


def check_gemini_key(api_key: str, model: str = DEFAULT_GEMINI_MODEL, timeout: float = 15.0) -> str | None:
    """Ask Google whether a key can use the model. Looking up a model spends no generation quota.

    Returns None when it can, or a warning when Google is rate-limiting the key (it is valid).
    Raises EngineError with a message for the person who pasted the key otherwise.
    """
    request = urllib.request.Request(f"{GEMINI_BASE_URL}/models/{model}", headers={"x-goog-api-key": api_key})
    try:
        with urllib.request.urlopen(request, timeout=timeout):
            return None
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")
        if exc.code == 429:
            return "Google is rate-limiting this key right now, so translations may wait a minute."
        if exc.code in (401, 403) or "API_KEY_INVALID" in detail or "API key not valid" in detail:
            raise EngineError(f"Google rejected this key (HTTP {exc.code}). Copy it again from Google AI Studio.") from None
        if exc.code == 404:
            raise EngineError(
                f"Google accepted the key, but the model {model} is not available to it. "
                "Set LIKHIBI_LLM_MODEL to a model the key can use."
            ) from None
        raise EngineError(f"Google could not check the key (HTTP {exc.code}).") from None
    except (urllib.error.URLError, TimeoutError, OSError):
        raise EngineError("Could not reach Google to check the key. Check the internet connection.") from None


@dataclass
class OpenAICompatibleClient:
    model: str
    api_key: str | None = None
    base_url: str = "https://api.openai.com/v1"
    timeout: float = 45.0
    provider: str = "openai"

    def complete(self, system: str, prompt: str) -> str:
        headers = {"Authorization": f"Bearer {self.api_key}"} if self.api_key else {}
        data = _post_json(
            f"{self.base_url.rstrip('/')}/chat/completions",
            {
                "model": self.model,
                "messages": [{"role": "system", "content": system}, {"role": "user", "content": prompt}],
                "temperature": 0.2,
                "max_tokens": 1024,
            },
            headers,
            self.timeout,
        )
        try:
            text = data["choices"][0]["message"]["content"] or ""
        except (KeyError, IndexError, TypeError) as exc:
            raise EngineError("The LLM provider returned an unexpected response.") from exc
        if not text.strip():
            raise EngineError("The LLM provider returned an empty answer.")
        return text
