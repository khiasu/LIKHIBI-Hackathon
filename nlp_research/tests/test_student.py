from __future__ import annotations

import importlib.util

import pytest

from nlp_research.translation.distill import _everyday, _plausible
from nlp_research.translation.engine_api import EN_TO_NAG, NAG_TO_EN, EngineState, TranslationRequest
from nlp_research.translation.engines.student import DEFAULT_DIR, REQUIRED_FILES, StudentEngine
from nlp_research.translation.export_student import repeated_trigram_completions


def test_repeated_trigrams_are_blocked() -> None:
    # "a b c a b" -> the next token may not be "c" again.
    assert repeated_trigram_completions([1, 2, 3, 1, 2]) == {3}
    assert repeated_trigram_completions([1, 2, 3, 4]) == set()
    assert repeated_trigram_completions([7]) == set()


def test_synthetic_data_filters() -> None:
    assert _everyday("I will come tomorrow.")
    assert not _everyday("Tom went to Boston.")  # names teach nothing about Nagamese
    assert not _everyday("It costs 20 rupees.")  # digits
    assert _plausible("I am going.", "Moi jai ase.")
    assert not _plausible("Yes.", "ase ase ase ase ase ase")  # stuck repeating one word


def test_student_engine_without_model_is_not_configured(tmp_path) -> None:
    engine = StudentEngine(tmp_path)
    engine.load()
    assert engine.status().state is EngineState.NOT_CONFIGURED


@pytest.mark.skipif(
    importlib.util.find_spec("onnxruntime") is None or not all((DEFAULT_DIR / f).is_file() for f in REQUIRED_FILES),
    reason="needs onnxruntime and an exported student (python -m nlp_research.translation.export_student)",
)
def test_exported_student_translates_both_ways() -> None:
    engine = StudentEngine()
    engine.load()
    assert engine.status().state is EngineState.READY
    nagamese = engine.translate(TranslationRequest("Love one another.", EN_TO_NAG)).translation
    english = engine.translate(TranslationRequest("Moi kali bazar te jabo.", NAG_TO_EN)).translation
    assert nagamese and english and nagamese != english
