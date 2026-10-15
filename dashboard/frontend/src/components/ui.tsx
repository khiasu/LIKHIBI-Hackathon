import {
  Check,
  CircleAlert,
  CircleCheck,
  CircleX,
  Copy,
  LoaderCircle,
  TriangleAlert,
  type LucideIcon,
} from "lucide-react";
import { useState, type ButtonHTMLAttributes, type ReactNode } from "react";

export function cx(...classes: (string | false | null | undefined)[]): string {
  return classes.filter(Boolean).join(" ");
}

// Buttons ------------------------------------------------------------------------

type ButtonVariant = "primary" | "secondary" | "ghost" | "danger";

const BUTTON_VARIANTS: Record<ButtonVariant, string> = {
  primary: "keycap-accent disabled:opacity-45",
  secondary: "keycap disabled:opacity-45",
  ghost: "border border-transparent text-ink-2 hover:bg-sunken hover:text-ink active:bg-key-pressed disabled:opacity-40",
  danger: "keycap text-critical disabled:opacity-45",
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
  return (
    <button
      type="button"
      {...props}
      disabled={props.disabled || loading}
      className={cx(
        "inline-flex shrink-0 items-center justify-center gap-2 font-medium whitespace-nowrap select-none",
        "disabled:cursor-not-allowed",
        size === "sm" ? "h-8 rounded-lg px-2.5 text-[13px]" : "h-9 rounded-lg px-3.5 text-[14px]",
        BUTTON_VARIANTS[variant],
        className,
      )}
    >
      {loading ? (
        <LoaderCircle aria-hidden className="size-4 animate-spin" />
      ) : (
        Icon && <Icon aria-hidden className="size-4" strokeWidth={2.2} />
      )}
      {children}
    </button>
  );
}

/** A link styled as a button, for navigation that reads as an action. */
export function ButtonLink({
  href,
  variant = "secondary",
  icon: Icon,
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
        "inline-flex h-9 shrink-0 items-center justify-center gap-2 rounded-lg px-3.5 text-[14px] font-medium whitespace-nowrap",
        BUTTON_VARIANTS[variant],
      )}
    >
      {Icon && <Icon aria-hidden className="size-4" strokeWidth={2.2} />}
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
      icon={copied ? Check : Copy}
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

// Status ----------------------------------------------------------------------

export type Tone = "good" | "warning" | "critical" | "neutral";

const TONE_ICON: Record<Tone, LucideIcon> = {
  good: CircleCheck,
  warning: TriangleAlert,
  critical: CircleX,
  neutral: CircleAlert,
};

const TONE_ICON_CLASS: Record<Tone, string> = {
  good: "text-good",
  warning: "text-warning",
  critical: "text-critical",
  neutral: "text-muted",
};

/** Status is never colour alone: an icon in the status colour plus a text label in ink. */
export function StatusBadge({ tone, label, icon }: { tone: Tone; label: string; icon?: LucideIcon }) {
  const Icon = icon ?? TONE_ICON[tone];
  return (
    <span className="inline-flex h-6 shrink-0 items-center gap-1.5 rounded-md bg-sunken px-2 text-[12.5px] font-medium whitespace-nowrap text-ink-2">
      <Icon aria-hidden className={cx("size-3.5", TONE_ICON_CLASS[tone])} strokeWidth={2.4} />
      {label}
    </span>
  );
}

