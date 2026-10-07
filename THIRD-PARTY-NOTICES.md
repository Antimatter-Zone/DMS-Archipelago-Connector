# Source and dependency provenance

The first-party AP handshake, ordered replay and loopback bridge design was
extracted and reworked from AMZ-Hub's `ArchipelagoLoopbackBridge.java` at
revision `fa7e3ddf58d4b3ced7d15f9a42040fc70f0b7f75`. No Hub artwork, credentials,
broker client, account implementation, game files or other assets are included.
MIT applies to this repository's first-party source only.

Runtime dependencies: Jackson databind/core 2.21.2 and Jackson annotations
2.21 (Apache License 2.0); license and notice metadata remain in their JARs.
Tests use JUnit Jupiter 5.14.3 and Platform 1.14.3 (EPL 2.0).
Builds use Gradle, obtained separately under Apache License 2.0; no wrapper
binary is vendored. Windows app-images bundle the selected OpenJDK runtime
(GPLv2 with Classpath Exception), including its legal directory. Release
publication must retain all runtime JAR notices and the Java legal directory.

The Archipelago wire protocol is implemented from the upstream 0.6.7
`docs/network protocol.md`; no upstream game implementation is copied here.
This development tree is not a signed or gameplay-qualified public release.
