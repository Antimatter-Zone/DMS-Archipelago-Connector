# DMS Archipelago Connector working rules

This repository owns the standalone Java connector, local loopback protocol, and packaged connector candidate. The game mods, AMZ Hub launcher, and hosted Archipelago server are separate repositories; do not change their contracts by assuming a connector-only edit updates them.

- Use [README.md](./README.md) for the protocol and current qualification status, [VERIFICATION.md](./VERIFICATION.md) for safe local checks, and [THIRD-PARTY-NOTICES.md](./THIRD-PARTY-NOTICES.md) when changing bundled material.
- Keep the connector usable with ordinary Archipelago servers, without an AMZ login or network dependency. The reviewed GDT adapter and still-disabled Farmer adapter are different release inputs: a matching contract hash is not gameplay qualification.
- Treat launch-scoped bearers, slot passwords, local configuration, and replay journals as sensitive. Never put them in logs, URLs, shell arguments, tracked files, or diagnostics. Preserve loopback, Host, Origin, and local-file permissions; game effects and applied-item state belong to the game adapter, not this connector.
- Keep generated app-images, dependencies, game installations, credentials, and release artifacts out of Git. Run the isolated Gradle tests and source checks relevant to a change; packaging or loopback tests alone do not approve a player release.
- Publishing requires the separate protected signing, provenance, licensed-game isolation, live WSS, and gameplay acceptance gates in the README. A normal push does not publish or deploy.
