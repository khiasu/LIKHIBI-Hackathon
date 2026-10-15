"""Request and response models for the HTTP API."""

from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field, SecretStr, model_validator

LangCode = Literal["en", "nag"]
FeedbackStatus = Literal["pending", "approved", "rejected"]


# Meta ----------------------------------------------------------------------


class Language(BaseModel):
    code: str
    name: str


class MetaOut(BaseModel):
    app_version: str
    languages: list[Language]
    max_chars: int
    log_translations: bool
    review_requires_token: bool
    mock_engine_enabled: bool


# Engines -------------------------------------------------------------------


class EngineSummary(BaseModel):
    id: str
    name: str
    kind: str
    version: str


class EngineOut(EngineSummary):
    description: str
    directions: list[str]
    offline: bool
    state: Literal["ready", "not_configured", "error"]
    status_message: str | None
    is_default: bool


class BrokenEngineOut(BaseModel):
    spec: str
    error: str


class EnginesOut(BaseModel):
    default_engine: str | None
    engines: list[EngineOut]
    broken: list[BrokenEngineOut]


# Translation -----------------------------------------------------------------


class TranslateIn(BaseModel):
    text: str = Field(min_length=1)
    source_lang: LangCode
    target_lang: LangCode
    engine: str = "auto"
    num_alternatives: int = Field(default=0, ge=0, le=5)


class AlternativeOut(BaseModel):
    text: str
    score: float | None


class EvidenceOut(BaseModel):
    kind: str
    source_text: str
    target_text: str
    origin: str
    similarity: float | None


class TranslationOut(BaseModel):
    request_id: str
    created_at: datetime
    engine: EngineSummary
    source_lang: str
    target_lang: str
    source_text: str
    translation: str
    alternatives: list[AlternativeOut]
    confidence: float | None
    evidence: list[EvidenceOut]
    warnings: list[str]
    latency_ms: float


class CompareIn(BaseModel):
    text: str = Field(min_length=1)
    source_lang: LangCode
    target_lang: LangCode


class CompareItem(BaseModel):
    engine: EngineSummary
    ok: bool
    result: TranslationOut | None
    error: str | None
    declined: bool = False  # the engine had no confident answer, which is not a failure


class CompareOut(BaseModel):
    results: list[CompareItem]


# Feedback --------------------------------------------------------------------


class FeedbackIn(BaseModel):
    translation_id: str | None = Field(default=None, max_length=64)
    source_lang: LangCode
    target_lang: LangCode
    source_text: str = Field(min_length=1, max_length=5000)
    machine_translation: str | None = Field(default=None, max_length=5000)
    corrected_translation: str | None = Field(default=None, max_length=5000)
    rating: Literal["up", "down"] | None = None
    comment: str | None = Field(default=None, max_length=1000)
    engine_id: str | None = Field(default=None, max_length=100)
    contributor: str | None = Field(default=None, max_length=80)
    # Sharing a translation means publishing it under CC BY-SA 4.0; it needs an explicit yes.
    share_consent: bool = False

    @model_validator(mode="after")
    def _clean(self) -> "FeedbackIn":
        for name in ("source_text", "machine_translation", "corrected_translation", "comment", "contributor"):
            value = getattr(self, name)
            if isinstance(value, str):
                stripped = value.strip()
                setattr(self, name, stripped or None)
        if not self.source_text:
            raise ValueError("source_text must not be blank")
        if self.source_lang == self.target_lang:
            raise ValueError("source_lang and target_lang must differ")
        if self.rating is None and self.corrected_translation is None:
            raise ValueError("Give a rating, a corrected translation, or both")
        if self.corrected_translation is not None and not self.share_consent:
            raise ValueError("To share a translation, agree to publish it under CC BY-SA 4.0")
        return self


class FeedbackOut(BaseModel):
    id: int
    created_at: datetime
    translation_id: str | None
    source_lang: str
    target_lang: str
    source_text: str
    machine_translation: str | None
    corrected_translation: str | None
    rating: str | None
    comment: str | None
    engine_id: str | None
    status: FeedbackStatus
    reviewed_at: datetime | None
    contributor: str | None = None
    license: str | None = None


