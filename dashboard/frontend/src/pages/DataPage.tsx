import { ExternalLink, RefreshCw } from "lucide-react";
import { useApp } from "../components/AppContext";
import {
  Button,
  cx,
  InlineAlert,
  Meter,
  PageHeader,
  SectionHeading,
  Skeleton,
  StatTile,
  StatusBadge,
  StatusIcon,
  type Tone,
} from "../components/ui";
import { api } from "../lib/api";
import { dateTime, fmt, pct } from "../lib/format";
import { href, useAsync } from "../lib/hooks";
import type { Check, DataHealth, DataSource, ParallelFile } from "../lib/types";

export function DataPage() {
  const health = useAsync(api.dataHealth);
  const sources = useAsync(api.dataSources);
  const { meta } = useApp();
  const data = health.data;

  return (
    <div>
      <PageHeader
        eyebrow="Under the hood"
        title="Language data"
        description="Where every sentence and word comes from, who may use it, and what is still missing. The numbers are measured from the files in datasets/ each time this page loads."
        actions={
          <Button
            icon={RefreshCw}
            loading={health.loading && !!data}
            onClick={() => {
              void health.reload();
              void sources.reload();
            }}
          >
            Re-measure
          </Button>
        }
      />

      {health.error && (
        <InlineAlert tone="critical" title="Could not measure the datasets">
          {health.error.message}
        </InlineAlert>
      )}

      {!data && !health.error && (
        <div className="space-y-4">
          <Skeleton className="h-32" />
          <Skeleton className="h-64" />
        </div>
      )}

      {data && (
        <div className={cx("space-y-12 transition-opacity", health.loading && "opacity-60")}>
          <Hero data={data} />
          <Provenance />
          <Readiness checks={data.checks} />
          {data.parallel.map((file) => (
            <ParallelSection key={file.file} file={file} several={data.parallel.length > 1} />
          ))}
          <LexiconSection data={data} />
          <CorpusSection data={data} reviewNeedsToken={meta?.review_requires_token ?? false} />
          {sources.data && <SourcesSection sources={sources.data} />}
          <p className="text-[13px] text-muted">Measured {dateTime(data.generated_at)}.</p>
        </div>
      )}
    </div>
  );
}

function Hero({ data }: { data: DataHealth }) {
  const rows = data.parallel.reduce((sum, file) => sum + file.rows, 0);
  const usable = data.parallel.reduce((sum, file) => sum + file.english_real, 0) + data.community.approved_pairs;
  const placeholders = data.parallel.reduce((sum, file) => sum + file.english_placeholder, 0);
  return (
    <section className="reveal card grid gap-6 overflow-hidden p-6 sm:grid-cols-[auto_1fr] sm:items-center sm:p-8">
      <p className="text-[clamp(3.5rem,8vw,5rem)] leading-none font-bold tracking-[-0.03em] text-ink">{fmt(usable)}</p>
      <div className="space-y-2">
        <p className="text-[18px] font-medium text-ink">sentence pairs ready for training or retrieval</p>
        <p className="max-w-2xl text-[15px] leading-relaxed text-ink-2">
          {placeholders > 0
            ? `${fmt(placeholders)} of ${fmt(rows)} rows in the parallel corpus have a placeholder where the English sentence should be. They hold Nagamese text only until English is aligned by verse reference.`
            : `The parallel corpus has ${fmt(rows)} rows. Approved community corrections are included in this count.`}
        </p>
      </div>
    </section>
  );
}

type Ledger = { name: string; origin: string; terms: string; use: string; tone: Tone; status: string };

