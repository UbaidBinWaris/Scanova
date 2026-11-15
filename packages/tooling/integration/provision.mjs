// Provisions the throwaway integration stack the way infra/scripts/bootstrap.sh
// provisions the real one: two databases with four roles and connect isolation,
// the Transit keys with least-privilege AppRoles, and a Garage bucket with a key.
// Idempotent. Writes the resulting connection details to the state file.
import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import pg from 'pg';
import { stateDir, stateFile } from './state.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const composeFile = join(here, 'compose.yaml');

const POSTGRES = {
  host: '127.0.0.1',
  port: 56432,
  superuser: { user: 'postgres', password: 'scanova-test-superuser' },
  main: {
    database: 'scanova',
    app: { user: 'scanova_app', password: 'scanova-test-app-password' },
    migrator: { user: 'scanova_migrator', password: 'scanova-test-migrator-password' },
  },
  keys: {
    database: 'scanova_keys',
    app: { user: 'scanova_keys_app', password: 'scanova-test-keys-app-password' },
    migrator: { user: 'scanova_keys_migrator', password: 'scanova-test-keys-migrator-password' },
  },
};

const BAO = {
  address: 'http://127.0.0.1:56200',
  rootToken: 'scanova-test-root',
  transitMount: 'transit',
  keys: {
    userTypeAdmin: 'scanova-utk-admin',
    userTypeMember: 'scanova-utk-member',
    system: 'scanova-sys',
    jwt: 'scanova-jwt',
  },
};

const S3 = {
  endpoint: 'http://127.0.0.1:56900',
  region: 'garage',
  bucket: 'scanova-documents',
  keyName: 'scanova-test',
};

// What the API may do: use keys and read their metadata.
const API_POLICY = `
path "transit/encrypt/scanova-*"   { capabilities = ["update"] }
path "transit/decrypt/scanova-*"   { capabilities = ["update"] }
path "transit/rewrap/scanova-*"    { capabilities = ["update"] }
path "transit/datakey/+/scanova-*" { capabilities = ["update"] }
path "transit/sign/scanova-*"      { capabilities = ["update"] }
path "transit/keys/+"              { capabilities = ["read"] }
`;
// Note: key metadata is granted with a single-segment `+`, not a trailing glob.
// A glob such as "transit/keys/scanova-*" also matches ".../rotate" and
// ".../config" and, being more specific than "transit/keys/+/rotate", would
// override the worker's rotate permission with read-only.

// The worker may additionally rotate keys and raise min_decryption_version —
// and only that parameter, so it cannot make a key exportable or deletable.
const WORKER_POLICY = `${API_POLICY}
path "transit/keys/+/rotate" { capabilities = ["update"] }
path "transit/keys/+/config" {
  capabilities = ["update"]
  allowed_parameters = { "min_decryption_version" = [] }
}
`;

async function provisionPostgres(caCertPath) {
  const connection = {
    host: POSTGRES.host,
    port: POSTGRES.port,
    user: POSTGRES.superuser.user,
    password: POSTGRES.superuser.password,
    ssl: { ca: readFileSync(caCertPath, 'utf8'), rejectUnauthorized: true },
  };
  const client = new pg.Client({ ...connection, database: 'postgres' });
  await client.connect();
  try {
    for (const target of [POSTGRES.main, POSTGRES.keys]) {
      for (const role of [target.migrator, target.app]) {
        const exists = await client.query('SELECT 1 FROM pg_roles WHERE rolname = $1', [role.user]);
        const verb = exists.rowCount === 0 ? 'CREATE' : 'ALTER';
        // Identifiers and literals here are fixed test constants, not input.
        await client.query(`${verb} ROLE "${role.user}" LOGIN PASSWORD '${role.password}'`);
      }
      const database = await client.query('SELECT 1 FROM pg_database WHERE datname = $1', [
        target.database,
      ]);
      if (database.rowCount === 0) {
        await client.query(`CREATE DATABASE "${target.database}"`);
      }
      // Same privilege model as infra/postgres/provision-database.sql.
      // Connect isolation: only a database's own two roles may connect to it. The
      // migrator may create schemas (the job queue lives in its own schema); it does
      // not own the database.
      await client.query(`REVOKE ALL ON DATABASE "${target.database}" FROM PUBLIC`);
      await client.query(`GRANT CONNECT ON DATABASE "${target.database}" TO "${target.app.user}"`);
      await client.query(
        `GRANT CONNECT, CREATE, TEMPORARY ON DATABASE "${target.database}" TO "${target.migrator.user}"`,
      );
      // The migrator owns schema `public`; the runtime role may only look objects up
      // in it. Table-level rights are granted by the migrate step.
      const schema = new pg.Client({ ...connection, database: target.database });
      await schema.connect();
      try {
        await schema.query(`ALTER SCHEMA public OWNER TO "${target.migrator.user}"`);
        await schema.query('REVOKE ALL ON SCHEMA public FROM PUBLIC');
        await schema.query(`REVOKE ALL ON SCHEMA public FROM "${target.app.user}"`);
        await schema.query(`GRANT USAGE ON SCHEMA public TO "${target.app.user}"`);
      } finally {
        await schema.end();
      }
    }
  } finally {
    await client.end();
  }
}

