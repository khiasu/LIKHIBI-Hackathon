import { LoaderCircle, type LucideIcon } from "lucide-react";
import { useState, type ButtonHTMLAttributes, type ReactNode } from "react";

export function cx(...classes: (string | false | null | undefined)[]): string {
  return classes.filter(Boolean).join(" ");
}

// Buttons -------------------------------------------------------------------------
//
// Words, not pictures: a labelled button carries no icon. An icon is drawn only when it is
// the whole button (swap, close), where it needs an aria-label instead.

type ButtonVariant = "primary" | "secondary" | "ghost" | "danger";

const BUTTON_VARIANTS: Record<ButtonVariant, string> = {
  primary: "keycap-accent disabled:opacity-35",
  secondary: "keycap disabled:opacity-40",
  ghost: "border border-transparent text-ink-2 hover:bg-sunken hover:text-ink disabled:opacity-40",
  danger: "keycap text-critical disabled:opacity-40",
};

const BUTTON_SIZES = {
  sm: "h-7 rounded-md px-2.5 text-[12.5px]",
  md: "h-[34px] rounded-md px-3.5 text-[13.5px]",
};

export function Button({
  variant = "secondary",
  size = "md",
  icon: Icon,
  loading = false,
  children,
  className,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant;
  size?: "sm" | "md";
  icon?: LucideIcon;
  loading?: boolean;
}) {
  const iconOnly = !children && Icon;
  return (
    <button
      type="button"
      {...props}
      disabled={props.disabled || loading}
      className={cx(
        "inline-flex shrink-0 items-center justify-center gap-2 font-medium whitespace-nowrap select-none",
        "disabled:cursor-not-allowed",
        BUTTON_SIZES[size],
        iconOnly && (size === "sm" ? "w-7 px-0" : "w-[34px] px-0"),
        BUTTON_VARIANTS[variant],
        className,
      )}
    >
      {loading && <LoaderCircle aria-hidden className="size-3.5 animate-spin" strokeWidth={1.75} />}
      {iconOnly && !loading && <Icon aria-hidden className="size-4" strokeWidth={1.75} />}
      {children}
    </button>
  );
}

/** A link styled as a button, for navigation that reads as an action. */
export function ButtonLink({
  href,
  variant = "secondary",
  children,
}: {
  href: string;
  variant?: "primary" | "secondary";
  icon?: LucideIcon;
  children: ReactNode;
}) {
  return (
    <a
      href={href}
      className={cx(
        "inline-flex shrink-0 items-center justify-center font-medium whitespace-nowrap",
        BUTTON_SIZES.md,
        BUTTON_VARIANTS[variant],
      )}
    >
      {children}
    </a>
  );
}

export function CopyButton({ text, label = "Copy" }: { text: string; label?: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <Button
      size="sm"
      variant="ghost"
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(text);
          setCopied(true);
          window.setTimeout(() => setCopied(false), 1600);
        } catch {
          setCopied(false);
        }
      }}
    >
      {copied ? "Copied" : label}
    </Button>
  );
}

// Status ------------------------------------------------------------------------------

export type Tone = "good" | "warning" | "critical" | "neutral";

const TONE_DOT: Record<Tone, string> = {
  good: "bg-good",
  warning: "bg-warning",
  critical: "bg-critical",
  neutral: "bg-faint",
};

const TONE_RULE: Record<Tone, string> = {
  good: "border-good",
  warning: "border-warning",
  critical: "border-critical",
  neutral: "border-line-strong",
};

/** State is a small dot in the state colour, always next to a word. */
export function StatusIcon({ tone }: { tone: Tone }) {
  return <span aria-hidden className={cx("inline-block size-[7px] shrink-0 rounded-full", TONE_DOT[tone])} />;
}

export function StatusBadge({ tone, label }: { tone: Tone; label: string; icon?: LucideIcon }) {
  return (
    <span className="inline-flex shrink-0 items-center gap-1.5 text-[12.5px] font-medium whitespace-nowrap text-ink-2">
      <StatusIcon tone={tone} />
      {label}
    </span>
  );
}

/** A small tag for metadata: ids, licences, kinds. */
export function Chip({ children, mono = false }: { children: ReactNode; mono?: boolean }) {
  return (
    <span
      className={cx(
        "inline-flex h-[22px] items-center gap-1 rounded border border-line px-1.5 text-[12px] text-muted",
        mono ? "font-mono text-[11.5px]" : "font-medium",
      )}
    >
      {children}
    </span>
  );
}

const METER_FILL: Record<Tone, string> = {
  good: "bg-good",
  warning: "bg-warning",
  critical: "bg-critical",
  neutral: "bg-ink",
};

