import { useState } from "react";
import type { FormEvent, ReactNode } from "react";
import { useApp, useToast } from "../components/AppContext";
import { Button, CodeBlock, EmptyState, InlineAlert, PageHeader, SectionHeading, Skeleton, StatusBadge } from "../components/ui";
import { api, getAdminToken } from "../lib/api";
import { kindMeta, STATE_META } from "../lib/engines";
import { shortDirection } from "../lib/format";
import { href, useAsync } from "../lib/hooks";
import type { Engine } from "../lib/types";

const ENGINE_SKELETON = `from nlp_research.translation.engine_api import (
    EN_TO_NAG, NAG_TO_EN, EngineInfo, EngineKind,
    TranslationEngine, TranslationRequest, TranslationResult,
)


class MyEngine(TranslationEngine):
    info = EngineInfo(
        id="my-engine",
        name="My engine",
        kind=EngineKind.NMT,
        version="0.1.0",
        directions=(EN_TO_NAG, NAG_TO_EN),
        offline=True,
    )

    def load(self) -> None:
        ...  # load weights or create an API client, once

    def translate(self, request: TranslationRequest) -> TranslationResult:
        text = ...  # call your model with request.text and request.direction
        return TranslationResult(translation=text)`;

const REGISTER = `# dashboard/backend, then restart the server
LIKHIBI_ENGINES=nlp_research.translation.engines.my_engine:MyEngine
LIKHIBI_DEFAULT_ENGINE=my-engine`;

export function EnginesPage() {
  const { engines, enginesError, refreshEngines } = useApp();
  const registered = engines?.engines ?? [];
  const ready = registered.filter((engine) => engine.state === "ready").length;

  return (
    <div>
      <PageHeader
        title="Engines"
        description="Each engine translates in its own way. In Best available mode the default answers first, then the next ready one."
        actions={<Button onClick={() => void refreshEngines()}>Refresh</Button>}
      />

      {enginesError && (
        <div className="mb-8">
          <InlineAlert tone="critical" title="Could not load engines">
            {enginesError.message}
          </InlineAlert>
        </div>
      )}

      {!engines && !enginesError && (
        <div className="space-y-4">
          <Skeleton className="h-36" />
          <Skeleton className="h-36" />
        </div>
      )}

      {engines && (
        <section className="reveal" aria-labelledby="registered-title">
          <SectionHeading
            id="registered-title"
            title="Registered"
            actions={
              registered.length > 0 && (
                <span className="num font-mono text-[11.5px] text-faint">
                  {ready} of {registered.length} ready
                </span>
              )
            }
          />
          {registered.length === 0 ? (
            <EmptyState title="No engines are registered yet">
              <p>The dashboard runs without one. Follow the three steps below to connect an engine.</p>
            </EmptyState>
          ) : (
            <ul className="-mt-4 divide-y divide-line">
              {registered.map((engine) => (
                <EngineSheet key={engine.id} engine={engine} />
              ))}
            </ul>
          )}

          {engines.broken.length > 0 && (
            <div className="mt-6 space-y-3">
              <h3 className="text-[14px] font-semibold text-ink">Specs that failed to load</h3>
              {engines.broken.map((item) => (
                <InlineAlert key={item.spec} tone="critical" title={item.spec}>
                  <span className="font-mono text-[12.5px]">{item.error}</span>
                </InlineAlert>
              ))}
            </div>
          )}
        </section>
      )}

      <section className="reveal mt-16" aria-labelledby="connect-title">
        <SectionHeading
          id="connect-title"
          title="Connect an engine"
          description={
            <>
              The contract is in <code className="font-mono text-[12.5px] text-ink-2">nlp_research/translation/engine_api.py</code> and needs only
              the standard library, so an engine can start life in a notebook.
            </>
          }
        />
        <ol className="max-w-[880px] space-y-8">
          <Step number={1} title="Implement the contract">
            <CodeBlock code={ENGINE_SKELETON} label="Python" />
          </Step>
          <Step number={2} title="Register it">
            <CodeBlock code={REGISTER} label="Environment" />
          </Step>
          <Step number={3} title="Measure it">
            <p className="text-[14px] leading-relaxed text-ink-2">
              Run <code className="font-mono text-[12.5px] text-ink">python -m nlp_research.translation.evaluate_engine</code> on a held-out
              test set. The scores appear under{" "}
              <a href={href("evaluation")} className="text-ink underline decoration-line-strong underline-offset-4 hover:decoration-ink">
                Evaluation
              </a>
              .
            </p>
          </Step>
        </ol>
      </section>
    </div>
  );
}

