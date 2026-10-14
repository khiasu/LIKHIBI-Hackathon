import {
  ChartColumn,
  ClipboardCheck,
  Database,
  HeartHandshake,
  House,
  Languages,
  Monitor,
  Moon,
  Server,
  Sun,
  type LucideIcon,
} from "lucide-react";
import type { ReactNode } from "react";
import { href, useTheme, type Route, type ThemeChoice } from "../lib/hooks";
import { useApp } from "./AppContext";
import { cx, StatusIcon } from "./ui";

type NavItem = { route: Route; label: string; icon: LucideIcon };

const NAV_MAIN: NavItem[] = [
  { route: "home", label: "Home", icon: House },
  { route: "translate", label: "Translate", icon: Languages },
  { route: "contribute", label: "Contribute", icon: HeartHandshake },
  { route: "review", label: "Review", icon: ClipboardCheck },
];

const NAV_HOOD: NavItem[] = [
  { route: "data", label: "Language data", icon: Database },
  { route: "engines", label: "Engines", icon: Server },
  { route: "evaluation", label: "Evaluation", icon: ChartColumn },
];

/** The keyboard's settings header: the launcher icon, then "Likhibi" in Naga red. */
function Brand({ compact = false }: { compact?: boolean }) {
  return (
    <a href={href("home")} className="flex min-w-0 items-center gap-3 rounded-xl">
      <img
        src="/brand/likhibi-mark-160.png"
        alt=""
        width={compact ? 36 : 44}
        height={compact ? 36 : 44}
        className={cx("shrink-0", compact ? "size-9" : "size-11")}
      />
      <span className="min-w-0 leading-tight">
        <span className={cx("block font-bold tracking-[-0.01em] text-accent-text", compact ? "text-[20px]" : "text-[22px]")}>
          Likhibi
        </span>
        {!compact && <span className="block text-[12.5px] text-muted">Nagamese keyboard &amp; translator</span>}
      </span>
    </a>
  );
}

function EngineStatusPill() {
  const { engines, enginesError } = useApp();
  let tone: "good" | "warning" | "critical" = "warning";
  let label = "Checking engines…";
  if (enginesError) {
    tone = "critical";
    label = "Backend unreachable";
  } else if (engines) {
    const real = engines.engines.filter((e) => e.kind !== "mock");
    const ready = real.filter((e) => e.state === "ready").length;
    const mockReady = engines.engines.some((e) => e.kind === "mock" && e.state === "ready");
    if (ready > 0) {
      tone = "good";
      label = `${ready} of ${real.length} engines ready`;
    } else if (mockReady) {
      label = "Mock engine only";
    } else {
      label = "No engine connected";
    }
  }
  return (
    <a href={href("engines")} className="pill h-9 px-3 text-[13.5px] font-medium text-ink">
      <StatusIcon tone={tone} />
      {label}
    </a>
  );
}

function ThemeSwitch() {
  const [theme, setTheme] = useTheme();
  const options: { value: ThemeChoice; label: string; icon: LucideIcon }[] = [
    { value: "system", label: "System theme", icon: Monitor },
    { value: "light", label: "Light theme", icon: Sun },
    { value: "dark", label: "Dark theme", icon: Moon },
  ];
  return (
    <div role="radiogroup" aria-label="Colour theme" className="inline-flex gap-0.5 rounded-xl bg-key-mod p-0.5">
      {options.map(({ value, label, icon: Icon }) => (
        <button
          key={value}
          role="radio"
          aria-checked={theme === value}
          aria-label={label}
          title={label}
          onClick={() => setTheme(value)}
          className={cx(
            "grid size-8 place-items-center rounded-[10px] transition-colors",
            theme === value ? "keycap text-ink" : "text-muted hover:text-ink",
          )}
        >
          <Icon aria-hidden className="size-4" />
        </button>
      ))}
    </div>
  );
}

function SideLink({ item, active }: { item: NavItem; active: boolean }) {
  const { route, label, icon: Icon } = item;
  return (
    <a
      href={href(route)}
      aria-current={active ? "page" : undefined}
      className={cx(
        "group flex h-10 items-center gap-3 rounded-xl px-3 text-[15px] font-medium transition-colors",
        active ? "keycap text-ink" : "text-ink-2 hover:bg-sunken hover:text-ink",
      )}
    >
      <Icon
        aria-hidden
        className={cx("size-[18px]", active ? "text-accent-text" : "text-muted group-hover:text-ink-2")}
        strokeWidth={2}
      />
      {label}
    </a>
  );
}

export function Layout({ route, children }: { route: Route; children: ReactNode }) {
  const { meta } = useApp();
  return (
    <div className="min-h-dvh lg:grid lg:grid-cols-[264px_1fr]">
      {/* Desktop sidebar */}
      <aside className="sticky top-0 hidden h-dvh flex-col border-r border-line lg:flex">
        <div className="px-5 pt-7 pb-8">
          <Brand />
        </div>
        <nav aria-label="Main" className="flex flex-col gap-1 px-3">
          {NAV_MAIN.map((item) => (
            <SideLink key={item.route} item={item} active={route === item.route} />
          ))}
          <p className="label-caps mt-6 mb-1 px-3">Under the hood</p>
          {NAV_HOOD.map((item) => (
            <SideLink key={item.route} item={item} active={route === item.route} />
          ))}
        </nav>
        <div className="mt-auto flex flex-col gap-3 px-5 pb-6">
          <EngineStatusPill />
          <div className="flex items-center justify-between">
            <ThemeSwitch />
            {meta && <span className="font-mono text-[12px] text-faint">v{meta.app_version}</span>}
          </div>
        </div>
      </aside>

      {/* Mobile: the brand, then the pages as a suggestion strip */}
      <div className="sticky top-0 z-20 border-b border-line bg-bg/95 backdrop-blur lg:hidden">
        <div className="flex items-center justify-between gap-3 px-4 pt-3 pb-2">
          <Brand compact />
          <ThemeSwitch />
        </div>
        <nav aria-label="Main" className="flex gap-1.5 overflow-x-auto px-4 pb-3 [scrollbar-width:none]">
          {[...NAV_MAIN, ...NAV_HOOD].map(({ route: target, label, icon: Icon }) => (
            <a
              key={target}
              href={href(target)}
              aria-current={route === target ? "page" : undefined}
              className={cx("pill h-9 shrink-0 px-3.5 text-[14px] font-medium", route === target && "pill-accent")}
            >
              <Icon aria-hidden className={cx("size-4", route === target ? "text-accent-text" : "text-muted")} />
              {label}
            </a>
          ))}
        </nav>
      </div>

      <main className="mx-auto w-full max-w-[1120px] px-4 py-8 sm:px-8 lg:px-12 lg:py-12">{children}</main>
    </div>
  );
}
