from __future__ import annotations

import pytest

from nlp_research.translation.engine_api import EN_TO_NAG, Direction, load_engine


def test_direction_parse_round_trips() -> None:
    assert Direction.parse("en-nag") == EN_TO_NAG
    assert EN_TO_NAG.code == "en-nag"


@pytest.mark.parametrize("code", ["en-en", "xx-nag", "ennag", "en-"])
def test_direction_rejects_bad_codes(code: str) -> None:
    with pytest.raises(ValueError):
        Direction.parse(code)


def test_load_engine_from_spec() -> None:
    engine = load_engine("tests.fake_engines:EchoEngine")
    assert engine.info.id == "echo"
    assert engine.supports(EN_TO_NAG)


@pytest.mark.parametrize("spec", ["tests.fake_engines", "tests.fake_engines:", ":EchoEngine"])
def test_load_engine_rejects_malformed_specs(spec: str) -> None:
    with pytest.raises(ValueError):
        load_engine(spec)


def test_load_engine_rejects_non_engines() -> None:
    with pytest.raises(TypeError):
        load_engine("tests.fake_engines:not_an_engine")