/** One engine as a specification: what it is on the left, the facts on the right. */
function EngineSheet({ engine }: { engine: Engine }) {
  const state = STATE_META[engine.state];
  const isLlm = engine.kind === "llm-rag";
  return (
    <li className="grid gap-x-14 gap-y-5 py-7 lg:grid-cols-[minmax(0,1fr)_280px]">
      <div className="min-w-0">
        <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
          <h3 className="text-[18px] leading-tight font-semibold tracking-[-0.015em] text-ink">{engine.name}</h3>
          {engine.is_default && <span className="text-[12px] font-medium text-accent-text">Default</span>}
        </div>
        <p className="mt-1 font-mono text-[11.5px] text-faint">
          {engine.id} · v{engine.version}
        </p>
        {engine.description && <p className="mt-3 max-w-2xl text-[14px] leading-relaxed text-ink-2">{engine.description}</p>}
        {engine.status_message && !(isLlm && engine.state === "not_configured") && (
          <p className="mt-2 max-w-2xl text-[13.5px] leading-relaxed text-muted">{engine.status_message}</p>
        )}
        {isLlm && <LlmKeyPanel />}
      </div>

      <dl className="grid grid-cols-[92px_minmax(0,1fr)] content-start gap-x-4 gap-y-2.5 text-[13px] lg:border-l lg:border-line lg:pl-8">
        <dt className="text-muted">State</dt>
        <dd>
          <StatusBadge {...state} />
        </dd>
        <dt className="text-muted">Method</dt>
        <dd className="text-ink-2">{kindMeta(engine.kind).label}</dd>
        <dt className="text-muted">Directions</dt>
        <dd className="font-mono text-[12px] text-ink-2">{engine.directions.map(shortDirection).join(", ")}</dd>
        <dt className="text-muted">Runs</dt>
        <dd className="text-ink-2">{engine.offline ? "On this computer" : "Online"}</dd>
      </dl>
    </li>
  );
}

const AI_STUDIO = "https://aistudio.google.com/apikey";

