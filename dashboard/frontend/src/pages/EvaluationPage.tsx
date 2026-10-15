import { Fragment, useMemo, useState } from "react";
import { Button, CodeBlock, cx, EmptyState, InlineAlert, PageHeader, SectionHeading, Skeleton } from "../components/ui";
import { api } from "../lib/api";
import { dateTime, shortDirection } from "../lib/format";
import { useAsync } from "../lib/hooks";
import type { EvalRun } from "../lib/types";

const HARNESS = `python -m nlp_research.translation.evaluate_engine \\
    --engine nlp_research.translation.engines.my_engine:MyEngine \\
    --test-set datasets/processed/test_sets/everyday_test_en_nag.tsv \\
    --direction en-nag`;

const score = (run: EvalRun, name: string) => (name in run.metrics ? run.metrics[name] : null);

export function EvaluationPage() {
  const runs = useAsync(api.evalRuns);
  const [open, setOpen] = useState<string | null>(null);
  const list = useMemo(() => runs.data?.runs ?? [], [runs.data]);

  // The best chrF++ for each test set and direction: the only scores that may be compared.
  const best = useMemo(() => {
    const top = new Map<string, EvalRun>();
    for (const run of list) {
      const key = `${run.test_set}|${run.direction}`;
      const held = top.get(key);
      if (!held || (score(run, "chrf++") ?? -1) > (score(held, "chrf++") ?? -1)) top.set(key, run);
    }
    return [...top.values()].slice(0, 4);
  }, [list]);

  return (
    <div>
      <PageHeader
        title="Evaluation"
        description="Scores on held-out text no engine has seen. chrF++ leads because it is fair to Nagamese's varied spelling; BLEU is kept for comparison."
        actions={
          <Button loading={runs.loading && !!runs.data} onClick={() => void runs.reload()}>
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
        <EmptyState title="No evaluation runs yet">
          <p>
            Connect an engine, then score it on a held-out test set: a TSV with the columns <code className="font-mono text-[12.5px]">source_text</code>{" "}
            and <code className="font-mono text-[12.5px]">target_text</code>, translated by native speakers and never used for training or retrieval.
          </p>
          <div className="pt-2">
            <CodeBlock code={HARNESS} label="From the repository root" />
          </div>
        </EmptyState>
      )}

      {best.length > 0 && (
        <section aria-label="Best scores" className="reveal mb-14 grid grid-cols-2 gap-y-8 border-y border-line py-7 lg:grid-cols-4">
          {best.map((run, index) => {
            const chrf = score(run, "chrf++");
            const bleu = score(run, "bleu");
            return (
              <div key={run.run_id} className={cx("min-w-0 pr-4", index % 2 === 1 && "border-l border-line pl-5", index >= 2 && "lg:border-l lg:border-line lg:pl-6", index === 1 && "lg:pl-6")}>
                <p className="flex items-baseline gap-2 text-[12.5px] font-medium text-muted">
                  <span className="font-mono text-[12px] text-ink-2">{shortDirection(run.direction)}</span>
                  <span className="truncate">{run.test_set}</span>
                </p>
                <p className="num mt-1 text-[34px] leading-none font-semibold tracking-[-0.03em] text-ink">{chrf === null ? "—" : chrf.toFixed(1)}</p>
                <p className="mt-2 truncate text-[12.5px] text-muted">
                  chrF++ by {run.engine_id}
                  {bleu !== null && <span className="text-faint"> · BLEU {bleu.toFixed(1)}</span>}
                </p>
              </div>
            );
          })}
        </section>
      )}

      {list.length > 0 && (
        <section className="reveal" aria-labelledby="runs-title">
          <SectionHeading id="runs-title" title="All runs" description="Scores compare only within the same test set and direction. Open a run to read its weakest segments." />
          <div className="-mt-4 overflow-x-auto">
            <table className="w-full min-w-[760px] text-left text-[13.5px]">
              <thead>
                <tr className="border-b border-line text-[12px] font-medium text-muted">
                  <th className="py-2.5 pr-4 font-medium">Engine</th>
                  <th className="py-2.5 pr-4 font-medium">Test set</th>
                  <th className="py-2.5 pr-4 font-medium">Direction</th>
                  <th className="py-2.5 pr-4 text-right font-medium">Segments</th>
                  <th className="py-2.5 pr-4 text-right font-medium">chrF++</th>
                  <th className="py-2.5 pr-4 text-right font-medium">BLEU</th>
                  <th className="py-2.5 pr-4 font-medium">Recorded</th>
                  <th className="py-2.5" aria-label="Segments" />
                </tr>
              </thead>
              <tbody>
                {list.map((run) => (
                  <Fragment key={run.run_id}>
                    <RunRow run={run} open={open === run.run_id} onToggle={() => setOpen(open === run.run_id ? null : run.run_id)} />
                    {open === run.run_id && (
                      <tr className="border-b border-line">
                        <td colSpan={8} className="pt-2 pb-8">
                          <RunDetail runId={run.run_id} notes={run.notes} />
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {runs.data && runs.data.invalid_files.length > 0 && (
        <section className="mt-12 space-y-2">
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
  const chrf = score(run, "chrf++");
  const bleu = score(run, "bleu");
  return (
    <tr className={cx("border-b border-line align-top", open && "border-transparent")}>
      <td className="py-3 pr-4">
        <span className="font-medium text-ink">{run.engine_id}</span>
        {run.engine_version && <span className="ml-1.5 font-mono text-[11px] text-faint">v{run.engine_version}</span>}
        {run.notes && (
          <div className="mt-0.5 max-w-[16rem] truncate text-[12px] text-muted" title={run.notes}>
            {run.notes}
          </div>
        )}
      </td>
      <td className="py-3 pr-4 whitespace-nowrap text-ink-2">{run.test_set}</td>
      <td className="py-3 pr-4 font-mono text-[12px] whitespace-nowrap text-ink-2">{shortDirection(run.direction)}</td>
      <td className="num py-3 pr-4 text-right font-mono text-[12px] text-muted">{run.n_segments}</td>
      <td className="py-3 pr-4 text-right">
        <span className="num font-mono text-[13px] font-medium text-ink">{chrf === null ? "—" : chrf.toFixed(2)}</span>
        {chrf !== null && (
          <span aria-hidden className="mt-1.5 ml-auto block h-[2px] w-16 bg-line">
            <span className="block h-full bg-ink" style={{ width: `${Math.min(chrf, 100)}%` }} />
          </span>
        )}
      </td>
      <td className="num py-3 pr-4 text-right font-mono text-[12px] text-ink-2">{bleu === null ? "—" : bleu.toFixed(2)}</td>
      <td className="py-3 pr-4 text-[12.5px] whitespace-nowrap text-muted">{dateTime(run.created_at)}</td>
      <td className="py-2 text-right">
        <Button size="sm" variant="ghost" onClick={onToggle} aria-expanded={open} aria-label={`${open ? "Hide" : "Show"} the segments of ${run.run_id}`}>
          {open ? "Hide" : "Segments"}
        </Button>
      </td>
    </tr>
  );
}

function RunDetail({ runId, notes }: { runId: string; notes: string | null }) {
  const detail = useAsync(() => api.evalRun(runId), [runId]);
  const [showAll, setShowAll] = useState(false);
  if (detail.error) {
    return (
      <InlineAlert tone="critical" title="Could not load the run">
        {detail.error.message}
      </InlineAlert>
    );
  }
  if (!detail.data) return <Skeleton className="h-24" />;

  const segments = [...detail.data.segments].sort((a, b) => (a.chrf ?? 0) - (b.chrf ?? 0));
  const visible = showAll ? segments : segments.slice(0, 12);
  const [source, target] = detail.data.direction.split("-");
  return (
    <div className="rounded-lg border border-line bg-surface">
      <p className="border-b border-line px-4 py-2.5 text-[12.5px] text-muted">
        Weakest segments first, for error analysis.{notes ? ` ${notes}` : ""}
      </p>
      <table className="w-full text-left text-[14px]">
        <thead className="sr-only">
          <tr>
            <th>chrF++</th>
            <th>Source</th>
            <th>Reference and engine output</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {visible.map((segment, index) => (
            <tr key={index} className="align-top">
              <td className="num w-16 py-3 pr-2 pl-4 text-right font-mono text-[12px] text-ink">{segment.chrf === null ? "—" : segment.chrf.toFixed(1)}</td>
              <td lang={source} className="w-[34%] px-4 py-3 text-[14.5px] leading-relaxed text-muted">
                {segment.source}
              </td>
              <td className="py-3 pr-4">
                <p lang={target} className="text-[14.5px] leading-relaxed text-ink">
                  {segment.reference}
                </p>
                <p className="mt-1 text-[14.5px] leading-relaxed text-ink-2">
                  <span className="mr-2 font-sans text-[11px] font-medium text-accent-text">Engine</span>
                  <span lang={target}>{segment.hypothesis || <em className="text-muted">failed</em>}</span>
                </p>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {segments.length > visible.length && (
        <div className="border-t border-line px-4 py-2.5">
          <Button size="sm" variant="ghost" onClick={() => setShowAll(true)} className="-ml-2.5">
            Show all {segments.length} segments
          </Button>
        </div>
      )}
    </div>
  );
}
