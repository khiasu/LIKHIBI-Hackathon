import { useState } from "react";
import { useToast } from "./AppContext";
import { Button, cx, InlineAlert, Meter, SectionHeading, Skeleton, StatusBadge, type Tone } from "./ui";
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

  return (
    <section aria-labelledby="learning-title">
      <SectionHeading
        id="learning-title"
        title="Model learning"
        actions={data?.running ? <StatusBadge tone="neutral" label="Learning now" /> : undefined}
      />
      {!data && !learning.error && <Skeleton className="h-24" />}
      {learning.error && (
        <InlineAlert tone="critical" title="Could not load the learning status">
          {learning.error.message}
        </InlineAlert>
      )}
      {data && (
        <>
          <div className="flex items-baseline justify-between gap-3">
            <p className="num text-[26px] leading-none font-semibold tracking-[-0.03em] text-ink">
              {fmt(data.new_since_last)}
              <span className="text-[15px] font-normal tracking-normal text-muted"> of {fmt(data.threshold)}</span>
            </p>
            <span className="num font-mono text-[11.5px] text-faint">{fmt(data.approved_pairs)} approved in all</span>
          </div>
          <div className="mt-3">
            <Meter value={data.new_since_last} max={data.threshold} tone="neutral" label="New approved pairs towards the next learning run" />
          </div>
          <p className="mt-3 text-[13px] leading-relaxed text-muted">
            Approved pairs reach the translator at once. Every {fmt(data.threshold)} new ones, the model retrains on them
            and keeps the result only if it scores at least as well on held-out text.
          </p>
          {data.problem && (
            <div className="mt-4">
              <InlineAlert tone="warning" title="Learning is not set up on this computer">
                {data.problem}
              </InlineAlert>
            </div>
          )}
          {data.runs.length > 0 && (
            <ul className="mt-4 divide-y divide-line border-t border-line">
              {data.runs.slice(0, 3).map((run) => (
                <RunRow key={run.id} run={run} />
              ))}
            </ul>
          )}
          {me.role === "admin" && (
            <div className="mt-4">
              <Button onClick={() => void start()} loading={starting} disabled={!data.available || data.running || data.approved_pairs === 0}>
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
  const tone: Tone = run.state === "running" ? "neutral" : run.accepted ? "good" : run.state === "done" ? "warning" : "critical";
  const label =
    run.state === "running" ? "Running" : run.accepted ? "In use" : run.state === "done" ? "Not used" : run.state === "failed" ? "Failed" : "Interrupted";
  return (
    <li className="py-2.5">
      <div className="flex items-center gap-3">
        <StatusBadge tone={tone} label={label} />
        {run.score_after !== null && (
          <span className="num font-mono text-[11.5px] text-muted">
            chrF++ {run.score_before ?? "–"} → {run.score_after}
          </span>
        )}
        <span className="ml-auto font-mono text-[11.5px] text-faint">{timeAgo(run.started_at)}</span>
      </div>
      <p className="mt-0.5 text-[12.5px] text-muted">
        {fmt(run.pairs)} pairs, {run.trigger === "auto" ? "started by itself" : "started by an admin"}
        {run.message && run.state !== "running" ? `. ${run.message}` : ""}
      </p>
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
        languages: languages
          .split(",")
          .map((code) => code.trim())
          .filter(Boolean),
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
    <section aria-labelledby="reviewers-title">
      <SectionHeading id="reviewers-title" title="Verified reviewers" />
      <p className="text-[13px] leading-relaxed text-muted">
        Trusted speakers who approve contributions for their own languages. Each gets a personal token, shown once.
      </p>

      {issued && (
        <div className="mt-4">
          <InlineAlert tone="good" title={`Token for ${issued.name}`}>
            <div className="mt-1.5 flex items-center gap-2">
              <code className="min-w-0 flex-1 truncate rounded border border-line bg-surface px-2 py-1 font-mono text-[12px] text-ink">{issued.token}</code>
              <Button
                size="sm"
                onClick={() => {
                  void navigator.clipboard?.writeText(issued.token);
                  toast("Token copied. Send it to them privately.");
                }}
              >
                Copy
              </Button>
            </div>
            <p className="mt-1.5 text-[12.5px]">It cannot be shown again. If it is lost, switch this reviewer off and add them again.</p>
          </InlineAlert>
        </div>
      )}

      {!reviewers.data && !reviewers.error && <Skeleton className="mt-4 h-16" />}
      {reviewers.data && reviewers.data.items.length > 0 && (
        <ul className="mt-4 divide-y divide-line border-y border-line">
          {reviewers.data.items.map((reviewer) => (
            <li key={reviewer.id} className="flex items-center gap-3 py-2.5">
              <div className={cx("min-w-0 flex-1", !reviewer.active && "opacity-50")}>
                <p className="truncate text-[13.5px] font-medium text-ink">{reviewer.name}</p>
                <p className="font-mono text-[11px] text-faint">
                  {reviewer.languages.length ? reviewer.languages.join(", ") : "all languages"} · {fmt(reviewer.reviews)} reviews
                </p>
              </div>
              <Button size="sm" variant="ghost" onClick={() => void toggle(reviewer.id, !reviewer.active)}>
                {reviewer.active ? "Switch off" : "Switch on"}
              </Button>
            </li>
          ))}
        </ul>
      )}

      <form
        className="mt-4 grid grid-cols-[1fr_88px_auto] gap-2"
        onSubmit={(event) => {
          event.preventDefault();
          void add();
        }}
      >
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="Name"
          aria-label="Reviewer name"
          maxLength={60}
          className="field h-[34px] min-w-0 px-2.5 text-[13.5px]"
        />
        <input
          value={languages}
          onChange={(e) => setLanguages(e.target.value)}
          placeholder="nag, ao"
          aria-label="Languages this reviewer checks, comma-separated; empty for all"
          className="field h-[34px] min-w-0 px-2.5 font-mono text-[12.5px]"
        />
        <Button type="submit" loading={adding} disabled={name.trim().length < 2}>
          Add
        </Button>
      </form>
    </section>
  );
}
