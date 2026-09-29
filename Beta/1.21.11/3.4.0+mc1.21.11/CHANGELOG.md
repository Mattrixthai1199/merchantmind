# Changelog

## 3.4.7

Verified world startup with Fabric API 0.155.2+26.1.2 and MerchantMind on Java 25. The crash reported while opening a world is caused by an incompatible `cardinal-components-leveldata` mixin targeting a `PrimaryLevelData` constructor that does not exist in Minecraft 26.1.2; MerchantMind does not include that mixin. Use a Cardinal Components build compatible with Minecraft 26.1.2 or remove the incompatible leveldata component from the modpack.

## 3.4.6

Renamed the action shown for a reserved listing from Buy to Pay so it is distinct from the regular purchase action. The action still opens the separate payment screen.

## 3.4.5

Reserve now completes without opening payment selection. Reserved listings show a Buy action that opens a separate payment screen, and installment payment uses a bounded slider to select the payment amount.

## 3.4.2

Added strict gameplay-only guards to the slam HUD and stretch overlay so neither effect can draw during title, loading, pause, or other screens.

## 3.4.1

Added Buy and Reserve buttons to the existing shop listing rows. Reservations are authoritative on the server, expire after five minutes, and do not create a separate screen or a second restock implementation.
