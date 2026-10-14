from __future__ import annotations

from fastapi import APIRouter, Depends

from ..deps import get_registry
from ..engines.registry import EngineRegistry
from ..schemas import BrokenEngineOut, EngineOut, EnginesOut

router = APIRouter(tags=["engines"])


@router.get("/engines", response_model=EnginesOut)
def list_engines(registry: EngineRegistry = Depends(get_registry)) -> EnginesOut:
    engines = []
    for entry in registry.entries():
        info = entry.engine.info
        status = entry.status()
        engines.append(
            EngineOut(
                id=info.id,
                name=info.name,
                kind=info.kind.value,
                version=info.version,
                description=info.description,
                directions=[direction.code for direction in info.directions],
                offline=info.offline,
                state=status.state.value,
                status_message=status.message,
                is_default=info.id == registry.default_engine_id,
            )
        )
    return EnginesOut(
        default_engine=registry.default_engine_id,
        engines=engines,
        broken=[BrokenEngineOut(spec=b.spec, error=b.error) for b in registry.broken],
    )
