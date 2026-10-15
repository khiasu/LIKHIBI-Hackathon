"""The model learning from approved contributions: progress, history, and a manual start."""

from __future__ import annotations

from fastapi import APIRouter, Depends, Request

from ..deps import require_admin_reviewer, require_reviewer
from ..errors import ApiError
from ..schemas import LearningOut, LearningRunOut

router = APIRouter(tags=["learning"])


def _status(request: Request) -> LearningOut:
    status = request.app.state.learning.status()
    runs = [
        LearningRunOut(**{**run, "accepted": None if run["accepted"] is None else bool(run["accepted"])})
        for run in status.pop("runs")
    ]
    return LearningOut(**status, runs=runs)


@router.get("/learning", response_model=LearningOut, dependencies=[Depends(require_reviewer)])
def learning_status(request: Request) -> LearningOut:
    return _status(request)


@router.post("/learning/run", response_model=LearningOut, status_code=202, dependencies=[Depends(require_admin_reviewer)])
def start_learning(request: Request) -> LearningOut:
    service = request.app.state.learning
    problem = service.problem()
    if problem is not None:
        raise ApiError(409, "training_unavailable", problem)
    if service.start("manual") is None:
        raise ApiError(409, "learning_running", "The model is already learning. It will finish shortly.")
    return _status(request)
