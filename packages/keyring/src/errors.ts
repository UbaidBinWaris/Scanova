export type KeyringErrorCode =
  /** No key exists for that user, document or purpose. */
  | 'KEY_NOT_FOUND'
  /** The key was destroyed (crypto-shredding or a finished rotation). Its data is unrecoverable. */
  | 'KEY_DESTROYED'
  /** A key already exists where a new one was to be created. */
  | 'KEY_ALREADY_EXISTS'
  /** The key database or a row in it is in a state the operation does not allow. */
  | 'INVALID_STATE'
  /** No such rotation run. */
  | 'RUN_NOT_FOUND';

/** Errors of the key hierarchy. Messages carry ids and states, never key material. */
export class KeyringError extends Error {
  public readonly code: KeyringErrorCode;

  public constructor(code: KeyringErrorCode, message: string) {
    super(message);
    this.name = 'KeyringError';
    this.code = code;
  }
}
