"""Verified reviewers: who may approve or reject community contributions."""

from __future__ import annotations

import secrets

from fastapi import APIRouter, Depends

from ..deps import Reviewer, get_storage, hash_token, require_admin_reviewer, require_reviewer
from ..errors import ApiError
from ..schemas import MeOut, ReviewerCreatedOut, ReviewerIn, ReviewerListOut, ReviewerOut
from ..storage import Storage

router = APIRouter(tags=["reviewers"])


def _out(row: dict) -> ReviewerOut:
    return ReviewerOut(
        id=row["id"], created_at=row["created_at"], name=row["name"],
        languages=[code for code in row["languages"].split(",") if code],
        role=row["role"], active=bool(row["active"]), reviews=row["reviews"],
    )


@router.get("/reviewers/me", response_model=MeOut)
def me(reviewer: Reviewer = Depends(require_reviewer)) -> MeOut:
    return MeOut(name=reviewer.name, role=reviewer.role, languages=list(reviewer.languages))


@router.get("/reviewers", response_model=ReviewerListOut, dependencies=[Depends(require_admin_reviewer)])
def list_reviewers(storage: Storage = Depends(get_storage)) -> ReviewerListOut:
    return ReviewerListOut(items=[_out(row) for row in storage.list_reviewers()])


@router.post("/reviewers", response_model=ReviewerCreatedOut, status_code=201, dependencies=[Depends(require_admin_reviewer)])
def add_reviewer(body: ReviewerIn, storage: Storage = Depends(get_storage)) -> ReviewerCreatedOut:
    if storage.reviewer_name_taken(body.name):
        raise ApiError(409, "reviewer_exists", f"There is already a reviewer called {body.name}.")
    token = "lkb_" + secrets.token_urlsafe(24)
    row = storage.add_reviewer(body.name, hash_token(token), ",".join(body.languages), body.role)
    return ReviewerCreatedOut(reviewer=_out(row), token=token)


@router.post("/reviewers/{reviewer_id}/deactivate", response_model=ReviewerOut, dependencies=[Depends(require_admin_reviewer)])
def deactivate_reviewer(reviewer_id: int, storage: Storage = Depends(get_storage)) -> ReviewerOut:
    row = storage.set_reviewer_active(reviewer_id, False)
    if row is None:
        raise ApiError(404, "reviewer_not_found", f"No reviewer with id {reviewer_id}.")
    return _out(row)


@router.post("/reviewers/{reviewer_id}/activate", response_model=ReviewerOut, dependencies=[Depends(require_admin_reviewer)])
def activate_reviewer(reviewer_id: int, storage: Storage = Depends(get_storage)) -> ReviewerOut:
    row = storage.set_reviewer_active(reviewer_id, True)
    if row is None:
        raise ApiError(404, "reviewer_not_found", f"No reviewer with id {reviewer_id}.")
    return _out(row)
