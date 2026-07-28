# Merchant Mind

> **Source code only — no issue tracker here. Please report bugs or feature requests via email: [mattrixthai9911@zohomail.com](mailto:mattrixthai9911@zohomail.com)**

An AI-powered Minecraft shopkeeper mod with a rotating catalog, smart barter trading, and a Charge & Slam combat mechanic. Built entirely with AI assistance in Thailand.

---

## Features

### The AI Shopkeeper
A rotating shop with a catalog of items across **11 categories**: Weapon, Armor, Tools, Throwables, Resources, Blocks, Utility, Food, Redstone, Decor, and Rare. Each category has an independent restock timer. When stock runs out or the timer expires, shelves are cleared and refilled with random items from the category pool.

### Iron Restock Shortcut
Restocking takes **2 minutes 30 seconds** (150 ticks). During restock, an optional iron slot opens — submitting **4 Iron Ingots** instantly completes the restock. Iron is never required; it is purely an optional speed-up.

### Per-World Saves
Shop inventory, restock timers, and player coin balances are saved to `<save>/data/merchantmind_shop.json`. Every world maintains a completely separate economy.

### Intelligent Trading (Barter)
The "Trade" tab calculates the true worth of offered items and matches them against shop stock using a deterministic value-matching algorithm with a square-root bundle curve. Leftover value returns as coins.

### Smart Selling
The "Sell" tab applies a **capped bulk bonus of up to +35%** — selling more yields a better per-item rate. Payout is clamped to **70% of true worth** to prevent arbitrage.

### Charge & Slam Combat
Hold `V` to charge your weapon. Bonus damage scales up to **+14.0** at full charge (38 ticks / ~1.9 s). A HUD bar above the hotbar shows charge percentage and bonus damage in real time, with a screen-stretch wind-up effect.

### Combat Tweaks
- **Arrow Reflect** — Mob arrows reflect back at **3x damage**
- **Instant Pearl Heal** — Ender Pearl landing grants instant regeneration (6 hearts + Regen III)
- **Jump Crits** — Small bonus for airborne critical hits on hostile mobs
- **Mob Suppression** — Skeletons and baby zombies have an **83% spawn cull rate**
- **Clear Dropped Items** — Press `K` to clear all dropped items

### Keybinds (Configurable)

| Key | Action |
|-----|--------|
| `B` | Open Merchant Shop |
| `K` | Clear Dropped Items |
| `V` | Charge & Slam (hold to charge) |

---

## Version Scheme

Merchant Mind follows **Semantic Versioning + build metadata** in the format `MAJOR.MINOR.PATCH+mc<minecraft_version>`.

| Component | When to Increment | Notes |
|-----------|-------------------|-------|
| **MAJOR** | Breaking change — old saves/configs no longer work | Reset MINOR and PATCH to 0 |
| **MINOR** | New feature added, everything old still works | Reset PATCH to 0 |
| **PATCH** | Bug-fix-only, no new features, nothing broken | |
| **+mc\*** | Minecraft version this build targets | No effect on version comparison |

Only one position is incremented per release, matching the **biggest change**. New features always outrank bug fixes.

---

## Building

```bash
./gradlew build
./gradlew runClient
./gradlew runServer
```

**Important notes:**
- Minecraft 26.1.2 does not use obfuscation mappings — do **not** add `mappings loom.officialMojangMappings()` to `build.gradle`
- Do not build on a network drive (NFS/SMB) — Gradle needs file locks
- Use `implementation`, not `modImplementation` — the no-remap mode has no such configuration

---

## Technical Details

| | |
|---|---|
| **Mod Loader** | Fabric |
| **Minecraft** | 26.1.2 |
| **Dependencies** | Fabric API, Fabric Loader (>= 0.19.3) |
| **External Libraries** | None — Fabric API is the only dependency |
| **License** | MIT |

---

## Contact

Found a bug? Want a feature? Have an idea? Email me at **mattrixthai9911@zohomail.com** — I will review it and decide whether to add it.

This repository contains source code only. There is no issue tracker here.

---

*This mod was made in Thailand with the assistance of AI tools.*
