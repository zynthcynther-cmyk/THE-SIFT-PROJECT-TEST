# TheSift (Paper 26.3, Java 25)

Server-side take on "The Sift": a floating-island dimension, rifts, Ichor, Soul Absorption, Siftite gear and Charoite.
No client mod needed. Custom items use `custom_model_data` strings (`siftite_ingot`, `charoite`, ...) so an
*optional* resource pack can restyle them; without one they look like their base items (echo shard, lapis, ...).

## Build

Requirements: JDK 25, Maven 3.9+, internet access to `repo.papermc.io`.

```
mvn clean package
```

Output: `target/TheSift-1.0.0.jar` -> drop into `plugins/` on a Paper 26.3 server.

`pom.xml` resolves Paper with the range `[26.3.build,26.4)` (as Paper's README does). To pin an exact build,
change `<paper.version>`.

## Layout

```
pom.xml
src/main/resources/{plugin.yml, config.yml}
src/main/java/dev/sift/plugin/
  SiftPlugin           bootstrap / wiring
  SiftWorld            creates world_the_sift, Overworld<->Sift pairing
  SiftChunkGenerator   islands, chasms, clearings, Ichor pools, trees, chests
  PortalManager        4x5 reinforced-deepslate portals, links, teleport, persistence
  PortalListener       goat horn + harmonic note blocks ("Wake the Singer")
  RiftManager          paired temporary rifts + mini rift visuals
  IchorManager         Ichor damage, Soul Absorption, Bottled Ichor
  SiftItems            all custom items, Siftite gear (Data Components), recipes
  GearListener         smithing upgrade, anvil tomes, crafting guards
  CharoiteListener     enchanting table boost
  DeathRecovery        mini-rift item recovery (persisted)
  LootListener         mob drops + chest loot
  SiftMobs             light hostile-mob spawner for The Sift
  SiftCommand          /sift ...
```

## Playing it

1. `/sift buildframe` - builds a reinforced deepslate frame (4x5, 2x3 opening) in front of you (or build your own).
2. Place 3+ note blocks within 8 blocks and play them as a major/minor chord (e.g. C-E-G).
3. Within 12 seconds blow a goat horn within 10 blocks of the frame -> the portal wakes. Walk in.
4. `/sift rift` opens a 5-minute rift pair. Rifts also open naturally (see `rifts.natural-spawning`).
5. Ichor = water in The Sift. Soul Absorption I: immune. II: Regeneration II. Get tomes from Sift chests or `/sift give soul_tome_1`.
6. Siftite Upgrade Template + Siftite Ingot + any Netherite tool/weapon/armor in a Smithing Table.
7. Charoite goes in the lapis slot of an Enchanting Table (+1 level above the cap, +5 XP levels).

## Commands (permission `sift.admin`, default op)

`/sift rift`, `/sift tp`, `/sift give <item> [amount] [player]`, `/sift soul <0|1|2>`, `/sift buildframe`, `/sift reload`

## Notes / design decisions

- "Poplar" is not a vanilla wood; the generator uses Pale Oak.
- Portal opening is particles + sound (no portal blocks), so no vanilla portal/gateway behaviour interferes.
- Soul Absorption is a PDC modifier on chestplates, applied with an Anvil + Tome (a registry enchantment would need a bootstrapper + datapack entry).
- Destination portals are auto-generated on first use; the area around them (8x8x5) is cleared.
