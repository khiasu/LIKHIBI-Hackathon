// Mirrors dashboard/backend/app/schemas.py. Keep the two in step.

export type LangCode = "en" | "nag";
export type EngineState = "ready" | "not_configured" | "error";
export type FeedbackStatus = "pending" | "approved" | "rejected";

export interface Meta {
  app_version: string;
  languages: { code: LangCode; name: string }[];
  max_chars: number;
  log_translations: boolean;
  review_requires_token: boolean;
  mock_engine_enabled: boolean;
}

export interface EngineSummary {
  id: string;
  name: string;
  kind: string;
  version: string;
}

export interface Engine extends EngineSummary {
  description: string;
  directions: string[];
  offline: boolean;
  state: EngineState;
  status_message: string | null;
  is_default: boolean;
}

export interface EnginesResponse {
  default_engine: string | null;
  engines: Engine[];
  broken: { spec: string; error: string }[];
}

export interface Evidence {
  kind: "example" | "glossary" | "memory" | string;
  source_text: string;
  target_text: string;
  origin: string;
  similarity: number | null;
}

export interface Translation {
  request_id: string;
  created_at: string;
  engine: EngineSummary;
  source_lang: LangCode;
  target_lang: LangCode;
  source_text: string;
  translation: string;
  alternatives: { text: string; score: number | null }[];
  confidence: number | null;
  evidence: Evidence[];
  warnings: string[];
  latency_ms: number;
}

export interface CompareItem {
  engine: EngineSummary;
  ok: boolean;
  result: Translation | null;
  error: string | null;
  declined: boolean;
}

export interface FeedbackInput {
  translation_id?: string | null;
  source_lang: LangCode;
  target_lang: LangCode;
  source_text: string;
  machine_translation?: string | null;
  corrected_translation?: string | null;
  rating?: "up" | "down" | null;
  comment?: string | null;
  engine_id?: string | null;
  contributor?: string | null;
  /** Sharing a translation publishes it under CC BY-SA 4.0, so it needs an explicit yes. */
  share_consent?: boolean;
}

export interface Feedback {
  id: number;
  created_at: string;
  translation_id: string | null;
  source_lang: LangCode;
  target_lang: LangCode;
  source_text: string;
  machine_translation: string | null;
  corrected_translation: string | null;
  rating: "up" | "down" | null;
  comment: string | null;
  engine_id: string | null;
  status: FeedbackStatus;
  reviewed_at: string | null;
  contributor: string | null;
  license: string | null;
}

export interface FeedbackList {
  items: Feedback[];
  counts: Record<FeedbackStatus | "all", number>;
}

export interface Check {
  id: string;
  label: string;
  current: number;
  target: number;
  passed: boolean;
  hint: string;
}

export interface ParallelFile {
  file: string;
  domain: string;
  schema_ok: boolean;
  schema_error: string | null;
  rows: number;
  english_real: number;
  english_placeholder: number;
  english_empty: number;
  nagamese_empty: number;
  duplicate_verse_ids: number;
  impossible_references: number;
  suspected_noise_rows: number;
  long_rows: number;
  avg_nagamese_tokens: number;
  books: Record<string, number>;
  nt_books_missing: string[];
  real_pairs_by_domain: Record<string, number>;
}

export interface DataHealth {
  generated_at: string;
  checks: Check[];
  parallel: ParallelFile[];
  monolingual: { file: string; exists: boolean; lines: number; tokens: number; word_types: number };
  lexicon: {
    file: string;
    exists: boolean;
    entries: number;
    glossed: number;
    pos_tagged: number;
    attested_in_corpus: number;
    etymology: Record<string, number>;
    auto_glossary_entries: number;
  };
  test_sets: { file: string; rows: number; domain: string }[];
  community: { approved_pairs: number; pending: number; approved: number; rejected: number };
}

export interface DataSource {
  id: string;
  name: string;
  description: string;
  size: string;
  license: string;
  url: string;
  expected_path: string;
  imported: boolean;
}

export interface EvalRun {
  run_id: string;
  engine_id: string;
  engine_version: string;
  test_set: string;
  direction: string;
  n_segments: number;
  metrics: Record<string, number>;
  created_at: string;
  notes: string | null;
  file: string;
}

export interface EvalRunDetail extends EvalRun {
  segments: { source: string; reference: string; hypothesis: string; chrf: number | null }[];
}

export interface LlmSettings {
  provider: string;
  model: string | null;
  key_source: "dashboard" | "environment" | null;
  key_env_var: string | null;
  /** Last four characters only: the server never sends the key itself. */
  key_hint: string | null;
  /** Where saved keys go, e.g. "Windows Credential Manager"; null when keys cannot be saved. */
  store: string | null;
  can_edit: boolean;
  edit_requires_token: boolean;
  warning: string | null;
}

export interface CommunityStats {
  approved_pairs: number;
  pending: number;
  contributors: number;
  translations: number;
}

export interface MeaningTask {
  lemma: string;
  frequency: number;
  origin: string | null;
  /** Unverified machine guesses from the auto-glossary. */
  guesses: string[];
}

export interface MeaningTasks {
  tasks: MeaningTask[];
  remaining: number;
  total: number;
}
