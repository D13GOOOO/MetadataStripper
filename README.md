# MetadataStripper

MetadataStripper is an enterprise-grade, high-performance, zero-garbage-collection (Zero-GC) anti-xray and anti-cheat plugin designed for modern high-capacity Minecraft servers running Paper and Folia.

By leveraging low-level Netty pipeline manipulation, deep memory cloning, primitive bitsets, and thread-local buffer reuse, MetadataStripper bypasses traditional high-overhead plugin loops, ensuring constant-time (O(1)) and logarithmic-time (O(log N)) performance, eliminating lag spikes caused by garbage collection during heavy chunk loading and player movement.

---

## Architecture and Design Philosophy

Traditional anti-xray plugins frequently rely on dynamic maps, frequent object allocations, and synchronous task scheduling, which degrades server performance under heavy player loads. MetadataStripper is built on a radically different architectural model:

1. **Deep Binary Injection & Unsafe Cloning:** Bypasses Minecraft 1.21.1's asynchronous chunk serialization race conditions and strict registry protections. It utilizes `sun.misc.Unsafe` to physically clone `LevelChunkSection` structures in memory without invoking native constructors (preventing `NoSuchMethodError` crashes) and injects the mutated `ClientboundLevelChunkPacketData` directly into the outbound Netty pipeline.
2. **Zero-GC Primitive Routing:** Sensitive block mappings are pre-calculated during server startup into a native primitive boolean array (`boolean[]`) and bit-vector `EnumSet`s. Netty pipeline interceptors evaluate block states via direct array indexing, achieving nanosecond-level execution times.
3. **ThreadLocal Buffer Reuse:** Chunk modification packets utilize thread-local buffer arrays (`ThreadLocal<short[]>` and `ThreadLocal<BlockState[]>`). This prevents memory churn across concurrent Netty event loop threads during mass chunk serialization.
4. **Lock-Free Spatial Engine:** Fully decoupled from legacy synchronous tasks. Entity proximities are pre-computed as primitive `int[]` arrays on Folia's regional threads. Netty I/O threads perform instantaneous O(log N) binary searches on these arrays to filter metadata safely without triggering Folia's AsyncCatcher.

---

## Core Features

- **Zero-Trust Chunk Obfuscation:** Aggressively obfuscates all configured valuable ores, containers, spawners, and **underground liquids (Y < 55)** directly at the byte level before serialization. Completely neutralizes Freecam and BlockESP by ensuring the client physically never receives the original block data.
- **Dynamic Proximity Radar:** A highly optimized, Zero-GC `PlayerMoveEvent` listener acts as a local radar. Evaluated only upon whole-block coordinate shifts, it executes an O(1) `EnumSet` lookup to dynamically reveal obfuscated blocks (pop-in) within a 5-block radius to legitimate exploring players.
- **Instant Excavation Revealer:** Seamlessly ties into the block-breaking mechanics. Upon breaking a block, the six adjacent faces are instantly updated via the native Bukkit API (`sendBlockChange`), bypassing Netty overhead and ensuring fluid mining for legitimate players.
- **Subterranean Culling & Bedrock Preservation:** Selectively fills subterranean air spaces (Y < 5) with uniform deepslate to neutralize cave-finder exploits, while mathematically identifying and preserving the natural bedrock boundaries to prevent visual glitches.
- **Anti-Seed Cracker:** Flattens deterministic world generation patterns by stripping auxiliary metadata (crop growth stages, waterlogged flags, block orientations, leaf distances) and normalizing exposed bedrock coordinates to prevent automated world seed reverse-engineering.
- **Zero-GC Entity Data Culling:** Dynamically drops sensitive entity metadata and equipment packets (defeating Armor Buster, Pop Chams, and Owner ESP) for entities outside a tactical engagement radius. Operates natively on Netty threads to prevent visual entity flickering.
- **Logout Ghost Entity Purge:** Instead of modifying disconnecting players' native NMS altitude coordinates (which corrupts persistent Folia chunk saves), it instantly broadcasts a surgical `ClientboundRemoveEntitiesPacket` to all viewers. This completely purges the ghost entity from tracking client modules, destroying ambush coordinates.

