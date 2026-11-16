/** A short, human device name for the sessions list, e.g. "Firefox on Linux". */
export function describeBrowser(userAgent: string | null): string {
  if (userAgent === null || userAgent.trim() === '') {
    return 'Web browser';
  }
  const browser = pick(userAgent, [
    [/Edg(e|A|iOS)?\//u, 'Edge'],
    [/OPR\/|Opera/u, 'Opera'],
    [/SamsungBrowser\//u, 'Samsung Internet'],
    [/Firefox\/|FxiOS\//u, 'Firefox'],
    [/Chrome\/|CriOS\//u, 'Chrome'],
    [/Safari\//u, 'Safari'],
  ]);
  const os = pick(userAgent, [
    [/Android/u, 'Android'],
    [/iPhone|iPod/u, 'iPhone'],
    [/iPad/u, 'iPad'],
    [/Windows/u, 'Windows'],
    [/Mac OS X|Macintosh/u, 'macOS'],
    [/CrOS/u, 'ChromeOS'],
    [/Linux/u, 'Linux'],
  ]);
  if (browser === null) {
    return os === null ? 'Web browser' : `Browser on ${os}`;
  }
  return os === null ? browser : `${browser} on ${os}`;
}

function pick(value: string, candidates: readonly (readonly [RegExp, string])[]): string | null {
  return candidates.find(([pattern]) => pattern.test(value))?.[1] ?? null;
}
