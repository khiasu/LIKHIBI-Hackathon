import { Check, Send, SkipForward } from "lucide-react";
import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { useToast } from "../components/AppContext";
import { ShareConsent, useCreditName } from "../components/ShareConsent";
import { Button, InlineAlert, PageHeader, SectionHeading, Skeleton } from "../components/ui";
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
        title="Teach Likhibi"
        description="Add a phrase or give a word its meaning. Approved contributions improve the translator."
      />

      <section className="reveal mb-8" aria-label="Sharing terms">
        <SectionHeading title="Before you start" />
        <div className="card p-4 sm:p-5">
          <ShareConsent consent={consent} onConsent={setConsent} credit={credit} onCredit={setCredit} />
        </div>
      </section>

      <div className="grid items-start gap-8 lg:grid-cols-2">
        <PhraseForm consent={consent} contributor={contributor} />
        <WordMeanings consent={consent} contributor={contributor} />
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

  return (
    <section className="reveal" style={{ animationDelay: "60ms" }}>
      <SectionHeading title="Add a phrase" description="Something people really say: a greeting, a question at the market, a proverb." />
      <form onSubmit={submit} className="card space-y-4 p-4 sm:p-5">
        <label className="block space-y-1.5">
          <span className="text-[14px] font-medium text-ink">Nagamese</span>
          <textarea
            lang="nag"
            value={nagamese}
            onChange={(event) => setNagamese(event.target.value)}
            rows={2}
            maxLength={500}
            placeholder="For example: Apuni kineka ase?"
            className="field resize-y px-3 py-2.5 text-[17px] leading-relaxed"
          />
        </label>
        <label className="block space-y-1.5">
          <span className="text-[14px] font-medium text-ink">What it means in English</span>
          <textarea
            lang="en"
            value={english}
            onChange={(event) => setEnglish(event.target.value)}
            rows={2}
            maxLength={500}
            placeholder="For example: How are you?"
            className="field resize-y px-3 py-2.5 text-[17px] leading-relaxed"
          />
        </label>
        <label className="block space-y-1.5">
          <span className="text-[14px] font-medium text-ink">
            Where is it said? <span className="font-normal text-muted">Optional</span>
          </span>
          <input
            value={where}
            onChange={(event) => setWhere(event.target.value)}
            maxLength={200}
            placeholder="For example: Dimapur, or how elders in Mon say it"
            className="field h-10 px-3 text-[14.5px]"
          />
        </label>
        <div className="flex items-center justify-between gap-3 pt-1">
          <span className="text-[13px] text-muted">{consent ? "" : "Tick the sharing box above first."}</span>
          <Button type="submit" variant={ready ? "primary" : "secondary"} icon={Send} loading={sending} disabled={!ready}>
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
    <section className="reveal" style={{ animationDelay: "120ms" }}>
      <SectionHeading
        title="Words that need a meaning"
        description={
          remaining === null
            ? "The most used words come first."
            : `${fmt(remaining)} words in the list still have no English meaning. The most used come first.`
        }
      />
      {error && <InlineAlert tone="critical" title="Could not load words">{error}</InlineAlert>}
      {!error && tasks.length === 0 && remaining !== 0 && (
        <div className="space-y-3">
          <Skeleton className="h-28 rounded-2xl" />
          <Skeleton className="h-28 rounded-2xl" />
        </div>
      )}
      {remaining === 0 && tasks.length === 0 && (
        <InlineAlert tone="good" title="Every word in the list has a meaning or is waiting for review." />
      )}
      <ul className="space-y-3">
        {tasks.map((task) => (
          <MeaningCard key={task.lemma} task={task} consent={consent} contributor={contributor} onDone={done} />
        ))}
      </ul>
      {added > 0 && (
        <p className="mt-3 flex items-center gap-1.5 text-[14px] font-medium text-good-ink">
          <Check aria-hidden className="size-4" />
          You added {added} {added === 1 ? "meaning" : "meanings"} this visit. Thank you.
        </p>
      )}
    </section>
  );
}

function MeaningCard({
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
    <li className="card p-4">
      <form onSubmit={submit} className="space-y-3">
        <div className="flex items-baseline justify-between gap-3">
          <p lang="nag" className="text-[22px] leading-tight font-semibold text-ink">
            {task.lemma}
          </p>
          <span className="text-[12.5px] whitespace-nowrap text-muted tabular-nums">
            seen {fmt(task.frequency)} times
          </span>
        </div>
        {task.guesses.length > 0 && (
          <div className="flex flex-wrap items-center gap-1.5 text-[13px] text-muted">
            <span>Machine guess, unverified:</span>
            {task.guesses.map((guess) => (
              <button
                key={guess}
                type="button"
                onClick={() => setMeaning(guess)}
                className={meaning === guess ? "pill pill-accent h-7 px-2.5 text-[13px]" : "pill h-7 px-2.5 text-[13px]"}
              >
                {guess}
              </button>
            ))}
          </div>
        )}
        <div className="flex flex-wrap gap-2">
          <input
            lang="en"
            value={meaning}
            onChange={(event) => setMeaning(event.target.value)}
            maxLength={200}
            placeholder="English meaning"
            aria-label={`English meaning of ${task.lemma}`}
            className="field h-10 min-w-0 flex-1 basis-40 px-3 text-[15px]"
          />
          <Button
            type="submit"
            variant={consent && meaning.trim() ? "primary" : "secondary"}
            icon={Send}
            loading={sending}
            disabled={!consent || !meaning.trim()}
          >
            Save
          </Button>
          <Button variant="ghost" icon={SkipForward} onClick={() => onDone(task.lemma, false)} title="Show another word">
            Skip
          </Button>
        </div>
      </form>
    </li>
  );
}
