# Merchant Mind 3.4.7 — Fabric / Minecraft Java 26.1.2

Merchant Mind is a Fabric mod for Minecraft Java 26.1.2. This release keeps the existing Trade, Sell, and three-mode Restock systems unchanged and adds Buy and Reserve actions to the existing shop screen.

## 3.4.1 changes

- Added a `Reserve` button beside the existing `Buy` button for each shop listing.
- Reservations are server-authoritative, last five minutes, and prevent other players from buying the reserved listing.
- The reserving player may still use the existing Buy action.
- Expired reservations are released automatically.
- No separate UI was added.
- No duplicate Restock system was added; the existing scheduled, manual, and sold-out/instant-iron behavior remains in place.

## Build

Use the included Gradle wrapper:

```text
./gradlew build
```

The build targets Minecraft 26.1.2, Fabric Loader 0.19.3, Fabric API 0.155.2+26.1.2, and Java 25.

## World startup compatibility

Merchant Mind 3.4.7 was verified to initialize and create a world with Fabric API 0.155.2+26.1.2 on Java 25. If opening or creating a world fails with `MixinPrimaryLevelData` from `cardinal-components-leveldata`, update that Cardinal Components module to a build compatible with Minecraft 26.1.2 or remove it from the modpack. That mixin is not included in Merchant Mind.
