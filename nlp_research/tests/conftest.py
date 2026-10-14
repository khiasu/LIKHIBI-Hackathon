from __future__ import annotations

import pytest

from nlp_research.translation import credentials


class MemoryCredentialStore:
    """Stands in for the OS credential store, so tests never read or write a real key."""

    def __init__(self) -> None:
        self.saved: dict[tuple[str, str], str] = {}

    def get_password(self, service: str, name: str) -> str | None:
        return self.saved.get((service, name))

    def set_password(self, service: str, name: str, value: str) -> None:
        self.saved[(service, name)] = value

    def delete_password(self, service: str, name: str) -> None:
        del self.saved[(service, name)]


@pytest.fixture(autouse=True)
def credential_store(monkeypatch) -> MemoryCredentialStore:
    store = MemoryCredentialStore()
    monkeypatch.setattr(credentials, "_secure_backend", lambda: (store, "Test credential store"))
    return store
