"""Application factory.

Run from dashboard/backend with:

    python -m uvicorn app.main:create_app --factory --reload
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from typing import AsyncIterator

import anyio.to_thread
from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

from . import __version__
from .config import Settings
from .engines.registry import EngineRegistry
from .errors import ApiError, api_error_handler, validation_error_handler
from .routers import api_keys, data, engines, evaluations, feedback, learning, lexicon, meta, reviewers, translate
from .services.translation import TranslationService, approved_examples
from .services.learning import LearningService
from .storage import Storage

logger = logging.getLogger(__name__)


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    storage = Storage(settings.db_path)
    storage.init()
    registry = EngineRegistry.from_settings(settings)

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        # Loading models can take a while; keep the event loop free meanwhile.
        await anyio.to_thread.run_sync(registry.load_all)
        # Approved corrections reach the engines before the first request.
        await anyio.to_thread.run_sync(lambda: registry.broadcast_community_examples(approved_examples(storage)))
        logger.info("Engines: %s", [entry.id for entry in registry.entries()] or "none")
        yield

    app = FastAPI(
        title="Likhibi Translation Dashboard API",
        version=__version__,
        description="English and Nagamese translation behind one engine contract.",
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.state.storage = storage
    app.state.registry = registry
    app.state.translation_service = TranslationService(
        registry, storage, log_translations=settings.log_translations
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=list(settings.cors_origins),
        allow_methods=["*"],
        allow_headers=["*"],
    )
    app.add_exception_handler(ApiError, api_error_handler)
    app.add_exception_handler(RequestValidationError, validation_error_handler)

    app.state.learning = LearningService(
        storage, registry, settings.db_path, settings.learn_threshold, settings.db_path.parent / "learning-logs"
    )

    for module in (meta, engines, api_keys, translate, feedback, lexicon, data, evaluations, reviewers, learning):
        app.include_router(module.router, prefix="/api")

    # In production the built React app is served from the same origin.
    if settings.frontend_dist.is_dir():
        app.mount("/", StaticFiles(directory=settings.frontend_dist, html=True), name="frontend")

    return app
