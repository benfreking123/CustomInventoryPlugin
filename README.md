# CustomInventoryPlugin — TheTower fork

Source for the `custominventoryplugin-1.2.0.jar` plugin used by TheTower.

This is a **fork** of MyServer's original `com.example.custominventoryplugin` JAR (May 2025, internal build). The original source repo is no longer accessible, so we decompiled the v1.0-SNAPSHOT JAR with [CFR 0.152](https://www.benf.org/other/cfr/) and committed the result here as the v1.1.0 baseline.

## Changelog

### v1.11.4 — Bestiary reveal + no-flicker nav + config-ification

- **Opped/staff bestiary reveal**: new permission `custominventory.bestiary.reveal`
  (`plugin.yml` `default: op`). Holders see every entry and all detail tiers
  (Lore / Weaknesses / Drops) regardless of kills; real kill counts still show and
  revealed-but-unearned entries are tagged *"Revealed by permission"*. The node is
  configurable via `bestiary.yml → reveal-permission`.
- **No-flicker tab/floor navigation**: category and floor clicks now **repaint the
  open inventory in place** (`BestiaryInventory.render(...)`) instead of
  `close`+`openInventory`. Kills off the screen flash and the cursor-recenter that
  happened on every click (kills are cached in the holder for the session).
- **Blank `???` cells**: undiscovered grid slots are left AIR so the painted `?`
  from the `codex_bg` art shows through (no more gray-dye placeholder items).
- **Account book honesty (OP fix)**: floor/haven progress now counts a permission
  only when **explicitly granted** (`isPermissionSet`), so opped staff no longer see
  all 100 floors as "done" via Bukkit's op fallback on unregistered `tower.*` nodes.
- **Config-ification** (tune without a rebuild):
  - `bestiary.yml`: `reveal-permission`, `display.glyph`, `display.shift`.
  - `compendium.yml`: `progress.{total-floors,misc-full-hours,weights.*}`,
    `counters.{crystals,dungeon,checkpoint}-prefix`,
    `permissions.{floor-done,haven-unlocked,haven-created}` (new `CompendiumConfig`).
- **Art**: `codex_bg.png` refreshed (baked MOBS/BOSSES tab labels, greyed locked
  plates); glyph `ascent 54` / `shift 16` alignment retained.

> _1.10.0 – 1.11.3 belong to the parallel auto-loot / pickup-pipeline / tooltip
> workstream and are documented with that work; 1.11.4 folds the compendium/bestiary
> changes above on top of it._

### v1.9.0 — Bestiary "codex" reskin (Nexo background + category tabs)

