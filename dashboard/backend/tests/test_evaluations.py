from __future__ import annotations

import json
from pathlib import Path

from nlp_research.translation.engine_api import EN_TO_NAG
from nlp_research.translation.evaluate_engine import evaluate


def _write_test_set(path: Path) -> Path:
    path.write_text(
        "source_text\ttarget_text\n"
        "where is the market\tWHERE IS THE MARKET\n"
        "good morning\tBHAL PUWA\n",
        encoding="utf-8",
    )
    return path


def test_harness_run_appears_in_the_dashboard(make_client, datasets_dir: Path, tmp_path: Path) -> None:
    runs_dir = datasets_dir / "evaluations" / "mt_runs"
    run_path = evaluate(
        engine_spec="tests.fake_engines:UppercaseEngine",
        test_set=_write_test_set(tmp_path / "everyday.tsv"),
        direction=EN_TO_NAG,
        test_set_name="everyday-test-v1",
        runs_dir=runs_dir,
    )
    run = json.loads(run_path.read_text(encoding="utf-8"))
    assert run["n_segments"] == 2
    assert run["failed_segments"] == 0
    assert run["segments"][0]["chrf++"] == 100.0  # exact match
    assert 0 < run["metrics"]["chrf++"] < 100  # the second segment is wrong

    client = make_client()
    listing = client.get("/api/eval/runs").json()
    assert [r["run_id"] for r in listing["runs"]] == [run["run_id"]]
    detail = client.get(f"/api/eval/runs/{run['run_id']}").json()
    assert detail["segments"][1]["hypothesis"] == "GOOD MORNING"


def test_harness_scores_a_fallback_chain(datasets_dir: Path, tmp_path: Path) -> None:
    run_path = evaluate(
        engine_spec=["tests.fake_engines:DecliningEngine", "tests.fake_engines:UppercaseEngine"],
        test_set=_write_test_set(tmp_path / "everyday.tsv"),
        direction=EN_TO_NAG,
        runs_dir=datasets_dir / "evaluations" / "mt_runs",
    )
    run = json.loads(run_path.read_text(encoding="utf-8"))
    assert run["engine_id"] == "declining+uppercase"
    assert run["answered_by"] == {"uppercase": 2}  # declines are not answers
    assert run["failed_segments"] == 0
    assert {segment["engine"] for segment in run["segments"]} == {"uppercase"}


def test_invalid_run_files_are_reported_not_hidden(make_client, datasets_dir: Path) -> None:
    runs_dir = datasets_dir / "evaluations" / "mt_runs"
    runs_dir.mkdir(parents=True)
    (runs_dir / "broken.json").write_text("{not json", encoding="utf-8")
    (runs_dir / "partial.json").write_text(json.dumps({"run_id": "x"}), encoding="utf-8")

    body = make_client().get("/api/eval/runs").json()
    assert body["runs"] == []
    assert {item["file"] for item in body["invalid_files"]} == {
        "evaluations/mt_runs/broken.json",
        "evaluations/mt_runs/partial.json",
    }
    assert make_client().get("/api/eval/runs/x").status_code == 404
