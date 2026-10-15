import { ArrowRight, ClipboardCheck, HeartHandshake, Languages } from "lucide-react";
import { useApp } from "../components/AppContext";
import { ButtonLink, PageHeader, Skeleton, StatTile, StatusIcon, type Tone } from "../components/ui";
import { api } from "../lib/api";
import { fmt } from "../lib/format";
import { href, useAsync } from "../lib/hooks";
import type { EngineState } from "../lib/types";

const ENGINE_TONE: Record<EngineState, Tone> = { ready: "good", not_configured: "warning", error: "critical" };

export function HomePage() {
  const { engines } = useApp();
  const health = useAsync(api.dataHealth);
  const community = useAsync(api.communityStats);
  const words = useAsync(() => api.meaningTasks([], 1));

  const data = health.data;
  const pairs = data ? data.parallel.reduce((sum, file) => sum + file.english_real, 0) + data.community.approved_pairs : null;
  const realEngines = engines?.engines.filter((engine) => engine.kind !== "mock") ?? [];
  const ready = realEngines.filter((engine) => engine.state === "ready");
  const pending = community.data?.pending ?? 0;

  return (
    <div>
      <PageHeader
        title="Overview"
        description="The Likhibi keyboard, translator and open word-bank for Nagamese."
        actions={
          <>
            <ButtonLink href={href("contribute")} icon={HeartHandshake}>
              Contribute
            </ButtonLink>
            <ButtonLink href={href("translate")} variant="primary" icon={Languages}>
              Translate
            </ButtonLink>
          </>
        }
      />

      <section aria-label="Likhibi in numbers" className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
        {pairs === null ? (
          <Skeleton className="h-[108px] rounded-xl" />
        ) : (
          <StatTile label="Sentence pairs" value={fmt(pairs)} context="Aligned English–Nagamese, with approved corrections" />
        )}
        {data && words.data ? (
          <StatTile label="Words in the lexicon" value={fmt(data.lexicon.entries)} context={`${fmt(words.data.remaining)} still need a meaning`} />
        ) : (
          <Skeleton className="h-[108px] rounded-xl" />
        )}
        {community.data ? (
          <StatTile
            label="Approved contributions"
            value={fmt(community.data.approved_pairs)}
            context={`${fmt(community.data.contributors)} credited contributors`}
          />
        ) : (
          <Skeleton className="h-[108px] rounded-xl" />
        )}
        {engines ? (
          <StatTile label="Engines ready" value={`${ready.length} / ${realEngines.length}`} context="Translation memory, LLM and NMT" />
        ) : (
          <Skeleton className="h-[108px] rounded-xl" />
        )}
      </section>

      <div className="mt-4 grid gap-4 lg:grid-cols-[1fr_1.4fr]">
        <section className="card flex flex-col p-5" aria-labelledby="queue-title">
          <div className="flex items-center gap-2">
            <ClipboardCheck aria-hidden className="size-4 text-muted" />
            <h2 id="queue-title" className="text-[15px] font-semibold text-ink">Review queue</h2>
          </div>
          {community.data ? (
            <>
              <div className="mt-3 flex flex-wrap items-end justify-between gap-3">
                <div>
                  <p className="text-[32px] leading-none font-semibold tracking-[-0.02em] text-ink tabular-nums">{fmt(pending)}</p>
                  <p className="mt-1.5 text-[13.5px] text-muted">
                    {pending === 0 ? "Nothing waiting for review." : `${pending === 1 ? "contribution" : "contributions"} waiting for a reviewer`}
                  </p>
                </div>
                <ButtonLink href={href("review")} variant={pending > 0 ? "primary" : "secondary"}>
                  {pending > 0 ? "Start reviewing" : "Open review"}
                </ButtonLink>
              </div>
              <p className="mt-4 border-t border-line pt-3 text-[12.5px] leading-relaxed text-muted">
                Corrections arrive from the Translate page and from the Likhibi keyboard. Approved ones reach the translator at once.
              </p>
            </>
          ) : (
            <Skeleton className="mt-4 h-20" />
          )}
        </section>

        <section className="card p-5" aria-labelledby="engines-title">
          <div className="flex items-center justify-between gap-2">
            <h2 id="engines-title" className="text-[15px] font-semibold text-ink">Translation engines</h2>
            <a href={href("engines")} className="inline-flex items-center gap-1 text-[13px] font-medium text-muted hover:text-ink">
              Manage <ArrowRight aria-hidden className="size-3.5" />
            </a>
          </div>
          {!engines && <Skeleton className="mt-4 h-24" />}
          {engines && (
            <ul className="mt-3 divide-y divide-line">
              {realEngines.map((engine) => (
                <li key={engine.id} className="flex items-start gap-3 py-3 first:pt-1 last:pb-0">
                  <span className="mt-0.5">
                    <StatusIcon tone={ENGINE_TONE[engine.state]} />
                  </span>
                  <div className="min-w-0">
                    <p className="text-[14px] font-medium text-ink">{engine.name}</p>
                    {engine.status_message && <p className="truncate text-[12.5px] text-muted">{engine.status_message}</p>}
                  </div>
                  <span className="ml-auto shrink-0 text-[12px] text-faint">{engine.offline ? "Offline" : "Online"}</span>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>

      <section className="mt-5 grid gap-x-8 gap-y-3 border-t border-line pt-5 text-[13.5px] leading-relaxed text-muted sm:grid-cols-3" aria-label="About Likhibi">
        <p>
          <span className="font-medium text-ink">Type.</span> The Android keyboard completes and predicts Nagamese on the phone,
          and translates offline.
        </p>
        <p>
          <span className="font-medium text-ink">Teach.</span> Speakers correct translations; verified reviewers approve them,
          and the models learn from what is approved.
        </p>
        <p>
          <span className="font-medium text-ink">Keep.</span> Contributions are published under CC BY-SA 4.0, credited if the
          contributor wishes, and removed on request.{" "}
          <a href={href("data")} className="font-medium text-ink-2 underline underline-offset-2 hover:text-ink">
            Data sources
          </a>
        </p>
      </section>
    </div>
  );
}
