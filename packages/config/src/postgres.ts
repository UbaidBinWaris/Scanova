import { readFileSync } from 'node:fs';
import type { PostgresConfig } from './sections.js';

export interface PostgresParts {
  readonly host: string;
  readonly port: number;
  readonly database: string;
  readonly user: string;
  readonly password: string;
  readonly tls: { readonly caCertPath: string } | null;
}

/**
 * Assembles the connection string from its parts so a password is written in
 * exactly one place (§4).
 *
 * The string is for the Prisma CLI's schema engine, which has its own TLS
 * parameters: `sslcert` is the CA certificate and `sslaccept=strict` turns
 * verification on (it is off by default there). Runtime connections do not use
 * this string; they use {@link toPgConnectionOptions}.
 */
export function buildPostgresConfig(parts: PostgresParts): PostgresConfig {
  const url = new URL('postgresql://localhost');
  url.hostname = parts.host;
  url.port = String(parts.port);
  url.pathname = `/${encodeURIComponent(parts.database)}`;
  url.username = encodeURIComponent(parts.user);
  url.password = encodeURIComponent(parts.password);
  if (parts.tls === null) {
    url.searchParams.set('sslmode', 'disable');
  } else {
    url.searchParams.set('sslmode', 'require');
    url.searchParams.set('sslaccept', 'strict');
    url.searchParams.set('sslcert', parts.tls.caCertPath);
  }
  return { ...parts, prismaCliUrl: url.toString() };
}

/** Options object for `pg.Pool` / `pg.Client` (structural, so this package needs no `pg` dependency). */
export interface PgConnectionOptions {
  readonly host: string;
  readonly port: number;
  readonly database: string;
  readonly user: string;
  readonly password: string;
  readonly ssl: false | { readonly ca: string; readonly rejectUnauthorized: true };
}

/**
 * Discrete options for node-postgres (Prisma driver adapter, pg-boss, grants).
 * With TLS the server certificate and host name are verified against the
 * internal CA; verification is never relaxed.
 */
export function toPgConnectionOptions(config: PostgresConfig): PgConnectionOptions {
  return {
    host: config.host,
    port: config.port,
    database: config.database,
    user: config.user,
    password: config.password,
    ssl:
      config.tls === null
        ? false
        : { ca: readFileSync(config.tls.caCertPath, 'utf8'), rejectUnauthorized: true },
  };
}

const ROLE_NAME_PATTERN = /^[a-z_][a-z0-9_]{0,62}$/u;

/** Quotes a role or table name for use in DDL. Only plain lower-case identifiers are accepted. */
export function quoteIdentifier(name: string): string {
  if (!ROLE_NAME_PATTERN.test(name)) {
    throw new Error('Identifier must be a plain lower-case PostgreSQL name.');
  }
  return `"${name}"`;
}
