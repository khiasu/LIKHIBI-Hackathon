import { Ban, Check, Download, Inbox, KeyRound, RefreshCw, ThumbsDown, ThumbsUp, Trash2, Undo2, UserRound } from "lucide-react";
import { useState } from "react";
import { useToast } from "../components/AppContext";
import {
  Button,
  Chip,
  cx,
  EmptyState,
  InlineAlert,
  PageHeader,
  Skeleton,
  StatusBadge,
  type Tone,
} from "../components/ui";
import { api, ApiError, getAdminToken, setAdminToken } from "../lib/api";
import { fmt, langName, timeAgo } from "../lib/format";
import { useAsync } from "../lib/hooks";
import type { Feedback, FeedbackStatus } from "../lib/types";

type Filter = FeedbackStatus | "all";

const FILTERS: { value: Filter; label: string }[] = [
  { value: "pending", label: "Pending" },
  { value: "approved", label: "Approved" },
  { value: "rejected", label: "Rejected" },
  { value: "all", label: "All" },
];

const STATUS_BADGE: Record<FeedbackStatus, { tone: Tone; label: string }> = {
  pending: { tone: "neutral", label: "Pending" },
  approved: { tone: "good", label: "Approved" },
  rejected: { tone: "critical", label: "Rejected" },
};

export function ContributionsPage() {
  const toast = useToast();
  const [filter, setFilter] = useState<Filter>("pending");
  const [tokenDraft, setTokenDraft] = useState("");
  const [tokenVersion, setTokenVersion] = useState(0);
  const feedback = useAsync(() => api.listFeedback(filter), [filter, tokenVersion]);
  const needsToken = feedback.error instanceof ApiError && feedback.error.status === 401;

  async function review(item: Feedback, status: FeedbackStatus) {
    try {
      await api.reviewFeedback(item.id, status);
      await feedback.reload();
    } catch (err) {
      toast(err instanceof Error ? err.message : "Review failed", "critical");
    }
  }

  async function remove(item: Feedback) {
    try {
      await api.deleteFeedback(item.id);
      toast("Removed for good. The engines no longer use it.");
      await feedback.reload();
    } catch (err) {
      toast(err instanceof Error ? err.message : "Could not remove it", "critical");
    }
  }

  async function exportTsv() {
    try {
      const blob = await api.exportCorrections();
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = "likhibi_approved_corrections.tsv";
      link.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      toast(err instanceof Error ? err.message : "Export failed", "critical");
    }
  }

  const counts = feedback.data?.counts;

  return (
    <div>
      <PageHeader
        eyebrow="Community"
        title="Review contributions"
        description="Approve what speakers send before the translator uses it. Every approved pair is human-verified, published under CC BY-SA 4.0 and credited to its contributor. Remove anything whose author withdraws it."
        actions={
          <>
            <Button icon={RefreshCw} onClick={() => void feedback.reload()} loading={feedback.loading && !!feedback.data}>
              Refresh
            </Button>
            <Button variant="primary" icon={Download} onClick={() => void exportTsv()} disabled={needsToken}>
              Export approved pairs
            </Button>
          </>
        }
      />

      {needsToken ? (
        <form
          className="card max-w-lg space-y-3 p-5"
          onSubmit={(event) => {
            event.preventDefault();
            setAdminToken(tokenDraft.trim() || null);
            setTokenDraft("");
            setTokenVersion((v) => v + 1);
          }}
        >
          <div className="flex items-center gap-2 font-semibold text-ink">
            <KeyRound aria-hidden className="size-4 text-accent-text" />
            Reviewing needs the admin token
          </div>
          <p className="text-[14.5px] text-ink-2">
            This server sets <code className="font-mono text-[13px]">LIKHIBI_ADMIN_TOKEN</code>. The token is kept in this
            browser only.
          </p>
          {getAdminToken() && <InlineAlert tone="warning" title="The saved token was rejected." />}
          <div className="flex gap-2">
            <input
              type="password"
              value={tokenDraft}
              onChange={(event) => setTokenDraft(event.target.value)}
              aria-label="Admin token"
              placeholder="Admin token"
              className="h-10 flex-1 rounded-lg border border-line-strong bg-surface-2 px-3 text-ink focus:border-accent focus:outline-none"
            />
            <Button variant="primary" type="submit" disabled={!tokenDraft.trim()}>
              Unlock
            </Button>
          </div>
        </form>
      ) : (
        <>
          <div role="tablist" aria-label="Filter by status" className="reveal mb-6 inline-flex flex-wrap gap-1 rounded-xl border border-line bg-surface p-1">
            {FILTERS.map((option) => (
              <button
                key={option.value}
                role="tab"
                aria-selected={filter === option.value}
                onClick={() => setFilter(option.value)}
                className={cx(
                  "flex h-9 items-center gap-2 rounded-lg px-3.5 text-[14.5px] font-semibold transition-colors",
                  filter === option.value ? "bg-sunken text-ink" : "text-ink-2 hover:text-ink",
                )}
              >
                {option.label}
                {counts && <span className="tabular-nums text-muted">{fmt(counts[option.value])}</span>}
              </button>
            ))}
          </div>

          {feedback.error && !needsToken && (
            <InlineAlert tone="critical" title="Could not load contributions">
              {feedback.error.message}
            </InlineAlert>
          )}
          {!feedback.data && !feedback.error && <Skeleton className="h-40" />}

          {feedback.data && feedback.data.items.length === 0 && (
            <EmptyState icon={Inbox} title={filter === "pending" ? "Nothing waiting for review" : "Nothing here yet"}>
              <p>
                Corrections arrive from the Translate page when someone marks a translation as wrong or suggests a
                better one.
              </p>
            </EmptyState>
          )}

          {feedback.data && feedback.data.items.length > 0 && (
            <ul className={cx("space-y-3 transition-opacity", feedback.loading && "opacity-60")}>
              {feedback.data.items.map((item) => (
                <ContributionCard
                  key={item.id}
                  item={item}
                  onReview={(status) => void review(item, status)}
                  onRemove={() => void remove(item)}
                />
              ))}
            </ul>
          )}
        </>
      )}
    </div>
  );
}

