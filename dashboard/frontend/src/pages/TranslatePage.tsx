import {
  ArrowLeftRight,
  GitCompareArrows,
  Languages,
  PencilLine,
  Plug,
  Send,
  ThumbsDown,
  ThumbsUp,
} from "lucide-react";
import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { useApp, useToast } from "../components/AppContext";
import { readVariety, ShareConsent, useCreditName } from "../components/ShareConsent";
import {
  Button,
  ButtonLink,
  Chip,
  CopyButton,
  cx,
  EmptyState,
  InlineAlert,
  Meter,
  PageHeader,
  SectionHeading,
  Skeleton,
} from "../components/ui";
import { api, ApiError } from "../lib/api";
import { EVIDENCE_META, kindMeta } from "../lib/engines";
import { fmt, langName, latency } from "../lib/format";
import { href } from "../lib/hooks";
import type { CompareItem, Evidence, LangCode, Translation } from "../lib/types";

const EXAMPLES: Record<LangCode, string[]> = {
  en: ["Where is the market?", "I will come tomorrow.", "How much does this cost?", "Please write your name here."],
  // Short sentences from the ISV Nagamese New Testament (CC BY-SA 4.0), so they are real Nagamese.
  nag: ["Jitia moi taike dikhise, moi bisi asurit hoise.", "Apuni ami logote ki kaam ase?", "Eneka he moi likhe."],
};

const ERROR_TITLES: Record<string, string> = {
  network_error: "The backend is unreachable",
  engine_failed: "The engine could not translate this",
  engine_not_ready: "That engine is not ready",
  unknown_engine: "That engine is not registered",
  text_too_long: "The text is too long",
  empty_text: "There is nothing to translate",
};

