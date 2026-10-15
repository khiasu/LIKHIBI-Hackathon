import { useState } from "react";

const CREDIT_KEY = "likhibi-credit-name";
const VARIETY_KEY = "likhibi-variety";

/** The dialect the contributor speaks, remembered in this browser; sent with each contribution. */
export function readVariety(): string | null {
  try {
    return localStorage.getItem(VARIETY_KEY)?.trim() || null;
  } catch {
    return null;
  }
}
const LICENSE_URL = "https://creativecommons.org/licenses/by-sa/4.0/";

/** The name to credit, remembered in this browser only. */
export function useCreditName(): [string, (name: string) => void] {
  const [name, setName] = useState(() => {
    try {
      return localStorage.getItem(CREDIT_KEY) ?? "";
    } catch {
      return "";
    }
  });
  const update = (next: string) => {
    setName(next);
    try {
      if (next.trim()) localStorage.setItem(CREDIT_KEY, next.trim());
      else localStorage.removeItem(CREDIT_KEY);
    } catch {
      // Without storage the name lasts for this visit only.
    }
  };
  return [name, update];
}

/** What sharing means, in plain words, with an explicit yes and an optional credit line. */
export function ShareConsent({
  consent,
  onConsent,
  credit,
  onCredit,
}: {
  consent: boolean;
  onConsent: (value: boolean) => void;
  credit: string;
  onCredit: (value: string) => void;
}) {
  return (
    <div className="space-y-3 rounded-xl bg-sunken p-3.5">
      <label className="flex items-start gap-2.5 text-[13.5px] leading-snug text-ink-2">
        <input
          type="checkbox"
          checked={consent}
          onChange={(event) => onConsent(event.target.checked)}
          className="mt-0.5 size-4 shrink-0 accent-accent"
        />
        <span>
          Publish what I add under{" "}
          <a href={LICENSE_URL} target="_blank" rel="noreferrer" className="font-medium text-accent-text underline-offset-2 hover:underline">
            CC BY-SA 4.0
          </a>
          , so anyone may use it with credit. A reviewer checks it first, and I can ask for it to be removed at any time.
        </span>
      </label>
      <label className="flex flex-wrap items-center gap-x-3 gap-y-1.5 text-[13.5px] text-ink-2">
        <span className="shrink-0">Credit me as</span>
        <input
          value={credit}
          onChange={(event) => onCredit(event.target.value)}
          placeholder="Your name, or leave empty"
          maxLength={80}
          autoComplete="name"
          className="field h-9 min-w-0 flex-1 basis-48 px-3 text-[14px]"
        />
      </label>
      <label className="flex flex-wrap items-center gap-x-3 gap-y-1.5 text-[13.5px] text-ink-2">
        <span className="shrink-0">My dialect</span>
        <input
          defaultValue={readVariety() ?? ""}
          onChange={(event) => {
            try {
              const value = event.target.value.trim();
              if (value) localStorage.setItem(VARIETY_KEY, value);
              else localStorage.removeItem(VARIETY_KEY);
            } catch {
              // Without storage the dialect is not remembered.
            }
          }}
          placeholder="e.g. Kohima Nagamese, or leave empty"
          maxLength={60}
          className="field h-9 min-w-0 flex-1 basis-48 px-3 text-[14px]"
        />
      </label>
    </div>
  );
}
