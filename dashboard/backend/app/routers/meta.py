from __future__ import annotations

from fastapi import APIRouter, Depends

from nlp_research.translation.engine_api import SUPPORTED_LANGUAGES

from .. import __version__
from ..config import Settings
from ..deps import get_settings
from ..schemas import Language, MetaOut

router = APIRouter(tags=["meta"])


@router.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "version": __version__}


@router.get("/meta", response_model=MetaOut)
def meta(settings: Settings = Depends(get_settings)) -> MetaOut:
    return MetaOut(
        app_version=__version__,
        languages=[Language(code=code, name=name) for code, name in SUPPORTED_LANGUAGES.items()],
        max_chars=settings.max_chars,
        log_translations=settings.log_translations,
        review_requires_token=settings.admin_token is not None,
        mock_engine_enabled=settings.enable_mock_engine,
    )