async function bao(method, path, body) {
  const response = await fetch(`${BAO.address}/v1/${path}`, {
    method,
    headers: { 'x-vault-token': BAO.rootToken, 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  return { status: response.status, body: text.length > 0 ? JSON.parse(text) : null };
}

async function baoOk(method, path, body, alsoAccept = []) {
  const response = await bao(method, path, body);
  if (response.status >= 300 && !alsoAccept.includes(response.status)) {
    throw new Error(
      `OpenBao ${method} ${path} answered ${response.status}: ${JSON.stringify(response.body)}`,
    );
  }
  return response.body;
}

async function provisionAppRole(name, policy) {
  await baoOk('PUT', `sys/policies/acl/scanova-${name}`, { policy });
  await baoOk('POST', `auth/approle/role/scanova-${name}`, {
    token_policies: `scanova-${name}`,
    token_ttl: '20m',
    token_max_ttl: '1h',
  });
  const role = await baoOk('GET', `auth/approle/role/scanova-${name}/role-id`);
  const secret = await baoOk('POST', `auth/approle/role/scanova-${name}/secret-id`, {});
  return { roleId: role.data.role_id, secretId: secret.data.secret_id };
}

async function provisionOpenBao() {
  const mounts = await baoOk('GET', 'sys/mounts');
  if (mounts.data[`${BAO.transitMount}/`] === undefined) {
    await baoOk('POST', `sys/mounts/${BAO.transitMount}`, { type: 'transit' });
  }
  const auth = await baoOk('GET', 'sys/auth');
  if (auth.data['approle/'] === undefined) {
    await baoOk('POST', 'sys/auth/approle', { type: 'approle' });
  }
  // User-type keys are derived (context = user id). None is exportable or deletable.
  const keys = [
    [BAO.keys.userTypeAdmin, { type: 'aes256-gcm96', derived: true }],
    [BAO.keys.userTypeMember, { type: 'aes256-gcm96', derived: true }],
    [BAO.keys.system, { type: 'aes256-gcm96' }],
    [BAO.keys.jwt, { type: 'ed25519' }],
  ];
  for (const [name, options] of keys) {
    const existing = await bao('GET', `${BAO.transitMount}/keys/${name}`);
    if (existing.status === 404) {
      await baoOk('POST', `${BAO.transitMount}/keys/${name}`, options);
    }
  }
  return {
    api: await provisionAppRole('api', API_POLICY),
    worker: await provisionAppRole('worker', WORKER_POLICY),
  };
}

function garage(...args) {
  return execFileSync(
    'docker',
    ['compose', '-f', composeFile, 'exec', '-T', 'garage', '/garage', ...args],
    {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
      // The Compose file interpolates this variable for the postgres service.
      env: { ...process.env, SCANOVA_TEST_PKI_DIR: join(stateDir, 'pki') },
    },
  );
}

async function waitForGarage() {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    try {
      return garage('status');
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
  }
  throw new Error('Garage did not become ready.');
}

async function provisionGarage() {
  const status = await waitForGarage();
  const nodeId = /^([0-9a-f]{16})\s/mu.exec(status)?.[1];
  if (nodeId === undefined) {
    throw new Error(`Could not find the Garage node id in:\n${status}`);
  }
  if (/NO ROLE ASSIGNED/u.test(status)) {
    garage('layout', 'assign', '-z', 'dc1', '-c', '1G', nodeId);
    garage('layout', 'apply', '--version', '1');
  }
  const buckets = garage('bucket', 'list');
  if (!buckets.includes(S3.bucket)) {
    garage('bucket', 'create', S3.bucket);
  }
  let keyInfo;
  try {
    keyInfo = garage('key', 'info', '--show-secret', S3.keyName);
  } catch {
    garage('key', 'create', S3.keyName);
    keyInfo = garage('key', 'info', '--show-secret', S3.keyName);
  }
  const accessKeyId = /Key ID:\s*(\S+)/u.exec(keyInfo)?.[1];
  const secretAccessKey = /Secret key:\s*(\S+)/u.exec(keyInfo)?.[1];
  if (accessKeyId === undefined || secretAccessKey === undefined) {
    throw new Error('Could not read the Garage access key.');
  }
  garage('bucket', 'allow', '--read', '--write', '--owner', S3.bucket, '--key', S3.keyName);
  return { accessKeyId, secretAccessKey };
}

/** @param {{ caCertPath: string }} options */
export async function provision({ caCertPath }) {
  await provisionPostgres(caCertPath);
  const appRoles = await provisionOpenBao();
  const s3Key = await provisionGarage();
  const state = {
    pki: { caCertPath },
    postgres: POSTGRES,
    openbao: { ...BAO, ...appRoles },
    s3: { endpoint: S3.endpoint, region: S3.region, bucket: S3.bucket, ...s3Key },
  };
  mkdirSync(stateDir, { recursive: true });
  writeFileSync(stateFile, `${JSON.stringify(state, null, 2)}\n`, { mode: 0o600 });
  return state;
}
