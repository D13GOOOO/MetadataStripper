# MetadataStripper

MetadataStripper is a Paper/Folia 1.21.1 plugin that reduces client-side x-ray, chunk-finder, stash-finder, and entity-ESP exposure by sanitizing outbound block, block entity, and entity packets.

The plugin is designed for production servers where packet protection must coexist with normal exploration and mining. It uses a Netty outbound handler, immutable lookup snapshots, Paper/Folia schedulers, bounded proximity scans, and packet-local chunk copies.

## Compatibility

- Paper 1.21.1
- Folia 1.21.1
- Java 21 or newer
- Other forks are unsupported unless they preserve the Paper 1.21.1 NMS layout

The implementation uses internal NMS classes, reflection, and `Unsafe`. A Minecraft minor-version change can require a new build and should not be assumed compatible.

## Protection Model

### Chunk protection

Outbound chunk packets are processed in the recipient player's Paper or Folia execution context. Each section is scanned first; only sections containing configured sensitive blocks, underground fluids, or bedrock changes are copied and rewritten. Unmodified sections are reused.

The embedded block entity list is filtered as part of the chunk payload. Sensitive block entity data outside the configured interaction radius is removed before serialization. If the regional transformation fails or exceeds its bounded wait, the original packet is forwarded and the failure is logged.

### Block state sanitization

The startup cache normalizes selected deterministic block-state properties, including crop stages, waterlogged state, leaf distance, snow layers, moisture, pickle count, and selected orientations. Lookup is constant-time after initialization and does not intentionally allocate per lookup.

### Legitimate visibility

The proximity revealer restores configured blocks and underground fluids within a five-block radius and along a line-of-sight ray up to 45 blocks. A full scan is used on first entry, teleport, or world change. Normal one-block movement scans only the newly entered shell of the radius.

Block-break events also refresh the six adjacent block faces for the player who performed the break.

### Entity protection

Entity metadata and equipment packets are filtered against a 32-block tactical radius. Visibility snapshots are calculated on the player's owning region thread and read by the packet filter through a lock-free map. Before the first snapshot is available, packets pass through to avoid incomplete entity initialization.

### Disconnect cleanup

When a player quits, the plugin sends an entity removal packet to viewers in the same world and removes player-local packet, position, and visibility snapshots.

## Installation

1. Build or obtain the release jar for Paper/Folia 1.21.1.
2. Place the jar in the server `plugins` directory.
3. Start the server once to create `config.yml`.
4. Set `client-name` and `license-key` in `config.yml`.
5. Configure `engine-mode`, `alert-threshold`, and `sensitive-blocks`.
6. Restart the server, or run `/ms reload` after changing the configuration.

The license values are required by the current build. The local key validator is an installation gate, not a remote licensing service or a substitute for server-side access control.

## Configuration

The complete maintained configuration is in `src/main/resources/config.yml` and is copied to the server on first startup.

### Engine mode

- `1`: sensitive block protection, block entity filtering, state sanitization, entity filtering, and anti-seed state normalization.
- `2`: mode 1 plus the aggressive subterranean fill behavior below Y=5.

The engine automatically falls back from mode 2 to mode 1 when the first reported TPS value is below 18.5. This fallback affects the active packet transformation only; it does not rewrite the configured mode.

### Sensitive blocks

`sensitive-blocks` is a list of Bukkit `Material` names. The list is used both by chunk obfuscation and by the legitimate-visibility revealer. Invalid material names are ignored during parsing. The default file includes ores, containers, trial chamber materials, mineshaft materials, stronghold materials, amethyst, sculk, and dripstone blocks.

### Alert threshold

`alert-threshold` controls the number of blocked block entity payloads counted for a player during each 60-second reporting interval. Matching players generate an alert for online staff with `metadatastripper.admin`.

## Commands and Permissions

### Commands

- `/ms`: displays packet counters and the active regional packet pipeline status.
- `/ms reload`: reloads the configuration, atomically replaces the sensitive block table, and updates the proximity revealer without reinjecting player channels.

### Permissions

- `metadatastripper.admin`: access to `/ms` and staff alerts.
- `metadatastripper.bypass`: bypasses packet obfuscation, block reveals, and entity restrictions for trusted staff.

## Performance Characteristics

| Component | Execution context | Cost profile |
| --- | --- | --- |
| Block-state cache | Startup and packet handling | O(1) array lookup after O(N) startup build |
| Chunk transformation | Player region scheduler | O(N) scan of non-empty sections; copies only modified sections |
| Block entity filtering | Regional snapshot plus outbound packet path | O(1) type/distance checks |
| Proximity revealer | Player region thread | Full O(11^3) scan only when required; shell scan for one-block movement |
| Entity culling | Global dispatch plus player region | O(E log E) per snapshot because visible IDs are sorted |
| Violation profiler | Global scheduler | O(P) online-player scan every 60 seconds |

The plugin is not literally zero-GC. It deliberately creates bounded arrays, packet copies, scheduler tasks, and Bukkit block update objects where required by the Paper/Folia APIs. The implementation minimizes unnecessary allocations but does not claim to eliminate garbage collection.

## Operational Notes

- The plugin protects outbound data; it is not a complete server anti-cheat.
- Packet-level obfuscation can interact with other packet manipulation plugins. Test ordering and bypass permissions on the target server.
- The Netty handler uses a bounded two-second wait when a chunk or block update must be transformed on the player scheduler. Repeated timeout logs indicate a scheduler or server health problem.
- Reloading changes future packet processing. Clients may need a chunk refresh, movement, or reconnect to receive every newly configured visual rule.
- Test Paper and Folia separately before deployment. Their scheduler models differ even though the plugin supports both.

## Build and Verification

From the project root:

```text
gradlew.bat clean build
gradlew.bat javadoc
```

The release target is the jar produced under `build/libs`. Before distribution, verify startup, reload, login/logout, teleport, chunk loading, block breaking, entity visibility, and server shutdown on the exact Paper/Folia build that will be supported.