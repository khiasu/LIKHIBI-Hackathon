import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { useToast } from "../components/AppContext";
import { ShareConsent, useCreditName } from "../components/ShareConsent";
import { Button, cx, InlineAlert, PageHeader, SectionHeading, Skeleton } from "../components/ui";
import { api } from "../lib/api";
import { fmt } from "../lib/format";
import type { MeaningTask } from "../lib/types";

export function ContributePage() {
  const [consent, setConsent] = useState(false);
  const [credit, setCredit] = useCreditName();
  const contributor = credit.trim() || null;

  return (
    <div>
      <PageHeader
        title="Contribute"
        description="Add a phrase people really say, or give a word its meaning. A verified speaker checks each one before the translator learns it."
      />

      <div className="grid gap-x-14 gap-y-12 xl:grid-cols-[minmax(0,1fr)_300px]">
        <aside className="reveal xl:sticky xl:top-14 xl:order-2 xl:self-start" aria-labelledby="terms-title">
          <h2 id="terms-title" className="pb-3 text-[15px] font-semibold tracking-[-0.01em] text-ink">
            Before you start
          </h2>
          <ShareConsent consent={consent} onConsent={setConsent} credit={credit} onCredit={setCredit} />
          <p className="mt-4 text-[12.5px] leading-relaxed text-muted">
            Your name and dialect are remembered in this browser only. Leave the name empty to contribute anonymously.
          </p>
        </aside>

        <div className="min-w-0 space-y-14 xl:order-1">
          <PhraseForm consent={consent} contributor={contributor} />
          <WordMeanings consent={consent} contributor={contributor} />
        </div>
      </div>
    </div>
  );
}

function PhraseForm({ consent, contributor }: { consent: boolean; contributor: string | null }) {
  const toast = useToast();
  const [nagamese, setNagamese] = useState("");
  const [english, setEnglish] = useState("");
  const [where, setWhere] = useState("");
  const [sending, setSending] = useState(false);
  const ready = consent && nagamese.trim() && english.trim();

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!ready) return;
    setSending(true);
    try {
      await api.submitFeedback({
        source_lang: "nag",
        target_lang: "en",
        source_text: nagamese.trim(),
        corrected_translation: english.trim(),
        comment: where.trim() ? `Where it is said: ${where.trim()}` : null,
        contributor,
        share_consent: true,
      });
      setNagamese("");
      setEnglish("");
      toast("Thank you. Your phrase is waiting for review.");
    } catch (err) {
      toast(err instanceof Error ? err.message : "Could not send the phrase", "critical");
    } finally {
      setSending(false);
    }
  }

  const pane = "min-h-[132px] w-full flex-1 resize-none bg-transparent px-6 pt-1 pb-5 text-[19px] leading-[1.5] text-ink placeholder:text-faint placeholder:italic focus:outline-none";

  return (
    <section className="reveal" aria-labelledby="phrase-title">
      <SectionHeading id="phrase-title" title="Add a phrase" description="A greeting, a question at the market, a proverb: something people really say." />
      <form onSubmit={submit} className="card overflow-hidden">
        <div className="grid md:grid-cols-2">
          <label className="flex flex-col">
            <span className="px-6 pt-4 pb-2 text-[12.5px] font-medium text-muted">Nagamese</span>
            <textarea lang="nag" value={nagamese} onChange={(event) => setNagamese(event.target.value)} maxLength={500} placeholder="Apuni kineka ase?" className={pane} />
          </label>
          <label className="flex flex-col border-t border-line bg-surface-2 md:border-t-0 md:border-l">
            <span className="px-6 pt-4 pb-2 text-[12.5px] font-medium text-muted">What it means in English</span>
            <textarea lang="en" value={english} onChange={(event) => setEnglish(event.target.value)} maxLength={500} placeholder="How are you?" className={pane} />
          </label>
        </div>
        <div className="flex flex-wrap items-center gap-3 border-t border-line px-6 py-3">
          <input
            value={where}
            onChange={(event) => setWhere(event.target.value)}
            maxLength={200}
            placeholder="Where is it said? Optional, e.g. Dimapur, or how elders in Mon say it"
            aria-label="Where it is said (optional)"
            className="h-[34px] min-w-0 flex-1 basis-64 bg-transparent text-[13.5px] text-ink placeholder:text-faint focus:outline-none"
          />
          {!consent && <span className="text-[12.5px] text-muted">Agree to share it first</span>}
          <Button type="submit" variant="primary" loading={sending} disabled={!ready}>
            Send for review
          </Button>
        </div>
      </form>
    </section>
  );
}

