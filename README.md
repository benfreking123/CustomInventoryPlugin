# CustomInventoryPlugin — TheTower fork

Source for the `custominventoryplugin-1.2.0.jar` plugin used by TheTower.

This is a **fork** of MyServer's original `com.example.custominventoryplugin` JAR (May 2025, internal build). The original source repo is no longer accessible, so we decompiled the v1.0-SNAPSHOT JAR with [CFR 0.152](https://www.benf.org/other/cfr/) and committed the result here as the v1.1.0 baseline.

## Changelog

### v1.4.0 — Group Drops (choose-your-reward)

- New `/groupdrop` command: choose-your-reward selection menus driven by the DB (cross-server).
  - `/groupdrop <id>` opens a selection GUI; left-click to choose, right-click to preview a bundle.
  - Configurable picks (`N of M`), distinct-pick enforcement, and `once`/`repeatable` claim modes.
  - Options can grant a single item, a multi-item **bundle**, and/or console **commands** (`%player%` / `%uuid%` substituted — itemgen, perms, money, customitems, etc.).
- **WYSIWYG in-game editor** (`/groupdrop edit <id>`): drop items in the top 5 rows to place options, shift-click an option to edit its bundle in a sub-window, and use the bottom control bar to set picks / distinct / claim mode / tokens. Saves on close.
- **Redeemable tokens**: enable per group, mint with `/groupdrop token give <id> [player] [amount]`; right-click the token to open the chooser (consumed on first pick).
- Admin surface: `list/create/edit/delete/set/option/token/reset/export/import/reload`. `export`/`import` round-trip definitions through `groupdrops.yml`.
- New MariaDB tables: `cip_groupdrop_def`, `cip_groupdrop_option`, `cip_groupdrop_grant`, `cip_groupdrop_claim` (claims are global, so a `once` group can only be claimed once network-wide).
- New permissions: `custominventory.groupdrop.use` (default true), `custominventory.groupdrop.admin` (default op).

### v1.2.0 — Backpacks

- New `/bp [id]` command opening config-driven cross-server backpacks.
- `backpacks.yml` declares each backpack (display name, size 9–54, LuckPerms perm, optional `/ci` button, smart-pickup).
- New MariaDB table `cip_player_backpack` (load-on-open, save-on-mutation — no in-memory cache, so contents stay consistent across the Velocity network without proxy messaging).
- Backpack-rejected items: vanilla shulker boxes (all 17 colors), bundles, and PDC-marked backpack items.
- Optional `/ci` icon buttons render at the configured slot when the player has access.
- Smart pickup: items the player picks up auto-deposit into any accessible `smart-pickup: true` backpack with a matching partial stack.
- PlaceholderAPI expansion under `customip_` (`backpack_<id>_used/size/free/pct`, `backpack_total_used`).
- New softdepend on PlaceholderAPI for the placeholders.

### v1.1.1 — Skill reset on gem removal

- Removing a skill gem from `/ci` now also dispatches `/class forceskill <player> reset <skill>` so Fabled refunds the spent point, removes the skill from the class roster, and clears any hotbar binding. Without this, players could still cast a skill from their hotbar after the LuckPerms permission was revoked (Fabled's `needs-permission` only gates the skill tree, not active bindings).

### v1.1.0 — Cross-server + dead-code fixes

Three problems in v1.0-SNAPSHOT:

1. **Skill gems didn't grant permissions.** `SkillHandler.handleSkillSlot` was never invoked anywhere in the bytecode — `SlotHandler.updateSlotAttributes` only had a dispatch branch for `slot-type == "attribute"`. v1.1.0 added the missing skill branch.
2. **Closing /ci wiped all skill perms.** `removeAllPermissions` was called on every `InventoryCloseEvent` — the original design relied on Skript to re-grant perms, but that Skript wasn't migrated. v1.1.0 made this a no-op.
3. **Per-server YAML state.** Players' equipped gear and granted perms lived in `plugins/CustomInventoryPlugin/playerData/<uuid>.yml`, isolated per-server. v1.1.0 moved storage to MariaDB (`cip_player_gear`, `cip_player_slot_attrs`, `cip_player_slot_perms`) and pushed skill perms through the LuckPerms API. Both follow players across `social ↔ zone1 ↔ dungeon ↔ dev` automatically.

## Building

```bash
JAVA_HOME=/usr/lib/jvm/java-25-amazon-corretto mvn clean package
# Output: target/custominventoryplugin-1.2.0.jar
```

Then deploy:

```bash
cp target/custominventoryplugin-1.2.0.jar ../../shared/plugins/jars/
cd ../.. && infra/tt-link.sh CustomInventoryPlugin
# Restart any running backends to pick up the new JAR
```

## Dependencies

| Dependency | Scope | Source |
|---|---|---|
| `io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT` | provided | papermc.io repo |
| `net.luckperms:api:5.4` | provided | Maven Central |
| `me.clip:placeholderapi:2.11.6` | provided | extendedclip.com repo |
| `studio.magemonkey:fabled:1.0.4` | system | Local — `lib/fabled.jar` (copy from `shared/plugins/jars/`) |
| `com.zaxxer:HikariCP:5.1.0` | compile, **shaded** | Maven Central |
| `org.mariadb.jdbc:mariadb-java-client:3.3.3` | compile, **shaded** | Maven Central |

HikariCP and the MariaDB JDBC driver are relocated to `com.example.custominventoryplugin.shaded.*` to avoid classpath conflicts with other plugins.

## Source layout

```
src/main/java/com/example/custominventoryplugin/
├── CustomInventoryPlugin.java       — main entry; init Database + LuckPermsBridge + Backpack config/data/listeners
├── commands/                        — /ci, /bp, /debug
├── config/
│   ├── ConfigManager.java           — settings.yml (slot layout)
│   └── BackpackConfig.java          — backpacks.yml (v1.2.0)
├── data/
│   ├── Database.java                — HikariCP pool + schema bootstrap
│   ├── LuckPermsBridge.java         — thin wrapper over LP UserManager
│   ├── PlayerGearData.java          — MariaDB-backed player gear state
│   └── BackpackData.java            — MariaDB-backed backpacks (load-on-open, save-on-mutation)
├── inventory/
│   ├── GearInventory.java           — the 54-slot /ci UI (renders backpack buttons in v1.2.0)
│   └── BackpackInventory.java       — per-backpack inventory window (v1.2.0)
├── listeners/
│   ├── ArmorHandler.java            — vanilla armor slots
│   ├── AttributeHandler.java        — attribute accessory slots (rings, etc.)
│   ├── InventoryListener.java       — top-level /ci click/drag dispatcher (handles backpack buttons in v1.2.0)
│   ├── SkillHandler.java            — skill gem slots → LuckPerms + Fabled forceskill reset
│   ├── SlotHandler.java             — per-slot dispatch (wires SkillHandler)
│   ├── BackpackListener.java        — clicks/drags/close on backpack windows; shulker/backpack filter (v1.2.0)
│   └── BackpackPickupListener.java  — smart-pickup auto-deposit (v1.2.0)
└── placeholders/
    └── BackpackPlaceholders.java    — PAPI customip_ expansion (v1.2.0)
```

`ArmorHandler`, `AttributeHandler`, `commands/DebugCommand`, `commands/GearCommand`, and `config/ConfigManager` are unchanged from the decompiled v1.0 source.

## See also

- [`Docs/technical/plugins/custominventoryplugin.md`](../../Docs/technical/plugins/custominventoryplugin.md) — full architecture & runbook
