import { useApp } from "../components/AppContext";
import { Button, cx, FigureGrid, InlineAlert, Meter, PageHeader, SectionHeading, Skeleton, StatTile, StatusBadge, StatusIcon, type Tone } from "../components/ui";
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
        title="Language data"
        description="Where every sentence and word comes from, on what terms, and what is still missing."
        actions={
          <Button
            loading={health.loading && !!data}
            onClick={() => {
              void health.reload();
              void sources.reload();
            }}
          >
            Measure again
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
        <div className={cx("space-y-16 transition-opacity", health.loading && "opacity-60")}>
          <Summary data={data} />
          <Provenance />
          <Readiness checks={data.checks} />
          {data.parallel.map((file) => (
            <ParallelSection key={file.file} file={file} several={data.parallel.length > 1} />
          ))}
          <LexiconSection data={data} />
          <CorpusSection data={data} reviewNeedsToken={meta?.review_requires_token ?? false} />
          {sources.data && <SourcesSection sources={sources.data} />}
          <p className="font-mono text-[11.5px] text-faint">Measured {dateTime(data.generated_at)}</p>
        </div>
      )}
    </div>
  );
}

function Summary({ data }: { data: DataHealth }) {
  const rows = data.parallel.reduce((sum, file) => sum + file.rows, 0);
  const usable = data.parallel.reduce((sum, file) => sum + file.english_real, 0) + data.community.approved_pairs;
  const placeholders = data.parallel.reduce((sum, file) => sum + file.english_placeholder, 0);
  return (
    <section className="reveal grid gap-6 border-y border-line py-8 md:grid-cols-[auto_minmax(0,1fr)] md:items-end md:gap-14">
      <div>
        <p className="num text-[64px] leading-[0.9] font-semibold tracking-[-0.045em] text-ink">{fmt(usable)}</p>
        <p className="mt-3 text-[13px] font-medium text-muted">sentence pairs ready for training or retrieval</p>
      </div>
      <p className="max-w-xl text-[14.5px] leading-relaxed text-ink-2">
        {placeholders > 0
          ? `${fmt(placeholders)} of ${fmt(rows)} rows in the parallel corpus hold a placeholder where the English should be. They keep the Nagamese until English is aligned by verse reference.`
          : `The parallel corpus has ${fmt(rows)} rows, and approved community corrections are counted with them.`}
      </p>
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
    use: "Translation memory, training the models, and the held-out test books.",
    tone: "good",
    status: "Open licence",
  },
  {
    name: "Community contributions",
    origin: "Speakers, through this dashboard and the keyboard. A reviewer checks each one before it is used.",
    terms: "CC BY-SA 4.0 by explicit consent, credited by name if the contributor wishes. Removed on request.",
    use: "The translator uses approved ones at once, and the models learn from them.",
    tone: "good",
    status: "Consented",
  },
  {
    name: "Nagamese lexicon",
    origin: "Words found in the New Testament, plus hand-curated everyday words and Naga place and tribe names. Each entry records its source.",
    terms: "CC BY-SA 4.0, because it is derived from the Bible text.",
    use: "Keyboard completions. Speakers add meanings on the Contribute page.",
    tone: "neutral",
    status: "Meanings unverified",
  },
  {
    name: "Auto-glossary",
    origin: "Word pairs extracted by machine (IBM Model 1) from the aligned verses.",
    terms: "Derived from CC BY-SA 4.0 text.",
    use: "Hints for the LLM, and guesses a speaker confirms or fixes.",
    tone: "neutral",
    status: "Unverified",
  },
  {
    name: "Translation models",
    origin: "Meta's NLLB-200 (600M), adapted with LoRA on the aligned verses; the phone model is distilled from it.",
    terms: "CC BY-NC 4.0, inherited from the base model: research and community use, not commercial.",
    use: "The NMT engine here, and offline translation on the phone.",
    tone: "neutral",
    status: "Non-commercial",
  },
];

