# Getting started

## Install

AntCraft targets Minecraft 26.3 with Fabric Loader 0.19.5 and Fabric API. Put the AntCraft jar (built with `./gradlew build`, in `build/libs/`) and Fabric API into the `mods` folder of the server and of every player's client: the mod must be installed on both (see [Server admins](features/server-admins.md)). On a dedicated server, bots stay off until the server owner sets `enableOnDedicatedServer = true` (see [Config](features/config.md)).

## First bot

1. Craft a Bot Core and use it on a block: a bot appears there, and you own it.
2. Write a script, such as `config/antcraft/scripts/hello.py`, and run it on the bot with `/antcraft bot run hello`. The [language guide](language/index.md) describes the language, the [API reference](reference/index.md) every action and sense, and the [cookbook](cookbook/index.md) has examples such as punching a tree into a wooden pickaxe.
3. When the script ends, fails or is stopped (`/antcraft bot stop`), the bot runs `idle.py` (see [Scripts in the game](features/scripts.md)): it walks home, where it was first spawned (or where a script moved it with `set_home`), and waits there.
4. To hand out workers rather than commands, hold a Bot Core and set its script with `/antcraft core script hello [args]`: the bot it makes runs `hello` as soon as it appears.

## Crafting a Bot Core

```
 . P .      P = carved pumpkin
 I R I      I = iron ingot
 . I .      R = block of copper
```
