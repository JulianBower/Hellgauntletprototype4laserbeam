# Gauntlet of Hell (NeoForge 1.21.1)

Build: JDK 21, then `./gradlew build` (jar ends up in build/libs/), or let the GitHub Action build it.

Craft: obsidian, netherite ingots and a nether star (see data/hellgauntlet/recipe), or `/give @s hellgauntlet:gauntlet_of_hell`.

Stats: 10000 durability, 5 attack damage, 1.8 attack speed, mending/sharpness/unbreaking allowed,
fire resistant, dropped copies survive explosions.

Controls
- Right click: shoot the orb (or the single laser in laser mode)
- Hold right click: continuous laser (laser mode, "Continuous laser")
- Shift + right click: next mode
- X: laser mode on/off (rebind in Controls under "Gauntlet of Hell")

Durability per use: orb Small 3, Entity only 5, Medium 5, Large 8; laser 10; continuous laser 10 every 2.5 seconds.
Tweak numbers in BlastMode.java and LaserMode.java.
