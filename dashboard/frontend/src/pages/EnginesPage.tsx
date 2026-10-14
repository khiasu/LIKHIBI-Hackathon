import { ExternalLink, KeyRound, Plug, RefreshCw, Trash2, Wifi, WifiOff } from "lucide-react";
import { useState } from "react";
import type { FormEvent, ReactNode } from "react";
import { useApp, useToast } from "../components/AppContext";
import {
  Button,
  Chip,
  CodeBlock,
  EmptyState,
  InlineAlert,
  PageHeader,
  SectionHeading,
  Skeleton,
  StatusBadge,
} from "../components/ui";
import { api, getAdminToken } from "../lib/api";
import { kindMeta, STATE_META } from "../lib/engines";
import { shortDirection } from "../lib/format";
import { useAsync } from "../lib/hooks";
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
        eyebrow="Under the hood"
        title="Translation engines"
        description="Every engine implements one contract, so the dashboard, the evaluation harness and later the keyboard can use any of them. Auto mode tries the default engine first and falls back to the next ready one."
        actions={
          <Button icon={RefreshCw} onClick={() => void refreshEngines()}>
            Refresh
          </Button>
        }
      />

      {enginesError && (
        <div className="mb-6">
          <InlineAlert tone="critical" title="Could not load engines">
            {enginesError.message}
          </InlineAlert>
        </div>
      )}

      {!engines && !enginesError && (
        <div className="grid gap-4 md:grid-cols-2">
          <Skeleton className="h-40" />
          <Skeleton className="h-40" />
        </div>
      )}

      {engines && (
        <section className="reveal mb-12">
          <SectionHeading
            title="Registered"
            description={
              registered.length
                ? `${registered.length} registered, ${ready} ready${engines.default_engine ? `, default: ${engines.default_engine}` : ""}.`
                : undefined
            }
          />
          {registered.length === 0 ? (
            <EmptyState icon={Plug} title="No engines are registered yet">
              <p>
                That is expected at this stage: the dashboard was built first, and the engine plugs in later. Follow the
                three steps below to connect one.
              </p>
            </EmptyState>
          ) : (
            <div className="grid gap-4 md:grid-cols-2">
              {registered.map((engine) => (
                <EngineCard key={engine.id} engine={engine} />
              ))}
            </div>
          )}

          {engines.broken.length > 0 && (
            <div className="mt-6 space-y-3">
              <h3 className="font-semibold text-ink">Specs that failed to load</h3>
              {engines.broken.map((item) => (
                <InlineAlert key={item.spec} tone="critical" title={item.spec}>
                  <span className="font-mono text-[13px]">{item.error}</span>
                </InlineAlert>
              ))}
            </div>
          )}
        </section>
      )}

      <section className="reveal mb-12" style={{ animationDelay: "80ms" }}>
        <SectionHeading
          title="Connect an engine"
          description="The contract lives in nlp_research/translation/engine_api.py and needs only the standard library, so engines can be built in a notebook."
        />
        <ol className="space-y-5">
          <Step number={1} title="Implement the contract">
            <CodeBlock code={ENGINE_SKELETON} label="Python" />
          </Step>
          <Step number={2} title="Register it with environment variables">
            <CodeBlock code={REGISTER} label="Environment" />
          </Step>
          <Step number={3} title="Measure it">
            <p className="text-[15px] leading-relaxed text-ink-2">
              Run <code className="font-mono text-[13.5px]">python -m nlp_research.translation.evaluate_engine</code> on a
              held-out test set. The run appears on the Evaluation page.
            </p>
          </Step>
        </ol>
      </section>
    </div>
  );
}

