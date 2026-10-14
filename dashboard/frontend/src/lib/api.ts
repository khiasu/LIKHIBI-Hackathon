import type {
  CommunityStats,
  CompareItem,
  DataHealth,
  DataSource,
  EnginesResponse,
  EvalRun,
  EvalRunDetail,
  Feedback,
  FeedbackInput,
  FeedbackList,
  FeedbackStatus,
  LangCode,
  LlmSettings,
  MeaningTasks,
  Meta,
  Translation,
} from "./types";

// Empty means "same origin": the Vite proxy in development, FastAPI in production.
const API_BASE = (import.meta.env.VITE_API_BASE as string | undefined)?.replace(/\/$/, "") ?? "";
const TOKEN_KEY = "likhibi-admin-token";

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details: Record<string, unknown>;

  constructor(status: number, code: string, message: string, details: Record<string, unknown> = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

export function getAdminToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setAdminToken(token: string | null): void {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else localStorage.removeItem(TOKEN_KEY);
  } catch {
    // Storage can be unavailable (private mode); the token then lasts for this page only.
  }
}

async function send(path: string, init: RequestInit = {}, admin = false): Promise<Response> {
  const headers = new Headers(init.headers);
  if (init.body) headers.set("Content-Type", "application/json");
  if (admin) {
    const token = getAdminToken();
    if (token) headers.set("X-Admin-Token", token);
  }
  let response: Response;
  try {
    response = await fetch(`${API_BASE}/api${path}`, { ...init, headers });
  } catch {
    throw new ApiError(0, "network_error", "Can't reach the Likhibi server. Check that the backend is running.");
  }
  if (!response.ok) {
    let code = "http_error";
    let message = `Request failed (${response.status})`;
    let details: Record<string, unknown> = {};
    try {
      const body = await response.json();
      if (body?.error) {
        code = body.error.code ?? code;
        message = body.error.message ?? message;
        details = body.error.details ?? {};
      } else if (typeof body?.detail === "string") {
        message = body.detail;
      }
    } catch {
      // Not JSON (e.g. a proxy error page); keep the generic message.
    }
    throw new ApiError(response.status, code, message, details);
  }
  return response;
}

async function json<T>(path: string, init?: RequestInit, admin = false): Promise<T> {
  return (await send(path, init, admin)).json() as Promise<T>;
}

export const api = {
  meta: () => json<Meta>("/meta"),
  engines: () => json<EnginesResponse>("/engines"),

  llmSettings: () => json<LlmSettings>("/settings/llm"),
  saveLlmKey: (apiKey: string) =>
    json<LlmSettings>("/settings/llm-key", { method: "PUT", body: JSON.stringify({ api_key: apiKey }) }, true),
  removeLlmKey: () => json<LlmSettings>("/settings/llm-key", { method: "DELETE" }, true),

  translate: (body: {
    text: string;
    source_lang: LangCode;
    target_lang: LangCode;
    engine: string;
    num_alternatives?: number;
  }) => json<Translation>("/translate", { method: "POST", body: JSON.stringify(body) }),

  compare: (body: { text: string; source_lang: LangCode; target_lang: LangCode }) =>
    json<{ results: CompareItem[] }>("/translate/compare", { method: "POST", body: JSON.stringify(body) }),

  submitFeedback: (body: FeedbackInput) =>
    json<Feedback>("/feedback", { method: "POST", body: JSON.stringify(body) }),

  listFeedback: (status: FeedbackStatus | "all") =>
    json<FeedbackList>(`/feedback?status=${status}&limit=200`, undefined, true),

  reviewFeedback: (id: number, status: FeedbackStatus) =>
    json<Feedback>(`/feedback/${id}`, { method: "PATCH", body: JSON.stringify({ status }) }, true),

  exportCorrections: async (): Promise<Blob> => (await send("/feedback/export.tsv", {}, true)).blob(),
  deleteFeedback: async (id: number): Promise<void> => {
    await send(`/feedback/${id}`, { method: "DELETE" }, true);
  },
  communityStats: () => json<CommunityStats>("/community/stats"),
  meaningTasks: (skip: string[] = [], count = 5) =>
    json<MeaningTasks>(`/lexicon/needs-meaning?count=${count}&skip=${encodeURIComponent(skip.join(","))}`),

  dataHealth: () => json<DataHealth>("/data/health"),
  dataSources: () => json<DataSource[]>("/data/sources"),

  evalRuns: () => json<{ runs: EvalRun[]; invalid_files: { file: string; error: string }[] }>("/eval/runs"),
  evalRun: (runId: string) => json<EvalRunDetail>(`/eval/runs/${encodeURIComponent(runId)}`),
};
