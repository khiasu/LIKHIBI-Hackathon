"""Likhibi translation dashboard backend (FastAPI)."""

import sys
from pathlib import Path

__version__ = "0.1.0"

# dashboard/backend/app/__init__.py -> repository root is three levels up.
REPO_ROOT = Path(__file__).resolve().parents[3]

# The engine contract and the engines themselves live in nlp_research/ at the
# repository root, so the root must be importable.
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))