/** Who made each part of the data, on what terms, and what Likhibi does with it. */
const LEDGER: Ledger[] = [
  {
    name: "Aligned Bible verses",
    origin: "ISV Nagamese New Testament, © 2019 Bridge Connectivity Solutions, from eBible.org. English from the World English Bible.",
    terms: "CC BY-SA 4.0 for the Nagamese, public domain for the English. Anything derived stays CC BY-SA 4.0.",
    use: "Translation memory, training the offline model, and the held-out test books.",
    tone: "good",
    status: "Open license",
  },
  {
    name: "Community contributions",
    origin: "Speakers, through this dashboard. A reviewer checks each one before it is used.",
    terms: "CC BY-SA 4.0 by explicit consent, credited by name if the contributor wishes. Removed on request.",
    use: "The translator uses approved ones at once. They export as training data.",
    tone: "good",
    status: "Consented",
  },
  {
    name: "Nagamese lexicon, 6,912 words",
    origin: "Words found in the New Testament text, plus hand-curated everyday words and Naga place and tribe names. Every entry records its source.",
    terms: "CC BY-SA 4.0, because it is derived from the Bible text.",
    use: "Keyboard completions. Speakers add the meanings on the Contribute page.",
    tone: "neutral",
    status: "Meanings unverified",
  },
  {
    name: "Auto-glossary",
    origin: "Word pairs extracted by machine (IBM Model 1) from the aligned verses.",
    terms: "Derived from CC BY-SA 4.0 text.",
    use: "Hints for the LLM, and guesses a speaker confirms or fixes on the Contribute page.",
    tone: "neutral",
    status: "Unverified",
  },
  {
    name: "Offline translation model",
    origin: "Meta's NLLB-200 (600M), adapted with LoRA on the aligned verses.",
    terms: "CC BY-NC 4.0, inherited from the base model: research and community use, not commercial.",
    use: "The offline translation engine.",
    tone: "neutral",
    status: "Non-commercial",
  },
];

