import { Brain, Copy, GraduationCap, Play, ShieldCheck, UserPlus, UserX } from "lucide-react";
import { useState } from "react";
import { useToast } from "./AppContext";
import { Button, Chip, cx, InlineAlert, Skeleton, StatusBadge } from "./ui";
import { api } from "../lib/api";
import { fmt, timeAgo } from "../lib/format";
import { useAsync } from "../lib/hooks";
import type { LearningRun, ReviewerIdentity } from "../lib/types";

/** How far the translator has come in learning from approved contributions. */
export function LearningPanel({ me, version }: { me: ReviewerIdentity; version: number }) {
  const toast = useToast();
  const learning = useAsync(() => api.learning(), [version]);
  const [starting, setStarting] = useState(false);
  const data = learning.data;

  async function start() {
    setStarting(true);
    try {
      await api.startLearning();
      toast("The model has started learning. This takes about ten minutes.");
      await learning.reload();
    } catch (err) {
      toast(err instanceof Error ? err.message : "Could not start learning", "critical");
    } finally {
      setStarting(false);
    }
  }

  const progress = data ? Math.min(100, Math.round((data.new_since_last / Math.max(1, data.threshold)) * 100)) : 0;
  return (
    <section className="card p-5" aria-labelledby="learning-title">
      <div className="mb-3 flex items-center gap-2">
        <Brain aria-hidden className="size-4 text-accent-text" />
        <h2 id="learning-title" className="font-semibold text-ink">Model learning</h2>
        {data?.running && <StatusBadge tone="neutral" label="Learning now" />}
      </div>
      {!data && !learning.error && <Skeleton className="h-24" />}
      {learning.error && <InlineAlert tone="critical" title="Could not load the learning status">{learning.error.message}</InlineAlert>}
      {data && (
        <>
          <p className="text-[14px] leading-relaxed text-ink-2">
            Approved corrections reach the translator at once. Every {fmt(data.threshold)} new ones, the model also
            retrains on them, and keeps the new version only if it translates the held-out test text at least as well.
          </p>
          <div className="mt-4">
            <div className="mb-1.5 flex justify-between text-[13px] text-ink-2">
              <span>{fmt(data.new_since_last)} new of {fmt(data.threshold)} to start learning</span>
              <span className="tabular-nums text-muted">{fmt(data.approved_pairs)} approved in total</span>
            </div>
            <div className="h-2 overflow-hidden rounded-full bg-sunken" role="progressbar" aria-valuenow={progress} aria-valuemin={0} aria-valuemax={100}>
              <div className="h-full rounded-full bg-accent transition-all" style={{ width: `${progress}%` }} />
            </div>
          </div>
          {data.problem && <InlineAlert tone="warning" title="Learning is not set up on this computer">{data.problem}</InlineAlert>}
          {data.runs.length > 0 && (
            <ul className="mt-4 space-y-2">
              {data.runs.slice(0, 3).map((run) => <RunRow key={run.id} run={run} />)}
            </ul>
          )}
          {me.role === "admin" && (
            <div className="mt-4 flex justify-end">
              <Button icon={Play} onClick={() => void start()} loading={starting} disabled={!data.available || data.running || data.approved_pairs === 0}>
                Learn now
              </Button>
            </div>
          )}
        </>
      )}
    </section>
  );
}

function RunRow({ run }: { run: LearningRun }) {
  const tone = run.state === "running" ? "neutral" : run.accepted ? "good" : run.state === "done" ? "warning" : "critical";
  const label = run.state === "running" ? "Running" : run.accepted ? "In use" : run.state === "done" ? "Not used" : run.state === "failed" ? "Failed" : "Interrupted";
  return (
    <li className="rounded-xl bg-sunken px-3 py-2 text-[13.5px] text-ink-2">
      <div className="flex flex-wrap items-center gap-2">
        <StatusBadge tone={tone} label={label} />
        <span>{fmt(run.pairs)} pairs · {run.trigger === "auto" ? "started by itself" : "started by an admin"}</span>
        {run.score_after !== null && (
          <span className="tabular-nums">
            chrF++ {run.score_before ?? "–"} → {run.score_after}
          </span>
        )}
        <span className="ml-auto text-muted">{timeAgo(run.started_at)}</span>
      </div>
      {run.message && run.state !== "running" && <p className="mt-1 text-[12.5px] text-muted">{run.message}</p>}
    </li>
  );
}

