# aibot

aibot — a Fabric mod for Minecraft 1.20.1, scaffolded by minecraft-dev.

## Build

```sh
# Windows
gradlew.bat build

# Linux / macOS
./gradlew build
```

The first build downloads Gradle, a JDK 17 toolchain, and dependencies — allow 5-15 minutes.

The deployable jar is `build/libs/aibot-0.1.0.jar`.

## Install

Copy `build/libs/aibot-0.1.0.jar` into your Fabric server's `mods/` directory (or your client's) and restart.

## Layout

- `src/main/java/com/example/aibot/Aibot.java` — mod entry point (ModInitializer.onInitialize + ClientModInitializer.onInitializeClient)
- `src/main/resources/fabric.mod.json` — mod metadata (`id`, `entrypoints`, `depends`)
- `build.gradle` — fabric-loom (1.17.19) + fabric-loader (0.19.3) + Fabric API build