function EngineCard({ engine }: { engine: Engine }) {
  const { icon: Icon, label } = kindMeta(engine.kind);
  const state = STATE_META[engine.state];
  const isLlm = engine.kind === "llm-rag";
  return (
    <article className="card flex flex-col gap-3 p-5">
      <header className="flex items-start justify-between gap-3">
        <div className="flex items-center gap-3">
          <span className="grid size-10 place-items-center rounded-xl bg-sunken text-ink-2">
            <Icon aria-hidden className="size-5" />
          </span>
          <div>
            <h3 className="text-[17px] leading-tight font-semibold text-ink">{engine.name}</h3>
            <p className="font-mono text-[12.5px] text-muted">
              {engine.id} · v{engine.version}
            </p>
          </div>
        </div>
        <StatusBadge tone={state.tone} label={state.label} />
      </header>
      {engine.description && <p className="text-[14.5px] leading-relaxed text-ink-2">{engine.description}</p>}
      {engine.status_message && !(isLlm && engine.state === "not_configured") && (
        <p className="text-[14px] text-ink">{engine.status_message}</p>
      )}
      {isLlm && <LlmKeyPanel />}
      <div className="mt-auto flex flex-wrap gap-1.5">
        <Chip>{label}</Chip>
        {engine.directions.map((direction) => (
          <Chip key={direction}>{shortDirection(direction)}</Chip>
        ))}
        <Chip>
          {engine.offline ? <WifiOff aria-hidden className="size-3" /> : <Wifi aria-hidden className="size-3" />}
          {engine.offline ? "Works offline" : "Needs internet"}
        </Chip>
        {engine.is_default && <Chip>Default</Chip>}
      </div>
    </article>
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
    return <InlineAlert tone="warning" title="Could not load the API key settings">{settings.error.message}</InlineAlert>;
  }
  if (!current) return <Skeleton className="h-28" />;
  if (current.provider !== "gemini") {
    return (
      <p className="text-[14px] text-ink-2">
        This engine uses an OpenAI-compatible server set by{" "}
        <code className="font-mono text-[13px]">LIKHIBI_LLM_PROVIDER</code>. Its key comes from the server environment.
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

  return (
    <div className="space-y-3 rounded-xl border border-line bg-surface-2 p-4">
      <div className="flex items-center gap-2 font-semibold text-ink">
        <KeyRound aria-hidden className="size-4 text-accent-text" />
        Gemini API key
      </div>

      {current.key_source === "dashboard" && (
        <p className="text-[14px] text-ink-2">
          Saved in {current.store}, ending in <code className="font-mono text-[13px] text-ink">••••{current.key_hint}</code>.
        </p>
      )}
      {current.key_source === "environment" && (
        <p className="text-[14px] text-ink-2">
          Using <code className="font-mono text-[13px]">{current.key_env_var}</code> from the server environment, ending in{" "}
          <code className="font-mono text-[13px] text-ink">••••{current.key_hint}</code>. A key saved here takes its place.
        </p>
      )}
      {current.key_source === null && (
        <p className="text-[14px] text-ink-2">
          Paste a key to switch this engine on. Keys are free from{" "}
          <a href={AI_STUDIO} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 font-semibold text-accent-text underline-offset-2 hover:underline">
            Google AI Studio
            <ExternalLink aria-hidden className="size-3.5" />
          </a>
          .
        </p>
      )}

      {!current.can_edit && (
        <p className="text-[14px] text-muted">To change the key, open the dashboard on the computer running it, at localhost.</p>
      )}
      {current.can_edit && current.store === null && (
        <p className="text-[14px] text-muted">
          This computer has no secure credential store, so keys cannot be saved here. Set{" "}
          <code className="font-mono text-[13px]">GEMINI_API_KEY</code> on the server instead.
        </p>
      )}
      {canSave && current.edit_requires_token && !getAdminToken() && (
        <p className="text-[14px] text-muted">Changing the key needs the admin token. Enter it on the Contributions page first.</p>
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
              className="h-10 min-w-0 flex-1 basis-56 rounded-lg border border-line-strong bg-surface px-3 font-mono text-[14px] text-ink focus:border-accent focus:outline-none"
            />
            <Button variant="primary" type="submit" loading={busy === "save"} disabled={!draft.trim() || busy !== null}>
              {busy === "save" ? "Checking with Google" : "Save key"}
            </Button>
            {replacing && (
              <Button variant="ghost" onClick={() => { setReplacing(false); setDraft(""); setError(null); }}>
                Cancel
              </Button>
            )}
          </div>
          <p className="text-[13px] leading-relaxed text-muted">
            The key is checked with Google, then kept in {current.store}, encrypted for your user account. It never goes
            into the project files or this browser, and is never shown again.
          </p>
        </form>
      )}

      {canSave && current.key_source !== null && !replacing && (
        <div className="flex flex-wrap gap-2">
          <Button size="sm" icon={KeyRound} onClick={() => { setReplacing(true); setConfirmRemove(false); setError(null); }}>
            {current.key_source === "dashboard" ? "Replace key" : "Save a key here"}
          </Button>
          {current.key_source === "dashboard" &&
            (confirmRemove ? (
              <>
                <Button size="sm" variant="danger" icon={Trash2} loading={busy === "remove"} onClick={() => void remove()}>
                  Remove key
                </Button>
                <Button size="sm" variant="ghost" onClick={() => setConfirmRemove(false)}>
                  Keep it
                </Button>
              </>
            ) : (
              <Button size="sm" variant="ghost" icon={Trash2} onClick={() => setConfirmRemove(true)}>
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
    <li className="grid gap-3 sm:grid-cols-[40px_1fr]">
      <span className="keycap grid size-9 place-items-center rounded-full text-[15px] font-semibold text-ink">
        {number}
      </span>
      <div className="min-w-0">
        <h3 className="mb-2 pt-1.5 font-medium text-ink">{title}</h3>
        {children}
      </div>
    </li>
  );
}
