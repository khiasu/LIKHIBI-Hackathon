import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";

/** Load data, keep the previous result visible while reloading (no skeleton flash). */
export function useAsync<T>(load: () => Promise<T>, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [loading, setLoading] = useState(true);
  const loadRef = useRef(load);
  loadRef.current = load;
  const requestId = useRef(0);

  const reload = useCallback(async () => {
    const id = ++requestId.current;
    setLoading(true);
    try {
      const value = await loadRef.current();
      if (id === requestId.current) {
        setData(value);
        setError(null);
      }
    } catch (err) {
      if (id === requestId.current) setError(err as Error);
    } finally {
      if (id === requestId.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
  }, deps);

  return { data, error, loading, reload, setData };
}

export type Route = "home" | "translate" | "contribute" | "review" | "data" | "engines" | "evaluation";
const ROUTES: Route[] = ["home", "translate", "contribute", "review", "data", "engines", "evaluation"];
// Older links keep working.
const ALIASES: Record<string, Route> = { contributions: "review" };

function currentRoute(): Route {
  const name = window.location.hash.replace(/^#\/?/, "").split("?")[0];
  if ((ROUTES as string[]).includes(name)) return name as Route;
  return ALIASES[name] ?? "home";
}

function subscribe(onChange: () => void) {
  window.addEventListener("hashchange", onChange);
  return () => window.removeEventListener("hashchange", onChange);
}

/** Hash routing: no server rewrite rules needed, so any static host works. */
export function useRoute(): Route {
  return useSyncExternalStore(subscribe, currentRoute, () => "home");
}

export function href(route: Route): string {
  return `#/${route}`;
}

export type ThemeChoice = "system" | "light" | "dark";
const THEME_KEY = "likhibi-theme";

export function useTheme(): [ThemeChoice, (choice: ThemeChoice) => void] {
  const [choice, setChoice] = useState<ThemeChoice>(() => {
    try {
      const saved = localStorage.getItem(THEME_KEY);
      return saved === "light" || saved === "dark" ? saved : "system";
    } catch {
      return "system";
    }
  });

  const update = useCallback((next: ThemeChoice) => {
    setChoice(next);
    const root = document.documentElement;
    if (next === "system") delete root.dataset.theme;
    else root.dataset.theme = next;
    try {
      if (next === "system") localStorage.removeItem(THEME_KEY);
      else localStorage.setItem(THEME_KEY, next);
    } catch {
      // Without storage the choice simply lasts for this visit.
    }
  }, []);

  return [choice, update];
}
