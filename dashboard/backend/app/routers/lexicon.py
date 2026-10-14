from __future__ import annotations

from fastapi import APIRouter, Depends, Query

from ..config import Settings
from ..deps import get_settings, get_storage
from ..schemas import MeaningTaskOut, MeaningTasksOut
from ..services.lexicon_tasks import meaning_tasks
from ..storage import Storage

router = APIRouter(tags=["lexicon"])


@router.get("/lexicon/needs-meaning", response_model=MeaningTasksOut)
def needs_meaning(
    count: int = Query(default=6, ge=1, le=20),
    skip: str = Query(default="", max_length=2000, description="Comma-separated words the person skipped"),
    settings: Settings = Depends(get_settings),
    storage: Storage = Depends(get_storage),
) -> MeaningTasksOut:
    """Frequent lexicon words without an English meaning yet, for speakers to define."""
    skipped = {word.strip().lower() for word in skip.split(",") if word.strip()}
    tasks, remaining, total = meaning_tasks(settings.datasets_dir, storage.contributed_sources("nag"), count, skipped)
    return MeaningTasksOut(
        tasks=[MeaningTaskOut(lemma=t.lemma, frequency=t.frequency, origin=t.origin, guesses=list(t.guesses)) for t in tasks],
        remaining=remaining,
        total=total,
    )
