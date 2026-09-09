# MetadataStripper

MetadataStripper is a high-performance, enterprise-grade anti-xray and anti-cheat engine engineered specifically for high-capacity Paper and Folia servers (1.21.x). By utilizing low-level Netty pipeline interception, native memory manipulation via `Unsafe`, and primitive bitsets, it eliminates the memory overhead and performance-draining loops typical of traditional obfuscation plugins.

It delivers Near-Zero-GC performance, bypassing costly constructor allocations during core block sanitization and spatial entity culling to ensure stable server tick rates under massive player loads.

## Key Features

* **Near-Zero-GC Architecture:** Built entirely on lock-free `ConcurrentHashMap` structures, primitive arrays, and `sun.misc.Unsafe` object cloning. This prevents garbage collection spikes during massive chunk packet serialization.
* **Folia & Regional Multithreading:** Seamlessly bridges global server schedulers with Folia's Region Threads, processing heavy chunk transformations safely without blocking the main tick loop or Netty I/O threads.
* **Zero-Trust Chunk Protection:** Intercepts and rewrites outbound chunk packets. Obfuscates sensitive blocks, underground fluids, and bedrock changes.
* **Advanced Entity Culling (Anti-ESP):** Filters entity metadata and equipment packets against a strict tactical radius. Neutralizes Armor Busters, Entity Owner ESPs, and Pop Chams via O(log N) binary search lookups.
* **Block Entity NBT Filtering (Anti-Stash Finder):** Aggressively strips out-of-range NBT payloads (Chests, Spawners, Signs, Vaults) before serialization, neutralizing City ESPs and Stash Finders.
* **Block State Sanitization (Anti-Seed Cracker):** Normalizes deterministic properties like crop stages, leaf distances, snow layers, and Deepslate axis alignments to prevent seed-cracking algorithms.
* **Smart Proximity & Raytrace Radar:** Preserves legitimate gameplay by seamlessly revealing obfuscated blocks using a spherical close-range scan combined with an occlusion-culled raytrace (supports deep cave exploration and MLG water drops).
* **Disconnect Cleanup:** Broadcasts entity removal packets instantly upon player logout to neutralize "Logout Spot" and Freecam exploits.

## Compatibility

* **Server:** Paper or Folia 1.21.x
* **Java:** Java 21 or newer

## Installation

1. Place the `MetadataStripper-1.3.jar` in your server's `plugins` directory.
2. Start the server once to generate the `config.yml`.
3. Open `config.yml` and insert your `client-name` and `license-key`. ECDSA cryptographic verification is required for the engine to boot.
4. Configure `engine-mode`, `alert-threshold`, and your `sensitive-blocks`.
5. Restart the server, or run `/ms reload` to hot-reload the configuration seamlessly.

## Configuration Guide

### Engine Modes
* **`1` (Lightweight):** Sensitive block protection, block entity filtering, state sanitization, entity filtering, and anti-seed state normalization. Replaces configured ores and containers with Stone or Deepslate depending on depth.
* **`2` (Aggressive):** All features of Mode 1, plus aggressive subterranean fill behavior below a configurable Y-level. Automatically degrades to Mode 1 if the server TPS drops below the configured threshold.

### Advanced Tuning (`advanced` section)
MetadataStripper allows administrators to fine-tune performance limits to match their hardware constraints:
* `degradation-tps-threshold`: TPS threshold to dynamically disable Mode 2 and relieve CPU pressure.
* `tactical-culling-radius`: Maximum distance (in blocks) to send entity equipment and metadata.
* `aggressive-y-max`: The Y-level threshold for Mode 2's aggressive cave masking.
* `max-pending-region-writes`: Maximum queued chunks per-player before the backpressure system drops packets to prevent OOM crashes.
* `proximity-radius`: Radius for the spherical legitimate block revealer.
* `raytrace-max-distance`: Maximum distance for the occlusion-culled Line-of-Sight revealer.

### Alert Threshold
Controls the number of blocked block entity payloads counted for a player during a 60-second window. Exceeding this threshold triggers a silent staff alert (highly accurate flag for Stash Finders).

## Commands and Permissions

### Commands
* `/ms`: Displays the active engine mode, background tasks, and general plugin info.
* `/ms reload`: Hot-reloads the configuration, atomically replaces the sensitive block table, and updates proximity settings without dropping active connections.
* `/ms diagnose`: Displays engine health state (ACTIVE/DEGRADED), transformed chunks, backpressure drops, timeouts, errors, and network filtering telemetry.
* `/ms validate`: Performs a dry-run validation of the `config.yml` (Engine mode, profiler thresholds, license validity, and Bukkit material names).

### Permissions
* `metadatastripper.admin`: Grants access to all `/ms` commands and allows receiving in-game Stash Finder alerts.
* `metadatastripper.bypass`: Completely bypasses packet obfuscation, block reveals, and entity restrictions (Recommended only for trusted staff or administrators).