from __future__ import annotations

from pathlib import Path

import pytest

from nlp_research.translation.engine_api import (
    EN_TO_NAG,
    NAG_TO_EN,
    EngineDeclinedError,
    EngineState,
    EngineUnavailableError,
    EvidenceKind,
    ParallelExample,
    TranslationRequest,
)
from nlp_research.translation.engines.nmt import NmtEngine
from nlp_research.translation.engines.rag_llm import RagLlmEngine, build_client, build_prompt, clean_output
from nlp_research.translation.engines.translation_memory import TranslationMemoryEngine
from nlp_research.translation.glossary import Glossary, extract, write
from nlp_research.translation.retrieval import BM25, ExampleIndex, Example, char_similarity
from nlp_research.translation.text import match_key

PAIRS = [
    ("Where is the market?", "Bazar kot te ase?", "train"),
    ("I am going to the market.", "Moi bazar te jai ase.", "train"),
    ("The people came to the city.", "Manu khan sheher te ahise.", "train"),
    ("Give me water.", "Moike pani dibi.", "train"),
    ("He went home.", "Tai ghor te jaise.", "train"),
    ("The test sentence stays hidden.", "Test laga kotha lukai thake.", "test"),
]


@pytest.fixture
def datasets(tmp_path: Path) -> Path:
    root = tmp_path / "datasets"
    corpus = root / "processed" / "parallel_corpus"
    corpus.mkdir(parents=True)
    lines = ["src_id\tverse_id\tenglish_text\tnagamese_text\tdomain\tsplit"]
    lines += [f"S{i}\tV{i}\t{en}\t{nag}\teveryday\t{split}" for i, (en, nag, split) in enumerate(PAIRS)]
    (corpus / "everyday.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8")
    tests = root / "processed" / "test_sets"
    tests.mkdir(parents=True)
    (tests / "everyday_test_en_nag.tsv").write_text(
        "id\tsource_text\ttarget_text\nT1\tThe test sentence stays hidden.\tTest laga kotha lukai thake.\n", encoding="utf-8"
    )
    return root


def test_spelling_folding_merges_variants() -> None:
    assert match_key("Apuni khan bhaal ase", "nag") == match_key("apunikhan bhal ase", "nag")
    assert match_key("Itu shanti", "nag") == match_key("etu santi", "nag")
    assert match_key("Where is it?", "en") == "where is it"


def test_bm25_and_similarity() -> None:
    bm25 = BM25([["bazar", "kot"], ["pani", "dibi"], ["bazar", "te", "jai"]])
    assert bm25.search(["pani"], 2)[0][0] == 1
    assert char_similarity("bazar kot te ase", "bazar kot te ase") == 1.0
    assert char_similarity("bazar", "pani") < 0.2


def test_example_index_prefers_close_matches() -> None:
    index = ExampleIndex([Example(en, nag, f"t:{i}", "everyday") for i, (en, nag, _) in enumerate(PAIRS)])
    hits = index.search("where is the big market", EN_TO_NAG, k=2)
    assert hits[0].example.english == "Where is the market?"
    assert index.exact("WHERE is the market", EN_TO_NAG)[0].nagamese == "Bazar kot te ase?"


def test_translation_memory(datasets: Path) -> None:
    engine = TranslationMemoryEngine(datasets_dir=datasets)
    engine.load()
    assert engine.status().state is EngineState.READY

    exact = engine.translate(TranslationRequest("where is the market", EN_TO_NAG))
    assert exact.translation == "Bazar kot te ase?"
    assert exact.evidence[0].kind is EvidenceKind.MEMORY
    assert engine.translate(TranslationRequest("moi bazar te jai ase", NAG_TO_EN)).translation == "I am going to the market."

    with pytest.raises(EngineDeclinedError):
        engine.translate(TranslationRequest("How much does this cost?", EN_TO_NAG))
    # Held-out test sentences are never in the memory.
    with pytest.raises(EngineDeclinedError):
        engine.translate(TranslationRequest("The test sentence stays hidden.", EN_TO_NAG))

    engine.update_community_examples([ParallelExample("How much does this cost?", "Etu kiman ase?", "feedback:7")])
    learned = engine.translate(TranslationRequest("How much does this cost?", EN_TO_NAG))
    assert learned.translation == "Etu kiman ase?"
    assert learned.evidence[0].origin == "feedback:7"


class FakeClient:
    provider = "fake"
    model = "fake-1"

    def __init__(self, answer: str) -> None:
        self.answer = answer
        self.calls: list[tuple[str, str]] = []

    def complete(self, system: str, prompt: str) -> str:
        self.calls.append((system, prompt))
        return self.answer