- **New look**: the Bestiary GUIs render over the `codex_bg` Nexo glyph (a 256×256
  book frame). `Text.nexoBackground(char, shiftLeft)` builds the title component
  using the `nexo:shift` + `nexo:default` fonts (same trick as Nexo's own menus).
  Source PNG: `nexo-compat/Oraxen/.../required/ui/codex_bg.png`; glyph registered
  in `Nexo-shared/glyphs/nexo_defaults/interface.yml` (char `ꐜ` U+A41C, ascent 37).
- **Category tabs** replace floor buttons: **Mobs** / **Bosses** in the left column
  (two locked plates reserved for Dungeons + a 4th tab later). Mobs = non-boss
  entries, Bosses = `boss: true` entries.
- **Floor paging**: the green arrows step through the floors that have entries in
  the current category; Close (painted X) exits.
- Decorative regions (tabs, arrows, close) are AIR so the painted frame shows
  through — clicks route by raw slot, entry icons sit on the painted grid cells.
- Detail pane back button returns to the same category + floor.
- **Alignment note**: vertical fit is tuned by the glyph `ascent` (interface.yml),
  horizontal by `BG_SHIFT` in `BestiaryInventory`. Grid cell pitch in the art is
  18px = vanilla chest slot pitch, so one offset aligns the whole grid. Needs a
  `nexo reload` + relog after deploy to appear.

### v1.8.0 — Quest %, generic counters, Harvest page, Personal Space tile

- **Quest progress is live**: the Account book and My Quests tile count BetonQuest
  done-tags straight from `betonquest_tags` (MariaDB, cross-server). Tag lists per
  floor live in `compendium.yml` (hybrid shared) — add a quest's done-tag there and
  it counts. The 20%-weight quest bar in Account Progress is no longer 0.
- **Generic counters**: new `cip_counters` table (`player_uuid, counter_key, value`)
  plus a console-only `/cipcount <player|uuid> <key> [amount]` command. Hooked:
  charged crystal activations (`crystal.<world>`, from `charged-crystal.sk`),
  dungeon completions (`dungeon.floor3_dungeon`, from the floor3 dungeon's
  `enddungeon` FunctionCommand), checkpoint discoveries (`checkpoint.<id>`, from
  `checkpoints.sk`). Account book shows Quests done / Camps found / Crystals
  charged / Dungeon runs.
- **Harvested & Collected page**: farming totals from RivalHarvesterHoes and mining
  totals from RivalPickaxes via their PAPI placeholders (pickaxes are per-server
  SQLite, so mining shows the local server's numbers).
- **Personal Space tile**: Haven status from LP `tower.haven.unlocked` / `.created`.

### v1.7.1 — Bestiary UX + Account layout

- Floor tabs moved to the bottom row as lime/white concrete (gray glass was invisible against the filler).
- Unlock tiers: discovered@1 · lore@10 · weaknesses@50 · drops@100. Stats removed; drops replace that detail slot. Locations show on discovery.
- Account page: Account book holds progress % / playtime / deaths / breakdown; My Quests + Floors Completed sit beside it. Floors tooltip shows highest / next gate / zone bar.
- `bestiary.yml` drops lists grounded in Divinity bindings.

### v1.7.0 — Bestiary

- Bestiary tab inside `/compendium`: floor tabs, discovered vs `???` silhouettes, detail pane with tiered unlocks (discovered @1 / stats @10 / lore @25 / weaknesses @50).
- MythicMobs kill tracking → MariaDB table `cip_bestiary_kills` (player, mythic_id, kills). Elites stored under their own id and roll up into the base entry.
- Config: `bestiary.yml` (hybrid shared) — 25 entries for floors 1–4, grounded in MythicMobs stats + Divinity drop bindings. VFX/dummies excluded.
- Account page Bestiary button is live; Account Progress score now includes real bestiary discovery %.
- Soft-depend MythicMobs; kill listener only registers when MM is present.

### v1.6.0 — Compendium (account/meta tracker)

- New `/compendium` command (alias `/comp`, permission `custominventory.compendium`, default true) — a read-only progression GUI. Wired to slot 2 of the E-menu button bar (the red-book icon) in `inventory-management-controls.sk`.
- **Account / Meta landing page (v1):** built entirely from data that already exists — Bukkit statistics (playtime, deaths, mob kills), first-join date, and LuckPerms floor-completion nodes (`tower.floor<N>.done`). No new counters or tables.
- **Derived "Account Progress" score:** a fixed weighted blend (floors 40 / quests 20 / bestiary 15 / collections 15 / misc 10), never stored. Not-yet-built categories contribute 0, so the number only climbs as those systems ship — it can't desync.
- Nav row scaffolds the future tabs: Quests opens `/myquest`; Bestiary and Collections show a "coming soon" notice (Compendium v2/v3).
- All clicks/drags in the GUI are cancelled (nothing is takeable).

### v1.5.2 — Q-drop AutoPick exemption

- Player-dropped items (Q / inventory drop) are tagged and skipped by the AutoPick vacuum so they stay on the ground for vanilla pickup instead of immediately routing back into bags/inventory. Mob/harvest delayed AutoPick is unchanged.

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
