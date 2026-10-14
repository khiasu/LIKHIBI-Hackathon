import { CircleCheck, CircleX, X } from "lucide-react";
import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from "react";
import { api } from "../lib/api";
import { useAsync } from "../lib/hooks";
import type { EnginesResponse, Meta } from "../lib/types";

interface AppState {
  meta: Meta | null;
  engines: EnginesResponse | null;
  enginesError: Error | null;
  refreshEngines: () => Promise<void>;
}

const AppContext = createContext<AppState | null>(null);

export function AppProvider({ children }: { children: ReactNode }) {
  const meta = useAsync(api.meta);
  const engines = useAsync(api.engines);
  const value = useMemo<AppState>(
    () => ({
      meta: meta.data,
      engines: engines.data,
      enginesError: engines.error,
      refreshEngines: engines.reload,
    }),
    [meta.data, engines.data, engines.error, engines.reload],
  );
  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}

export function useApp(): AppState {
  const state = useContext(AppContext);
  if (!state) throw new Error("useApp must be used inside AppProvider");
  return state;
}

// Toasts ------------------------------------------------------------------------

interface Toast {
  id: number;
  message: string;
  tone: "good" | "critical";
}

const ToastContext = createContext<(message: string, tone?: Toast["tone"]) => void>(() => {});

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextId = useRef(1);

  const dismiss = useCallback((id: number) => setToasts((all) => all.filter((t) => t.id !== id)), []);
  const show = useCallback(
    (message: string, tone: Toast["tone"] = "good") => {
      const id = nextId.current++;
      setToasts((all) => [...all.slice(-2), { id, message, tone }]);
      window.setTimeout(() => dismiss(id), 4000);
    },
    [dismiss],
  );

  return (
    <ToastContext.Provider value={show}>
      {children}
      <div aria-live="polite" className="pointer-events-none fixed inset-x-4 bottom-4 z-50 flex flex-col items-end gap-2 sm:inset-x-auto sm:right-6">
        {toasts.map((toast) => (
          <div
            key={toast.id}
            className="reveal pointer-events-auto flex max-w-sm items-start gap-3 rounded-xl border border-line bg-surface px-4 py-3 text-[14.5px] text-ink shadow-[0_10px_30px_-12px_rgb(0_0_0/0.35)]"
          >
            {toast.tone === "good" ? (
              <CircleCheck aria-hidden className="mt-0.5 size-4 shrink-0 text-good" strokeWidth={2.4} />
            ) : (
              <CircleX aria-hidden className="mt-0.5 size-4 shrink-0 text-critical" strokeWidth={2.4} />
            )}
            <span className="flex-1">{toast.message}</span>
            <button aria-label="Dismiss" onClick={() => dismiss(toast.id)} className="text-muted hover:text-ink">
              <X aria-hidden className="size-4" />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast() {
  return useContext(ToastContext);
}
