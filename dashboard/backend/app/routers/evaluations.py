from __future__ import annotations

from fastapi import APIRouter, Depends

from ..config import Settings
from ..deps import get_settings
from ..errors import ApiError
from ..schemas import EvalRunDetailOut, EvalRunsOut
from ..services.evaluations import get_run, list_runs

router = APIRouter(tags=["evaluation"])


@router.get("/eval/runs", response_model=EvalRunsOut)
def eval_runs(settings: Settings = Depends(get_settings)) -> EvalRunsOut:
    return EvalRunsOut(**list_runs(settings.datasets_dir))


@router.get("/eval/runs/{run_id}", response_model=EvalRunDetailOut)
def eval_run(run_id: str, settings: Settings = Depends(get_settings)) -> EvalRunDetailOut:
    run = get_run(settings.datasets_dir, run_id)
    if run is None:
        raise ApiError(404, "run_not_found", f"No evaluation run with id {run_id}.")
    return EvalRunDetailOut(**run)
