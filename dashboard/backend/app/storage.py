"""SQLite storage for translation logs and user feedback.

Feedback with a corrected translation is the seed of new parallel data. An
approved correction is a human-verified sentence pair, which is exactly the
everyday-domain data Nagamese translation lacks.

Every shared translation records the contributor's consent as a license
(CC BY-SA 4.0) and, if they want credit, the name to credit. Ratings carry no
text of the contributor's own, so they need neither.
"""

from __future__ import annotations

import sqlite3
from contextlib import closing
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

FEEDBACK_STATUSES = ("pending", "approved", "rejected")

_SCHEMA = """
CREATE TABLE IF NOT EXISTS translations (
    id             TEXT PRIMARY KEY,
    created_at     TEXT NOT NULL,
    engine_id      TEXT NOT NULL,
    engine_version TEXT NOT NULL,
    source_lang    TEXT NOT NULL,
    target_lang    TEXT NOT NULL,
    source_text    TEXT NOT NULL,
    translation    TEXT NOT NULL,
    latency_ms     REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS feedback (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at            TEXT NOT NULL,
    translation_id        TEXT,
    source_lang           TEXT NOT NULL,
    target_lang           TEXT NOT NULL,
    source_text           TEXT NOT NULL,
    machine_translation   TEXT,
    corrected_translation TEXT,
    rating                TEXT,
    comment               TEXT,
    engine_id             TEXT,
    status                TEXT NOT NULL DEFAULT 'pending',
    reviewed_at           TEXT,
    contributor           TEXT,
    license               TEXT
);

CREATE INDEX IF NOT EXISTS idx_feedback_status ON feedback (status);
"""


# Columns added after the first release; older databases gain them on start-up.
_ADDED_FEEDBACK_COLUMNS = {"contributor": "TEXT", "license": "TEXT"}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


@dataclass(frozen=True)
class TranslationRecord:
    id: str
    created_at: str
    engine_id: str
    engine_version: str
    source_lang: str
    target_lang: str
    source_text: str
    translation: str
    latency_ms: float


