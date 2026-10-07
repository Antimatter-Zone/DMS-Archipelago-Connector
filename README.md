# DMS Archipelago Connector

Development candidate, **not yet a gameplay-qualified release**. A standalone
Java 25 connector for ordinary Archipelago servers. There is no AZAS, Discord
bot, AMZ Hub, account, or mandatory AMZ network dependency.

Current adapter: Game Dev Tycoon contract schema 1, reviewed game 1.7.9 /
Steam build 20332874. Farmer is explicitly disabled until its canonical
manifest and actual game hooks pass qualification; a server cannot enable it
by claiming `release_qualified=true`.
The connector validates GDT contract identity, binding and canonical hash;
the GDT mod must still validate the full topic/research manifest and reward
shape before changing game state. A hash is integrity, not qualification.

## Build and verify

Use an installed Gradle 9.5.1 and JDK 25 (no bundled unverified bootstrap):

```powershell
gradle test installDist --console=plain
gradle packageAppImage --console=plain
```

Packaging refuses to overwrite an existing app-image. For a fresh candidate
use `-PappImageDirectory=app-image-candidate-2` (a simple name under `build/`).

The app-image bundles Java; end users do not install Java/Gradle. This task
does not sign/publish packages. A protected release job must package legal
notices, checksums, source revisions, provenance and rollback references.

Launch without arguments for the native connection form. Select the managed
game instance, enter server/slot/password and connect. Passwords are not saved.
Plain WS is clearly warned; DMS AP domains and managed launches require WSS.
This connector does **not** install, isolate, verify, or launch licensed games.
Manual GDT installation/launch glue must consume the launch configuration
and prove isolation before the standalone setup flow is release-ready.

## Local protocol

`POST http://127.0.0.1:<ephemeral>/api/archipelago/v1/client/<action>`.
GDT has exact aliases under `/api/archipelago/gdt/client/`.
Requests require a launch-scoped bearer, exact Host, loopback peer, no query,
and no web Origin except `null`/`file://`. Responses are always `no-store`.
Every body includes `serverId` and `clientId` from the protected local config.

| Action | Additional fields | Behavior |
| --- | --- | --- |
| `sync`, `poll` | `nextItemIndex` | Contract, authoritative identity, full replay suffix, data package, bounded AP messages and hint policy |
| `items/ack` | `contractHash`, `throughIndex` | Monotonic acknowledgement; never deletes replay |
| `progress` | `contractHash`, `checkedLocationIds`, `clientStatus` | Durable outbox; known locations only; goal requires all adapter goal checks |
| `hint` | `itemName`, `confirmCost: true` | Sends ordinary `!hint` command; server enforces policy/cost; no free scouting bypass |
| `status` | none | Credential-free connection status |

The connection learns team/slot from AP's Connected packet and validates the
game-specific contract. Reconnect must retain seed, team, slot, contract and
the entire item history, including source location/player/flags. Unknown
items and changed replay fail closed. Game effects and applied indices must
be committed atomically by each game's save adapter; this journal cannot
prove that a game applied an item. Rollbacks request replay from their saved
index. Checks can queue while disconnected; no incoming reward is fabricated.

The per-instance `.dms-connector` directory is current-user-only. It contains
a locked, atomically replaced credential-free journal and, for GUI launches,
a local-bearer configuration removed on normal shutdown. Startup generates a
new bearer; stale configuration cannot reconnect to a new connector. Existing
credentials never go into the journal, diagnostic status, URLs or arguments.
An additional per-user lease under LocalAppData prevents two connectors from
opening the same canonical endpoint/alias in different instance folders.
After AP identifies the slot, an endpoint/seed/team/slot lease also rejects
different aliases resolving to the same slot. Different aliases cannot be
proven equivalent before AP authentication. Leases survive reconnect and
release on rejection/close; empty hash-named lock files contain no identity or
credentials and deliberately remain to avoid file-handle races.

## Launcher IPC

Launch with only `--stdio` and private stdin/stdout pipes. First stdin line is
at most 16 KiB JSON: `schemaVersion:1`, `endpoint`, `gameId`, `slotName`,
`password`, `instance`, `clientId`, `serverId`, `dmsManaged`, and optionally
`expectedTeam` + `expectedSlot`. Both expected fields must be present together.
All named text fields must be strings; `dmsManaged` is a required boolean.
Omitting password means an empty password; nulls/coercions and unknown fields
are rejected. Slot-name aliases are not treated as AMZ account identities.
Stdout first line is the game configuration containing a **local bearer**;
never inherit stdout to a terminal or put it in diagnostic logs. AP passwords
are never returned. Subsequent `STATUS` requests return safe status; `STOP` or
stdin EOF closes the connector. One instance lock prevents duplicate sockets.
Hub must eventually call this process instead of its internal bridge; this
repository does not silently change the current Hub launch path.

## Release gates

Real GDT install/isolation and gameplay, Farmer hook/contract qualification,
live WSS wrong-password/revocation, protected Hub process integration,
signed Windows installer and full standalone-offline-from-AMZ acceptance are
still required. Unit and loopback protocol tests are not those acceptance
tests. See `THIRD-PARTY-NOTICES.md` for extraction provenance.

The October 6 development pass built an unsigned app-image, but this PC's
Windows Application Control blocked its native launcher. No policy was
disabled, no alternate launcher bypass was used, and native package launch is
not an accepted gate. Actual Java child-process IPC is tested separately.