function Provenance() {
  const head = "pb-2.5 text-[12px] font-medium text-muted";
  const inline = "mb-0.5 block text-[12px] font-medium text-muted lg:hidden";
  return (
    <section className="reveal" aria-labelledby="provenance-title">
      <SectionHeading
        id="provenance-title"
        title="Where the data comes from"
        description="Nothing is used without knowing who made it and on what terms. What the community adds stays a commons."
      />
      <div className="-mt-4">
        <div className="hidden grid-cols-[minmax(0,1.15fr)_minmax(0,1fr)_minmax(0,1fr)_150px] gap-x-8 border-b border-line pt-1 lg:grid">
          <p className={head}>Source</p>
          <p className={head}>Terms</p>
          <p className={head}>Used for</p>
          <p className={head}>Status</p>
        </div>
        <ul className="divide-y divide-line">
          {LEDGER.map((item) => (
            <li key={item.name} className="grid gap-x-8 gap-y-3 py-5 lg:grid-cols-[minmax(0,1.15fr)_minmax(0,1fr)_minmax(0,1fr)_150px]">
              <div>
                <p className="text-[14.5px] font-medium text-ink">{item.name}</p>
                <p className="mt-1 text-[13px] leading-relaxed text-muted">{item.origin}</p>
              </div>
              <p className="text-[13.5px] leading-relaxed text-ink-2">
                <span className={inline}>Terms</span>
                {item.terms}
              </p>
              <p className="text-[13.5px] leading-relaxed text-ink-2">
                <span className={inline}>Used for</span>
                {item.use}
              </p>
              <div className="lg:pt-0.5">
                <StatusBadge tone={item.tone} label={item.status} />
              </div>
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}

function Readiness({ checks }: { checks: Check[] }) {
  const passed = checks.filter((check) => check.passed).length;
  return (
    <section className="reveal" aria-labelledby="readiness-title">
      <SectionHeading
        id="readiness-title"
        title="Readiness targets"
        description="Minimums for a credible system, not ceilings."
        actions={
          <span className="num font-mono text-[11.5px] text-faint">
            {passed} of {checks.length} met
          </span>
        }
      />
      <ul className="-mt-4 divide-y divide-line">
        {checks.map((check) => {
          const tone: Tone = check.passed ? "good" : "warning";
          return (
            <li key={check.id} className="grid items-center gap-x-10 gap-y-3 py-4 md:grid-cols-[minmax(0,1fr)_200px]">
              <div className="grid grid-cols-[15px_minmax(0,1fr)] items-baseline">
                <StatusIcon tone={tone} />
                <div>
                  <p className="text-[14px] font-medium text-ink">
                    {check.label}
                    <span className="sr-only">: {check.passed ? "met" : "not met"}</span>
                  </p>
                  <p className="mt-0.5 text-[13px] leading-relaxed text-muted">{check.hint}</p>
                </div>
              </div>
              <div className="pl-[15px] md:pl-0">
                <p className="num mb-2 text-right font-mono text-[12px]">
                  <span className="text-ink">{fmt(check.current)}</span>
                  <span className="text-faint"> / {fmt(check.target)}</span>
                </p>
                <Meter value={check.current} max={check.target} tone={check.passed ? "neutral" : "warning"} label={check.label} />
              </div>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

const DOMAIN_LABEL: Record<string, string> = { bible: "Bible text", mixed: "mixed domains", other: "other domain" };

function FilePath({ path }: { path: string }) {
  return <span className="font-mono text-[12px] break-all text-faint">{path}</span>;
}

function ParallelSection({ file, several }: { file: ParallelFile; several: boolean }) {
  const englishTone: Tone = file.english_real === file.rows && file.rows > 0 ? "good" : file.english_real === 0 ? "critical" : "warning";
  const byDomain = Object.entries(file.real_pairs_by_domain);
  const breakdown = byDomain.length > 1 ? ` By domain: ${byDomain.map(([d, n]) => `${d} ${fmt(n)}`).join(", ")}.` : "";
  return (
    <section className="reveal" aria-label="Parallel corpus">
      <SectionHeading
        title={several ? `Parallel corpus: ${file.file.split("/").pop()}` : "Parallel corpus"}
        description={
          <>
            <FilePath path={file.file} /> · {DOMAIN_LABEL[file.domain] ?? file.domain}
          </>
        }
      />
      {!file.schema_ok ? (
        <InlineAlert tone="critical" title="Unrecognised columns">
          {file.schema_error}
        </InlineAlert>
      ) : (
        <FigureGrid>
          <StatTile size="md" label="Rows" value={fmt(file.rows)} context={`${file.avg_nagamese_tokens} Nagamese words per row on average.`} />
          <StatTile
            size="md"
            label="English side filled"
            value={fmt(file.english_real)}
            tone={englishTone}
            context={`${pct(file.english_real, file.rows)} of rows. ${fmt(file.english_placeholder)} placeholders, ${fmt(file.english_empty)} empty.${breakdown}`}
          />
          <StatTile
            size="md"
            label="Impossible references"
            value={fmt(file.impossible_references)}
            tone={file.impossible_references ? "warning" : "good"}
            context="Chapters that do not exist in their book, so alignment by verse would pair the wrong sentences."
          />
          <StatTile
            size="md"
            label="Duplicate verse IDs"
            value={fmt(file.duplicate_verse_ids)}
            tone={file.duplicate_verse_ids ? "warning" : "good"}
            context="References that appear on more than one row."
          />
          <StatTile
            size="md"
            label="Rows with extraction noise"
            value={fmt(file.suspected_noise_rows)}
            tone={file.suspected_noise_rows ? "warning" : "good"}
            context={`${pct(file.suspected_noise_rows, file.rows)} of rows carry footnote markers or page headers.`}
          />
          <StatTile
            size="md"
            label="Missing New Testament books"
            value={file.nt_books_missing.length ? fmt(file.nt_books_missing.length) : "None"}
            tone={file.nt_books_missing.length ? "warning" : "good"}
            context={file.nt_books_missing.length ? file.nt_books_missing.join(", ") : "All 27 books are present."}
          />
        </FigureGrid>
      )}
    </section>
  );
}

function LexiconSection({ data }: { data: DataHealth }) {
  const lexicon = data.lexicon;
  if (!lexicon.exists) return null;
  return (
    <section className="reveal" aria-label="Lexicon">
      <SectionHeading title="Lexicon" description={<FilePath path={lexicon.file} />} />
      <FigureGrid>
        <StatTile size="md" label="Entries" value={fmt(lexicon.entries)} context="Words the keyboard can complete and predict." />
        <StatTile
          size="md"
          label="With English meanings"
          value={fmt(lexicon.glossed)}
          tone={lexicon.glossed ? "good" : "warning"}
          context={`${pct(lexicon.glossed, lexicon.entries)} of entries. Unverified until a speaker checks them.`}
        />
        <StatTile
          size="md"
          label="Checked by speakers"
          value={fmt(lexicon.verified)}
          tone={lexicon.verified ? "good" : "warning"}
          context={`${pct(lexicon.verified, lexicon.entries)} of entries, confirmed in the review queue.`}
        />
        <StatTile
          size="md"
          label="Part of speech tagged"
          value={fmt(lexicon.pos_tagged)}
          tone={lexicon.pos_tagged ? "good" : "warning"}
          context={`${pct(lexicon.pos_tagged, lexicon.entries)} of entries.`}
        />
        <StatTile
          size="md"
          label="Found in the corpus"
          value={fmt(lexicon.attested_in_corpus)}
          tone={lexicon.attested_in_corpus / Math.max(lexicon.entries, 1) >= 0.5 ? "good" : "warning"}
          context={`${pct(lexicon.attested_in_corpus, lexicon.entries)} occur in the monolingual corpus; many of the rest are generated forms.`}
        />
        <StatTile
          size="md"
          label="Auto-glossary candidates"
          value={fmt(lexicon.auto_glossary_entries)}
          context="Pairs extracted from the aligned Bible. Hints only, until a speaker checks them."
        />
      </FigureGrid>
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
    <section className="reveal" aria-label="Monolingual text, test sets and contributions">
      <SectionHeading title="Monolingual text, test sets and contributions" />
      <FigureGrid columns={4}>
        <StatTile
          size="md"
          label="Monolingual lines"
          value={fmt(mono.lines)}
          context={mono.exists ? `${fmt(mono.tokens)} words, ${fmt(mono.word_types)} distinct.` : "File not found."}
        />
        <StatTile
          size="md"
          label="Everyday test sentences"
          value={fmt(testRows)}
          tone={testRows >= 200 ? "good" : "warning"}
          context={
            (everydayTests.length ? everydayTests.map((item) => item.file).join(", ") : "None yet: speakers must write these.") +
            (bibleRows ? ` The Bible held-out sets add ${fmt(bibleRows)} rows, counted apart.` : "")
          }
        />
        <StatTile size="md" label="Approved community pairs" value={fmt(data.community.approved_pairs)} context="Corrections a reviewer accepted." />
        <StatTile
          size="md"
          label="Waiting for review"
          value={fmt(data.community.pending)}
          context={
            <a href={href("review")} className="text-ink-2 underline decoration-line-strong underline-offset-4 hover:text-ink hover:decoration-ink">
              Open the queue{reviewNeedsToken ? " (reviewers)" : ""}
            </a>
          }
        />
      </FigureGrid>
    </section>
  );
}

function SourcesSection({ sources }: { sources: DataSource[] }) {
  return (
    <section className="reveal" aria-labelledby="sources-title">
      <SectionHeading
        id="sources-title"
        title="Data to import next"
        description="Verified public sources. One counts as imported once its folder exists and holds files. Licences checked on 13 October 2026."
      />
      <ul className="-mt-4 grid gap-x-14 md:grid-cols-2">
        {sources.map((source) => (
          <li key={source.id} className="flex min-w-0 flex-col border-b border-line py-5">
            <div className="flex items-baseline justify-between gap-4">
              <a
                href={source.url}
                target="_blank"
                rel="noreferrer"
                className="text-[14.5px] leading-snug font-medium text-ink underline decoration-transparent underline-offset-4 hover:decoration-ink"
              >
                {source.name}
                <span aria-hidden className="ml-1 text-faint">
                  ↗
                </span>
              </a>
              <StatusBadge tone={source.imported ? "good" : "neutral"} label={source.imported ? "Imported" : "Not yet"} />
            </div>
            <p className="mt-1.5 text-[13.5px] leading-relaxed text-muted">{source.description}</p>
            <p className="mt-3 font-mono text-[11.5px] leading-relaxed text-faint">
              {source.size} · {source.license}
              <br />
              <span className="break-all">datasets/{source.expected_path}</span>
            </p>
          </li>
        ))}
      </ul>
    </section>
  );
}
