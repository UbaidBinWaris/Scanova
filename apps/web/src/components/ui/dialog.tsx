'use client';

import { useEffect, useId, useRef, type ReactNode } from 'react';

export interface DialogProps {
  readonly open: boolean;
  readonly onClose: () => void;
  readonly title: string;
  readonly description?: ReactNode;
  readonly children?: ReactNode;
  /** Buttons, right-aligned; the primary action last. */
  readonly footer?: ReactNode;
}

/**
 * A modal on the native <dialog>: showModal() makes the rest of the page inert,
 * traps focus inside, restores it on close and closes on Escape.
 */
export function Dialog({
  open,
  onClose,
  title,
  description,
  children,
  footer,
}: DialogProps): ReactNode {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const descriptionId = useId();

  useEffect(() => {
    const dialog = ref.current;
    if (dialog === null) {
      return;
    }
    if (open && !dialog.open) {
      dialog.showModal();
    } else if (!open && dialog.open) {
      dialog.close();
    }
  }, [open]);

  return (
    <dialog
      ref={ref}
      className="dialog"
      aria-labelledby={titleId}
      aria-describedby={description === undefined ? undefined : descriptionId}
      onClose={onClose}
    >
      {open ? (
        <div className="flex max-h-[85dvh] flex-col gap-4 overflow-y-auto p-6">
          <div className="flex flex-col gap-1">
            <h2 id={titleId} className="text-xl font-semibold">
              {title}
            </h2>
            {description !== undefined ? (
              <div id={descriptionId} className="text-sm text-muted-foreground">
                {description}
              </div>
            ) : null}
          </div>
          {children}
          {footer !== undefined ? (
            <div className="flex flex-col-reverse gap-2 pt-2 sm:flex-row sm:justify-end">
              {footer}
            </div>
          ) : null}
        </div>
      ) : null}
    </dialog>
  );
}
