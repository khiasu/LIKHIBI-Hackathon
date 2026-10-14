import { Archive, ArrowRight, HeartHandshake, Keyboard, Languages, type LucideIcon } from "lucide-react";
import { useApp } from "../components/AppContext";
import { ButtonLink, SectionHeading, Skeleton, StatTile } from "../components/ui";
import { api } from "../lib/api";
import { fmt } from "../lib/format";
import { href, useAsync } from "../lib/hooks";

const STEPS: { icon: LucideIcon; title: string; text: string; link?: { label: string; to: Parameters<typeof href>[0] } }[] = [
  {
    icon: Keyboard,
    title: "Type",
    text: "An Android keyboard that completes and predicts Nagamese words on the phone itself, with no internet: a word tree for completions, word-sequence statistics for the next word.",
  },
  {
    icon: Languages,
    title: "Translate",
    text: "Stored human translations answer first. New sentences go to an AI model shown the most similar human examples, or to our own model, which runs offline.",
    link: { label: "Try it", to: "translate" },
  },
  {
    icon: HeartHandshake,
    title: "Teach",
    text: "Speakers fix wrong translations and give words their meanings. A reviewer approves each one, and the translator uses it on the very next request.",
    link: { label: "Contribute", to: "contribute" },
  },
  {
    icon: Archive,
    title: "Keep",
    text: "What people add is published openly under CC BY-SA 4.0, credited to them if they wish, and removed if they ask. It stays a commons.",
    link: { label: "Where the data comes from", to: "data" },
  },
];

const PEOPLE: { title: string; text: string }[] = [
  {
    title: "Who contributes",
    text: "Nagamese speakers: elders who know older words, young people who know how it is typed today, teachers and translators.",
  },
  {
    title: "Who benefits",
    text: "Everyone who writes or learns Nagamese, and the teachers and researchers who get an open dataset instead of starting from nothing.",
  },
  {
    title: "Who decides",
    text: "Reviewers approve every addition before it is used. Contributors choose whether to be credited, and can withdraw what they gave.",
  },
];

export function HomePage() {
  const { engines } = useApp();
  const health = useAsync(api.dataHealth);
  const community = useAsync(api.communityStats);
  const words = useAsync(() => api.meaningTasks([], 1));

  const data = health.data;
  const pairs = data ? data.parallel.reduce((sum, file) => sum + file.english_real, 0) + data.community.approved_pairs : null;
  const realEngines = engines?.engines.filter((engine) => engine.kind !== "mock") ?? [];
  const ready = realEngines.filter((engine) => engine.state === "ready");

  return (
    <div>
      <header className="reveal flex flex-col items-center pt-2 pb-12 text-center sm:pt-6">
        <img src="/brand/likhibi-mark-512.png" alt="Likhibi logo" width={112} height={112} className="size-28" />
        <h1 className="mt-5 text-[40px] leading-none font-bold tracking-[-0.02em] text-accent-text">Likhibi</h1>
        <p className="mt-3 max-w-xl text-[17px] leading-relaxed text-ink-2">
          A keyboard, a translator and an open word-bank for Nagamese, built with the people who speak it.
        </p>
        <div className="mt-7 flex flex-wrap justify-center gap-3">
          <ButtonLink href={href("translate")} variant="primary" icon={Languages}>
            Translate
          </ButtonLink>
          <ButtonLink href={href("contribute")} icon={HeartHandshake}>
            Contribute a word
          </ButtonLink>
        </div>
      </header>

      <section className="reveal mb-12" style={{ animationDelay: "60ms" }} aria-label="Likhibi in numbers">
        <SectionHeading title="Right now" />
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {pairs === null ? (
            <Skeleton className="h-[118px] rounded-2xl" />
          ) : (
            <StatTile
              label="Sentence pairs"
              value={fmt(pairs)}
              context="Bible verses aligned with English, plus approved community pairs"
            />
          )}
          {data && words.data ? (
            <StatTile
              label="Words listed"
              value={fmt(data.lexicon.entries)}
              context={`${fmt(words.data.remaining)} still need an English meaning`}
            />
          ) : (
            <Skeleton className="h-[118px] rounded-2xl" />
          )}
          {community.data ? (
            <StatTile
              label="Community additions"
              value={fmt(community.data.approved_pairs)}
              context={`${fmt(community.data.pending)} waiting for review · ${fmt(community.data.contributors)} credited contributors`}
            />
          ) : (
            <Skeleton className="h-[118px] rounded-2xl" />
          )}
          {engines ? (
            <StatTile
              label="Translation engines"
              value={`${ready.length} of ${realEngines.length}`}
              context={ready.length ? `Ready: ${ready.map((engine) => engine.name).join(", ")}` : "None ready yet"}
            />
          ) : (
            <Skeleton className="h-[118px] rounded-2xl" />
          )}
        </div>
      </section>

      <section className="reveal mb-12" style={{ animationDelay: "120ms" }}>
        <SectionHeading title="How it works" />
        <ol className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {STEPS.map(({ icon: Icon, title, text, link }, index) => (
            <li key={title} className="card flex flex-col gap-3 p-5">
              <div className="flex items-center gap-3">
                <span className="grid size-10 place-items-center rounded-xl bg-accent-soft text-accent-text">
                  <Icon aria-hidden className="size-5" />
                </span>
                <span className="text-[13px] font-medium text-faint tabular-nums">{index + 1}</span>
              </div>
              <h3 className="text-[17px] font-semibold text-ink">{title}</h3>
              <p className="text-[14.5px] leading-relaxed text-ink-2">{text}</p>
              {link && (
                <a
                  href={href(link.to)}
                  className="mt-auto inline-flex items-center gap-1.5 pt-1 text-[14px] font-medium text-accent-text underline-offset-2 hover:underline"
                >
                  {link.label}
                  <ArrowRight aria-hidden className="size-3.5" />
                </a>
              )}
            </li>
          ))}
        </ol>
      </section>

      <section className="reveal" style={{ animationDelay: "180ms" }}>
        <SectionHeading title="Who takes part" />
        <ul className="card divide-y divide-line">
          {PEOPLE.map(({ title, text }) => (
            <li key={title} className="grid gap-1 px-5 py-4 sm:grid-cols-[200px_1fr] sm:gap-6">
              <p className="text-[16px] font-medium text-ink">{title}</p>
              <p className="text-[14.5px] leading-relaxed text-ink-2">{text}</p>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
