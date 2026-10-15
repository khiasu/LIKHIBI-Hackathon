import { ChartColumn, ChevronDown, ChevronRight, RefreshCw } from "lucide-react";
import { Fragment, useState } from "react";
import {
  Button,
  CodeBlock,
  cx,
  EmptyState,
  InlineAlert,
  PageHeader,
  SectionHeading,
  Skeleton,
} from "../components/ui";
import { api } from "../lib/api";
import { dateTime, shortDirection } from "../lib/format";
import { useAsync } from "../lib/hooks";
import type { EvalRun } from "../lib/types";

const HARNESS = `python -m nlp_research.translation.evaluate_engine \\
    --engine nlp_research.translation.engines.my_engine:MyEngine \\
    --test-set datasets/processed/test_sets/everyday_test_en_nag.tsv \\
    --direction en-nag`;

export function EvaluationPage() {
  const runs = useAsync(api.evalRuns);
  const [open, setOpen] = useState<string | null>(null);
  const list = runs.data?.runs ?? [];

  return (
    <div>
      <PageHeader
        title="Evaluation"
        description="Scores on held-out text. chrF++ leads because it suits Nagamese's varied spelling; BLEU is shown for comparison."
        actions={
          <Button icon={RefreshCw} loading={runs.loading && !!runs.data} onClick={() => void runs.reload()}>
            Refresh
          </Button>
        }
      />

      {runs.error && (
        <InlineAlert tone="critical" title="Could not load evaluation runs">
          {runs.error.message}
        </InlineAlert>
      )}
      {!runs.data && !runs.error && <Skeleton className="h-48" />}

      {runs.data && list.length === 0 && (
        <EmptyState icon={ChartColumn} title="No evaluation runs yet">
          <p>
            Connect an engine, then score it on a held-out test set. A test set is a TSV with the columns{" "}
            <code className="font-mono text-[13px]">source_text</code> and{" "}
            <code className="font-mono text-[13px]">target_text</code>, translated by native speakers and never used for
            training or retrieval.
          </p>
          <div className="pt-2">
            <CodeBlock code={HARNESS} label="From the repository root" />
          </div>
        </EmptyState>
      )}

      {list.length > 0 && (
        <section className="reveal">
          <div className="card overflow-x-auto">
            <table className="w-full min-w-[720px] text-left text-[14.5px]">
              <thead>
                <tr className="border-b border-line text-[12.5px] font-medium text-muted">
                  <th className="w-8 px-4 py-3" aria-label="Expand" />
                  <th className="px-4 py-3">Recorded</th>
                  <th className="px-4 py-3">Engine</th>
                  <th className="px-4 py-3">Test set</th>
                  <th className="px-4 py-3">Direction</th>
                  <th className="px-4 py-3 text-right">Segments</th>
                  <th className="px-4 py-3 text-right normal-case">chrF++</th>
                  <th className="px-4 py-3 text-right">BLEU</th>
                </tr>
              </thead>
              <tbody>
                {list.map((run) => (
                  <Fragment key={run.run_id}>
                    <RunRow run={run} open={open === run.run_id} onToggle={() => setOpen(open === run.run_id ? null : run.run_id)} />
                    {open === run.run_id && (
                      <tr className="border-b border-line bg-surface-2">
                        <td colSpan={8} className="px-4 py-5">
                          <RunDetail runId={run.run_id} notes={run.notes} />
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
          <p className="mt-3 text-[13px] text-muted">
            Scores are only comparable within the same test set and direction.
          </p>
        </section>
      )}

      {runs.data && runs.data.invalid_files.length > 0 && (
        <section className="mt-8 space-y-2">
          <SectionHeading title="Files that could not be read" />
          {runs.data.invalid_files.map((item) => (
            <InlineAlert key={item.file} tone="warning" title={item.file}>
              {item.error}
            </InlineAlert>
          ))}
        </section>
      )}
    </div>
  );
}

function RunRow({ run, open, onToggle }: { run: EvalRun; open: boolean; onToggle: () => void }) {
  const metric = (name: string) => (name in run.metrics ? run.metrics[name].toFixed(2) : "—");
  return (
    <tr className={cx("border-b border-line transition-colors hover:bg-sunken/60", open && "bg-sunken/60")}>
      <td className="px-4 py-3">
        <button
          onClick={onToggle}
          aria-expanded={open}
          aria-label={`${open ? "Hide" : "Show"} segments for ${run.run_id}`}
          className="grid size-7 place-items-center rounded-md text-muted hover:bg-surface hover:text-ink"
        >
          {open ? <ChevronDown aria-hidden className="size-4" /> : <ChevronRight aria-hidden className="size-4" />}
        </button>
      </td>
      <td className="px-4 py-3 whitespace-nowrap text-ink-2">{dateTime(run.created_at)}</td>
      <td className="px-4 py-3">
        <span className="font-semibold text-ink">{run.engine_id}</span>
        {run.engine_version && <span className="ml-1.5 font-mono text-[12px] text-muted">v{run.engine_version}</span>}
        {run.notes && (
          <div className="mt-0.5 max-w-[13rem] truncate text-[12.5px] text-muted" title={run.notes}>
            {run.notes}
          </div>
        )}
      </td>
      <td className="px-4 py-3 whitespace-nowrap text-ink">{run.test_set}</td>
      <td className="px-4 py-3 whitespace-nowrap text-ink-2">{shortDirection(run.direction)}</td>
      <td className="px-4 py-3 text-right tabular-nums text-ink-2">{run.n_segments}</td>
      <td className="px-4 py-3 text-right font-semibold tabular-nums text-ink">{metric("chrf++")}</td>
      <td className="px-4 py-3 text-right tabular-nums text-ink-2">{metric("bleu")}</td>
    </tr>
  );
}

function RunDetail({ runId, notes }: { runId: string; notes: string | null }) {
  const detail = useAsync(() => api.evalRun(runId), [runId]);
  const [showAll, setShowAll] = useState(false);
  if (detail.error) return <InlineAlert tone="critical" title="Could not load the run">{detail.error.message}</InlineAlert>;
  if (!detail.data) return <Skeleton className="h-24" />;

  const segments = [...detail.data.segments].sort((a, b) => (a.chrf ?? 0) - (b.chrf ?? 0));
  const visible = showAll ? segments : segments.slice(0, 12);
  return (
    <div className="space-y-3">
      {notes && <p className="text-[14px] text-ink-2">Notes: {notes}</p>}
      <p className="text-[14px] font-semibold text-ink">Weakest segments first, for error analysis.</p>
      <div className="overflow-x-auto rounded-xl border border-line bg-surface">
        <table className="w-full min-w-[680px] text-left text-[14px]">
          <thead>
            <tr className="border-b border-line text-[12.5px] font-medium text-muted">
              <th className="px-3 py-2 text-right normal-case">chrF++</th>
              <th className="px-3 py-2">Source</th>
              <th className="px-3 py-2">Reference</th>
              <th className="px-3 py-2">Engine output</th>
            </tr>
          </thead>
          <tbody>
            {visible.map((segment, index) => (
              <tr key={index} className="border-b border-line align-top last:border-0">
                <td className="px-3 py-2 text-right font-semibold tabular-nums text-ink">
                  {segment.chrf === null ? "—" : segment.chrf.toFixed(1)}
                </td>
                <td className="px-3 py-2 text-ink-2">{segment.source}</td>
                <td className="px-3 py-2 text-ink">{segment.reference}</td>
                <td className="px-3 py-2 text-[15px] text-ink">{segment.hypothesis || <em className="text-muted">failed</em>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {segments.length > visible.length && (
        <Button size="sm" onClick={() => setShowAll(true)}>
          Show all {segments.length} segments
        </Button>
      )}
    </div>
  );
}
