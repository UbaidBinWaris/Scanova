/**
 * The web app serves two surfaces from one server, told apart by the Host
 * header: the member portal (on the download site's host, under /app) and the
 * admin console (only on the admin host, under /admin).
 */
export type Surface = 'site' | 'admin';

export interface Origins {
  readonly siteOrigin: string;
  readonly adminOrigin: string | null;
}

export interface SurfacePaths {
  /** First path segment every route of this surface lives under. */
  readonly root: string;
  readonly home: string;
  readonly signIn: string;
  readonly password: string;
  /** Route handler that checks a dead session with the API and clears the cookie. */
  readonly expired: string;
}

export const PATHS: Readonly<Record<Surface, SurfacePaths>> = {
  site: {
    root: '/app',
    home: '/app',
    signIn: '/app/sign-in',
    password: '/app/password',
    expired: '/app/session/expired',
  },
  admin: {
    root: '/admin',
    home: '/admin/users',
    signIn: '/admin/sign-in',
    password: '/admin/password',
    expired: '/admin/session/expired',
  },
};

/** The admin host is recognised exactly; every other host is the member portal. */
export function surfaceForHost(host: string | null, origins: Origins): Surface {
  if (origins.adminOrigin === null || host === null) {
    return 'site';
  }
  return host.trim().toLowerCase() === new URL(origins.adminOrigin).host ? 'admin' : 'site';
}

/** The origin a browser shows for this surface; mutations must come from it. */
export function originFor(surface: Surface, origins: Origins): string {
  if (surface === 'admin') {
    if (origins.adminOrigin === null) {
      throw new Error('The admin console has no origin configured.');
    }
    return origins.adminOrigin;
  }
  return origins.siteOrigin;
}

/** Admins use the admin console only; members use the member portal only. */
export function userTypeFits(userType: 'ADMIN' | 'MEMBER', surface: Surface): boolean {
  return surface === 'admin' ? userType === 'ADMIN' : userType === 'MEMBER';
}

const SAFE_PATH = /^\/[A-Za-z0-9/_-]*$/u;

/**
 * A `next` parameter is followed after sign-in only when it is a plain path
 * inside the same surface: no scheme, no host, no `//`, no dot segments.
 */
export function safeReturnPath(value: unknown, surface: Surface): string | null {
  if (typeof value !== 'string' || value.length > 512 || !SAFE_PATH.test(value)) {
    return null;
  }
  if (value.includes('//')) {
    return null;
  }
  const { root, signIn, password, expired } = PATHS[surface];
  if (value !== root && !value.startsWith(`${root}/`)) {
    return null;
  }
  if ([signIn, password, expired].some((path) => value === path || value.startsWith(`${path}/`))) {
    return null;
  }
  return value;
}
