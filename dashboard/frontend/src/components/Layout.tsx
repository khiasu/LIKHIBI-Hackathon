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

function Brand() {
  return (
    <a href={href("home")} className="flex min-w-0 items-center gap-2.5 rounded-lg">
      <img src="/brand/likhibi-mark-160.png" alt="" width={30} height={30} className="size-[30px] shrink-0 rounded-md" />
      <span className="min-w-0 leading-tight">
        <span className="block text-[15px] font-semibold tracking-[-0.01em] text-ink">Likhibi</span>
        <span className="block text-[12px] text-muted">Nagamese</span>
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
    <a href={href("engines")} className="flex h-8 items-center gap-2 rounded-lg px-2 text-[13px] text-ink-2 hover:bg-sunken hover:text-ink">
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
    <div role="radiogroup" aria-label="Colour theme" className="inline-flex gap-0.5 rounded-lg border border-line bg-sunken p-0.5">
      {options.map(({ value, label, icon: Icon }) => (
        <button
          key={value}
          role="radio"
          aria-checked={theme === value}
          aria-label={label}
          title={label}
          onClick={() => setTheme(value)}
          className={cx(
            "grid size-7 place-items-center rounded-md transition-colors",
            theme === value ? "bg-surface text-ink shadow-[var(--shadow)]" : "text-muted hover:text-ink",
          )}
        >
          <Icon aria-hidden className="size-3.5" />
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
        "group flex h-9 items-center gap-2.5 rounded-lg px-2.5 text-[14px] font-medium transition-colors",
        active ? "bg-sunken text-ink" : "text-ink-2 hover:bg-sunken/70 hover:text-ink",
      )}
    >
      <Icon
        aria-hidden
        className={cx("size-4", active ? "text-ink" : "text-muted group-hover:text-ink-2")}
        strokeWidth={2}
      />
      {label}
    </a>
  );
}

export function Layout({ route, children }: { route: Route; children: ReactNode }) {
  const { meta } = useApp();
  return (
    <div className="min-h-dvh lg:grid lg:grid-cols-[232px_1fr]">
      {/* Desktop sidebar */}
      <aside className="sticky top-0 hidden h-dvh flex-col border-r border-line bg-surface lg:flex">
        <div className="flex h-16 items-center px-4">
          <Brand />
        </div>
        <nav aria-label="Main" className="flex flex-col gap-0.5 px-2.5 pt-2">
          {NAV_MAIN.map((item) => (
            <SideLink key={item.route} item={item} active={route === item.route} />
          ))}
          <p className="mt-5 mb-1 px-2.5 text-[12px] font-medium text-faint">Data and models</p>
          {NAV_HOOD.map((item) => (
            <SideLink key={item.route} item={item} active={route === item.route} />
          ))}
        </nav>
        <div className="mt-auto flex flex-col gap-2 border-t border-line px-2.5 py-3">
          <EngineStatusPill />
          <div className="flex items-center justify-between px-2">
            <ThemeSwitch />
            {meta && <span className="font-mono text-[11.5px] text-faint">v{meta.app_version}</span>}
          </div>
        </div>
      </aside>

      {/* Mobile: the brand, then the pages as a suggestion strip */}
      <div className="sticky top-0 z-20 border-b border-line bg-surface lg:hidden">
        <div className="flex items-center justify-between gap-3 px-4 pt-3 pb-2">
          <Brand />
          <ThemeSwitch />
        </div>
        <nav aria-label="Main" className="flex gap-1.5 overflow-x-auto px-4 pb-3 [scrollbar-width:none]">
          {[...NAV_MAIN, ...NAV_HOOD].map(({ route: target, label, icon: Icon }) => (
            <a
              key={target}
              href={href(target)}
              aria-current={route === target ? "page" : undefined}
              className={cx(
                "inline-flex h-8 shrink-0 items-center gap-1.5 rounded-lg px-3 text-[13.5px] font-medium",
                route === target ? "bg-sunken text-ink" : "text-ink-2",
              )}
            >
              <Icon aria-hidden className={cx("size-4", route === target ? "text-ink" : "text-muted")} />
              {label}
            </a>
          ))}
        </nav>
      </div>

      <main className="mx-auto w-full max-w-[1600px] px-4 pt-7 pb-8 sm:px-6 lg:px-8 lg:pt-11 lg:pb-10">{children}</main>
    </div>
  );
}
