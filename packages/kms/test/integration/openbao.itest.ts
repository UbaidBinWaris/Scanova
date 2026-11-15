import { randomBytes } from 'node:crypto';
import { readIntegrationState } from '@scanova/tooling/integration/state';
import { afterAll, describe, expect, it } from 'vitest';
import { KmsError } from '../../src/errors.js';
import { OpenBaoKms } from '../../src/openbao-kms.js';
import { describeKmsContract } from '../../src/test-support/kms-contract.js';

const state = readIntegrationState();
const bao = state.openbao;

async function root(method: string, path: string, body?: unknown): Promise<unknown> {
  const response = await fetch(`${bao.address}/v1/${path}`, {
    method,
    headers: { 'x-vault-token': bao.rootToken, 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    throw new Error(`OpenBao ${method} ${path} answered ${response.status}`);
  }
  const text = await response.text();
  return text.length > 0 ? (JSON.parse(text) as unknown) : null;
}

function client(role: 'api' | 'worker'): OpenBaoKms {
  return new OpenBaoKms({
    address: bao.address,
    transitMount: bao.transitMount,
    caCertPath: null,
    roleId: bao[role].roleId,
    secretId: bao[role].secretId,
  });
}

const clients: OpenBaoKms[] = [];
afterAll(async () => {
  await Promise.all(clients.map((kms) => kms.close()));
});

describeKmsContract(
  'OpenBaoKms against a real OpenBao (AppRole, least-privilege policies)',
  async () => {
    // Fresh keys per test: rotation and min_decryption_version are not reversible.
    const suffix = randomBytes(6).toString('hex');
    const keys = {
      derived: `scanova-it-${suffix}-derived`,
      plain: `scanova-it-${suffix}-plain`,
      signing: `scanova-it-${suffix}-signing`,
    };
    await root('POST', `${bao.transitMount}/keys/${keys.derived}`, {
      type: 'aes256-gcm96',
      derived: true,
    });
    await root('POST', `${bao.transitMount}/keys/${keys.plain}`, { type: 'aes256-gcm96' });
    await root('POST', `${bao.transitMount}/keys/${keys.signing}`, { type: 'ed25519' });
    const api = client('api');
    const worker = client('worker');
    clients.push(api, worker);
    return { api, worker, keys };
  },
);

describe('OpenBaoKms session handling', () => {
  it('rejects wrong AppRole credentials without retrying forever', async () => {
    const kms = new OpenBaoKms({
      address: bao.address,
      transitMount: bao.transitMount,
      caCertPath: null,
      roleId: bao.api.roleId,
      secretId: 'not-the-secret',
    });
    clients.push(kms);
    await expect(kms.getKeyInfo(bao.keys.jwt)).rejects.toMatchObject({ code: 'PERMISSION_DENIED' });
    await expect(kms.health()).rejects.toBeInstanceOf(KmsError);
  });

  it('logs in again after its token is revoked', async () => {
    const kms = client('api');
    clients.push(kms);
    const before = await kms.encrypt(bao.keys.system, randomBytes(32));
    // Revoke every token the AppRole method has issued, as a vault restart or an operator would.
    await root('POST', 'sys/leases/revoke-prefix/auth/approle/login');
    const after = await kms.encrypt(bao.keys.system, randomBytes(32));
    expect(before).toMatch(/^vault:v\d+:/u);
    expect(after).toMatch(/^vault:v\d+:/u);
    await expect(kms.health()).resolves.toBeUndefined();
  });

  it('renews a short-lived token in the background and keeps working past its TTL', async () => {
    await root('POST', 'auth/approle/role/scanova-it-short', {
      token_policies: 'scanova-api',
      token_ttl: '6s',
      token_max_ttl: '60s',
    });
    const role = (await root('GET', 'auth/approle/role/scanova-it-short/role-id')) as {
      data: { role_id: string };
    };
    const secret = (await root('POST', 'auth/approle/role/scanova-it-short/secret-id', {})) as {
      data: { secret_id: string };
    };
    const kms = new OpenBaoKms({
      address: bao.address,
      transitMount: bao.transitMount,
      caCertPath: null,
      roleId: role.data.role_id,
      secretId: secret.data.secret_id,
    });
    clients.push(kms);
    await kms.getKeyInfo(bao.keys.jwt);
    await new Promise((resolve) => setTimeout(resolve, 9000));
    const info = await kms.getKeyInfo(bao.keys.jwt);
    expect(info.type).toBe('ed25519');
  });

  it('reports UNAVAILABLE when the vault cannot be reached', async () => {
    const kms = new OpenBaoKms(
      {
        address: 'http://127.0.0.1:9',
        transitMount: 'transit',
        caCertPath: null,
        roleId: 'x',
        secretId: 'y',
      },
      { requestTimeoutMs: 1000 },
    );
    clients.push(kms);
    await expect(kms.health()).rejects.toMatchObject({ code: 'UNAVAILABLE' });
    await expect(kms.encrypt(bao.keys.system, randomBytes(32))).rejects.toMatchObject({
      code: 'UNAVAILABLE',
    });
  });

  it('cannot export, delete or reconfigure a key under either policy', async () => {
    const worker = client('worker');
    clients.push(worker);
    const login = (await (
      await fetch(`${bao.address}/v1/auth/approle/login`, {
        method: 'POST',
        body: JSON.stringify({ role_id: bao.worker.roleId, secret_id: bao.worker.secretId }),
      })
    ).json()) as { auth: { client_token: string } };
    const asWorker = async (method: string, path: string, body?: unknown): Promise<number> =>
      (
        await fetch(`${bao.address}/v1/${path}`, {
          method,
          headers: { 'x-vault-token': login.auth.client_token },
          body: body === undefined ? undefined : JSON.stringify(body),
        })
      ).status;
    expect(
      await asWorker('GET', `${bao.transitMount}/export/encryption-key/${bao.keys.system}`),
    ).toBe(403);
    expect(await asWorker('DELETE', `${bao.transitMount}/keys/${bao.keys.system}`)).toBe(403);
    expect(
      await asWorker('POST', `${bao.transitMount}/keys/${bao.keys.system}/config`, {
        deletion_allowed: true,
      }),
    ).toBe(403);
    expect(
      await asWorker('POST', `${bao.transitMount}/keys/${bao.keys.system}/config`, {
        exportable: true,
      }),
    ).toBe(403);
    const info = await worker.getKeyInfo(bao.keys.system);
    expect(info.exportable).toBe(false);
    expect(info.deletionAllowed).toBe(false);
  });
});
