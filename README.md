# Example Mod

## Setup

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using.

## Building

This project is built on GitHub Actions, not locally — no local Java or Gradle install is needed. Every push runs `./gradlew build` and uploads the mod jar as a workflow artifact. Pushes to `main` also publish a GitHub Release (tag `v<version>` from `gradle.properties`) with the mod jar attached.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
