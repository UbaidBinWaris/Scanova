import 'reflect-metadata';
import { describe, expect, it } from 'vitest';
import { AuthController } from './auth/auth.controller.js';
import { DocumentsController } from './documents/documents.controller.js';
import { HealthController } from './health/health.controller.js';
import { AdminController } from './internal/admin/admin.controller.js';
import { WebAuthController } from './internal/web/web-auth.controller.js';
import { MeController } from './me/me.controller.js';
import { OPERATIONS, buildOpenApiDocument } from './openapi.js';
import { ServerInfoController } from './server-info/server-info.controller.js';

// NestJS `RequestMethod` values, as stored in route metadata.
const METHODS: Readonly<Record<number, string>> = {
  0: 'get',
  1: 'post',
  2: 'put',
  3: 'delete',
  4: 'patch',
};
const CONTROLLERS = [
  AuthController,
  MeController,
  DocumentsController,
  HealthController,
  ServerInfoController,
  WebAuthController,
  AdminController,
];

/** Every route the controllers really register, as `method /path/{param}`. */
function registeredRoutes(): string[] {
  const routes: string[] = [];
  for (const controller of CONTROLLERS) {
    const prefix = Reflect.getMetadata('path', controller) as string;
    const prototype = controller.prototype as unknown as Record<string, unknown>;
    for (const name of Object.getOwnPropertyNames(prototype)) {
      const handler = prototype[name];
      if (name === 'constructor' || typeof handler !== 'function') {
        continue;
      }
      const method = Reflect.getMetadata('method', handler) as number | undefined;
      if (method === undefined) {
        continue;
      }
      const path = Reflect.getMetadata('path', handler) as string;
      const full = `/${prefix}/${path}`.replace(/\/+/gu, '/').replace(/\/$/u, '');
      routes.push(`${METHODS[method]} ${full.replace(/:(\w+)/gu, '{$1}')}`);
    }
  }
  return routes.sort();
}

describe('OpenAPI document', () => {
  it('describes exactly the routes the controllers register', () => {
    expect(OPERATIONS.map((operation) => `${operation.method} ${operation.path}`).sort()).toEqual(
      registeredRoutes(),
    );
    expect(OPERATIONS).toHaveLength(31);
  });

  it('keeps every client path within what the edge proxy forwards', () => {
    for (const operation of OPERATIONS.filter((candidate) =>
      candidate.path.startsWith('/api/v1'),
    )) {
      // The edge only proxies /api/v1(/[A-Za-z0-9_-]+)*; ids are UUIDs.
      expect(operation.path.replace(/\{\w+\}/gu, '0b0e7a52-5c1d-4c53-9f0a-3d2e1f4a5b6c')).toMatch(
        /^\/api\/v1(\/[A-Za-z0-9_-]+)*$/u,
      );
    }
  });

  it('is a complete OpenAPI 3.1 document with the envelope and the error codes', () => {
    const document = buildOpenApiDocument() as {
      openapi: string;
      paths: Record<
        string,
        Record<
          string,
          {
            security: unknown[];
            responses: Record<
              string,
              { content: Record<string, { schema: Record<string, unknown> }> }
            >;
            requestBody?: unknown;
            parameters?: { name: string; in: string }[];
          }
        >
      >;
    };
    expect(document.openapi).toBe('3.1.0');
    expect(JSON.parse(JSON.stringify(document))).toEqual(document);

    const login = document.paths['/api/v1/auth/login']!.post!;
    expect(login.security).toEqual([]);
    expect(Object.keys(login.responses).sort()).toEqual([
      '200',
      '400',
      '401',
      '403',
      '429',
      '500',
      '503',
    ]);
    const ok = login.responses['200']!.content['application/json']!.schema as {
      required: string[];
      properties: { data: { properties: Record<string, unknown> } };
    };
    expect(ok.required).toEqual(['data', 'error', 'meta']);
    expect(Object.keys(ok.properties.data.properties)).toEqual([
      'accessToken',
      'accessTokenExpiresAt',
      'refreshToken',
      'refreshTokenExpiresAt',
      'user',
    ]);

    const upload = document.paths['/api/v1/documents/{id}/file']!.put!;
    expect(upload.requestBody).toEqual({
      required: true,
      content: { 'application/pdf': { schema: { type: 'string', format: 'binary' } } },
    });
    expect(upload.parameters!.map((parameter) => `${parameter.in}:${parameter.name}`)).toEqual([
      'path:id',
      'header:X-Content-SHA256',
    ]);
    expect(Object.keys(upload.responses)).toEqual(expect.arrayContaining(['413', '415', '422']));
    expect(
      Object.keys(document.paths['/api/v1/documents/{id}/file']!.get!.responses['200']!.content),
    ).toEqual(['application/pdf']);

    const list = document.paths['/api/v1/documents']!.get!;
    expect(list.parameters!.map((parameter) => parameter.name).sort()).toEqual([
      'cursor',
      'limit',
      'q',
      'updatedSince',
    ]);
    expect(document.paths['/internal/admin/v1/users']!.get!.security).toEqual([
      { accessToken: [], serviceToken: [] },
    ]);
    expect(document.paths['/internal/web/v1/auth/login']!.post!.security).toEqual([
      { serviceToken: [] },
    ]);
  });
});
