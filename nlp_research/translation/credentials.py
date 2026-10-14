"""
API keys saved from the dashboard, kept in the operating system's credential store.

That is Credential Manager on Windows, the Keychain on macOS, and the Secret Service
(GNOME Keyring or KWallet) on Linux. The store encrypts the key for the signed-in
user, so it never sits in the repository, a config file or the browser. Where no
secure store exists, as on most servers, nothing is saved: set the key as an
environment variable there instead.

Needs the keyring package (dashboard/backend/requirements.txt). Without it, saving
is unavailable and engines fall back to environment variables.
"""

from __future__ import annotations

import logging
from typing import Any

logger = logging.getLogger(__name__)

SERVICE = "likhibi"
GEMINI_API_KEY = "gemini-api-key"

# Only stores that encrypt for the signed-in user count; plaintext backends never do.
_SECURE_BACKENDS = {
    "keyring.backends.Windows": "Windows Credential Manager",
    "keyring.backends.macOS": "macOS Keychain",
    "keyring.backends.SecretService": "Secret Service keyring",
    "keyring.backends.libsecret": "Secret Service keyring",
    "keyring.backends.kwallet": "KWallet",
}


class CredentialStoreError(Exception):
    """The credential store is missing or refused to save."""


def _secure_backend() -> tuple[Any, str] | None:
    """The OS credential store and its display name, or None when there is none."""
    try:
        import keyring
    except ImportError:
        return None
    try:
        backend = keyring.get_keyring()
    except Exception:
        logger.warning("The credential store could not be opened.")
        return None
    candidates = getattr(backend, "backends", None) or [backend]  # a chainer wraps several stores
    for candidate in candidates:
        name = _SECURE_BACKENDS.get(type(candidate).__module__)
        if name:
            return candidate, name
    return None


def store_name() -> str | None:
    """Name of the secure store keys are saved in, or None when keys cannot be saved here."""
    found = _secure_backend()
    return found[1] if found else None


def get_secret(name: str) -> str | None:
    found = _secure_backend()
    if not found:
        return None
    try:
        return found[0].get_password(SERVICE, name) or None
    except Exception:  # a locked store must not stop the engines from loading
        logger.warning("Could not read %s from the %s.", name, found[1])
        return None


def set_secret(name: str, value: str) -> None:
    found = _secure_backend()
    if not found:
        raise CredentialStoreError(
            "This computer has no secure credential store. Set the key as an environment variable instead."
        )
    try:
        found[0].set_password(SERVICE, name, value)
    except Exception as exc:
        raise CredentialStoreError(f"The {found[1]} refused to save the key ({type(exc).__name__}).") from None


def delete_secret(name: str) -> bool:
    """Remove a saved secret. Returns whether one was there."""
    found = _secure_backend()
    if not found:
        return False
    try:
        if found[0].get_password(SERVICE, name) is None:
            return False
        found[0].delete_password(SERVICE, name)
        return True
    except Exception as exc:
        raise CredentialStoreError(f"The {found[1]} refused to remove the key ({type(exc).__name__}).") from None