class Storage:
    def __init__(self, path: Path) -> None:
        self.path = path

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.path, timeout=10)
        conn.row_factory = sqlite3.Row
        return conn

    def _query(self, sql: str, params: tuple[Any, ...] = ()) -> list[dict[str, Any]]:
        with closing(self._connect()) as conn:
            return [dict(row) for row in conn.execute(sql, params)]

    def init(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with closing(self._connect()) as conn:
            conn.execute("PRAGMA journal_mode=WAL")
            with conn:
                conn.executescript(_SCHEMA)
                existing = {row["name"] for row in conn.execute("PRAGMA table_info(feedback)")}
                for column, kind in _ADDED_FEEDBACK_COLUMNS.items():
                    if column not in existing:
                        conn.execute(f"ALTER TABLE feedback ADD COLUMN {column} {kind}")

    # Translations -------------------------------------------------------

    def record_translation(self, record: TranslationRecord) -> None:
        with closing(self._connect()) as conn, conn:
            conn.execute(
                "INSERT INTO translations (id, created_at, engine_id, engine_version, source_lang,"
                " target_lang, source_text, translation, latency_ms)"
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    record.id,
                    record.created_at,
                    record.engine_id,
                    record.engine_version,
                    record.source_lang,
                    record.target_lang,
                    record.source_text,
                    record.translation,
                    record.latency_ms,
                ),
            )

    def translation_count(self) -> int:
        return self._query("SELECT COUNT(*) AS n FROM translations")[0]["n"]

    # Feedback -----------------------------------------------------------

    def add_feedback(self, data: dict[str, Any]) -> dict[str, Any]:
        with closing(self._connect()) as conn, conn:
            cursor = conn.execute(
                "INSERT INTO feedback (created_at, translation_id, source_lang, target_lang,"
                " source_text, machine_translation, corrected_translation, rating, comment,"
                " engine_id, contributor, license) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    utc_now(),
                    data.get("translation_id"),
                    data["source_lang"],
                    data["target_lang"],
                    data["source_text"],
                    data.get("machine_translation"),
                    data.get("corrected_translation"),
                    data.get("rating"),
                    data.get("comment"),
                    data.get("engine_id"),
                    data.get("contributor"),
                    data.get("license"),
                ),
            )
            feedback_id = cursor.lastrowid
        created = self.get_feedback(feedback_id)
        assert created is not None
        return created

    def get_feedback(self, feedback_id: int) -> dict[str, Any] | None:
        rows = self._query("SELECT * FROM feedback WHERE id = ?", (feedback_id,))
        return rows[0] if rows else None

    def list_feedback(self, status: str | None, limit: int, offset: int) -> list[dict[str, Any]]:
        if status is None:
            sql = "SELECT * FROM feedback ORDER BY id DESC LIMIT ? OFFSET ?"
            params: tuple[Any, ...] = (limit, offset)
        else:
            sql = "SELECT * FROM feedback WHERE status = ? ORDER BY id DESC LIMIT ? OFFSET ?"
            params = (status, limit, offset)
        return self._query(sql, params)

    def feedback_counts(self) -> dict[str, int]:
        counts = {status: 0 for status in FEEDBACK_STATUSES}
        for row in self._query("SELECT status, COUNT(*) AS n FROM feedback GROUP BY status"):
            counts[row["status"]] = row["n"]
        counts["all"] = sum(counts[status] for status in FEEDBACK_STATUSES)
        return counts

    def set_feedback_status(self, feedback_id: int, status: str) -> dict[str, Any] | None:
        if status not in FEEDBACK_STATUSES:
            raise ValueError(f"Unknown feedback status: {status}")
        reviewed_at = None if status == "pending" else utc_now()
        with closing(self._connect()) as conn, conn:
            conn.execute(
                "UPDATE feedback SET status = ?, reviewed_at = ? WHERE id = ?",
                (status, reviewed_at, feedback_id),
            )
        return self.get_feedback(feedback_id)

    def delete_feedback(self, feedback_id: int) -> bool:
        """Remove a contribution for good, for example when its author withdraws consent."""
        with closing(self._connect()) as conn, conn:
            return conn.execute("DELETE FROM feedback WHERE id = ?", (feedback_id,)).rowcount > 0

    def community_stats(self) -> dict[str, int]:
        shared = "corrected_translation IS NOT NULL AND TRIM(corrected_translation) != ''"
        row = self._query(
            f"SELECT SUM(status = 'approved' AND {shared}) AS approved,"
            f" SUM(status = 'pending' AND {shared}) AS pending,"
            " COUNT(DISTINCT CASE WHEN status = 'approved' AND TRIM(COALESCE(contributor, '')) != ''"
            " THEN LOWER(TRIM(contributor)) END) AS contributors"
            " FROM feedback"
        )[0]
        return {
            "approved_pairs": row["approved"] or 0,
            "pending": row["pending"] or 0,
            "contributors": row["contributors"] or 0,
            "translations": self.translation_count(),
        }

    def contributed_sources(self, source_lang: str) -> set[str]:
        """Lower-cased source texts that already have a shared translation waiting or approved."""
        rows = self._query(
            "SELECT LOWER(TRIM(source_text)) AS text FROM feedback WHERE source_lang = ?"
            " AND status != 'rejected' AND corrected_translation IS NOT NULL",
            (source_lang,),
        )
        return {row["text"] for row in rows}

    def approved_corrections(self) -> list[dict[str, Any]]:
        """Human-verified sentence pairs, ready to use as training or retrieval data."""
        return self._query(
            "SELECT * FROM feedback WHERE status = 'approved'"
            " AND corrected_translation IS NOT NULL AND TRIM(corrected_translation) != ''"
            " ORDER BY id"
        )