function ContributionCard({
  item,
  onReview,
  onRemove,
}: {
  item: Feedback;
  onReview: (status: FeedbackStatus) => void;
  onRemove: () => void;
}) {
  const badge = STATUS_BADGE[item.status];
  const [confirmRemove, setConfirmRemove] = useState(false);
  const fromEngine = item.machine_translation !== null;
  const dtClass = "mb-1 text-[12.5px] font-medium text-muted";
  return (
    <li className="card p-5">
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <StatusBadge tone={badge.tone} label={badge.label} />
        {item.rating === "up" && (
          <Chip>
            <ThumbsUp aria-hidden className="size-3" /> Rated good
          </Chip>
        )}
        {item.rating === "down" && (
          <Chip>
            <ThumbsDown aria-hidden className="size-3" /> Rated wrong
          </Chip>
        )}
        {item.engine_id && <Chip mono>{item.engine_id}</Chip>}
        {item.license && <Chip>{item.license === "CC-BY-SA-4.0" ? "CC BY-SA 4.0" : item.license}</Chip>}
        {item.contributor && (
          <Chip>
            <UserRound aria-hidden className="size-3" /> {item.contributor}
          </Chip>
        )}
        <span className="ml-auto text-[13px] text-muted">{timeAgo(item.created_at)}</span>
      </div>

      <dl className={cx("grid gap-4", fromEngine ? "md:grid-cols-3" : "md:grid-cols-2")}>
        <div>
          <dt className={dtClass}>{langName(item.source_lang)}</dt>
          <dd lang={item.source_lang} className="text-[16px] leading-relaxed text-ink">{item.source_text}</dd>
        </div>
        {fromEngine && (
          <div>
            <dt className={dtClass}>Engine output</dt>
            <dd lang={item.target_lang} className="text-[16px] leading-relaxed text-ink-2">
              {item.machine_translation}
            </dd>
          </div>
        )}
        <div>
          <dt className={dtClass}>{fromEngine ? "Correction" : `${langName(item.target_lang)}, from a speaker`}</dt>
          <dd lang={item.target_lang} className="border-l-2 border-accent pl-3 text-[16px] leading-relaxed text-ink">
            {item.corrected_translation ?? <em className="text-muted">none, rating only</em>}
          </dd>
        </div>
      </dl>

      {item.comment && <p className="mt-4 rounded-xl bg-sunken px-3 py-2 text-[14px] text-ink-2">{item.comment}</p>}

      <div className="mt-4 flex flex-wrap justify-end gap-2 border-t border-line pt-4">
        {confirmRemove ? (
          <>
            <span className="mr-auto self-center text-[13.5px] text-ink-2">Remove it for good, everywhere?</span>
            <Button size="sm" variant="ghost" onClick={() => setConfirmRemove(false)}>
              Keep it
            </Button>
            <Button size="sm" variant="danger" icon={Trash2} onClick={onRemove}>
              Remove for good
            </Button>
          </>
        ) : (
          <Button size="sm" variant="ghost" icon={Trash2} onClick={() => setConfirmRemove(true)} className="mr-auto">
            Remove
          </Button>
        )}
        {!confirmRemove && item.status !== "pending" && (
          <Button size="sm" variant="ghost" icon={Undo2} onClick={() => onReview("pending")}>
            Back to pending
          </Button>
        )}
        {!confirmRemove && item.status !== "rejected" && (
          <Button size="sm" variant="danger" icon={Ban} onClick={() => onReview("rejected")}>
            Reject
          </Button>
        )}
        {!confirmRemove && item.status !== "approved" && (
          <Button size="sm" variant="primary" icon={Check} onClick={() => onReview("approved")}>
            Approve
          </Button>
        )}
      </div>
    </li>
  );
}