/** The admin's list of verified reviewers: add someone, see their token once, switch them off. */
export function ReviewersPanel() {
  const toast = useToast();
  const reviewers = useAsync(() => api.reviewers(), []);
  const [name, setName] = useState("");
  const [languages, setLanguages] = useState("nag");
  const [adding, setAdding] = useState(false);
  const [issued, setIssued] = useState<{ name: string; token: string } | null>(null);

  async function add() {
    setAdding(true);
    try {
      const created = await api.addReviewer({
        name: name.trim(),
        languages: languages.split(",").map((code) => code.trim()).filter(Boolean),
        role: "reviewer",
      });
      setIssued({ name: created.reviewer.name, token: created.token });
      setName("");
      await reviewers.reload();
    } catch (err) {
      toast(err instanceof Error ? err.message : "Could not add the reviewer", "critical");
    } finally {
      setAdding(false);
    }
  }

  async function toggle(id: number, active: boolean) {
    try {
      await api.setReviewerActive(id, active);
      await reviewers.reload();
    } catch (err) {
      toast(err instanceof Error ? err.message : "Could not change the reviewer", "critical");
    }
  }

  return (
    <section className="card p-5" aria-labelledby="reviewers-title">
      <div className="mb-3 flex items-center gap-2">
        <ShieldCheck aria-hidden className="size-4 text-accent-text" />
        <h2 id="reviewers-title" className="font-semibold text-ink">Verified reviewers</h2>
      </div>
      <p className="text-[14px] leading-relaxed text-ink-2">
        Trusted speakers who approve or reject contributions, each for their own languages and dialects. Each gets a
        personal token; it is shown once.
      </p>

      {issued && (
        <InlineAlert tone="good" title={`Token for ${issued.name}`}>
          <div className="mt-1 flex items-center gap-2">
            <code className="min-w-0 flex-1 truncate rounded-md bg-surface px-2 py-1 font-mono text-[13px]">{issued.token}</code>
            <Button
              size="sm"
              icon={Copy}
              onClick={() => {
                void navigator.clipboard?.writeText(issued.token);
                toast("Token copied. Send it to them privately.");
              }}
            >
              Copy
            </Button>
          </div>
          <p className="mt-1 text-[12.5px]">It cannot be shown again. If it is lost, switch this reviewer off and add them again.</p>
        </InlineAlert>
      )}

      <form
        className="mt-4 flex flex-wrap gap-2"
        onSubmit={(event) => {
          event.preventDefault();
          void add();
        }}
      >
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Name" aria-label="Reviewer name" maxLength={60} className="field h-10 min-w-0 flex-1 basis-40 px-3" />
        <input
          value={languages}
          onChange={(e) => setLanguages(e.target.value)}
          placeholder="Languages, e.g. nag, ao"
          aria-label="Languages this reviewer checks, comma-separated; empty for all"
          className="field h-10 w-40 px-3"
        />
        <Button variant="primary" type="submit" icon={UserPlus} loading={adding} disabled={name.trim().length < 2}>
          Add
        </Button>
      </form>

      {!reviewers.data && !reviewers.error && <Skeleton className="mt-4 h-16" />}
      {reviewers.data && reviewers.data.items.length > 0 && (
        <ul className="mt-4 divide-y divide-line">
          {reviewers.data.items.map((reviewer) => (
            <li key={reviewer.id} className={cx("flex flex-wrap items-center gap-2 py-2.5", !reviewer.active && "opacity-60")}>
              <GraduationCap aria-hidden className="size-4 text-muted" />
              <span className="font-medium text-ink">{reviewer.name}</span>
              <Chip>{reviewer.languages.length ? reviewer.languages.join(", ") : "all languages"}</Chip>
              <span className="text-[13px] text-muted">{fmt(reviewer.reviews)} reviews</span>
              <Button size="sm" variant="ghost" icon={UserX} className="ml-auto" onClick={() => void toggle(reviewer.id, !reviewer.active)}>
                {reviewer.active ? "Switch off" : "Switch on"}
              </Button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
