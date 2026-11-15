/**
 * Overwrites buffers that held key material. Best effort in a garbage-collected
 * runtime: copies made by the engine or by OpenSSL are out of reach, but the
 * long-lived buffer we control does not keep the secret.
 */
export function zeroize(...buffers: readonly (Uint8Array | null | undefined)[]): void {
  for (const buffer of buffers) {
    buffer?.fill(0);
  }
}
