"""FastAPI dependencies that hand routes the shared application objects."""

from __future__ import annotations

import hmac
import ipaddress

from fastapi import Header, Request

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