export function TranslatePage() {
  const { meta, engines } = useApp();
  const [source, setSource] = useState<LangCode>("en");
  const target: LangCode = source === "en" ? "nag" : "en";
  const direction = `${source}-${target}`;
  const [text, setText] = useState("");
  const [engineId, setEngineId] = useState("auto");
  const [compareMode, setCompareMode] = useState(false);
  const [result, setResult] = useState<Translation | null>(null);
  const [compared, setCompared] = useState<CompareItem[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const [lastInput, setLastInput] = useState<{ text: string; source: LangCode; target: LangCode } | null>(null);
  const [swapTurns, setSwapTurns] = useState(0);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const maxChars = meta?.max_chars ?? 2000;
  const usable = useMemo(
    () => engines?.engines.filter((engine) => engine.directions.includes(direction)) ?? [],
    [engines, direction],
  );
  const readyCount = usable.filter((engine) => engine.state === "ready").length;
  const canCompare = readyCount >= 2;

  useEffect(() => {
    if (engineId !== "auto" && !usable.some((engine) => engine.id === engineId)) setEngineId("auto");
  }, [engineId, usable]);

  useEffect(() => {
    if (!canCompare) setCompareMode(false);
  }, [canCompare]);

  async function run(input: string = text) {
    const trimmed = input.trim();
    if (!trimmed || busy) return;
    setBusy(true);
    setError(null);
    setLastInput({ text: trimmed, source, target });
    try {
      if (compareMode) {
        const response = await api.compare({ text: trimmed, source_lang: source, target_lang: target });
        setCompared(response.results);
        setResult(null);
      } else {
        const response = await api.translate({
          text: trimmed,
          source_lang: source,
          target_lang: target,
          engine: engineId,
          num_alternatives: 2,
        });
        setResult(response);
        setCompared(null);
      }
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError(0, "unknown", String(err)));
      setResult(null);
      setCompared(null);
    } finally {
      setBusy(false);
    }
  }

  function swap() {
    setSource(target);
    setSwapTurns((turns) => turns + 1);
    if (result) setText(result.translation);
    setResult(null);
    setCompared(null);
    setError(null);
    textareaRef.current?.focus();
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      void run();
    }
  }

  const tooLong = text.length > maxChars;

  return (
    <div>
      <PageHeader
        title="Translate"
        description="English and Nagamese, both ways. Each answer shows which engine produced it."
      />

      {/* Controls */}
      <div className="reveal mb-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between" style={{ animationDelay: "60ms" }}>
        <div className="flex items-center gap-2" aria-label="Translation direction">
          <LanguageTag code={source} role="From" />
          <button
            onClick={swap}
            aria-label={`Swap to ${langName(target)} → ${langName(source)}`}
            title="Swap languages"
            className="keycap grid size-10 place-items-center rounded-full text-ink hover:text-accent-text"
          >
            <ArrowLeftRight
              aria-hidden
              className="size-4 transition-transform duration-500"
              style={{ transform: `rotate(${swapTurns * 180}deg)` }}
            />
          </button>
          <LanguageTag code={target} role="To" />
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <label className="flex items-center gap-2 text-[14px] font-medium text-ink-2">
            Engine
            <select
              value={engineId}
              onChange={(event) => setEngineId(event.target.value)}
              disabled={compareMode}
              className="field h-10 w-auto pr-8 pl-3 text-[14.5px] font-medium disabled:opacity-50"
            >
              <option value="auto">Auto (best available)</option>
              {usable.map((engine) => (
                <option key={engine.id} value={engine.id} disabled={engine.state !== "ready"}>
                  {engine.name}
                  {engine.state !== "ready" ? " (not ready)" : ""}
                </option>
              ))}
            </select>
          </label>
          <button
            role="switch"
            aria-checked={compareMode}
            disabled={!canCompare}
            onClick={() => setCompareMode((on) => !on)}
            title={canCompare ? "Run every ready engine on the same text" : "Needs at least two ready engines"}
            className={cx(
              "inline-flex h-10 items-center gap-2 rounded-xl px-3.5 text-[14.5px] font-medium disabled:cursor-not-allowed disabled:opacity-45",
              compareMode ? "pill pill-accent" : "keycap text-ink-2 hover:text-ink",
            )}
          >
            <GitCompareArrows aria-hidden className={cx("size-4", compareMode && "text-accent-text")} />
            Compare engines
          </button>
        </div>
      </div>

      {/* The two pages of the spread */}
      <div className="reveal card overflow-hidden" style={{ animationDelay: "120ms" }}>
        <div className="grid md:grid-cols-2">
          <section aria-label={`${langName(source)} text`} className="flex min-h-[460px] flex-col p-5 sm:p-6">
            <div className="mb-3 flex items-center justify-between text-[13px] text-muted">
              <span className="label-caps">{langName(source)}</span>
              <span className={cx("tabular-nums", tooLong && "text-critical")}>
                {fmt(text.length)} / {fmt(maxChars)}
              </span>
            </div>
            <textarea
              ref={textareaRef}
              value={text}
              onChange={(event) => setText(event.target.value)}
              onKeyDown={onKeyDown}
              lang={source}
              rows={6}
              placeholder={source === "en" ? "Type or paste English…" : "Nagamese likhibi…"}
              aria-label={`Text to translate from ${langName(source)}`}
              className="min-h-[280px] w-full flex-1 resize-y bg-transparent text-[19px] leading-relaxed text-ink placeholder:text-faint focus:outline-none"
            />
            <div className="mt-4 flex flex-col gap-3">
              <div className="flex flex-wrap gap-1.5" aria-label="Example sentences">
                {EXAMPLES[source].map((example) => (
                  <button
                    key={example}
                    onClick={() => {
                      setText(example);
                      void run(example);
                    }}
                    className="pill h-8 px-3 text-[13.5px]"
                  >
                    {example}
                  </button>
                ))}
              </div>
              <div className="flex items-center justify-between gap-3">
                <span className="hidden text-[13px] text-muted sm:inline">
                  <kbd className="keycap rounded-md px-1.5 py-0.5 font-mono text-[11.5px] text-ink-2">Ctrl</kbd>{" "}
                  + <kbd className="keycap rounded-md px-1.5 py-0.5 font-mono text-[11.5px] text-ink-2">Enter</kbd>{" "}
                  to translate
                </span>
                <Button
                  variant="primary"
                  icon={compareMode ? GitCompareArrows : Languages}
                  loading={busy}
                  disabled={!text.trim() || tooLong}
                  onClick={() => void run()}
                  className="ml-auto"
                >
                  {compareMode ? "Compare" : "Translate"}
                </Button>
              </div>
            </div>
          </section>

          <section
            aria-label={`${langName(target)} translation`}
            aria-busy={busy}
            className="flex min-h-[460px] flex-col border-t border-line bg-surface-2 p-5 sm:p-6 md:border-t-0 md:border-l"
          >
            <div className="mb-3 flex items-center justify-between text-[13px] text-muted">
              <span className="label-caps">{langName(target)}</span>
              {result && <EngineLine result={result} />}
            </div>
            <div aria-live="polite" className="flex flex-1 flex-col">
              <Output
                busy={busy}
                result={result}
                compared={compared}
                error={error}
                target={target}
                lastInput={lastInput}
                mockEnabled={meta?.mock_engine_enabled ?? false}
              />
            </div>
          </section>
        </div>
      </div>

      {meta?.log_translations && (
        <p className="mt-3 text-[13px] text-muted">
          Text you translate is stored on this server so speakers can review and improve the engine.
        </p>
      )}

      {compared && <CompareGrid items={compared} target={target} />}
      {result && result.evidence.length > 0 && <EvidenceList items={result.evidence} source={source} target={target} />}
    </div>
  );
}