function WordMeanings({ consent, contributor }: { consent: boolean; contributor: string | null }) {
  const [tasks, setTasks] = useState<MeaningTask[]>([]);
  const [remaining, setRemaining] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [added, setAdded] = useState(0);
  const passed = useRef<string[]>([]); // skipped or answered this visit, so they are not offered again

  const refill = useCallback(async (current: MeaningTask[]) => {
    try {
      const response = await api.meaningTasks([...passed.current, ...current.map((task) => task.lemma)], 4 - current.length);
      setTasks([...current, ...response.tasks]);
      setRemaining(response.remaining);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not load words");
    }
  }, []);

  useEffect(() => {
    void refill([]);
  }, [refill]);

  function done(lemma: string, answered: boolean) {
    passed.current.push(lemma);
    if (answered) {
      setAdded((count) => count + 1);
      setRemaining((count) => (count === null ? count : Math.max(count - 1, 0)));
    }
    const rest = tasks.filter((task) => task.lemma !== lemma);
    setTasks(rest);
    void refill(rest);
  }

  return (
    <section className="reveal" aria-labelledby="words-title">
      <SectionHeading
        id="words-title"
        title="Words that need a meaning"
        description="The most used words come first. Pick a guess if it is right, or write your own."
        actions={
          remaining !== null && (
            <span className="num font-mono text-[11.5px] text-faint">
              {added > 0 && <span className="text-good-ink">{fmt(added)} added · </span>}
              {fmt(remaining)} left
            </span>
          )
        }
      />
      {error && (
        <InlineAlert tone="critical" title="Could not load words">
          {error}
        </InlineAlert>
      )}
      {!error && tasks.length === 0 && remaining !== 0 && (
        <div className="space-y-3">
          <Skeleton className="h-20" />
          <Skeleton className="h-20" />
        </div>
      )}
      {remaining === 0 && tasks.length === 0 && <InlineAlert tone="good" title="Every word in the list has a meaning or is waiting for review." />}
      <ul className="-mt-4 divide-y divide-line">
        {tasks.map((task) => (
          <MeaningRow key={task.lemma} task={task} consent={consent} contributor={contributor} onDone={done} />
        ))}
      </ul>
    </section>
  );
}

/** A glossary entry with the definition left blank. */
function MeaningRow({
  task,
  consent,
  contributor,
  onDone,
}: {
  task: MeaningTask;
  consent: boolean;
  contributor: string | null;
  onDone: (lemma: string, answered: boolean) => void;
}) {
  const toast = useToast();
  const [meaning, setMeaning] = useState("");
  const [sending, setSending] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!consent || !meaning.trim()) return;
    setSending(true);
    try {
      await api.submitFeedback({
        source_lang: "nag",
        target_lang: "en",
        source_text: task.lemma,
        corrected_translation: meaning.trim(),
        comment: "Word meaning",
        contributor,
        share_consent: true,
      });
      onDone(task.lemma, true);
    } catch (err) {
      toast(err instanceof Error ? err.message : "Could not send the meaning", "critical");
      setSending(false);
    }
  }

  return (
    <li className="py-5">
      <form onSubmit={submit} className="grid gap-x-8 gap-y-3 md:grid-cols-[200px_minmax(0,1fr)]">
        <div>
          <p lang="nag" className="text-[24px] leading-tight font-medium tracking-[-0.01em] text-ink">
            {task.lemma}
          </p>
          <p className="num mt-1 font-mono text-[11px] text-faint">seen {fmt(task.frequency)} times</p>
        </div>
        <div className="min-w-0">
          <div className="flex gap-2">
            <input
              lang="en"
              value={meaning}
              onChange={(event) => setMeaning(event.target.value)}
              maxLength={200}
              placeholder="English meaning"
              aria-label={`English meaning of ${task.lemma}`}
              className="field h-[34px] min-w-0 flex-1 px-2.5 text-[15px]"
            />
            <Button type="submit" variant="primary" loading={sending} disabled={!consent || !meaning.trim()}>
              Save
            </Button>
            <Button variant="ghost" onClick={() => onDone(task.lemma, false)} title="Show another word">
              Skip
            </Button>
          </div>
          {task.guesses.length > 0 && (
            <p className="mt-2 text-[12.5px] text-muted">
              <span className="mr-1.5 text-faint">Unverified guesses</span>
              {task.guesses.map((guess, index) => (
                <span key={guess}>
                  {index > 0 && <span className="mx-1 text-faint">·</span>}
                  <button
                    type="button"
                    lang="en"
                    onClick={() => setMeaning(guess)}
                    className={cx(
                      "underline underline-offset-4",
                      meaning === guess ? "text-ink decoration-accent decoration-[1.5px]" : "text-ink-2 decoration-line-strong hover:decoration-ink",
                    )}
                  >
                    {guess}
                  </button>
                </span>
              ))}
            </p>
          )}
        </div>
      </form>
    </li>
  );
}
