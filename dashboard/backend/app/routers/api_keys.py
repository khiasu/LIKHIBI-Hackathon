"""Save the Gemini API key from the dashboard into the operating system's credential store.

The key is checked with Google first, then saved to the credential store (Credential
Manager on Windows), and the LLM engine reconnects at once. The app never writes the
key to its own files or logs, and never sends it back: responses carry only its last
four characters.
"""

from __future__ import annotations

import re

from fastapi import APIRouter, Depends, Request

from nlp_research.translation import credentials
from nlp_research.translation.engine_api import EngineError
from nlp_research.translation.engines import llm_providers
from nlp_research.translation.engines.rag_llm import configured_model, configured_provider, find_api_key

from ..deps import get_registry, is_local_request, require_local_or_admin
from ..engines.registry import EngineRegistry
from ..errors import ApiError
from ..schemas import LlmKeyIn, LlmSettingsOut

router = APIRouter(tags=["settings"])

# Printable ASCII without spaces. Checked by hand so an error message never repeats the key.
_KEY_SHAPE = re.compile(r"[\x21-\x7e]{20,200}")


def _settings_out(request: Request, warning: str | None = None) -> LlmSettingsOut:
    provider = configured_provider()
    key, source = find_api_key(provider)
    admin = request.app.state.settings.admin_token is not None
    return LlmSettingsOut(
        provider=provider,
        model=configured_model(provider),
        key_source=None if key is None else ("dashboard" if source == "dashboard" else "environment"),
        key_env_var=source if key is not None and source != "dashboard" else None,
        key_hint=key[-4:] if key else None,
        store=credentials.store_name(),
        can_edit=admin or is_local_request(request),
        edit_requires_token=admin,
        warning=warning,
    )


@router.get("/settings/llm", response_model=LlmSettingsOut)
def llm_settings(request: Request) -> LlmSettingsOut:
    return _settings_out(request)


@router.put("/settings/llm-key", response_model=LlmSettingsOut, dependencies=[Depends(require_local_or_admin)])
def save_llm_key(body: LlmKeyIn, request: Request, registry: EngineRegistry = Depends(get_registry)) -> LlmSettingsOut:
    if configured_provider() != "gemini":
        raise ApiError(
            409,
            "not_gemini",
            "The LLM engine is set to an OpenAI-compatible server (LIKHIBI_LLM_PROVIDER), so a Gemini key would not be used.",
        )
    if credentials.store_name() is None:
        raise ApiError(
            409,
            "no_secure_store",
            "This computer has no secure credential store, so the key cannot be saved here. "
            "Set GEMINI_API_KEY in the server's environment instead.",
        )
    key = body.api_key.get_secret_value().strip()
    if not _KEY_SHAPE.fullmatch(key):
        raise ApiError(422, "invalid_api_key", "That does not look like an API key. Copy it again from Google AI Studio.")
    model = configured_model("gemini") or llm_providers.DEFAULT_GEMINI_MODEL
    try:
        warning = llm_providers.check_gemini_key(key, model)
    except EngineError as exc:
        raise ApiError(400, "api_key_rejected", str(exc)) from None
    try:
        credentials.set_secret(credentials.GEMINI_API_KEY, key)
    except credentials.CredentialStoreError as exc:
        raise ApiError(500, "credential_store_failed", str(exc)) from None
    registry.broadcast_credentials_changed()
    return _settings_out(request, warning)


@router.delete("/settings/llm-key", response_model=LlmSettingsOut, dependencies=[Depends(require_local_or_admin)])
def remove_llm_key(request: Request, registry: EngineRegistry = Depends(get_registry)) -> LlmSettingsOut:
    try:
        credentials.delete_secret(credentials.GEMINI_API_KEY)
    except credentials.CredentialStoreError as exc:
        raise ApiError(500, "credential_store_failed", str(exc)) from None
    registry.broadcast_credentials_changed()
    return _settings_out(request)
