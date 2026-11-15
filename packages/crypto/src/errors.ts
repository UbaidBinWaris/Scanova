export type CryptoErrorCode =
  /** Tag verification failed: wrong key, wrong associated data, or tampered bytes. */
  | 'AUTHENTICATION_FAILED'
  /** Bytes are not in the expected layout (version, length, magic). */
  | 'INVALID_FORMAT'
  /** A key or parameter has the wrong size or an illegal value. */
  | 'INVALID_INPUT';

/**
 * The only error this package throws. Messages never contain key material or
 * plaintext. `AUTHENTICATION_FAILED` deliberately does not say which input was wrong.
 */
export class CryptoError extends Error {
  public readonly code: CryptoErrorCode;

  public constructor(code: CryptoErrorCode, message: string) {
    super(message);
    this.name = 'CryptoError';
    this.code = code;
  }
}