/** A ratio against a target, as a hairline bar. */
export function Meter({ value, max, tone, label }: { value: number; max: number; tone: Tone; label: string }) {
  const ratio = max > 0 ? Math.min(value / max, 1) : 0;
  return (
    <div
      role="meter"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={max}
      aria-valuenow={value}
      className="h-[3px] w-full overflow-hidden rounded-full bg-line"
    >
      <div
        className={cx("h-full rounded-full transition-[width] duration-700", METER_FILL[tone])}
        style={{ width: `${ratio * 100}%`, minWidth: value > 0 ? 3 : 0 }}
      />
    </div>
  );
}

// Layout pieces ---------------------------------------------------------------------

export function PageHeader({
  title,
  description,
  actions,
}: {
  eyebrow?: string;
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <header className="reveal mb-9 flex flex-col gap-5 sm:flex-row sm:items-end sm:justify-between">
      <div className="max-w-2xl">
        <h1 className="text-[28px] leading-[1.15] font-semibold tracking-[-0.025em] text-ink">{title}</h1>
        {description && <p className="mt-2 text-[15px] leading-relaxed text-muted">{description}</p>}
      </div>
      {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
    </header>
  );
}

/** A section: title and an optional line of context over a hairline rule. */
export function SectionHeading({
  id,
  title,
  description,
  actions,
}: {
  id?: string;
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="mb-4 flex flex-col gap-2 border-b border-line pb-3 sm:flex-row sm:items-end sm:justify-between">
      <div>
        <h2 id={id} className="text-[15px] font-semibold tracking-[-0.01em] text-ink">
          {title}
        </h2>
        {description && <p className="mt-0.5 max-w-2xl text-[13.5px] leading-relaxed text-muted">{description}</p>}
      </div>
      {actions && <div className="flex shrink-0 gap-2">{actions}</div>}
    </div>
  );
}

/** A figure: label, large tabular number, a line of context. Set side by side, divided by rules. */
export function StatTile({
  label,
  value,
  context,
  tone,
  size = "lg",
}: {
  label: string;
  value: string;
  context?: ReactNode;
  tone?: Tone;
  size?: "lg" | "md";
}) {
  return (
    <div className="flex flex-col gap-1">
      <span className="flex items-center gap-2 text-[12.5px] font-medium text-muted">
        {tone && tone !== "neutral" && <StatusIcon tone={tone} />}
        {label}
      </span>
      <span
        className={cx(
          "num leading-none font-semibold text-ink",
          size === "lg" ? "text-[34px] tracking-[-0.03em]" : "mt-0.5 text-[24px] tracking-[-0.025em]",
        )}
      >
        {value}
      </span>
      {context && <span className="mt-1 text-[12.5px] leading-snug break-words text-muted">{context}</span>}
    </div>
  );
}

/** Figures in a ruled table: hairlines between cells, none around the outside edge. */
export function FigureGrid({ children, columns = 3 }: { children: ReactNode; columns?: 3 | 4 }) {
  return (
    <div
      className={cx(
        "grid gap-px overflow-hidden rounded-[10px] border border-line bg-line",
        columns === 3 ? "sm:grid-cols-2 lg:grid-cols-3" : "sm:grid-cols-2 lg:grid-cols-4",
        "[&>*]:bg-surface [&>*]:px-5 [&>*]:py-[18px]",
      )}
    >
      {children}
    </div>
  );
}

export function EmptyState({
  title,
  children,
  actions,
}: {
  icon?: LucideIcon;
  title: string;
  children?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="rounded-[10px] border border-dashed border-line-strong px-6 py-8 sm:px-8">
      <h3 className="text-[15px] leading-snug font-semibold text-ink">{title}</h3>
      {children && <div className="mt-1.5 max-w-xl space-y-2 text-[14px] leading-relaxed text-muted">{children}</div>}
      {actions && <div className="mt-4 flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
}

/** A note in the flow: a coloured rule on the left, a title, then detail. */
export function InlineAlert({ tone, title, children }: { tone: Tone; title: string; children?: ReactNode }) {
  return (
    <div
      role={tone === "critical" ? "alert" : "status"}
      className={cx("border-l-2 bg-surface-2 py-2.5 pr-3 pl-3.5", TONE_RULE[tone])}
    >
      <p className="text-[13.5px] font-medium text-ink">{title}</p>
      {children && <div className="mt-0.5 text-[13.5px] leading-relaxed text-muted">{children}</div>}
    </div>
  );
}

export function CodeBlock({ code, label }: { code: string; label?: string }) {
  return (
    <div className="overflow-hidden rounded-md border border-line bg-sunken">
      <div className="flex items-center justify-between border-b border-line px-3 py-1">
        <span className="font-mono text-[11.5px] text-muted">{label ?? "Code"}</span>
        <CopyButton text={code} />
      </div>
      <pre className="overflow-x-auto px-4 py-3 font-mono text-[12.5px] leading-relaxed text-ink">
        <code>{code}</code>
      </pre>
    </div>
  );
}

export function Skeleton({ className }: { className?: string }) {
  return <div aria-hidden className={cx("shimmer h-4", className)} />;
}
