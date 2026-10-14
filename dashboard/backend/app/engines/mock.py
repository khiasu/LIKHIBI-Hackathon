"""Development-only engine that echoes its input.

It exists so the dashboard can be built and demonstrated end to end before a
real engine is connected. Its output is never a translation, and every result
carries a warning saying so. Enable it with LIKHIBI_ENABLE_MOCK_ENGINE=1.
"""

from __future__ import annotations

from nlp_research.translation.engine_api import (
    EN_TO_NAG,
    NAG_TO_EN,
    Alternative,
    EngineInfo,
    EngineKind,
    Evidence,
    EvidenceKind,
    TranslationEngine,
    TranslationRequest,
    TranslationResult,
)

MOCK_WARNING = "Mock engine: this output is the input echoed back, not a translation."


class MockEngine(TranslationEngine):
    info = EngineInfo(
        id="mock",
        name="Mock engine",
        kind=EngineKind.MOCK,
        version="0.0.0",
        directions=(EN_TO_NAG, NAG_TO_EN),
        description="Echoes the input so the dashboard can be developed before a real engine exists.",
        offline=True,
    )

    def translate(self, request: TranslationRequest) -> TranslationResult:
        text = request.text.strip()
        return TranslationResult(
            translation=f"[mock] {text}",
            alternatives=tuple(
                Alternative(text=f"[mock alternative {n}] {text}")
                for n in range(1, request.num_alternatives + 1)
            ),
            evidence=(
                Evidence(
                    kind=EvidenceKind.EXAMPLE,
                    source_text="(a retrieved source sentence appears here)",
                    target_text="(its human translation appears here)",
                    origin="mock",
                ),
            ),
            warnings=(MOCK_WARNING,),
        )
