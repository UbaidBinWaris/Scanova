/** Injection tokens for the infrastructure objects created in `PlatformModule`. */
export const API_CONFIG = Symbol('API_CONFIG');
export const DATABASE = Symbol('DATABASE');
export const KMS = Symbol('KMS');
export const KEYRING = Symbol('KEYRING');
export const OBJECT_STORE = Symbol('OBJECT_STORE');
export const ENCRYPTED_STORE = Symbol('ENCRYPTED_STORE');
export const JOBS = Symbol('JOBS');
export const CLOCK = Symbol('CLOCK');

/** Source of the current time, injectable so tests can move it. */
export type Clock = () => Date;
