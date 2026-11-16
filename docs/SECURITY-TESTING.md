# Security testing

What is checked automatically, where, and how to run the same checks on a
workstation. The design these checks protect is in
[ARCHITECTURE.md §5](ARCHITECTURE.md) (security model).

## In GitHub Actions

| Workflow | Job | What it catches | Fails on |
|---|---|---|---|
| `security.yml` | Secrets | API keys, private keys, tokens in **every commit** (gitleaks) | any new finding |
| | Dependencies | known vulnerabilities in npm packages (`pnpm audit`, OSV), the Python tools (OSV) and the Android release classpath (OSV) | moderate and above (npm), any (OSV) |
| | | supply-chain policy on the lockfile: versions at least a day old, no git or tarball sub-dependencies, no provenance downgrades (pnpm) | any violation |
| | Code | injection, crypto misuse, unsafe Node/React/Next.js/Kotlin patterns, secrets, OWASP Top 10, workflow injection (Semgrep) | any finding |
| | Config | workflow mistakes (actionlint); unpinned actions, persisted credentials, template injection, excessive permissions (zizmor); shell bugs (shellcheck); Dockerfile problems (hadolint); infrastructure misconfiguration (Trivy) | medium and above (zizmor), warnings (shellcheck, hadolint), high (Trivy) |
| | Images | fixable high and critical vulnerabilities in the api, worker and web images, built exactly as the server builds them (Trivy) | any fixable high/critical |
| | New dependencies | on pull requests: vulnerable or copyleft-licensed dependencies being added (GitHub dependency review) | moderate and above |
| `codeql.yml` | Analyze | data-flow analysis of TypeScript, Kotlin, Python and the workflows (CodeQL, `security-extended`) | results go to Security → Code scanning |
| `dependabot.yml` | – | weekly update PRs for npm, Gradle, actions and the base image | – |

Triggers: every push to `main`, every pull request, and weekly (Mondays), because new advisories
appear for code that did not change.

How the tools are trusted: actions are pinned to a commit SHA (with the version in a comment, which
Dependabot keeps up to date). Scanner binaries come from each project's GitHub release, pinned by
version and SHA-256 in [tools/security/install-tools.sh](../tools/security/install-tools.sh); a
mismatch stops the job. Semgrep and zizmor are pinned PyPI versions.

## Decisions behind the configuration

- **Accepted findings are marked where they are**, not hidden by path: test fixtures carry an inline
  `gitleaks:allow` or `nosemgrep` with the reason, and findings in already-published commits are in
  [.gitleaksignore](../.gitleaksignore) by fingerprint (commit, file, rule, line), which cannot
  match anything new.
- **`minimumReleaseAge` is one day**, not the seven Semgrep suggests: a poisoned npm release is
  usually pulled within hours, and a week would block security fixes. The Semgrep rule is excluded
  for that reason only.
- **`trustPolicyExclude: prisma@7.10.1`**: the Prisma CLI 7.10.x is published without provenance by
  Prisma's long-standing bot account while its runtime packages keep provenance. The tarball was
  checked against the registry signature and against 7.9.0 (identical install script and file
  list). Exact version only; the next release is checked again.
- **`minimatch` override**: typescript-eslint pins a vulnerable `minimatch` range with no fixed 10.x;
  the override moves it to 11.0.8 (the version ESLint itself uses) until typescript-eslint updates.
- **Guava constraint** (Android): ML Kit and Media3 bring Guava 33.3.1; the app forces 33.7.2.
- **Runtime images** install Debian's pending security updates at build time and drop npm and
  corepack: the services run `node` only, and npm's bundled libraries were the source of most image
  findings. The base image is pinned by tag and digest.
- **Exported Android activity**: `MainActivity` must be exported (launcher, `scanova://pair` links);
  a link only fills in the pairing form, which the user confirms, and only while signed out.

## On a workstation

```bash
tools/security/install-tools.sh            # → .security-tools/bin (git-ignored)
export PATH="$PWD/.security-tools/bin:$PATH"

gitleaks git --redact --log-opts="--all" .
pnpm install --frozen-lockfile && pnpm audit --prod
osv-scanner scan source --recursive .
tools/security/android-deps-osv.sh
actionlint && uvx zizmor==1.32.0 --min-severity medium .github/workflows
git ls-files -z '*.sh' | xargs -0 shellcheck -x -S warning
git ls-files -z '*Dockerfile*' | xargs -0 -n 1 hadolint --config .hadolint.yaml
trivy config --severity HIGH,CRITICAL .
```

Semgrep: the exact command is in `security.yml` (job "Code").

## Checks against the running server

Run by hand after changes to the edge, TLS or authentication, against the server you operate. They
only read and do not change anything:

- TLS: only 1.2 and 1.3 with forward-secret AEAD ciphers (`nmap --script ssl-enum-ciphers -p 443`).
- Headers on every host: HSTS, `nosniff`, CSP on the web surfaces, no `Server`/`X-Powered-By`.
- API: `Cache-Control: no-store` on every answer; no CORS; `/internal/*` answers 404 through the
  edge on every host; malformed and oversized bodies answer 400/413 without detail.
- Sign-in: the sixth failed attempt within a minute answers 429 with `Retry-After`; an unknown
  e-mail answers like a wrong password.
- Exposure: from outside, only SSH (keys only), 80 (redirect) and 443 answer; the data services,
  OpenBao and the internal ports are filtered.
