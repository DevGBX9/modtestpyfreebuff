# Example Mod

## Setup

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using.

## Features

### Charged Punch (v1.1.0)

Hold left click for 3 seconds to charge your punch, then release it:

1. **Massive knockback** — the target is launched away from you (with an upwards boost).
2. **Delayed explosion** — 8 ticks later the target detonates (TNT-power, no fire).
3. **Ground shockwave** — a dust ring and a radial push that dashes nearby entities like dirt.
4. **Debuffs** — Slowness III + Weakness II for 5 seconds on everything nearby.

While charging you'll see enchantment particles swirl around you, the arm winds up with a vanilla-style animation (trembling at full charge), and a chime plays when ready. Short clicks still work as normal punches.

## Building

This project is built on GitHub Actions, not locally — no local Java or Gradle install is needed. Every push runs `./gradlew build` and uploads the mod jar as a workflow artifact. Pushes to `main` also publish a GitHub Release (tag `v<version>` from `gradle.properties`) with the mod jar attached.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
