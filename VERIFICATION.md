# Development verification — 2026-10-06

Status: **source candidate; not a gameplay-qualified release**.

## Passed locally

- Java 25 / Gradle 9.5.1 compilation, `test`, and `installDist`.
- **32 JUnit tests, 0 failures, 0 errors, 0 skips.**
- Real loopback RFC6455 handshake using Java's production WebSocket client;
  authoritative team/slot learning; item delivery, check reporting, ordinary
  server-priced hint request, reconnect, connector-process restart and
  server-check rollback reconciliation.
- Wrong password fails closed with a credential-free local reason.
- Exact Host, Origin, bearer, client identity, request and replay boundaries.
- Strict stdin schema and real child-process stdin/stdout handoff/shutdown.
- Alias leases across instance directories; different aliases are permitted
  until authoritative identity proves a same-slot collision, then rejected.
  Leases release on close and constructor failure; default port, hostname case
  and terminal DNS root-dot normalization retain the WSS/lease boundaries.
- Credential-free, atomically replaced replay/outbox journal. Acknowledgments
  retain history; disconnected checks survive restart. State write failure
  cannot return successful progress. Stale socket callbacks are fenced as
  soon as disconnection is observed.
- Fresh Windows `jpackage` app-image build with bundled Java and legal files.
  First-party license/provenance notices are also embedded in the app JAR.

## Not accepted / blocked

- Windows Application Control blocked the generated **unsigned native
  launcher**. No policy was altered and no alternate-launcher bypass was
  attempted. App-image construction is not native-launch acceptance.
- The loopback AP fixture is a real WebSocket protocol test, **not a real game
  client, real AP server, live WSS, GDT gameplay, or credential revocation
  acceptance test**. Its reduced GDT contract exercises connector validation;
  the installed mod remains responsible for full manifest/contract validation.
- Manual game installation, isolated saves, game launch/config injection,
  current Hub child-process integration, signed installer, live DMS flows and
  the no-AMZ clean-Windows-profile acceptance run remain to be implemented or
  qualified. Existing AMZ Hub source was not changed by this extraction.
- Farmer remains explicitly disabled. No qualified manifest/hash/version is
  available; server-provided qualification flags cannot enable it.
- No GitHub publication, release upload, production deployment or licensed
  game-file redistribution was performed during this component's development.

## Source audit

The source inventory contains Java, Gradle Kotlin configuration, Markdown,
MIT license text and `.gitignore` only. Build outputs, native app-images,
dependency JARs, local state, credentials and game files are not source files.
The sole password literal is an explicitly synthetic test fixture. Runtime
secret material is supplied in memory through GUI fields or private pipes.