function LanguageTag({ code, role }: { code: LangCode; role: string }) {
  return (
    <span className="keycap inline-flex h-10 items-center gap-2 rounded-xl px-3.5">
      <span className="text-[12.5px] text-muted">{role}</span>
      <span className="text-[15.5px] font-semibold text-ink">{langName(code)}</span>
    </span>
  );
}

function EngineLine({ result }: { result: Translation }) {
  const { icon: Icon } = kindMeta(result.engine.kind);
  return (
    <span className="flex items-center gap-1.5 text-[13px] font-medium text-ink-2">
      <Icon aria-hidden className="size-3.5" />
      {result.engine.name} · {latency(result.latency_ms)}
    </span>
  );
}

function Output({
  busy,
  result,
  compared,
  error,
  target,
  lastInput,
  mockEnabled,
}: {
  busy: boolean;
  result: Translation | null;
  compared: CompareItem[] | null;
  error: ApiError | null;
  target: LangCode;
  lastInput: { text: string; source: LangCode; target: LangCode } | null;
  mockEnabled: boolean;
}) {
  if (busy && !result) {
    return (
      <div className="space-y-3 pt-2">
        <Skeleton className="h-6 w-11/12" />
        <Skeleton className="h-6 w-4/5" />
        <Skeleton className="h-6 w-2/3" />
      </div>
    );
  }

  if (error?.code === "no_engine_available") {
    return (
      <EmptyState
        icon={Plug}
        title="No translation engine is connected yet"
        actions={
          <ButtonLink href={href("engines")} variant="primary" icon={Plug}>
            Connect an engine
          </ButtonLink>
        }
      >
        <p>
          The dashboard is ready. Once an engine is registered through <code className="font-mono text-[13px]">LIKHIBI_ENGINES</code>,
          its translation appears here.
        </p>
        {!mockEnabled && (
          <p>
            To try the flow now, restart the backend with{" "}
            <code className="font-mono text-[13px]">LIKHIBI_ENABLE_MOCK_ENGINE=1</code>.
          </p>
        )}
      </EmptyState>
    );
  }

  if (error?.code === "no_confident_translation") {
    return (
      <div className="space-y-4">
        <InlineAlert tone="warning" title="No engine has a confident translation yet">
          The translation memory only answers sentences it already holds. New sentences need the LLM engine or the
          offline model.{" "}
          <a href={href("engines")} className="font-semibold text-accent-text underline-offset-2 hover:underline">
            See which engines are ready
          </a>
          .
        </InlineAlert>
        {lastInput && <ContributeForm key={`${lastInput.source}:${lastInput.text}`} input={lastInput} />}
      </div>
    );
  }

  if (error) {
    return (
      <InlineAlert tone="critical" title={ERROR_TITLES[error.code] ?? "Something went wrong"}>
        {error.message}
      </InlineAlert>
    );
  }

  if (compared) {
    return (
      <p className="pt-2 text-[17px] leading-relaxed text-ink-2">
        {compared.filter((item) => item.ok).length} of {compared.length} engines answered. Their translations are listed below.
      </p>
    );
  }

  if (!result) {
    return (
      <p className="pt-1 text-[19px] leading-relaxed text-faint">
        The translation appears here.
      </p>
    );
  }

  return (
    <div className={cx("flex flex-1 flex-col gap-4 transition-opacity", busy && "opacity-50")}>
      <p lang={target} className="text-[clamp(1.4rem,2.4vw,1.7rem)] leading-snug font-medium tracking-[-0.005em] text-ink">
        {result.translation}
      </p>

      {result.warnings.map((warning) => (
        <InlineAlert key={warning} tone="warning" title={warning} />
      ))}

      {result.confidence !== null && (
        <div className="max-w-xs">
          <div className="mb-1.5 flex justify-between text-[13px] font-medium text-ink-2">
            <span>Engine confidence</span>
            <span>{Math.round(result.confidence * 100)}%</span>
          </div>
          <Meter value={result.confidence} max={1} tone="neutral" label="Engine confidence" />
        </div>
      )}

      {result.alternatives.length > 0 && (
        <details className="group rounded-xl border border-line bg-surface px-3.5 py-2.5">
          <summary className="text-[14px] font-medium text-ink-2 select-none">
            Other options ({result.alternatives.length})
          </summary>
          <ul className="mt-2 space-y-1.5">
            {result.alternatives.map((alternative) => (
              <li key={alternative.text} lang={target} className="text-[16.5px] text-ink">
                {alternative.text}
              </li>
            ))}
          </ul>
        </details>
      )}

      <div className="mt-auto">
        <FeedbackBar key={result.request_id} result={result} />
      </div>
    </div>
  );
}

