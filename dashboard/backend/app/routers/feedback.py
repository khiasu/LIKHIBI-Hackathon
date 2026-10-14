from __future__ import annotations

from typing import Literal

from fastapi import APIRouter, Depends, Query, Response

from ..deps import get_registry, get_storage, require_admin
from ..engines.registry import EngineRegistry
from ..errors import ApiError
from ..services.translation import approved_examples
from ..schemas import (
    CommunityStatsOut,
    FeedbackCounts,
    FeedbackIn,
    FeedbackListOut,
    FeedbackOut,
    FeedbackReviewIn,
)
from ..storage import Storage

router = APIRouter(tags=["feedback"])

LICENSE = "CC-BY-SA-4.0"

_EXPORT_COLUMNS = (
    "id",
    "source_lang",
    "target_lang",
    "source_text",
    "target_text",
    "contributor",
    "license",
    "engine_id",
    "created_at",
)


def _tsv_cell(value: object) -> str:
    text = "" if value is None else str(value)
    return " ".join(text.replace("\t", " ").split())


@router.post("/feedback", response_model=FeedbackOut, status_code=201)
def submit_feedback(body: FeedbackIn, storage: Storage = Depends(get_storage)) -> FeedbackOut:
    data = body.model_dump()
    shared = data.pop("share_consent") and body.corrected_translation is not None
    data["license"] = LICENSE if shared else None
    if not shared:
        data["contributor"] = None  # nothing of theirs is published, so there is nothing to credit
    return FeedbackOut(**storage.add_feedback(data))


@router.get("/community/stats", response_model=CommunityStatsOut)
def community_stats(storage: Storage = Depends(get_storage)) -> CommunityStatsOut:
    return CommunityStatsOut(**storage.community_stats())


@router.get(
    "/feedback/export.tsv",
    dependencies=[Depends(require_admin)],
    response_class=Response,
    responses={200: {"content": {"text/tab-separated-values": {}}}},
)
def export_corrections(storage: Storage = Depends(get_storage)) -> Response:
    """Approved corrections as a parallel-data TSV, ready for training or retrieval."""
    lines = ["\t".join(_EXPORT_COLUMNS)]
    for row in storage.approved_corrections():
        lines.append(
            "\t".join(
                _tsv_cell(value)
                for value in (
                    row["id"],
                    row["source_lang"],
                    row["target_lang"],
                    row["source_text"],
                    row["corrected_translation"],
                    row["contributor"],
                    row["license"],
                    row["engine_id"],
                    row["created_at"],
                )
            )
        )
    return Response(
        content="\n".join(lines) + "\n",
        media_type="text/tab-separated-values; charset=utf-8",
        headers={"Content-Disposition": 'attachment; filename="likhibi_approved_corrections.tsv"'},
    )


@router.get("/feedback", response_model=FeedbackListOut, dependencies=[Depends(require_admin)])
def list_feedback(
    status: Literal["pending", "approved", "rejected", "all"] = "pending",
    limit: int = Query(default=100, ge=1, le=500),
    offset: int = Query(default=0, ge=0),
    storage: Storage = Depends(get_storage),
) -> FeedbackListOut:
    rows = storage.list_feedback(None if status == "all" else status, limit, offset)
    return FeedbackListOut(
        items=[FeedbackOut(**row) for row in rows],
        counts=FeedbackCounts(**storage.feedback_counts()),
    )


@router.patch(
    "/feedback/{feedback_id}",
    response_model=FeedbackOut,
    dependencies=[Depends(require_admin)],
)
def review_feedback(
    feedback_id: int,
    body: FeedbackReviewIn,
    storage: Storage = Depends(get_storage),
    registry: EngineRegistry = Depends(get_registry),
) -> FeedbackOut:
    updated = storage.set_feedback_status(feedback_id, body.status)
    if updated is None:
        raise ApiError(404, "feedback_not_found", f"No feedback with id {feedback_id}.")
    # The data flywheel: engines see an approved correction on the very next request.
    registry.broadcast_community_examples(approved_examples(storage))
    return FeedbackOut(**updated)


@router.delete("/feedback/{feedback_id}", status_code=204, dependencies=[Depends(require_admin)])
def delete_feedback(
    feedback_id: int,
    storage: Storage = Depends(get_storage),
    registry: EngineRegistry = Depends(get_registry),
) -> Response:
    """Remove a contribution for good, for example when its author withdraws consent."""
    if not storage.delete_feedback(feedback_id):
        raise ApiError(404, "feedback_not_found", f"No feedback with id {feedback_id}.")
    registry.broadcast_community_examples(approved_examples(storage))
    return Response(status_code=204)
