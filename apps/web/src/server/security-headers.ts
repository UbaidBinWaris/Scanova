/**
 * Headers on every response of the web app. The edge adds HSTS; the
 * Content-Security-Policy is per response (nonce) and set by the proxy.
 */
export const SECURITY_HEADERS: Readonly<Record<string, string>> = {
  'x-content-type-options': 'nosniff',
  'x-frame-options': 'DENY',
  'referrer-policy': 'no-referrer',
  'cross-origin-opener-policy': 'same-origin',
  'cross-origin-resource-policy': 'same-origin',
  'permissions-policy':
    'camera=(), microphone=(), geolocation=(), payment=(), usb=(), browsing-topics=()',
  // Private pages: never indexed, on either host.
  'x-robots-tag': 'noindex, nofollow',
};