function FeedbackBar({ result }: { result: Translation }) {
  const toast = useToast();
  const [rated, setRated] = useState<"up" | "down" | null>(null);
  const [editing, setEditing] = useState(false);
  const [correction, setCorrection] = useState(result.translation);
  const [comment, setComment] = useState("");
  const [sending, setSending] = useState(false);
  const [sent, setSent] = useState(false);
  const [consent, setConsent] = useState(false);
  const [credit, setCredit] = useCreditName();

  async function submit(rating: "up" | "down" | null, withCorrection: boolean) {
    setSending(true);
    try {
      const corrected = correction.trim();
      await api.submitFeedback({
        translation_id: result.request_id,
        source_lang: result.source_lang,
        target_lang: result.target_lang,
        source_text: result.source_text,
        machine_translation: result.translation,
        corrected_translation: withCorrection && corrected && corrected !== result.translation ? corrected : null,
        rating,
        comment: comment.trim() || null,
        engine_id: result.engine.id,
        contributor: withCorrection ? credit.trim() || null : null,
        variety: withCorrection ? readVariety() : null,
        share_consent: withCorrection && consent,
      });
      setSent(true);
      setEditing(false);
      toast(withCorrection ? "Thank you. Your correction is waiting for review." : "Thank you for the rating.");
    } catch (err) {
      toast(err instanceof Error ? err.message : "Could not send feedback", "critical");
    } finally {
      setSending(false);
    }
  }

  if (sent) {
    return <p className="text-[14px] font-medium text-good-ink">Feedback received. Thank you.</p>;
  }

  if (editing) {
    return (
      <div className="space-y-3 rounded-xl border border-line bg-surface p-3.5">
        <label className="block text-[13.5px] font-medium text-ink-2" htmlFor="correction">
          Better {langName(result.target_lang)} translation
        </label>
        <textarea
          id="correction"
          lang={result.target_lang}
          value={correction}
          onChange={(event) => setCorrection(event.target.value)}
          rows={3}
          className="field resize-y p-2.5 text-[17px]"
        />
        <input
          value={comment}
          onChange={(event) => setComment(event.target.value)}
          placeholder="Optional note, e.g. which word was wrong"
          aria-label="Optional note"
          className="field h-9 px-2.5 text-[14px]"
        />
        <ShareConsent consent={consent} onConsent={setConsent} credit={credit} onCredit={setCredit} />
        <div className="flex flex-wrap justify-end gap-2">
          <Button size="sm" variant="ghost" onClick={() => setEditing(false)}>
            Cancel
          </Button>
          {rated === "down" && (
            <Button size="sm" onClick={() => void submit("down", false)} loading={sending}>
              Send rating only
            </Button>
          )}
          <Button
            size="sm"
            variant="primary"
            icon={Send}
            loading={sending}
            disabled={!consent || !correction.trim() || correction.trim() === result.translation}
            onClick={() => void submit(rated, true)}
          >
            Send correction
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-wrap items-center gap-1 border-t border-line pt-3">
      <CopyButton text={result.translation} />
      <Button
        size="sm"
        variant="ghost"
        icon={ThumbsUp}
        aria-label="Good translation"
        onClick={() => {
          setRated("up");
          void submit("up", false);
        }}
      >
        Good
      </Button>
      <Button
        size="sm"
        variant="ghost"
        icon={ThumbsDown}
        aria-label="Bad translation"
        onClick={() => {
          setRated("down");
          setEditing(true);
        }}
      >
        Wrong
      </Button>
      <Button size="sm" variant="ghost" icon={PencilLine} onClick={() => setEditing(true)} className="ml-auto">
        Suggest correction
      </Button>
    </div>
  );
}

/** Lets a speaker supply the translation no engine knew. Once approved, the memory answers it. */
function ContributeForm({ input }: { input: { text: string; source: LangCode; target: LangCode } }) {
  const toast = useToast();
  const [translation, setTranslation] = useState("");
  const [sending, setSending] = useState(false);
  const [sent, setSent] = useState(false);
  const [consent, setConsent] = useState(false);
  const [credit, setCredit] = useCreditName();

  if (sent) {
    return (
      <p className="text-[14.5px] font-medium text-good-ink">
        Thank you. Once a reviewer approves it, Likhibi will answer this sentence.
      </p>
    );
  }

  return (
    <form
      className="space-y-3 rounded-xl border border-line bg-surface p-3.5"
      onSubmit={async (event) => {
        event.preventDefault();
        setSending(true);
        try {
          await api.submitFeedback({
            source_lang: input.source,
            target_lang: input.target,
            source_text: input.text,
            corrected_translation: translation.trim(),
            contributor: credit.trim() || null,
            variety: readVariety(),
            share_consent: consent,
          });
          setSent(true);
          toast("Thank you. Your translation is waiting for review.");
        } catch (err) {
          toast(err instanceof Error ? err.message : "Could not send the translation", "critical");
        } finally {
          setSending(false);
        }
      }}
    >
      <label htmlFor="contribution" className="block text-[13.5px] font-medium text-ink-2">
        Speak {langName(input.target)}? Add the translation, and a reviewer will check it.
      </label>
      <textarea
        id="contribution"
        lang={input.target}
        value={translation}
        onChange={(event) => setTranslation(event.target.value)}
        rows={2}
        className="field resize-y p-2.5 text-[17px]"
      />
      <ShareConsent consent={consent} onConsent={setConsent} credit={credit} onCredit={setCredit} />
      <div className="flex justify-end">
        <Button type="submit" size="sm" variant="primary" icon={Send} loading={sending} disabled={!consent || !translation.trim()}>
          Send for review
        </Button>
      </div>
    </form>
  );
}

function CompareGrid({ items, target }: { items: CompareItem[]; target: LangCode }) {
  return (
    <section className="reveal mt-10">
      <SectionHeading title="Engine comparison" description="The same input sent to every ready engine at once." />
      <div className="grid gap-4 md:grid-cols-2">
        {items.map((item) => {
          const { icon: Icon, label } = kindMeta(item.engine.kind);
          return (
            <article key={item.engine.id} className="card flex flex-col gap-3 p-5">
              <header className="flex items-center justify-between gap-2">
                <span className="flex items-center gap-2 font-medium text-ink">
                  <Icon aria-hidden className="size-4 text-muted" />
                  {item.engine.name}
                </span>
                <Chip>{label}</Chip>
              </header>
              {item.ok && item.result ? (
                <>
                  <p lang={target} className="text-[19px] leading-snug font-medium text-ink">
                    {item.result.translation}
                  </p>
                  <div className="mt-auto flex items-center justify-between text-[13px] text-muted">
                    <span>{latency(item.result.latency_ms)}</span>
                    <CopyButton text={item.result.translation} />
                  </div>
                </>
              ) : item.declined ? (
                <InlineAlert tone="neutral" title="No confident answer">
                  {item.error}
                </InlineAlert>
              ) : (
                <InlineAlert tone="critical" title="This engine failed">
                  {item.error}
                </InlineAlert>
              )}
            </article>
          );
        })}
      </div>
    </section>
  );
}

function EvidenceList({ items, source, target }: { items: Evidence[]; source: LangCode; target: LangCode }) {
  return (
    <section className="reveal mt-10" aria-label="Why this translation">
      <SectionHeading
        title="Why this translation"
        description="Human translations and dictionary entries the engine relied on. Check them before you trust the output."
      />
      <ol className="grid gap-3 md:grid-cols-2">
        {items.map((item, index) => {
          const { label, icon: Icon } = EVIDENCE_META[item.kind] ?? { label: item.kind, icon: Languages };
          return (
            <li key={`${item.origin}-${index}`} className="card flex flex-col gap-3 p-4">
              <div className="flex items-center justify-between gap-2">
                <span className="inline-flex items-center gap-1.5 text-[13px] font-medium text-ink-2">
                  <Icon aria-hidden className="size-3.5 text-accent-text" />
                  {label}
                </span>
                <Chip mono>{item.origin}</Chip>
              </div>
              <p lang={source} className="text-[15px] leading-relaxed text-ink-2">
                {item.source_text}
              </p>
              <p lang={target} className="border-l-2 border-accent pl-3 text-[16.5px] leading-relaxed text-ink">
                {item.target_text}
              </p>
              {item.similarity !== null && (
                <div>
                  <div className="mb-1.5 flex justify-between text-[12.5px] font-medium text-muted">
                    <span>Similarity to your text</span>
                    <span>{Math.round(item.similarity * 100)}%</span>
                  </div>
                  <Meter value={item.similarity} max={1} tone="neutral" label="Similarity to your text" />
                </div>
              )}
            </li>
          );
        })}
      </ol>
    </section>
  );
}
