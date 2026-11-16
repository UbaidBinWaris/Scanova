/** What every server action returns to the browser: no exceptions cross the boundary. */
export type ActionResult<T = null> =
  | { readonly ok: true; readonly data: T }
  | {
      readonly ok: false;
      readonly code: string;
      readonly message: string;
      /** Per-field messages for the form that called the action. */
      readonly fieldErrors?: Readonly<Record<string, string>>;
    };