---

## Requirements

- **Server Software:** Paper, Folia, or compatible forks (API version 1.21+).
- **Java Runtime:** Java 21 or higher.

---

## Installation

1. Place the `MetadataStripper.jar` file into your server's `plugins` directory.
2. Start or restart the server to generate the default configuration file (`config.yml`).
3. Modify the configuration to match your server's target block profiles.
4. Execute `/ms reload` to apply configuration updates live without disconnecting active clients.

---

## Configuration (`config.yml`)

```yaml
# ========================================== #
#     MetadataStripper - Configuration       #
# ========================================== #

# Operational Engine Mode:
# 2 = Full Aggressive (Ores + Containers + Cave Filling + Anti-Seed Cracker)
# 1 = Lightweight (Ores + Containers + Anti-Seed Cracker, skips heavy cave filling)
engine-mode: 2

# Profiler Alert Threshold (Stash Finder Detection):
# Triggers a silent staff alert if a player intercepts more than this many NBT packets in 60 seconds.
alert-threshold: 5000

# Exact list of Bukkit materials to obfuscate and protect
sensitive-blocks:
  - DIAMOND_ORE
  - DEEPSLATE_DIAMOND_ORE
  - GOLD_ORE
  - DEEPSLATE_GOLD_ORE
  - IRON_ORE
  - DEEPSLATE_IRON_ORE
  - COPPER_ORE
  - DEEPSLATE_COPPER_ORE
  - EMERALD_ORE
  - DEEPSLATE_EMERALD_ORE
  - LAPIS_ORE
  - DEEPSLATE_LAPIS_ORE
  - COAL_ORE
  - DEEPSLATE_COAL_ORE
  - REDSTONE_ORE
  - DEEPSLATE_REDSTONE_ORE
  - NETHER_QUARTZ_ORE
  - NETHER_GOLD_ORE
  - ANCIENT_DEBRIS
  - AMETHYST_BLOCK
  - BUDDING_AMETHYST
  - CHEST
  - TRAPPED_CHEST
  - BARREL
  - ENDER_CHEST
  - SPAWNER
  - TRIAL_SPAWNER
  - VAULT
  - HEAVY_CORE
  - DECORATED_POT
```

## Commands and Permissions

### Commands
- `/ms` - Displays real-time engine telemetry, including destroyed NBT packets, blocked entity data payloads, and total culled entities.
- `/ms reload` - Triggers a live hot-reload of configuration settings and internal lookup tables without uninjecting connected clients.

### Permissions
- `metadatastripper.admin` - Grants access to administrative commands (`/ms` and `/ms reload`) and enables receipt of automated silent exploit profiler alerts.
- `metadatastripper.bypass` - Completely bypasses all Netty packet obfuscation, spatial culling, and entity visibility restrictions for trusted staff members.

---

## Technical Specifications

| Component | Execution Context | Algorithmic Complexity | Memory Allocation |
| :--- | :--- | :--- | :--- |
| **Block State Sanitization** | Async Startup / Netty Write | O(1) Array Indexing | Zero-GC (Static Array) |
| **Binary Chunk Obfuscation** | Netty EventLoop Thread | O(N) Section Scan | Low-GC (`Unsafe.allocateInstance`) |
| **Dynamic Proximity Radar**| Main/Region Spigot Thread | O(1) EnumSet Lookup | Zero-GC (Bit-Vector) |
| **Entity Metadata Culling** | Netty / Region Scheduler | O(log N) Binary Search | Zero-GC (Primitive Arrays) |
| **Stash Finder Profiler** | Netty Pipeline / 60s Task | O(1) Concurrent Map | Zero-GC (Atomic Counters) |