function Provenance() {
  return (
    <section className="reveal" style={{ animationDelay: "40ms" }}>
      <SectionHeading
        title="Where the data comes from"
        description="Nothing is used without knowing who made it and on what terms. What the community adds stays a commons."
      />
      <ul className="card divide-y divide-line">
        {LEDGER.map((item) => (
          <li key={item.name} className="grid gap-3 p-4 sm:p-5 lg:grid-cols-[minmax(0,0.9fr)_minmax(0,1.6fr)_auto] lg:gap-6">
            <div>
              <p className="text-[16px] font-medium text-ink">{item.name}</p>
              <p className="mt-1 text-[13.5px] leading-relaxed text-muted">{item.origin}</p>
            </div>
            <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1.5 text-[14px] leading-relaxed">
              <dt className="text-muted">Terms</dt>
              <dd className="text-ink">{item.terms}</dd>
              <dt className="text-muted">Used for</dt>
              <dd className="text-ink-2">{item.use}</dd>
            </dl>
            <div className="lg:text-right">
              <StatusBadge tone={item.tone} label={item.status} />
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}

function Readiness({ checks }: { checks: Check[] }) {
  const passed = checks.filter((check) => check.passed).length;
  return (
    <section className="reveal" style={{ animationDelay: "60ms" }}>
      <SectionHeading
        title="Readiness targets"
        description={`${passed} of ${checks.length} targets met. The targets come from the engine strategy and are minimums for a credible demo, not ceilings.`}
      />
      <ul className="card divide-y divide-line">
        {checks.map((check) => {
          const tone: Tone = check.passed ? "good" : "warning";
          return (
            <li key={check.id} className="grid gap-3 p-4 sm:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)] sm:items-center sm:gap-6 sm:p-5">
              <div className="flex gap-3">
                <span className="mt-0.5">
                  <StatusIcon tone={tone} />
                </span>
                <div>
                  <p className="font-semibold text-ink">
                    {check.label}
                    <span className="sr-only">: {check.passed ? "met" : "not met"}</span>
                  </p>
                  <p className="mt-0.5 text-[14px] leading-relaxed text-ink-2">{check.hint}</p>
                </div>
              </div>
              <div>
                <div className="mb-1.5 flex items-baseline justify-between gap-2 text-[14px]">
                  <span className="font-medium text-ink-2">{check.passed ? "Met" : "Not met"}</span>
                  <span className="tabular-nums text-ink">
                    <strong>{fmt(check.current)}</strong>
                    <span className="text-muted"> / {fmt(check.target)}</span>
                  </span>
                </div>
                <Meter value={check.current} max={check.target} tone={tone} label={check.label} />
              </div>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

const DOMAIN_LABEL: Record<string, string> = { bible: "Bible text", mixed: "mixed domains", other: "other domain" };

function ParallelSection({ file, several }: { file: ParallelFile; several: boolean }) {
  const englishTone: Tone =
    file.english_real === file.rows && file.rows > 0 ? "good" : file.english_real === 0 ? "critical" : "warning";
  const byDomain = Object.entries(file.real_pairs_by_domain);
  const breakdown = byDomain.length > 1 ? ` By domain: ${byDomain.map(([d, n]) => `${d} ${fmt(n)}`).join(", ")}.` : "";
  return (
    <section className="reveal" style={{ animationDelay: "100ms" }}>
      <SectionHeading
        title={several ? `Parallel corpus: ${file.file.split("/").pop()}` : "Parallel corpus"}
        description={
          <>
            <span className="font-mono text-[13.5px] break-all">{file.file}</span> · {DOMAIN_LABEL[file.domain] ?? file.domain}
          </>
        }
      />
      {!file.schema_ok ? (
        <InlineAlert tone="critical" title="Unrecognised columns">
          {file.schema_error}
        </InlineAlert>
      ) : (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <StatTile label="Rows" value={fmt(file.rows)} context={`${file.avg_nagamese_tokens} Nagamese words per row on average`} />
          <StatTile
            label="English side filled"
            value={fmt(file.english_real)}
            tone={englishTone}
            context={`${pct(file.english_real, file.rows)} of rows. ${fmt(file.english_placeholder)} placeholders, ${fmt(file.english_empty)} empty.${breakdown}`}
          />
          <StatTile
            label="Impossible references"
            value={fmt(file.impossible_references)}
            tone={file.impossible_references ? "warning" : "good"}
            context="Chapter numbers that do not exist in that book, so verse-ID alignment would pair the wrong sentences."
          />
          <StatTile
            label="Duplicate verse IDs"
            value={fmt(file.duplicate_verse_ids)}
            tone={file.duplicate_verse_ids ? "warning" : "good"}
            context="References that appear on more than one row."
          />
          <StatTile
            label="Rows with extraction noise"
            value={fmt(file.suspected_noise_rows)}
            tone={file.suspected_noise_rows ? "warning" : "good"}
            context={`${pct(file.suspected_noise_rows, file.rows)} of rows carry footnote markers or page headers.`}
          />
          <StatTile
            label="Missing New Testament books"
            value={file.nt_books_missing.length ? fmt(file.nt_books_missing.length) : "None"}
            tone={file.nt_books_missing.length ? "warning" : "good"}
            context={file.nt_books_missing.length ? file.nt_books_missing.join(", ") : "All 27 books are present."}
          />
        </div>
      )}
    </section>
  );
}

function LexiconSection({ data }: { data: DataHealth }) {
  const lexicon = data.lexicon;
  if (!lexicon.exists) return null;
  return (
    <section className="reveal">
      <SectionHeading
        title="Lexicon"
        description={<span className="font-mono text-[13.5px] break-all">{lexicon.file}</span>}
      />
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <StatTile label="Entries" value={fmt(lexicon.entries)} />
        <StatTile
          label="With English glosses"
          value={fmt(lexicon.glossed)}
          tone={lexicon.glossed ? "good" : "warning"}
          context={`${pct(lexicon.glossed, lexicon.entries)} usable as a bilingual glossary.`}
        />
        <StatTile
          label="Part-of-speech tagged"
          value={fmt(lexicon.pos_tagged)}
          tone={lexicon.pos_tagged ? "good" : "warning"}
          context={`${pct(lexicon.pos_tagged, lexicon.entries)} of entries.`}
        />
        <StatTile
          label="Found in the corpus"
          value={fmt(lexicon.attested_in_corpus)}
          tone={lexicon.attested_in_corpus / Math.max(lexicon.entries, 1) >= 0.5 ? "good" : "warning"}
          context={`${pct(lexicon.attested_in_corpus, lexicon.entries)} of lemmas occur in the monolingual corpus. The rest are unattested, many of them generated forms.`}
        />
        <StatTile
          label="Auto-glossary candidates"
          value={fmt(lexicon.auto_glossary_entries)}
          context="Word pairs extracted from the aligned Bible with IBM Model 1. Unverified: used only as hints until a speaker checks them."
        />
      </div>
    </section>
  );
}

function CorpusSection({ data, reviewNeedsToken }: { data: DataHealth; reviewNeedsToken: boolean }) {
  const mono = data.monolingual;
  const everydayTests = data.test_sets.filter((item) => item.domain !== "bible");
  const bibleTests = data.test_sets.filter((item) => item.domain === "bible");
  const testRows = everydayTests.reduce((sum, item) => sum + item.rows, 0);
  const bibleRows = bibleTests.reduce((sum, item) => sum + item.rows, 0);
  return (
    <section className="reveal">
      <SectionHeading title="Monolingual text, test sets and contributions" />
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatTile
          label="Monolingual lines"
          value={fmt(mono.lines)}
          context={mono.exists ? `${fmt(mono.tokens)} words, ${fmt(mono.word_types)} distinct` : "File not found"}
        />
        <StatTile
          label="Everyday test sentences"
          value={fmt(testRows)}
          tone={testRows >= 200 ? "good" : "warning"}
          context={
            (everydayTests.length ? everydayTests.map((item) => item.file).join(", ") : "None yet: speakers must write these.") +
            (bibleRows ? ` Bible held-out sets add ${fmt(bibleRows)} rows, counted separately.` : "")
          }
        />
        <StatTile
          label="Approved community pairs"
          value={fmt(data.community.approved_pairs)}
          context="Corrections a reviewer accepted. Exported as training data."
        />
        <StatTile
          label="Waiting for review"
          value={fmt(data.community.pending)}
          context={
            <a href={href("review")} className="font-medium text-accent-text underline-offset-2 hover:underline">
              Open the review queue{reviewNeedsToken ? " (admin)" : ""}
            </a>
          }
        />
      </div>
    </section>
  );
}

function SourcesSection({ sources }: { sources: DataSource[] }) {
  return (
    <section className="reveal">
      <SectionHeading
        title="Data to import next"
        description="Verified public sources. A source counts as imported once its expected folder exists and holds files. Licenses were checked on 13 October 2026."
      />
      <ul className="grid gap-3 md:grid-cols-2">
        {sources.map((source) => (
          <li key={source.id} className="card flex min-w-0 flex-col gap-2 p-5">
            <div className="flex items-start justify-between gap-3">
              <a
                href={source.url}
                target="_blank"
                rel="noreferrer"
                className="group inline-flex items-start gap-1.5 text-[16px] leading-snug font-semibold text-ink hover:text-accent-text"
              >
                {source.name}
                <ExternalLink aria-hidden className="mt-1 size-3.5 shrink-0 text-muted group-hover:text-accent-text" />
              </a>
              <StatusBadge tone={source.imported ? "good" : "neutral"} label={source.imported ? "Imported" : "Not yet"} />
            </div>
            <p className="text-[14.5px] leading-relaxed text-ink-2">{source.description}</p>
            <dl className="mt-auto grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1 pt-2 text-[13.5px]">
              <dt className="text-muted">Size</dt>
              <dd className="text-ink">{source.size}</dd>
              <dt className="text-muted">License</dt>
              <dd className="text-ink">{source.license}</dd>
              <dt className="text-muted">Folder</dt>
              <dd>
                <code className="rounded-md bg-sunken px-2 py-0.5 font-mono text-[12.5px] break-all text-ink-2">
                  datasets/{source.expected_path}
                </code>
              </dd>
            </dl>
          </li>
        ))}
      </ul>
    </section>
  );
}
