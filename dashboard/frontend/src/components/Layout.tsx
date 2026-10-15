import type { ReactNode } from "react";
import { href, useTheme, type Route, type ThemeChoice } from "../lib/hooks";
import { useApp } from "./AppContext";
import { cx, StatusIcon } from "./ui";

type NavItem = { route: Route; label: string };

const NAV_WORK: NavItem[] = [
  { route: "home", label: "Overview" },
  { route: "translate", label: "Translate" },
  { route: "contribute", label: "Contribute" },
  { route: "review", label: "Review" },
];

const NAV_SYSTEM: NavItem[] = [
  { route: "data", label: "Language data" },
  { route: "engines", label: "Engines" },
  { route: "evaluation", label: "Evaluation" },
];

/** The mark, the name, and the shawl band the keyboard app also wears. */
function Brand() {
  return (
    <a href={href("home")} className="inline-flex flex-col gap-2.5 rounded">
      <span className="flex items-center gap-2.5">
        <img src="/brand/likhibi-mark-160.png" alt="" width={26} height={26} className="brand-mark size-[26px] rounded-[6px]" />
        <span className="text-[16px] font-semibold tracking-[-0.015em] text-ink">Likhibi</span>
      </span>
      <span aria-hidden className="shawl" />
    </a>
  );
}

function EngineStatus() {
  const { engines, enginesError } = useApp();
  let tone: "good" | "warning" | "critical" = "warning";
  let label = "Checking engines";
  if (enginesError) {
    tone = "critical";
    label = "Server unreachable";
  } else if (engines) {
    const real = engines.engines.filter((e) => e.kind !== "mock");
    const ready = real.filter((e) => e.state === "ready").length;
    tone = ready === real.length && ready > 0 ? "good" : ready > 0 ? "warning" : "critical";
    label = `${ready} of ${real.length} engines ready`;
  }
  return (
    <a href={href("engines")} className="flex items-center gap-2 text-[12.5px] text-muted hover:text-ink">
      <StatusIcon tone={tone} />
      {label}
    </a>
  );
}

function ThemeSwitch() {
  const [theme, setTheme] = useTheme();
  const options: { value: ThemeChoice; label: string }[] = [
    { value: "light", label: "Light" },
    { value: "dark", label: "Dark" },
    { value: "system", label: "Auto" },
  ];
  return (
    <div role="radiogroup" aria-label="Colour theme" className="flex gap-3 text-[12.5px]">
      {options.map(({ value, label }) => (
        <button
          key={value}
          role="radio"
          aria-checked={theme === value}
          onClick={() => setTheme(value)}
          className={cx(
            "underline-offset-4 transition-colors",
            theme === value ? "font-medium text-ink underline decoration-accent decoration-[1.5px]" : "text-muted hover:text-ink",
          )}
        >
          {label}
        </button>
      ))}
    </div>
  );
}

function SideLink({ item, active }: { item: NavItem; active: boolean }) {
  return (
    <a
      href={href(item.route)}
      aria-current={active ? "page" : undefined}
      className={cx(
        "relative flex h-8 items-center rounded-md pl-3 text-[14px] transition-colors",
        active ? "font-medium text-ink" : "text-muted hover:text-ink",
      )}
    >
      {active && <span aria-hidden className="absolute top-1.5 bottom-1.5 left-0 w-[2px] rounded-full bg-accent" />}
      {item.label}
    </a>
  );
}

export function Layout({ route, children }: { route: Route; children: ReactNode }) {
  const { meta } = useApp();
  return (
    <div className="min-h-dvh lg:grid lg:grid-cols-[212px_1fr]">
      <aside className="sticky top-0 hidden h-dvh flex-col border-r border-line lg:flex">
        <div className="px-6 pt-8 pb-9">
          <Brand />
        </div>
        <nav aria-label="Main" className="flex flex-col px-3">
          {NAV_WORK.map((item) => (
            <SideLink key={item.route} item={item} active={route === item.route} />
          ))}
          <p className="mt-7 mb-1.5 pl-3 text-[11.5px] font-medium tracking-[0.02em] text-faint">System</p>
          {NAV_SYSTEM.map((item) => (
            <SideLink key={item.route} item={item} active={route === item.route} />
          ))}
        </nav>
        <div className="mt-auto flex flex-col gap-3 px-6 pb-7">
          <EngineStatus />
          <div className="flex items-center justify-between">
            <ThemeSwitch />
            {meta && <span className="font-mono text-[11px] text-faint">v{meta.app_version}</span>}
          </div>
        </div>
      </aside>

      {/* Phones: the name, then the pages as a scrolling row of words. */}
      <div className="sticky top-0 z-20 border-b border-line bg-bg lg:hidden">
        <div className="flex items-center justify-between gap-3 px-5 pt-4 pb-3">
          <Brand />
          <ThemeSwitch />
        </div>
        <nav aria-label="Main" className="flex gap-5 overflow-x-auto px-5 pb-3 [scrollbar-width:none]">
          {[...NAV_WORK, ...NAV_SYSTEM].map(({ route: target, label }) => (
            <a
              key={target}
              href={href(target)}
              aria-current={route === target ? "page" : undefined}
              className={cx(
                "shrink-0 text-[14px] underline-offset-[6px]",
                route === target ? "font-medium text-ink underline decoration-accent decoration-2" : "text-muted",
              )}
            >
              {label}
            </a>
          ))}
        </nav>
      </div>

      <main className="mx-auto w-full max-w-[1320px] px-5 pt-9 pb-16 sm:px-8 lg:px-14 lg:pt-14">{children}</main>
    </div>
  );
}
