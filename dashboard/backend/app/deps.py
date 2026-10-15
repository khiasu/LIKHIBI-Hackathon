"""FastAPI dependencies that hand routes the shared application objects."""

from __future__ import annotations

import hashlib
import hmac
import ipaddress
from dataclasses import dataclass

from fastapi import Depends, Header, Request

from .config import Settings
from .engines.registry import EngineRegistry
from .errors import ApiError
from .services.translation import TranslationService
from .storage import Storage


def get_settings(request: Request) -> Settings:
    return request.app.state.settings


def get_registry(request: Request) -> EngineRegistry:
    return request.app.state.registry


def get_storage(request: Request) -> Storage:
    return request.app.state.storage


def get_service(request: Request) -> TranslationService:
    return request.app.state.translation_service


def require_admin(request: Request, x_admin_token: str | None = Header(default=None)) -> None:
    """Guard review and export routes when LIKHIBI_ADMIN_TOKEN is set."""
    expected = request.app.state.settings.admin_token
    if expected is None:
        return
    if x_admin_token is None or not hmac.compare_digest(x_admin_token, expected):
        raise ApiError(
            401,
            "admin_token_required",
            "This action needs the admin token. Enter it on the Contributions page.",
        )


def is_local_request(request: Request) -> bool:
    """True when the browser runs on the computer serving the dashboard.

    Both the client address and the Host header must be loopback. The Host check stops a
    web page that points its own domain at 127.0.0.1 (DNS rebinding) from passing.
    """
    try:
        address = ipaddress.ip_address(request.client.host if request.client else "")
    except ValueError:
        return False
    mapped = getattr(address, "ipv4_mapped", None)
    if mapped is not None:
        address = mapped
    host = (request.url.hostname or "").lower()
    return address.is_loopback and (host in ("localhost", "::1") or host.startswith("127."))


def require_local_or_admin(request: Request, x_admin_token: str | None = Header(default=None)) -> None:
    """Guard API key changes: the admin token when one is set, otherwise this computer only."""
    expected = request.app.state.settings.admin_token
    if expected is not None:
        if x_admin_token is None or not hmac.compare_digest(x_admin_token, expected):
            raise ApiError(
                401,
                "admin_token_required",
                "Changing the API key needs the admin token. Enter it on the Contributions page.",
            )
        return
    if not is_local_request(request):
        raise ApiError(
            403,
            "local_only",
            "The API key can only be changed on the computer running the dashboard, at http://localhost.",
        )


# Reviewers ----------------------------------------------------------------------
#
# Verified reviewers each have their own token, sent in the same X-Admin-Token header the
# dashboard already uses. The admin token (LIKHIBI_ADMIN_TOKEN) can do everything,
# including adding and removing reviewers. Without an admin token the dashboard runs open,
# for local development, as it always has.


@dataclass(frozen=True)
class Reviewer:
    name: str
    role: str  # "admin" or "reviewer"
    languages: tuple[str, ...]  # empty: every language

    @property
    def is_admin(self) -> bool:
        return self.role == "admin"

    def may_review(self, *langs: str) -> bool:
        """English is the pivot; a reviewer is scoped by the other language of a pair."""
        if self.is_admin or not self.languages:
            return True
        return any(lang in self.languages for lang in langs if lang != "en")


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def current_reviewer(request: Request, x_admin_token: str | None = Header(default=None)) -> Reviewer:
    expected = request.app.state.settings.admin_token
    if x_admin_token and expected is not None and hmac.compare_digest(x_admin_token, expected):
        return Reviewer("admin", "admin", ())
    if x_admin_token:
        row = request.app.state.storage.reviewer_by_token_hash(hash_token(x_admin_token))
        if row is not None:
            languages = tuple(code for code in row["languages"].split(",") if code)
            return Reviewer(row["name"], row["role"], languages)
    if expected is None:
        return Reviewer("local", "admin", ())
    raise ApiError(
        401,
        "admin_token_required",
        "Reviewing needs a reviewer token. Enter yours on the Contributions page.",
    )


def require_reviewer(reviewer: Reviewer = Depends(current_reviewer)) -> Reviewer:
    return reviewer


def require_admin_reviewer(reviewer: Reviewer = Depends(current_reviewer)) -> Reviewer:
    if not reviewer.is_admin:
        raise ApiError(403, "admin_only", "Only the dashboard admin can do this.")
    return reviewer