/** A small neutral tag. */
export function Chip({ children, mono = false }: { children: ReactNode; mono?: boolean }) {
  return (
    <span
      className={cx(
        "inline-flex h-6 items-center gap-1 rounded-md border border-line px-2 text-[12.5px] font-medium text-ink-2",
        mono && "font-mono font-normal",
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
  neutral: "bg-ink-2",
};

const METER_TRACK: Record<Tone, string> = {
  good: "bg-good-track",
  warning: "bg-warning-track",
  critical: "bg-critical-track",
  neutral: "bg-key-mod",
};

/** A ratio against a target. The track is a light step of the fill's hue, so state reads across the whole bar. */
export function Meter({ value, max, tone, label }: { value: number; max: number; tone: Tone; label: string }) {
  const ratio = max > 0 ? Math.min(value / max, 1) : 0;
  return (
    <div
      role="meter"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={max}
      aria-valuenow={value}
      className={cx("h-1.5 w-full overflow-hidden rounded-full", METER_TRACK[tone])}
    >
      <div
        className={cx("h-full rounded-full transition-[width] duration-700", METER_FILL[tone])}
        style={{ width: `${ratio * 100}%`, minWidth: value > 0 ? 4 : 0 }}
      />
    </div>
  );
}

// Layout pieces ---------------------------------------------------------------

export function PageHeader({
  eyebrow,
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
    <header className="reveal mb-7 flex flex-col gap-4 border-b border-line pb-6 sm:flex-row sm:items-end sm:justify-between">
      <div className="max-w-2xl">
        {eyebrow && <p className="mb-1 text-[13px] font-medium text-muted">{eyebrow}</p>}
        <h1 className="text-[24px] leading-tight font-semibold tracking-[-0.015em] text-ink">{title}</h1>
        {description && <p className="mt-1.5 text-[14.5px] leading-relaxed text-muted">{description}</p>}
      </div>
      {actions && <div className="flex shrink-0 flex-wrap gap-2">{actions}</div>}
    </header>
  );
}

/** A section title, an optional line of context, then its content. */
export function SectionHeading({
  title,
  description,
  actions,
}: {
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="mb-3 flex flex-col gap-2 px-1 sm:flex-row sm:items-end sm:justify-between">
      <div>
        <h2 className="text-[15px] font-semibold text-ink">{title}</h2>
        {description && <p className="mt-0.5 max-w-2xl text-[13.5px] leading-relaxed text-muted">{description}</p>}
      </div>
      {actions && <div className="flex shrink-0 gap-2">{actions}</div>}
    </div>
  );
}

export function StatTile({
  label,
  value,
  context,
  tone,
}: {
  label: string;
  value: string;
  context?: ReactNode;
  tone?: Tone;
}) {
  return (
    <div className="card flex flex-col gap-1 p-4">
      <div className="flex items-center justify-between gap-2">
        <span className="text-[13px] font-medium text-muted">{label}</span>
        {tone && tone !== "neutral" && <StatusIcon tone={tone} />}
      </div>
      <span className="mt-1 text-[26px] leading-tight font-semibold tracking-[-0.02em] text-ink tabular-nums">{value}</span>
      {context && <span className="text-[12.5px] leading-snug break-words text-muted">{context}</span>}
    </div>
  );
}

export function StatusIcon({ tone }: { tone: Tone }) {
  const Icon = TONE_ICON[tone];
  return <Icon aria-hidden className={cx("size-4 shrink-0", TONE_ICON_CLASS[tone])} strokeWidth={2.4} />;
}

export function EmptyState({
  icon: Icon,
  title,
  children,
  actions,
}: {
  icon: LucideIcon;
  title: string;
  children?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="card flex flex-col items-start gap-3 p-6 sm:p-8">
      <span className="grid size-10 place-items-center rounded-lg border border-line bg-sunken text-ink-2">
        <Icon aria-hidden className="size-5" />
      </span>
      <h3 className="text-[16px] leading-snug font-semibold text-ink">{title}</h3>
      {children && <div className="max-w-xl space-y-2 text-[14.5px] leading-relaxed text-muted">{children}</div>}
      {actions && <div className="mt-1 flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
}

export function InlineAlert({ tone, title, children }: { tone: Tone; title: string; children?: ReactNode }) {
  return (
    <div
      role={tone === "critical" ? "alert" : "status"}
      className="flex gap-3 rounded-lg border border-line bg-surface-2 p-3.5"
    >
      <span className="mt-0.5">
        <StatusIcon tone={tone} />
      </span>
      <div className="min-w-0 text-[14px] leading-relaxed">
        <p className="font-medium text-ink">{title}</p>
        {children && <div className="mt-0.5 text-ink-2">{children}</div>}
      </div>
    </div>
  );
}

export function CodeBlock({ code, label }: { code: string; label?: string }) {
  return (
    <div className="overflow-hidden rounded-lg border border-line bg-sunken">
      <div className="flex items-center justify-between border-b border-line px-3 py-1.5">
        <span className="text-[12px] font-medium text-muted">{label ?? "Code"}</span>
        <CopyButton text={code} />
      </div>
      <pre className="overflow-x-auto px-4 py-3 font-mono text-[13px] leading-relaxed text-ink">
        <code>{code}</code>
      </pre>
    </div>
  );
}

export function Skeleton({ className }: { className?: string }) {
  return <div aria-hidden className={cx("shimmer h-4", className)} />;
}
