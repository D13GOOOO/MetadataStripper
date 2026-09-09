# MetadataStripper

MetadataStripper 1.3 is a Paper/Folia 1.21.x plugin that reduces client-side x-ray, chunk-finder, stash-finder, and entity-ESP exposure by sanitizing outbound block, block entity, and entity packets.

## Compatibility

* Paper/Folia 1.21.x targets
* Java 21 or newer

## Protection Features

* **Chunk Protection:** Processes outbound chunk packets to scan and rewrite sections containing sensitive blocks, underground fluids, or bedrock changes. Out-of-range block entity data is removed before serialization.
* **Block State Sanitization:** Normalizes deterministic block-state properties, including crop stages, waterlogged states, leaf distances, snow layers, moisture, and pickle counts.
* **Legitimate Visibility:** Restores configured blocks and underground fluids within a 5-block radius and along a line-of-sight ray up to 45 blocks to support normal cave exploration without exposing hidden ores behind solid walls. Block-break events also refresh adjacent block faces.
* **Entity Protection:** Filters entity metadata and equipment packets against a 32-block tactical radius.
* **Disconnect Cleanup:** Broadcasts an entity removal packet to world viewers when a player quits to neutralize "Logout Spot" exploits.

## Installation

1. Place the release jar in the server `plugins` directory.
2. Start the server once to create `config.yml`.
3. Set `client-name` and `license-key` in `config.yml`.
4. Configure `engine-mode`, `alert-threshold`, and `sensitive-blocks`.
5. Restart the server, or run `/ms reload` after changing the configuration.

## Configuration

### Engine mode

* `1`: Sensitive block protection, block entity filtering, state sanitization, entity filtering, and anti-seed state normalization.
* `2`: Mode 1 plus aggressive subterranean fill behavior below Y=5 (automatically falls back to mode 1 if TPS drops below 18.5).

### Sensitive blocks

A list of Bukkit `Material` names used by chunk obfuscation, block entity filtering, and the proximity revealer.

### Alert threshold

Controls the number of blocked block entity payloads counted for a player during each 60-second reporting interval to generate staff alerts.

## Commands and Permissions

### Commands

* `/ms`: Displays packet counters and active regional packet pipeline status.
* `/ms reload`: Reloads configuration, replaces the sensitive block table, and updates the proximity revealer.
* `/ms diagnose`: Displays health state, transformed packets, fallbacks, timeouts, errors, backpressure drops, and filtering counters.
* `/ms validate`: Validates engine mode, profiler threshold, license fields, and configured sensitive materials.

### Permissions

* `metadatastripper.admin`: Access to `/ms` commands and staff alerts.
* `metadatastripper.bypass`: Bypasses packet obfuscation, block reveals, and entity restrictions for trusted staff.