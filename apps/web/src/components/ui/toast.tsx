'use client';

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { CheckIcon } from './icons';

interface ToastContextValue {
  readonly notify: (message: string) => void;
}

const ToastContext = createContext<ToastContextValue>({ notify: () => undefined });

export function useToast(): ToastContextValue {
  return useContext(ToastContext);
}

/** Short confirmations ("Saved"), announced politely to screen readers. */
export function ToastProvider({ children }: { children: ReactNode }): ReactNode {
  const [message, setMessage] = useState<{ text: string; key: number } | null>(null);
  const notify = useCallback((text: string) => {
    setMessage({ text, key: Date.now() });
  }, []);
  const value = useMemo(() => ({ notify }), [notify]);

  useEffect(() => {
    if (message === null) {
      return;
    }
    const timer = window.setTimeout(() => {
      setMessage(null);
    }, 4000);
    return () => {
      window.clearTimeout(timer);
    };
  }, [message]);

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div
        role="status"
        aria-live="polite"
        className="pointer-events-none fixed inset-x-0 bottom-4 z-50 flex justify-center px-4"
      >
        {message !== null ? (
          <div
            key={message.key}
            className="pointer-events-auto flex items-center gap-2 rounded-lg bg-foreground px-4 py-3 text-sm font-medium text-background shadow-lg"
          >
            <CheckIcon width={16} height={16} />
            {message.text}
          </div>
        ) : null}
      </div>
    </ToastContext.Provider>
  );
}
