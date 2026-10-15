import { X } from "lucide-react";
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
            role={toast.tone === "critical" ? "alert" : "status"}
            className="reveal pointer-events-auto flex max-w-sm items-start gap-3 rounded-lg bg-ink py-2.5 pr-2.5 pl-3.5 text-[13.5px] leading-snug text-bg shadow-[0_12px_32px_-12px_rgb(0_0_0/0.45)]"
          >
            <span aria-hidden className={`mt-[6px] size-[7px] shrink-0 rounded-full ${toast.tone === "good" ? "bg-good" : "bg-critical"}`} />
            <span className="flex-1">{toast.message}</span>
            <button aria-label="Dismiss" onClick={() => dismiss(toast.id)} className="-my-0.5 grid size-6 place-items-center rounded opacity-60 hover:opacity-100">
              <X aria-hidden className="size-3.5" strokeWidth={2} />
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
