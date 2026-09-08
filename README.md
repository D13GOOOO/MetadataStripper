# MetadataStripper

MetadataStripper is an enterprise-grade, high-performance, zero-garbage-collection (Zero-GC) anti-xray and anti-cheat plugin designed for modern high-capacity Minecraft servers running Paper and Folia.

By leveraging low-level Netty pipeline manipulation, primitive bitsets, and thread-local buffer reuse, MetadataStripper bypasses traditional high-overhead plugin loops, ensuring constant-time (O(1)) performance and eliminating lag spikes caused by garbage collection during heavy chunk loading and player movement.

---

## Architecture and Design Philosophy

Traditional anti-xray plugins frequently rely on dynamic maps, frequent object allocations, and synchronous task scheduling, which degrades server performance under heavy player loads. MetadataStripper is built on a radically different architectural model:

1. **Zero-GC Primitive Routing:** Sensitive block mappings are pre-calculated during server startup into a native primitive boolean array (`boolean[]`). Netty pipeline interceptors evaluate block states via direct array indexing, achieving nanosecond-level execution times with zero dynamic object allocations.
2. **ThreadLocal Buffer Reuse:** Chunk modification packets utilize thread-local buffer arrays (`ThreadLocal<short[]>` and `ThreadLocal<BlockState[]>>`). This prevents memory churn across concurrent Netty event loop threads during mass chunk serialization.
3. **Folia and Paper Native Concurrency:** Fully decoupled from legacy synchronous tasks. Spatial culling and entity visibility updates are dynamically dispatched to regional threads using the Global Region Scheduler and Entity Scheduler APIs, preventing cross-thread state corruption and concurrency crashes.
4. **Fire and Forget Network Pipeline:** Obfuscation logic executes entirely as an outbound packet filter within the Netty duplex handler, requiring no persistent entity tracking maps or asynchronous database lookups during runtime operations.

---

## Core Features

- **Advanced Chunk Obfuscation:** Selectively obfuscates valuable ores, containers, and spawners on the surface layer (Y >= 5) while aggressively filling subterranean air spaces and fluid bodies (Y < 5) with uniform background blocks to neutralize Freecam and cave-finder exploits.
- **Anti-Seed Cracker:** Flattens deterministic world generation patterns by stripping auxiliary metadata (crop growth stages, waterlogged flags, block orientations, leaf distances) and normalizing exposed bedrock coordinates to prevent automated world seed reverse-engineering.
- **Entity Culling Radar:** Dynamically culls invisible or distant entities (Item Frames, Armor Stands, Storage Carts, and mobs) using native spatial hashing and line-of-sight raycasting. Features dynamic TPS auto-tuning that automatically scales down tracking radiuses during server performance degradation.
- **Silent Stash Finder Profiler:** Monitors outbound NBT data packets for containers and signs outside legitimate interaction ranges. Tracks anomalous request frequencies via thread-safe atomic profilers and dispatches silent administrative alerts when thresholds are breached.
- **Graceful Netty Ejection:** Implements surgical pipeline attachment and detachment protocols. Ensures zero memory leaks, orphan handlers, or duplicate packet interception layers during live hot-reloads or runtime plugin re-activations (e.g., via PlugMan).
- **Logout Spot Spoofing:** Instantly mutates disconnecting players' NBT position coordinates to the world ceiling altitude prior to entity removal broadcasting, rendering ambush and tracking modules ineffective.

---

## Requirements

- **Server Software:** Paper, Folia, or compatible forks (API version 1.20+).
- **Java Runtime:** Java 21 or higher.

---

## Installation

1. Place the `MetadataStripper.jar` file into your server's `plugins` directory.
2. Start or restart the server to generate the default configuration file (`config.yml`).
3. Modify the configuration to match your server's specific world rules, ignored namespaces, and target block/entity profiles.
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

# Worlds where anti-xray and packet obfuscation are completely disabled
ignored-worlds:
  - "world_the_end"
  - "spawn_world"

# Exact list of entities to hide from the client until they are in direct line of sight
sensitive-entities:
  - MINECART_CHEST
  - MINECART_HOPPER
  - ITEM_FRAME
  - GLOW_ITEM_FRAME
  - ARMOR_STAND
  - DROPPED_ITEM
  - EXPERIENCE_ORB
  - CREEPER
  - SKELETON
  - SPIDER
  - ZOMBIE
  - ENDERMAN
  - SLIME
  - WITCH
  - VILLAGER
  - ZOMBIE_VILLAGER
  - IRON_GOLEM
  - COW
  - SHEEP
  - PIG
  - HORSE

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
| **Chunk Obfuscation** | Netty EventLoop Thread | O(N) Section Scan | Zero-GC (ThreadLocal Buffers) |
| **Entity Visibility Radar** | Folia Region Scheduler | O(V) Spatial Hashing | Constant Object Pool |
| **Stash Finder Profiler** | Netty Pipeline / 60s Task | O(1) Concurrent Map | Zero-GC (Atomic Counters) |