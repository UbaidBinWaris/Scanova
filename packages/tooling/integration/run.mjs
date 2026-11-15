// Runs the integration tests against real PostgreSQL, OpenBao and Garage:
//
//   pnpm test:integration            start → provision → build → migrate → test → tear down
//   pnpm test:integration:up         start and provision only (leave running)
//   pnpm test:integration:down       tear down
//   pnpm test:integration -- --keep  leave the stack running afterwards
//
// The stack is always torn down at the end unless --keep or --up-only is given.
import { execFileSync, spawnSync } from 'node:child_process';
import { chmodSync, existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { provision } from './provision.mjs';
import { stateDir, workspaceRoot } from './state.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const composeFile = join(here, 'compose.yaml');
const pkiDir = join(stateDir, 'pki');
const args = new Set(process.argv.slice(2));

function run(command, commandArgs, options = {}) {
  const result = spawnSync(command, commandArgs, {
    stdio: 'inherit',
    cwd: workspaceRoot,
    ...options,
  });
  if (result.status !== 0) {
    throw new Error(`${command} ${commandArgs.join(' ')} exited with ${result.status}`);
  }
}

function compose(...composeArgs) {
  run('docker', ['compose', '-f', composeFile, ...composeArgs], {
    env: { ...process.env, SCANOVA_TEST_PKI_DIR: pkiDir },
  });
}

/** A throwaway CA and a server certificate for 127.0.0.1, so PostgreSQL runs with TLS like production. */
function createCertificates() {
  if (existsSync(join(pkiDir, 'server.crt'))) {
    return;
  }
  mkdirSync(pkiDir, { recursive: true });
  const openssl = (...opensslArgs) =>
    execFileSync('openssl', opensslArgs, { cwd: pkiDir, stdio: 'pipe' });
  openssl(
    'req',
    '-x509',
    '-newkey',
    'ec',
    '-pkeyopt',
    'ec_paramgen_curve:P-256',
    '-nodes',
    '-days',
    '30',
    '-subj',
    '/CN=Scanova Test CA',
    '-keyout',
    'ca.key',
    '-out',
    'ca.crt',
    '-addext',
    'basicConstraints=critical,CA:TRUE',
    '-addext',
    'keyUsage=critical,keyCertSign,cRLSign',
  );
  openssl(
    'req',
    '-newkey',
    'ec',
    '-pkeyopt',
    'ec_paramgen_curve:P-256',
    '-nodes',
    '-subj',
    '/CN=postgres',
    '-keyout',
    'server.key',
    '-out',
    'server.csr',
  );
  writeFileSync(
    join(pkiDir, 'server.ext'),
    'subjectAltName=IP:127.0.0.1,DNS:localhost,DNS:postgres\nextendedKeyUsage=serverAuth\n',
  );
  openssl(
    'x509',
    '-req',
    '-in',
    'server.csr',
    '-CA',
    'ca.crt',
    '-CAkey',
    'ca.key',
    '-CAcreateserial',
    '-days',
    '30',
    '-out',
    'server.crt',
    '-extfile',
    'server.ext',
  );
  // The container copies the key and fixes its ownership; it only needs to read it here.
  chmodSync(join(pkiDir, 'server.key'), 0o644);
}

function down() {
  try {
    compose('down', '--volumes', '--remove-orphans');
  } finally {
    rmSync(stateDir, { recursive: true, force: true });
  }
}

/** Environment for the real migrate entry point, built from the provisioned state. */
function migrateEnvironment(state) {
  return {
    PATH: process.env.PATH ?? '',
    HOME: process.env.HOME ?? '',
    NODE_ENV: 'test',
    SCANOVA_CA_CERT_PATH: state.pki.caCertPath,
    POSTGRES_HOST: state.postgres.host,
    POSTGRES_PORT: String(state.postgres.port),
    POSTGRES_DB: state.postgres.main.database,
    POSTGRES_APP_USER: state.postgres.main.app.user,
    POSTGRES_MIGRATOR_USER: state.postgres.main.migrator.user,
    POSTGRES_MIGRATOR_PASSWORD: state.postgres.main.migrator.password,
    KEYS_POSTGRES_HOST: state.postgres.host,
    KEYS_POSTGRES_PORT: String(state.postgres.port),
    KEYS_POSTGRES_DB: state.postgres.keys.database,
    KEYS_POSTGRES_APP_USER: state.postgres.keys.app.user,
    KEYS_POSTGRES_MIGRATOR_USER: state.postgres.keys.migrator.user,
    KEYS_POSTGRES_MIGRATOR_PASSWORD: state.postgres.keys.migrator.password,
  };
}

async function main() {
  if (args.has('--down-only')) {
    down();
    return;
  }
  let failed = false;
  try {
    createCertificates();
    compose('up', '--detach', '--wait', 'postgres', 'openbao');
    compose('up', '--detach', 'garage');
    const state = await provision({ caCertPath: join(pkiDir, 'ca.crt') });
    if (args.has('--up-only')) {
      return;
    }
    run('pnpm', ['exec', 'turbo', 'run', 'build']);
    // The same entry point the `migrate` container runs, from a directory with no `.env`.
    run(process.execPath, [join(workspaceRoot, 'apps/api/dist/migrate.js')], {
      cwd: stateDir,
      env: migrateEnvironment(state),
    });
    run('pnpm', ['exec', 'turbo', 'run', 'test:integration', '--concurrency=1']);
  } catch (error) {
    failed = true;
    console.error(error instanceof Error ? error.message : error);
  } finally {
    if (!args.has('--keep') && !args.has('--up-only')) {
      down();
    }
  }
  process.exitCode = failed ? 1 : 0;
}

await main();
