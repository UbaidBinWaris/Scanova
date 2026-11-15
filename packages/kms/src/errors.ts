export type KmsErrorCode =
  /** The vault cannot be reached, is sealed, or answered with a server error. */
  | 'UNAVAILABLE'
  /** The token's policy does not allow the operation. */
  | 'PERMISSION_DENIED'
  /** The vault refused the request: wrong context, tampered or retired ciphertext, bad input. */
  | 'REJECTED'
  /** No such key. */
  | 'NOT_FOUND'
  /** The vault answered with something this client does not understand. */
  | 'UNEXPECTED_RESPONSE';

/** The only error a `Kms` throws. Messages never contain key material, tokens or plaintext. */
export class KmsError extends Error {
  public readonly code: KmsErrorCode;

  public constructor(code: KmsErrorCode, message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = 'KmsError';
    this.code = code;
  }
}
