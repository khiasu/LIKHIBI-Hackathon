import { ArrowLeftRight } from "lucide-react";
import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { useApp, useToast } from "../components/AppContext";
import { readVariety, ShareConsent, useCreditName } from "../components/ShareConsent";
import { Button, ButtonLink, CopyButton, cx, EmptyState, InlineAlert, PageHeader, SectionHeading, Skeleton } from "../components/ui";
import { api, ApiError } from "../lib/api";
import { EVIDENCE_META, kindMeta } from "../lib/engines";
import { fmt, langName, latency } from "../lib/format";
import { href } from "../lib/hooks";
import type { CompareItem, Evidence, LangCode, Translation } from "../lib/types";

const EXAMPLES: Record<LangCode, string[]> = {
  en: ["Where is the market?", "I will come tomorrow.", "How much does this cost?"],
  // Short sentences from the ISV Nagamese New Testament (CC BY-SA 4.0), so they are real Nagamese.
  nag: ["Jitia moi taike dikhise, moi bisi asurit hoise.", "Apuni ami logote ki kaam ase?", "Eneka he moi likhe."],
};

const ERROR_TITLES: Record<string, string> = {
  network_error: "The server is unreachable",
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
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const maxChars = meta?.max_chars ?? 2000;
  const usable = useMemo(() => engines?.engines.filter((engine) => engine.directions.includes(direction)) ?? [], [engines, direction]);
  const canCompare = usable.filter((engine) => engine.state === "ready").length >= 2;

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
        const response = await api.translate({ text: trimmed, source_lang: source, target_lang: target, engine: engineId, num_alternatives: 2 });
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
  const sources = result?.evidence ?? [];

  return (
    <div>
      <PageHeader
        title="Translate"
        description="English and Nagamese, both ways. Every answer cites the human translations it drew on."
        actions={
          <>
            <label className="flex items-center gap-2 text-[13px] text-muted">
              Engine
              <select
                value={engineId}
                onChange={(event) => setEngineId(event.target.value)}
                disabled={compareMode}
                className="field h-[34px] w-[190px] pr-7 pl-2.5 text-[13.5px] font-medium text-ink disabled:opacity-40"
              >
                <option value="auto">Best available</option>
                {usable.map((engine) => (
                  <option key={engine.id} value={engine.id} disabled={engine.state !== "ready"}>
                    {engine.name}
                    {engine.state !== "ready" ? " (not ready)" : ""}
                  </option>
                ))}
              </select>
            </label>
            <Button
              role="switch"
              aria-checked={compareMode}
              disabled={!canCompare}
              onClick={() => setCompareMode((on) => !on)}
              title={canCompare ? "Send the text to every ready engine" : "Needs two ready engines"}
              className={cx(compareMode && "border-ink")}
            >
              {compareMode ? "Comparing all engines" : "Compare engines"}
            </Button>
          </>
        }
      />

      <div className="reveal card overflow-hidden">
        {/* The direction, centred over each page of the spread, with the swap on the fold. */}
        <div className="relative grid grid-cols-2 border-b border-line">
          <p className="px-6 py-3.5 text-[13.5px] font-semibold text-ink sm:px-8">{langName(source)}</p>
          <p className="border-l border-line px-6 py-3.5 text-[13.5px] font-semibold text-ink sm:px-8 sm:pl-10">{langName(target)}</p>
          <button
            onClick={swap}
            aria-label={`Translate from ${langName(target)} to ${langName(source)} instead`}
            title="Swap languages"
            className="absolute top-1/2 left-1/2 grid size-8 -translate-x-1/2 -translate-y-1/2 place-items-center rounded-full border border-line-strong bg-surface text-ink-2 transition-colors hover:border-ink hover:text-ink"
          >
            <ArrowLeftRight aria-hidden className="size-3.5" strokeWidth={1.75} />
          </button>
        </div>

        <div className="grid md:grid-cols-2">
          <section aria-label={`${langName(source)} text`} className="flex flex-col md:min-h-[420px]">
            <textarea
              ref={textareaRef}
              value={text}
              onChange={(event) => setText(event.target.value)}
              onKeyDown={onKeyDown}
              lang={source}
              placeholder={source === "en" ? "Type or paste English" : "Nagamese likhibi"}
              aria-label={`Text to translate from ${langName(source)}`}
              className="min-h-[150px] w-full flex-1 resize-none md:min-h-[300px] bg-transparent px-6 pt-6 pb-4 text-[21px] leading-[1.55] text-ink placeholder:text-faint placeholder:italic focus:outline-none sm:px-8"
            />
            {!text && (
              <div className="px-6 pb-6 sm:px-8">
                <p className="mb-2 text-[12px] font-medium text-faint">Try one</p>
                <ul className="space-y-1.5">
                  {EXAMPLES[source].map((example) => (
                    <li key={example}>
                      <button
                        lang={source}
                        onClick={() => {
                          setText(example);
                          void run(example);
                        }}
                        className="text-left text-[15.5px] text-ink-2 underline decoration-line-strong underline-offset-4 hover:text-ink hover:decoration-ink"
                      >
                        {example}
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            )}
            <div className="flex items-center gap-4 border-t border-line px-6 py-3 sm:px-8">
              <span className="mr-auto hidden font-mono text-[11px] text-faint sm:inline">Ctrl + Enter</span>
              <span className={cx("num ml-auto font-mono text-[11.5px] sm:ml-0", tooLong ? "text-critical" : "text-faint")}>
                {fmt(text.length)} / {fmt(maxChars)}
              </span>
              <Button variant="primary" loading={busy} disabled={!text.trim() || tooLong} onClick={() => void run()} title="Ctrl + Enter">
                {compareMode ? "Compare" : "Translate"}
              </Button>
            </div>
          </section>

          <section
            aria-label={`${langName(target)} translation`}
            aria-busy={busy}
            className="flex min-h-[200px] flex-col border-t border-line bg-surface-2 md:min-h-[420px] md:border-t-0 md:border-l"
          >
            <div aria-live="polite" className="flex flex-1 flex-col px-6 pt-6 pb-4 sm:px-8 sm:pl-10">
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
            {result && <FeedbackBar key={result.request_id} result={result} />}
          </section>
        </div>
      </div>

      {meta?.log_translations && (
        <p className="mt-3 text-[12.5px] text-faint">Text you translate is kept on this server so speakers can review it and improve the engines.</p>
      )}

      {compared && <CompareTable items={compared} target={target} />}
      {sources.length > 0 && <Sources items={sources} source={source} target={target} />}
    </div>
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
      <div className="space-y-3.5 pt-2">
        <Skeleton className="h-6 w-11/12" />
        <Skeleton className="h-6 w-4/5" />
        <Skeleton className="h-6 w-1/2" />
      </div>
    );
  }

  if (error?.code === "no_engine_available") {
    return (
      <EmptyState
        title="No translation engine is connected"
        actions={
          <ButtonLink href={href("engines")} variant="primary">
            Connect an engine
          </ButtonLink>
        }
      >
        <p>
          Register one with <code className="font-mono text-[12.5px]">LIKHIBI_ENGINES</code> and its translations appear here.
          {!mockEnabled && (
            <>
              {" "}
              To try the flow, restart the backend with <code className="font-mono text-[12.5px]">LIKHIBI_ENABLE_MOCK_ENGINE=1</code>.
            </>
          )}
        </p>
      </EmptyState>
    );
  }

  if (error?.code === "no_confident_translation") {
    return (
      <div className="space-y-5">
        <InlineAlert tone="warning" title="No engine has a confident translation">
          The memory answers only sentences it already holds; new ones need the LLM or the trained model.{" "}
          <a href={href("engines")} className="text-ink underline underline-offset-4">
            See engines
          </a>
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
      <p className="pt-1 text-[15px] leading-relaxed text-muted">
        {compared.filter((item) => item.ok).length} of {compared.length} engines answered. Their translations are compared below.
      </p>
    );
  }

  if (!result) {
    return <p className="pt-0.5 font-[family-name:var(--font-text)] text-[21px] leading-[1.55] text-faint italic">Translation</p>;
  }

  return (
    <div className={cx("flex flex-1 flex-col transition-opacity", busy && "opacity-50")}>
      <p lang={target} className="text-[24px] leading-[1.45] tracking-[-0.005em] text-ink">
        {result.translation}
        {result.evidence.map((_, index) => (
          <a
            key={index}
            href={`#source-${index + 1}`}
            className="ml-0.5 align-super font-mono text-[11px] text-accent-text no-underline hover:underline"
            aria-label={`Source ${index + 1}`}
          >
            {index + 1}
          </a>
        ))}
      </p>

      <p className="mt-3 font-mono text-[11.5px] text-muted">
        {result.engine.name}
        <span className="mx-1.5 text-faint">·</span>
        {latency(result.latency_ms)}
        {result.confidence !== null && (
          <>
            <span className="mx-1.5 text-faint">·</span>
            {Math.round(result.confidence * 100)}% confident
          </>
        )}
      </p>

      {result.warnings.map((warning) => (
        <div key={warning} className="mt-4">
          <InlineAlert tone="warning" title={warning} />
        </div>
      ))}

      {result.alternatives.length > 0 && (
        <div className="mt-6">
          <p className="text-[12.5px] font-medium text-muted">Also possible</p>
          <ul className="mt-1.5 space-y-1">
            {result.alternatives.map((alternative) => (
              <li key={alternative.text} lang={target} className="text-[16.5px] leading-snug text-ink-2">
                {alternative.text}
              </li>
            ))}
          </ul>
        </div>
      )}
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
      toast(withCorrection ? "Thank you. Your correction is waiting for a reviewer." : "Thank you for rating it.");
    } catch (err) {
      toast(err instanceof Error ? err.message : "Could not send feedback", "critical");
    } finally {
      setSending(false);
    }
  }

  if (sent) {
    return <p className="border-t border-line px-6 py-3 text-[13px] text-good-ink sm:px-8 sm:pl-10">Received. Thank you.</p>;
  }

  if (editing) {
    return (
      <div className="space-y-3 border-t border-line px-6 py-4 sm:px-8 sm:pl-10">
        <label className="block text-[12.5px] font-medium text-muted" htmlFor="correction">
          A better {langName(result.target_lang)} translation
        </label>
        <textarea
          id="correction"
          lang={result.target_lang}
          value={correction}
          onChange={(event) => setCorrection(event.target.value)}
          rows={3}
          className="field resize-y px-3 py-2 text-[17px] leading-snug"
        />
        <input
          value={comment}
          onChange={(event) => setComment(event.target.value)}
          placeholder="A note for the reviewer (optional)"
          aria-label="A note for the reviewer"
          className="field h-[34px] px-3 text-[13.5px]"
        />
        <ShareConsent consent={consent} onConsent={setConsent} credit={credit} onCredit={setCredit} />
        <div className="flex flex-wrap justify-end gap-2">
          <Button size="sm" variant="ghost" onClick={() => setEditing(false)}>
            Cancel
          </Button>
          {rated === "down" && (
            <Button size="sm" onClick={() => void submit("down", false)} loading={sending}>
              Send the rating only
            </Button>
          )}
          <Button
            size="sm"
            variant="primary"
            loading={sending}
            disabled={!consent || !correction.trim() || correction.trim() === result.translation}
            onClick={() => void submit(rated, true)}
          >
            Send for review
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-wrap items-center gap-1 border-t border-line px-4 py-2 sm:px-6 sm:pl-8">
      <CopyButton text={result.translation} />
      <Button
        size="sm"
        variant="ghost"
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
        onClick={() => {
          setRated("down");
          setEditing(true);
        }}
      >
        Wrong
      </Button>
      <Button size="sm" variant="ghost" onClick={() => setEditing(true)} className="ml-auto text-ink">
        Suggest a correction
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
    return <p className="text-[14px] text-good-ink">Thank you. Once a reviewer approves it, Likhibi will answer this sentence.</p>;
  }

  return (
    <form
      className="space-y-3"
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
          toast("Thank you. Your translation is waiting for a reviewer.");
        } catch (err) {
          toast(err instanceof Error ? err.message : "Could not send the translation", "critical");
        } finally {
          setSending(false);
        }
      }}
    >
      <label htmlFor="contribution" className="block text-[13px] font-medium text-ink-2">
        Speak {langName(input.target)}? Add the translation for a reviewer to check.
      </label>
      <textarea
        id="contribution"
        lang={input.target}
        value={translation}
        onChange={(event) => setTranslation(event.target.value)}
        rows={2}
        className="field resize-y px-3 py-2 text-[17px]"
      />
      <ShareConsent consent={consent} onConsent={setConsent} credit={credit} onCredit={setCredit} />
      <div className="flex justify-end">
        <Button type="submit" size="sm" variant="primary" loading={sending} disabled={!consent || !translation.trim()}>
          Send for review
        </Button>
      </div>
    </form>
  );
}

