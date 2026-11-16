# Security policy

Scanova handles private documents, so security reports are very welcome.

## Reporting a vulnerability

Please report privately through GitHub: **Security → Report a vulnerability** on
this repository. Do not open a public issue for a vulnerability.

Helpful to include: what is affected (server, web app, Android app, deployment
scripts), the version or commit, steps to reproduce, and what an attacker could
gain. You will get an answer within a week.

Please do not test against servers you do not operate. Every Scanova server
belongs to the person running it; set up your own (see the README) to test.

## Supported versions

Only the latest release of the server and of the Android app receives fixes.

## How the project checks itself

Automated security checks (secrets, dependencies, code, configuration, container
images, CodeQL) run on every change; see
[docs/SECURITY-TESTING.md](docs/SECURITY-TESTING.md).