class FeedbackCounts(BaseModel):
    pending: int
    approved: int
    rejected: int
    all: int


class FeedbackListOut(BaseModel):
    items: list[FeedbackOut]
    counts: FeedbackCounts


class FeedbackReviewIn(BaseModel):
    status: FeedbackStatus


class CommunityStatsOut(BaseModel):
    approved_pairs: int = Field(description="Shared translations a reviewer approved; the engines use them")
    pending: int = Field(description="Shared translations waiting for review")
    contributors: int = Field(description="Distinct credited names among approved contributions")
    translations: int = Field(description="Translations served by this dashboard")


class MeaningTaskOut(BaseModel):
    lemma: str
    frequency: int
    origin: str | None
    guesses: list[str] = Field(description="Unverified machine guesses from the auto-glossary")


class MeaningTasksOut(BaseModel):
    tasks: list[MeaningTaskOut]
    remaining: int = Field(description="Lexicon words still without a meaning or a pending one")
    total: int


# Data ------------------------------------------------------------------------


class CheckOut(BaseModel):
    id: str
    label: str
    current: int
    target: int
    passed: bool
    hint: str


class ParallelFileOut(BaseModel):
    file: str
    domain: str
    schema_ok: bool
    schema_error: str | None
    rows: int
    english_real: int
    english_placeholder: int
    english_empty: int
    nagamese_empty: int
    duplicate_verse_ids: int
    impossible_references: int
    suspected_noise_rows: int
    long_rows: int
    avg_nagamese_tokens: float
    books: dict[str, int]
    nt_books_missing: list[str]
    real_pairs_by_domain: dict[str, int]


class MonolingualOut(BaseModel):
    file: str
    exists: bool
    lines: int
    tokens: int
    word_types: int


class LexiconOut(BaseModel):
    file: str
    exists: bool
    entries: int
    glossed: int
    verified: int = 0  # entries a speaker has checked
    pos_tagged: int
    attested_in_corpus: int
    etymology: dict[str, int]
    auto_glossary_entries: int  # machine-extracted, unverified


class TestSetOut(BaseModel):
    file: str
    rows: int
    domain: str


class CommunityOut(BaseModel):
    approved_pairs: int
    pending: int
    approved: int
    rejected: int


class DataHealthOut(BaseModel):
    generated_at: datetime
    checks: list[CheckOut]
    parallel: list[ParallelFileOut]
    monolingual: MonolingualOut
    lexicon: LexiconOut
    test_sets: list[TestSetOut]
    community: CommunityOut


class DataSourceOut(BaseModel):
    id: str
    name: str
    description: str
    size: str
    license: str
    url: str
    expected_path: str
    imported: bool


# Evaluation ------------------------------------------------------------------


class EvalRunOut(BaseModel):
    run_id: str
    engine_id: str
    engine_version: str
    test_set: str
    direction: str
    n_segments: int
    metrics: dict[str, float]
    created_at: str
    notes: str | None
    file: str


class InvalidRunFile(BaseModel):
    file: str
    error: str


class EvalRunsOut(BaseModel):
    runs: list[EvalRunOut]
    invalid_files: list[InvalidRunFile]


class EvalSegmentOut(BaseModel):
    source: str
    reference: str
    hypothesis: str
    chrf: float | None


class EvalRunDetailOut(EvalRunOut):
    segments: list[EvalSegmentOut]


# Settings ------------------------------------------------------------------


class LlmKeyIn(BaseModel):
    # SecretStr keeps the key out of logs and reprs.
    api_key: SecretStr


class LlmSettingsOut(BaseModel):
    provider: str
    model: str | None
    key_source: Literal["dashboard", "environment"] | None
    key_env_var: str | None = Field(description="Environment variable the key comes from, when key_source is environment")
    key_hint: str | None = Field(description="Last four characters of the active key. The key itself is never returned.")
    store: str | None = Field(description="Secure store that saved keys go to; null when keys cannot be saved here")
    can_edit: bool = Field(description="Whether this browser may change the key")
    edit_requires_token: bool
    warning: str | None = None
