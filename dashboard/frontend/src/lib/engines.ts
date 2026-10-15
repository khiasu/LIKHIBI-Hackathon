import type { EngineState } from "./types";
import type { Tone } from "../components/ui";

export const KIND_META: Record<string, { label: string }> = {
  "llm-rag": { label: "LLM with retrieval" },
  nmt: { label: "Fine-tuned NMT" },
  "llm-finetuned": { label: "Fine-tuned LLM" },
  "translation-memory": { label: "Translation memory" },
  "rule-based": { label: "Rule-based" },
  mock: { label: "Mock, for development" },
};

export function kindMeta(kind: string) {
  return KIND_META[kind] ?? { label: kind };
}

export const STATE_META: Record<EngineState, { tone: Tone; label: string }> = {
  ready: { tone: "good", label: "Ready" },
  not_configured: { tone: "warning", label: "Not set up" },
  error: { tone: "critical", label: "Error" },
};

export const EVIDENCE_META: Record<string, { label: string }> = {
  example: { label: "Example" },
  glossary: { label: "Glossary" },
  memory: { label: "Translation memory" },
};
