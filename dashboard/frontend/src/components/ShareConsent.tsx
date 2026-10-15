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
    <fieldset className="@container border-t border-line pt-3.5">
      <legend className="sr-only">Sharing and credit</legend>
      <div className="grid gap-3 @sm:grid-cols-2">
        <label className="block">
          <span className="mb-1 block text-[12px] font-medium text-muted">Credit me as</span>
          <input
            value={credit}
            onChange={(event) => onCredit(event.target.value)}
            placeholder="Your name, or leave empty"
            maxLength={80}
            autoComplete="name"
            className="field h-8 px-2.5 text-[13.5px]"
          />
        </label>
        <label className="block">
          <span className="mb-1 block text-[12px] font-medium text-muted">My dialect</span>
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
            placeholder="e.g. Kohima Nagamese"
            maxLength={60}
            className="field h-8 px-2.5 text-[13.5px]"
          />
        </label>
      </div>
      <label className="mt-3.5 flex items-start gap-2.5 text-[13px] leading-snug text-ink-2">
        <input
          type="checkbox"
          checked={consent}
          onChange={(event) => onConsent(event.target.checked)}
          className="mt-[2px] size-[15px] shrink-0 accent-ink"
        />
        <span>
          Publish it under{" "}
          <a href={LICENSE_URL} target="_blank" rel="noreferrer" className="text-ink underline decoration-line-strong underline-offset-[3px] hover:decoration-ink">
            CC BY-SA 4.0
          </a>{" "}
          so anyone may use it with credit. A reviewer checks it first, and I can have it removed at any time.
        </span>
      </label>
    </fieldset>
  );
}
