import { Fragment, useState } from "react";
import { useToast } from "../components/AppContext";
import { LearningPanel, ReviewersPanel } from "../components/CommunityPanels";
import { Button, cx, EmptyState, InlineAlert, PageHeader, Skeleton, StatusBadge, type Tone } from "../components/ui";
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
  const me = useAsync(() => api.me(), [tokenVersion]);
  const needsToken = feedback.error instanceof ApiError && feedback.error.status === 401;
  const [learningVersion, setLearningVersion] = useState(0);

  async function review(item: Feedback, status: FeedbackStatus) {
    try {
      await api.reviewFeedback(item.id, status);
      await feedback.reload();
      if (status === "approved") setLearningVersion((v) => v + 1);
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
        title="Review"
        description="Corrections from the dashboard and the keyboard wait here for a verified speaker. What you approve is credited, shared under CC BY-SA 4.0, and teaches the models."
        actions={
          !needsToken && (
            <>
              <Button onClick={() => void feedback.reload()} loading={feedback.loading && !!feedback.data}>
                Refresh
              </Button>
              <Button variant="primary" onClick={() => void exportTsv()}>
                Export approved pairs
              </Button>
            </>
          )
        }
      />

      {needsToken ? (
        <form
          className="reveal card max-w-[480px] p-6 sm:p-7"
          onSubmit={(event) => {
            event.preventDefault();
            setAdminToken(tokenDraft.trim() || null);
            setTokenDraft("");
            setTokenVersion((v) => v + 1);
          }}
        >
          <h2 className="text-[17px] font-semibold tracking-[-0.015em] text-ink">Sign in to review</h2>
          <p className="mt-1.5 text-[13.5px] leading-relaxed text-muted">
            Use your reviewer token, or the admin token set in <code className="font-mono text-[12px] text-ink-2">LIKHIBI_ADMIN_TOKEN</code>. It
            stays in this browser.
          </p>
          {getAdminToken() && (
            <div className="mt-4">
              <InlineAlert tone="warning" title="The saved token was not accepted." />
            </div>
          )}
          <label htmlFor="token" className="mt-5 mb-1 block text-[12px] font-medium text-muted">
            Token
          </label>
          <div className="flex gap-2">
            <input
              id="token"
              type="password"
              value={tokenDraft}
              onChange={(event) => setTokenDraft(event.target.value)}
              autoComplete="current-password"
              className="field h-[34px] flex-1 px-2.5 font-mono text-[13px]"
            />
            <Button variant="primary" type="submit" disabled={!tokenDraft.trim()}>
              Sign in
            </Button>
          </div>
        </form>
      ) : (
        <div className="grid gap-x-14 gap-y-12 xl:grid-cols-[minmax(0,1fr)_320px]">
          <div className="min-w-0">
            {/* Status filter: words with counts, the current one underlined like the theme switch. */}
            <div className="reveal flex flex-wrap items-end justify-between gap-x-6 gap-y-3 border-b border-line">
              <div role="tablist" aria-label="Filter by status" className="-mb-px flex gap-6">
                {FILTERS.map((option) => (
                  <button
                    key={option.value}
                    role="tab"
                    aria-selected={filter === option.value}
                    onClick={() => setFilter(option.value)}
                    className={cx(
                      "flex items-baseline gap-1.5 border-b-2 pb-2.5 text-[14px] transition-colors",
                      filter === option.value ? "border-accent font-medium text-ink" : "border-transparent text-muted hover:text-ink",
                    )}
                  >
                    {option.label}
                    {counts && <span className="num font-mono text-[11.5px] text-faint">{fmt(counts[option.value])}</span>}
                  </button>
                ))}
              </div>
              {me.data && (
                <p className="pb-2.5 text-[12.5px] text-muted">
                  {me.data.name}, {me.data.role === "admin" ? "admin" : `reviewer for ${me.data.languages.length ? me.data.languages.join(", ") : "all languages"}`}
                  {getAdminToken() && (
                    <>
                      <span className="mx-1.5 text-faint">·</span>
                      <button
                        onClick={() => {
                          setAdminToken(null);
                          setTokenVersion((v) => v + 1);
                        }}
                        className="text-ink-2 underline decoration-line-strong underline-offset-[3px] hover:text-ink hover:decoration-ink"
                      >
                        Sign out
                      </button>
                    </>
                  )}
                </p>
              )}
            </div>

            {feedback.error && !needsToken && (
              <div className="mt-6">
                <InlineAlert tone="critical" title="Could not load contributions">
                  {feedback.error.message}
                </InlineAlert>
              </div>
            )}
            {!feedback.data && !feedback.error && <Skeleton className="mt-6 h-40" />}

            {feedback.data && feedback.data.items.length === 0 && (
              <div className="mt-6">
                <EmptyState title={filter === "pending" ? "Nothing is waiting for review" : "Nothing here yet"}>
                  <p>
                    Corrections arrive when someone marks a translation as wrong on the Translate page, or sends one from the
                    keyboard.
                  </p>
                </EmptyState>
              </div>
            )}

            {feedback.data && feedback.data.items.length > 0 && (
              <ul className={cx("divide-y divide-line transition-opacity", feedback.loading && "opacity-60")}>
                {feedback.data.items.map((item) => (
                  <ContributionRow key={item.id} item={item} onReview={(status) => void review(item, status)} onRemove={() => void remove(item)} />
                ))}
              </ul>
            )}
          </div>

          {me.data && (
            <aside className="flex flex-col gap-12" aria-label="Learning and reviewers">
              <LearningPanel me={me.data} version={learningVersion} />
              {me.data.role === "admin" && <ReviewersPanel />}
            </aside>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * One contribution, set like a proof correction: the source, the engine's words struck
 * through, and the speaker's wording beneath them.
 */
function ContributionRow({
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
  const corrected = item.corrected_translation !== null && item.corrected_translation !== item.machine_translation;
  const fromEngine = item.machine_translation !== null;

  const facts = [
    item.contributor ? `by ${item.contributor}` : "anonymous",
    item.variety,
    item.origin === "keyboard" ? "from the keyboard" : "from the dashboard",
    item.rating === "up" ? "rated good" : item.rating === "down" ? "rated wrong" : null,
    item.license ? (item.license === "CC-BY-SA-4.0" ? "CC BY-SA 4.0" : item.license) : null,
  ].filter(Boolean) as string[];

  const term = "pt-[5px] text-[12px] font-medium text-muted";

  return (
    <li className="py-6">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <StatusBadge tone={badge.tone} label={badge.label} />
        <p className="text-[12.5px] text-muted">
          {facts.map((fact, index) => (
            <Fragment key={fact}>
              {index > 0 && <span className="mx-1.5 text-faint">·</span>}
              {fact}
            </Fragment>
          ))}
        </p>
        <span className="ml-auto font-mono text-[11.5px] text-faint">{timeAgo(item.created_at)}</span>
      </div>

      <dl className="mt-4 grid grid-cols-[84px_minmax(0,1fr)] gap-x-5 gap-y-2 sm:grid-cols-[104px_minmax(0,1fr)]">
        <dt className={term}>{langName(item.source_lang)}</dt>
        <dd lang={item.source_lang} className="text-[17px] leading-relaxed text-ink">
          {item.source_text}
        </dd>

        {fromEngine && (
          <>
            <dt className={term}>
              Engine
              {item.engine_id && <span className="block truncate font-mono text-[10.5px] font-normal text-faint">{item.engine_id}</span>}
            </dt>
            <dd
              lang={item.target_lang}
              className={cx(
                "text-[17px] leading-relaxed",
                corrected ? "text-muted line-through decoration-accent decoration-[1.5px]" : "text-ink-2",
              )}
            >
              {item.machine_translation}
            </dd>
          </>
        )}

        <dt className={term}>{fromEngine ? "Correction" : langName(item.target_lang)}</dt>
        <dd lang={item.target_lang} className="text-[17px] leading-relaxed text-ink">
          {item.corrected_translation ?? <span className="font-sans text-[13.5px] text-faint">No correction, a rating only.</span>}
        </dd>

        {item.comment && (
          <>
            <dt className={term}>Note</dt>
            <dd className="text-[14px] leading-relaxed text-ink-2">{item.comment}</dd>
          </>
        )}
      </dl>

      <div className="mt-5 flex flex-wrap items-center gap-2 sm:pl-[124px]">
        {item.reviewed_by && item.status !== "pending" && !confirmRemove && (
          <p className="mr-auto text-[12.5px] text-muted">
            {item.status === "approved" ? "Approved" : "Rejected"} by {item.reviewed_by}
            {item.reviewed_at ? `, ${timeAgo(item.reviewed_at)}` : ""}
            {item.review_note ? `: ${item.review_note}` : ""}
          </p>
        )}
        {confirmRemove ? (
          <>
            <span className="mr-auto text-[13px] text-ink-2">Remove it everywhere, for good?</span>
            <Button size="sm" variant="ghost" onClick={() => setConfirmRemove(false)}>
              Keep it
            </Button>
            <Button size="sm" variant="danger" onClick={onRemove}>
              Remove for good
            </Button>
          </>
        ) : (
          <>
            <Button size="sm" variant="ghost" onClick={() => setConfirmRemove(true)} className={cx(!(item.reviewed_by && item.status !== "pending") && "mr-auto -ml-2.5", "text-muted")}>
              Remove
            </Button>
            {item.status !== "pending" && (
              <Button size="sm" variant="ghost" onClick={() => onReview("pending")}>
                Back to pending
              </Button>
            )}
            {item.status !== "rejected" && (
              <Button size="sm" variant="danger" onClick={() => onReview("rejected")}>
                Reject
              </Button>
            )}
            {item.status !== "approved" && (
              <Button size="sm" variant="primary" onClick={() => onReview("approved")}>
                Approve
              </Button>
            )}
          </>
        )}
      </div>
    </li>
  );
}