function LlmKeyPanel() {
  const { refreshEngines } = useApp();
  const toast = useToast();
  const settings = useAsync(api.llmSettings);
  const [draft, setDraft] = useState("");
  const [replacing, setReplacing] = useState(false);
  const [confirmRemove, setConfirmRemove] = useState(false);
  const [busy, setBusy] = useState<"save" | "remove" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const current = settings.data;

  if (settings.error) {
    return (
      <div className="mt-5">
        <InlineAlert tone="warning" title="Could not load the API key settings">
          {settings.error.message}
        </InlineAlert>
      </div>
    );
  }
  if (!current) return <Skeleton className="mt-5 h-24" />;
  if (current.provider !== "gemini") {
    return (
      <p className="mt-4 text-[13.5px] text-muted">
        This engine uses an OpenAI-compatible server set by <code className="font-mono text-[12.5px]">LIKHIBI_LLM_PROVIDER</code>. Its key comes from
        the server environment.
      </p>
    );
  }

  const canSave = current.can_edit && current.store !== null;
  const showForm = canSave && (current.key_source === null || replacing);

  async function save(event: FormEvent) {
    event.preventDefault();
    const key = draft.trim();
    if (!key) return;
    setBusy("save");
    setError(null);
    try {
      const next = await api.saveLlmKey(key);
      settings.setData(next);
      setDraft("");
      setReplacing(false);
      toast(next.warning ?? "Key saved. The LLM engine is ready.");
      await refreshEngines();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not save the key.");
    } finally {
      setBusy(null);
    }
  }

  async function remove() {
    setBusy("remove");
    setError(null);
    try {
      settings.setData(await api.removeLlmKey());
      setConfirmRemove(false);
      toast("Key removed from this computer.");
      await refreshEngines();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not remove the key.");
    } finally {
      setBusy(null);
    }
  }

  const code = "font-mono text-[12px] text-ink";

  return (
    <div className="mt-5 max-w-2xl space-y-3 rounded-lg border border-line bg-surface-2 p-4">
      <p className="text-[13.5px] font-semibold text-ink">Gemini API key</p>

      {current.key_source === "dashboard" && (
        <p className="text-[13.5px] text-ink-2">
          Saved in {current.store}, ending in <code className={code}>••••{current.key_hint}</code>.
        </p>
      )}
      {current.key_source === "environment" && (
        <p className="text-[13.5px] text-ink-2">
          Using <code className={code}>{current.key_env_var}</code> from the server environment, ending in <code className={code}>••••{current.key_hint}</code>. A key
          saved here takes its place.
        </p>
      )}
      {current.key_source === null && (
        <p className="text-[13.5px] text-ink-2">
          Paste a key to switch this engine on. Keys are free from{" "}
          <a href={AI_STUDIO} target="_blank" rel="noreferrer" className="text-ink underline decoration-line-strong underline-offset-4 hover:decoration-ink">
            Google AI Studio
          </a>
          .
        </p>
      )}

      {!current.can_edit && <p className="text-[13px] text-muted">To change the key, open the dashboard on the computer running it, at localhost.</p>}
      {current.can_edit && current.store === null && (
        <p className="text-[13px] text-muted">
          This computer has no secure credential store, so keys cannot be saved here. Set <code className="font-mono text-[12px]">GEMINI_API_KEY</code> on the
          server instead.
        </p>
      )}
      {canSave && current.edit_requires_token && !getAdminToken() && (
        <p className="text-[13px] text-muted">Changing the key needs the admin token. Sign in on the Review page first.</p>
      )}

      {showForm && (
        <form onSubmit={save} className="space-y-2">
          <div className="flex flex-wrap gap-2">
            <input
              type="password"
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              aria-label="Gemini API key"
              placeholder="Paste your Gemini API key"
              autoComplete="off"
              spellCheck={false}
              className="field h-[34px] min-w-0 flex-1 basis-56 px-2.5 font-mono text-[13px]"
            />
            <Button variant="primary" type="submit" loading={busy === "save"} disabled={!draft.trim() || busy !== null}>
              {busy === "save" ? "Checking with Google" : "Save key"}
            </Button>
            {replacing && (
              <Button
                variant="ghost"
                onClick={() => {
                  setReplacing(false);
                  setDraft("");
                  setError(null);
                }}
              >
                Cancel
              </Button>
            )}
          </div>
          <p className="text-[12.5px] leading-relaxed text-muted">
            Google checks the key, then it is kept in {current.store}, encrypted for your user account. It never goes into the project files or
            this browser, and is not shown again.
          </p>
        </form>
      )}

      {canSave && current.key_source !== null && !replacing && (
        <div className="flex flex-wrap gap-2">
          <Button
            size="sm"
            onClick={() => {
              setReplacing(true);
              setConfirmRemove(false);
              setError(null);
            }}
          >
            {current.key_source === "dashboard" ? "Replace key" : "Save a key here"}
          </Button>
          {current.key_source === "dashboard" &&
            (confirmRemove ? (
              <>
                <Button size="sm" variant="danger" loading={busy === "remove"} onClick={() => void remove()}>
                  Remove key
                </Button>
                <Button size="sm" variant="ghost" onClick={() => setConfirmRemove(false)}>
                  Keep it
                </Button>
              </>
            ) : (
              <Button size="sm" variant="ghost" onClick={() => setConfirmRemove(true)}>
                Remove
              </Button>
            ))}
        </div>
      )}

      {error && <InlineAlert tone="critical" title={error} />}
    </div>
  );
}

function Step({ number, title, children }: { number: number; title: string; children: ReactNode }) {
  return (
    <li className="grid gap-x-6 gap-y-2 md:grid-cols-[48px_minmax(0,1fr)]">
      <span className="num pt-px font-mono text-[12px] text-accent-text">{String(number).padStart(2, "0")}</span>
      <div className="min-w-0">
        <h3 className="mb-3 text-[14.5px] font-semibold text-ink">{title}</h3>
        {children}
      </div>
    </li>
  );
}
