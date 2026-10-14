from __future__ import annotations

from fastapi import APIRouter, Depends

from ..config import Settings
from ..deps import get_settings, get_storage
from ..schemas import DataHealthOut, DataSourceOut
from ..services.data_health import build_data_health
from ..services.data_sources import list_sources
from ..services.evaluations import list_runs
from ..storage import Storage

router = APIRouter(tags=["data"])


@router.get("/data/health", response_model=DataHealthOut)
def data_health(
    settings: Settings = Depends(get_settings),
    storage: Storage = Depends(get_storage),
) -> DataHealthOut:
    report = build_data_health(
        settings.datasets_dir,
        feedback_counts=storage.feedback_counts(),
        approved_pairs=len(storage.approved_corrections()),
        eval_run_count=len(list_runs(settings.datasets_dir)["runs"]),
    )
    return DataHealthOut(**report)


@router.get("/data/sources", response_model=list[DataSourceOut])
def data_sources(settings: Settings = Depends(get_settings)) -> list[DataSourceOut]:
    return [DataSourceOut(**source) for source in list_sources(settings.datasets_dir)]
