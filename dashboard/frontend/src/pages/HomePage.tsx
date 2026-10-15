import { useApp } from "../components/AppContext";
import { ButtonLink, cx, PageHeader, SectionHeading, Skeleton, StatTile, StatusBadge } from "../components/ui";
import { api } from "../lib/api";
import { STATE_META } from "../lib/engines";
import { fmt } from "../lib/format";
import { href, useAsync } from "../lib/hooks";

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

  const figures = [
    pairs === null ? null : { label: "Sentence pairs", value: fmt(pairs), context: "Aligned English and Nagamese" },
    data && words.data ? { label: "Words in the lexicon", value: fmt(data.lexicon.entries), context: `${fmt(words.data.remaining)} without a meaning yet` } : null,
    community.data ? { label: "Approved contributions", value: fmt(community.data.approved_pairs), context: `${fmt(community.data.contributors)} credited contributors` } : null,
    engines ? { label: "Engines ready", value: `${ready.length} of ${realEngines.length}`, context: "Memory, LLM and NMT" } : null,
  ];

  return (
    <div>
      <PageHeader
        title="Overview"
        description="The Likhibi keyboard, translator and open word-bank for Nagamese."
        actions={
          <>
            <ButtonLink href={href("contribute")}>Contribute</ButtonLink>
            <ButtonLink href={href("translate")} variant="primary">
              Translate
            </ButtonLink>
          </>
        }
      />

      <section aria-label="Likhibi in numbers" className="reveal grid grid-cols-2 gap-y-8 border-y border-line py-7 lg:grid-cols-4">
        {figures.map((figure, index) => (
          <div key={index} className={cx("px-0 sm:px-6", index % 2 === 1 && "border-l border-line pl-5", index >= 2 && "lg:border-l lg:border-line", index === 0 && "sm:pl-0", index === 2 && "max-lg:pl-0 lg:pl-6")}>
            {figure ? <StatTile {...figure} /> : <Skeleton className="h-[78px] w-40" />}
          </div>
        ))}
      </section>

      <div className="mt-12 grid gap-12 lg:grid-cols-[5fr_7fr]">
        <section aria-labelledby="queue-title">
          <SectionHeading id="queue-title" title="Review queue" />
          {community.data ? (
            <div>
              <p className="num text-[44px] leading-none font-semibold tracking-[-0.035em] text-ink">{fmt(pending)}</p>
              <p className="mt-2 max-w-sm text-[14px] leading-relaxed text-muted">
                {pending === 0
                  ? "Nothing is waiting. Corrections from the Translate page and the keyboard arrive here for a verified reviewer."
                  : `${pending === 1 ? "contribution is" : "contributions are"} waiting for a verified reviewer. Approved pairs reach the translator at once.`}
              </p>
              <div className="mt-5">
                <ButtonLink href={href("review")} variant={pending > 0 ? "primary" : "secondary"}>
                  {pending > 0 ? "Start reviewing" : "Open the queue"}
                </ButtonLink>
              </div>
            </div>
          ) : (
            <Skeleton className="h-28" />
          )}
        </section>

        <section aria-label="Translation engines">
          <SectionHeading
            title="Translation engines"
            actions={
              <a href={href("engines")} className="text-[13px] font-medium text-muted underline-offset-4 hover:text-ink hover:underline">
                Manage
              </a>
            }
          />
          {!engines && <Skeleton className="h-28" />}
          {engines && (
            <table className="w-full text-left">
              <thead className="sr-only">
                <tr>
                  <th>Engine</th>
                  <th>State</th>
                  <th>Runs</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {realEngines.map((engine) => (
                  <tr key={engine.id} className="align-top">
                    <td className="py-3 pr-4">
                      <p className="text-[14px] leading-5 font-medium text-ink">{engine.name}</p>
                      {engine.status_message && <p className="mt-0.5 text-[12.5px] leading-snug text-muted">{engine.status_message}</p>}
                    </td>
                    <td className="py-3 pr-6 whitespace-nowrap">
                      <div className="flex h-5 items-center">
                        <StatusBadge {...STATE_META[engine.state]} />
                      </div>
                    </td>
                    <td className="py-3 text-right font-mono text-[11.5px] leading-5 whitespace-nowrap text-faint">
                      {engine.offline ? "on device" : "online"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      </div>

      <footer className="mt-16 grid gap-x-10 gap-y-4 border-t border-line pt-6 text-[13px] leading-relaxed text-muted md:grid-cols-3" aria-label="About Likhibi">
        <p>
          <span className="font-medium text-ink-2">Type.</span> The Android keyboard completes and predicts Nagamese on the
          phone, and translates without a connection.
        </p>
        <p>
          <span className="font-medium text-ink-2">Teach.</span> Speakers correct translations. Verified reviewers approve
          them, and the models learn from what is approved.
        </p>
        <p>
          <span className="font-medium text-ink-2">Keep.</span> Contributions are shared under CC BY-SA 4.0, credited if the
          contributor wishes and removed on request.{" "}
          <a href={href("data")} className="text-ink-2 underline decoration-line-strong underline-offset-4 hover:decoration-ink">
            Sources
          </a>
        </p>
      </footer>
    </div>
  );
}