def test_rag_engine_builds_prompt_and_caches(datasets: Path, tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setenv("LIKHIBI_LLM_CACHE", str(tmp_path / "cache.sqlite3"))
    client = FakeClient("Nagamese: Moi kali ahibo.")
    engine = RagLlmEngine(client=client, datasets_dir=datasets)
    engine.load()
    assert engine.status().state is EngineState.READY

    result = engine.translate(TranslationRequest("I am going to the market tomorrow.", EN_TO_NAG))
    assert result.translation == "Moi kali ahibo."  # label stripped
    system, prompt = client.calls[0]
    assert "Nagamese (Naga Pidgin" in system
    assert "Moi bazar te jai ase." in prompt  # the closest example is shown
    assert "Test laga kotha" not in prompt  # held-out sentences never leak into prompts
    assert prompt.rstrip().endswith("Nagamese:")
    assert result.evidence[0].kind is EvidenceKind.EXAMPLE

    again = engine.translate(TranslationRequest("I am going to the market tomorrow.", EN_TO_NAG))
    assert again.translation == result.translation
    assert len(client.calls) == 1  # served from the cache
    assert again.metadata["cached"] is True


def test_rag_engine_without_key_is_not_configured(monkeypatch) -> None:
    for name in ("LIKHIBI_LLM_PROVIDER", "LIKHIBI_LLM_API_KEY", "GEMINI_API_KEY", "GOOGLE_API_KEY"):
        monkeypatch.delenv(name, raising=False)
    client, problem = build_client()
    assert client is None and "GEMINI_API_KEY" in problem
    monkeypatch.setenv("LIKHIBI_LLM_CACHE", "off")
    engine = RagLlmEngine()
    assert engine.status().state is EngineState.NOT_CONFIGURED
    with pytest.raises(EngineUnavailableError):
        engine.translate(TranslationRequest("Hello", EN_TO_NAG))


def test_openai_compatible_local_server_needs_no_key(monkeypatch) -> None:
    monkeypatch.setenv("LIKHIBI_LLM_PROVIDER", "openai")
    monkeypatch.setenv("LIKHIBI_LLM_BASE_URL", "http://localhost:11434/v1")
    monkeypatch.setenv("LIKHIBI_LLM_MODEL", "llama3.2")
    monkeypatch.delenv("LIKHIBI_LLM_API_KEY", raising=False)
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    client, problem = build_client()
    assert problem is None and client.model == "llama3.2"


def test_gemini_client_defaults_and_thinking_fallback(monkeypatch) -> None:
    import copy

    from nlp_research.translation.engine_api import EngineError
    from nlp_research.translation.engines import llm_providers

    for name in ("LIKHIBI_LLM_PROVIDER", "LIKHIBI_LLM_MODEL", "LIKHIBI_LLM_THINKING", "LIKHIBI_LLM_API_KEY"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("GEMINI_API_KEY", "test-key")
    client, problem = build_client()
    assert problem is None and client.model == "gemini-3.8-flash" and client.thinking_level == "low"

    sent = []

    def fake_post(url, payload, headers, timeout):
        sent.append(copy.deepcopy(payload))
        if "thinkingConfig" in payload["generationConfig"]:  # how an older model answers a thinking level
            raise EngineError('LLM provider error HTTP 400: {"error": {"message": "Thinking level is not supported for this model."}}')
        return {"candidates": [{"content": {"parts": [{"text": "Moi kali ahibo."}]}}]}

    monkeypatch.setattr(llm_providers, "_post_json", fake_post)
    old = llm_providers.GeminiClient(api_key="test-key", model="gemini-2.5-flash")
    assert old.complete("system", "prompt") == "Moi kali ahibo."
    assert sent[0]["generationConfig"]["thinkingConfig"] == {"thinkingLevel": "low"}
    assert "temperature" not in sent[0]["generationConfig"]
    assert "thinkingConfig" not in sent[1]["generationConfig"]


def test_saved_key_wins_and_engine_reconnects(datasets: Path, monkeypatch) -> None:
    from nlp_research.translation import credentials
    from nlp_research.translation.engines.rag_llm import find_api_key

    for name in ("LIKHIBI_LLM_PROVIDER", "LIKHIBI_LLM_API_KEY", "GOOGLE_API_KEY"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("LIKHIBI_LLM_CACHE", "off")
    monkeypatch.setenv("GEMINI_API_KEY", "env-key-0123456789abcdef")
    assert find_api_key("gemini") == ("env-key-0123456789abcdef", "GEMINI_API_KEY")
    credentials.set_secret(credentials.GEMINI_API_KEY, "saved-key-0123456789abcdef")
    assert find_api_key("gemini") == ("saved-key-0123456789abcdef", "dashboard")

    monkeypatch.delenv("GEMINI_API_KEY")
    assert credentials.delete_secret(credentials.GEMINI_API_KEY) is True
    engine = RagLlmEngine(datasets_dir=datasets)
    engine.load()
    assert engine.status().state is EngineState.NOT_CONFIGURED
    credentials.set_secret(credentials.GEMINI_API_KEY, "saved-key-0123456789abcdef")
    engine.credentials_changed()  # what the dashboard calls after saving
    assert engine.status().state is EngineState.READY
    assert engine.client.api_key == "saved-key-0123456789abcdef"


def test_google_quota_errors_are_understood() -> None:
    import json

    from nlp_research.translation.engines.llm_providers import _quota_details

    body = json.dumps({"error": {"code": 429, "status": "RESOURCE_EXHAUSTED", "details": [
        {"@type": "type.googleapis.com/google.rpc.QuotaFailure", "violations": [
            {"quotaId": "GenerateRequestsPerDayPerProjectPerModel-FreeTier", "quotaValue": "20"}]},
        {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "48636s"},
    ]}})
    assert _quota_details(body) == (48636.0, True)
    assert _quota_details("not json") == (None, False)


def test_gemini_rests_an_exhausted_model_and_falls_back(monkeypatch) -> None:
    from nlp_research.translation.engines import llm_providers

    calls = []

    def fake_post(url, payload, headers, timeout):
        model = url.split("/models/")[1].split(":")[0]
        calls.append(model)
        if model == "gemini-3.8-flash":
            raise llm_providers.RateLimitedError("The free daily limit for this model is used up.", 3600, True)
        return {"candidates": [{"content": {"parts": [{"text": "Moi kali ahibo."}]}}]}

    monkeypatch.setattr(llm_providers, "_post_json", fake_post)
    client = llm_providers.GeminiClient(api_key="test-key")
    assert client.complete("system", "prompt") == "Moi kali ahibo."
    assert calls == ["gemini-3.8-flash", "gemini-3.5-flash-lite"]
    assert client.last_model == "gemini-3.5-flash-lite"
    assert client.note() == "Free limit used up for gemini-3.8-flash, so gemini-3.5-flash-lite answers"
    client.complete("system", "prompt")
    assert calls[2:] == ["gemini-3.5-flash-lite"]  # the resting model is not asked again

    alone = llm_providers.GeminiClient(api_key="test-key", model="gemini-3.8-flash", fallback_models=())
    with pytest.raises(llm_providers.RateLimitedError):
        alone.complete("system", "prompt")
    asked = len(calls)
    with pytest.raises(llm_providers.RateLimitedError, match="used up for now"):
        alone.complete("system", "prompt")
    assert len(calls) == asked  # nothing sent while every model rests


def test_clean_output_and_prompt_format() -> None:
    assert clean_output('"Bazar kot te ase?"\nExplanation: ...', "Where is the market?") == "Bazar kot te ase?"
    assert clean_output("Translation: Moi ahibo", "I will come") == "Moi ahibo"
    prompt = build_prompt("Hi", EN_TO_NAG, [], [("market", "bazar")])
    assert "market: bazar" in prompt and prompt.endswith("Nagamese:")


def test_glossary_extraction_finds_word_pairs(tmp_path: Path) -> None:
    pairs = [
        ("Give me water", "Moike pani dibi"),
        ("The water is cold", "Pani to thanda ase"),
        ("Bring water now", "Etiya pani anibi"),
        ("The house is big", "Ghor to dangor ase"),
        ("Go to the house", "Ghor te jabi"),
        ("My house is here", "Moi laga ghor yate ase"),
    ]
    entries = extract(pairs, min_count=2, min_score=0.05)
    found = {(e.nagamese, e.english) for e in entries}
    assert ("pani", "water") in found and ("ghor", "house") in found
    path = tmp_path / "processed" / "lexical_database" / "auto_glossary.tsv"
    write(entries, path)
    glossary = Glossary.load(tmp_path)
    assert ("water", "pani") in glossary.hints("Bring me water please", EN_TO_NAG)


def test_nmt_engine_reports_missing_adapter(tmp_path: Path) -> None:
    engine = NmtEngine(adapter_dir=tmp_path / "missing")
    engine.load()
    status = engine.status()
    assert status.state is EngineState.NOT_CONFIGURED
    with pytest.raises(EngineUnavailableError):
        engine.translate(TranslationRequest("Hello", EN_TO_NAG))


def test_nmt_splits_batches_to_fit_gpu_memory() -> None:
    from nlp_research.translation.engines import nmt

    class WordTokenizer:  # one token per word: enough to test batching without the model
        def __call__(self, text, truncation=True, max_length=None):
            return {"input_ids": text.split()[:max_length]}

    engine = NmtEngine()
    engine._tokenizer = WordTokenizer()
    lengths = [3, 200, 5, 120, 4, 250, 6] + [8] * 30
    requests = [TranslationRequest(" ".join(["w"] * n), EN_TO_NAG) for n in lengths]
    chunks = engine._chunks(list(range(len(requests))), requests, beams=4)
    assert sorted(i for chunk in chunks for i in chunk) == list(range(len(requests)))
    for chunk in chunks:
        assert len(chunk) <= nmt.MAX_BATCH
        longest = max(lengths[i] for i in chunk)
        assert len(chunk) == 1 or len(chunk) * 4 * (longest + nmt._max_new_tokens(longest)) <= nmt.HYPOTHESIS_TOKEN_BUDGET
