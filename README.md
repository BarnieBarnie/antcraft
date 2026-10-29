# AntCraft

A Fabric mod for Minecraft that adds bots you program. Each bot runs a script written in a small subset of Python: it walks where the script sends it, mines, places, crafts, smelts and fills chests, and the script decides what comes next. A bot with nothing to run walks home, through a nether portal if its home is in another dimension, and waits there. Colonies split work between bots with a library of default scripts.

The bots are in-game entities and are not linked to real accounts. This is a hobby project, made for fun in your own worlds.

**Documentation: <https://barniebarnie.github.io/antcraft/>** (getting started, features, the scripting language, the API reference, development and design). Its source is in `docs/`.

## Install

AntCraft targets Minecraft 26.3 with Fabric Loader 0.19.5 and Fabric API. Put the AntCraft jar (built with `./gradlew build`, in `build/libs/`; requires JDK 25) and Fabric API into the `mods` folder of the server and of every player's client. Then craft a Bot Core, use it on a block and run a script on the bot: see [Getting started](https://barniebarnie.github.io/antcraft/getting-started/).

## Fair-play policy

AntCraft must be installed on the server and on every player's client: it adds items, screens and network messages that a client without it cannot handle. It cannot be used as a cheat client on other people's servers, since bots only exist on a server that has the mod installed.

- **Singleplayer / LAN:** enabled by default.
- **Dedicated servers:** disabled by default. The server owner must explicitly set `enableOnDedicatedServer = true` in the config.
- The `/antcraft` commands are restricted to operators. Server owners can cap the bot count per dimension (`maxBotsPerLevel`).
- Each colony belongs to the player who placed its core. Only that player, the players they trust with the colony (`/antcraft colony trust`) and operators may change it, and a Bot Core's bot joins only a colony its player may change.

## License

MIT
