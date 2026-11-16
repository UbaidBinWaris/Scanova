/** Container health check (Compose). The edge never routes /healthz from outside. */
export function GET(): Response {
  return Response.json({ status: 'ok' }, { headers: { 'cache-control': 'no-store' } });
}
