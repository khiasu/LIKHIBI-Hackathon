import {
  BookOpenText,
  Cpu,
  Database,
  FlaskConical,
  Languages,
  Quote,
  Sparkles,
  Workflow,
  type LucideIcon,
} from "lucide-react";
import type { EngineState } from "./types";
import type { Tone } from "../components/ui";

export const KIND_META: Record<string, { label: string; icon: LucideIcon }> = {
  "llm-rag": { label: "LLM + retrieval", icon: Sparkles },
  nmt: { label: "Fine-tuned NMT", icon: Languages },
  "llm-finetuned": { label: "Fine-tuned LLM", icon: Cpu },
  "translation-memory": { label: "Translation memory", icon: Database },
  "rule-based": { label: "Rule-based", icon: Workflow },
  mock: { label: "Mock, for development", icon: FlaskConical },
};

export function kindMeta(kind: string) {
  return KIND_META[kind] ?? { label: kind, icon: Languages };
}

export const STATE_META: Record<EngineState, { tone: Tone; label: string }> = {
  ready: { tone: "good", label: "Ready" },
  not_configured: { tone: "warning", label: "Not configured" },
  error: { tone: "critical", label: "Error" },
};

export const EVIDENCE_META: Record<string, { label: string; icon: LucideIcon }> = {
  example: { label: "Example", icon: Quote },
  glossary: { label: "Glossary", icon: BookOpenText },
  memory: { label: "Translation memory", icon: Database },
};