function CompareTable({ items, target }: { items: CompareItem[]; target: LangCode }) {
  return (
    <section className="reveal mt-14" aria-label="Engine comparison">
      <SectionHeading title="Engine comparison" description="The same text, sent to every ready engine at once." />
      <ul className="divide-y divide-line">
        {items.map((item) => (
          <li key={item.engine.id} className="grid gap-2 py-4 md:grid-cols-[200px_1fr_auto] md:gap-6">
            <div>
              <p className="text-[14px] font-medium text-ink">{item.engine.name}</p>
              <p className="font-mono text-[11.5px] text-faint">{kindMeta(item.engine.kind).label}</p>
            </div>
            {item.ok && item.result ? (
              <p lang={target} className="text-[18px] leading-snug text-ink">
                {item.result.translation}
              </p>
            ) : (
              <p className="text-[13.5px] text-muted">{item.declined ? "No confident answer." : `Failed: ${item.error}`}</p>
            )}
            <div className="flex items-start gap-3">
              {item.ok && item.result && (
                <>
                  <span className="pt-1.5 font-mono text-[11.5px] text-faint">{latency(item.result.latency_ms)}</span>
                  <CopyButton text={item.result.translation} />
                </>
              )}
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}

/** The footnotes: each human translation the answer drew on, numbered as in the text above. */
function Sources({ items, source, target }: { items: Evidence[]; source: LangCode; target: LangCode }) {
  return (
    <section className="reveal mt-14" aria-label="Sources">
      <SectionHeading title="Sources" description="Human translations the engine drew on. Read them before you trust the answer." />
      <ol className="grid gap-x-12 md:grid-cols-2">
        {items.map((item, index) => {
          const label = EVIDENCE_META[item.kind]?.label ?? item.kind;
          return (
            <li id={`source-${index + 1}`} key={`${item.origin}-${index}`} className="grid scroll-mt-8 grid-cols-[28px_1fr] border-b border-line py-4">
              <span className="font-mono text-[12px] text-accent-text">{index + 1}</span>
              <div className="min-w-0">
                <p className="font-mono text-[11.5px] text-faint">
                  {label}
                  <span className="mx-1.5">·</span>
                  {item.origin}
                  {item.similarity !== null && (
                    <>
                      <span className="mx-1.5">·</span>
                      {Math.round(item.similarity * 100)}% similar
                    </>
                  )}
                </p>
                <p lang={source} className="mt-1.5 text-[15px] leading-relaxed text-muted">
                  {item.source_text}
                </p>
                <p lang={target} className="mt-1 text-[16.5px] leading-relaxed text-ink">
                  {item.target_text}
                </p>
              </div>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
