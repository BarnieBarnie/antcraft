# Server admins

## Fair-play policy

AntCraft must be installed on the server and on every player's client: it adds items, screens and network messages that a client without it cannot handle. It cannot be used as a cheat client on other people's servers, since bots only exist on a server that has the mod installed.

- **Singleplayer / LAN:** enabled by default.
- **Dedicated servers:** disabled by default. The server owner must explicitly set `enableOnDedicatedServer = true` in the config.
- The `/antcraft` commands are restricted to operators. Server owners can cap the bot count per dimension (`maxBotsPerLevel`).
- Each colony belongs to the player who placed its core. Only that player, the players they trust with the colony (`/antcraft colony trust`) and operators may change it, and a Bot Core's bot joins only a colony its player may change.

## Server load

**Server load:** 30 bots running scripts keep well within the server's 50 ms a tick (see the bot benchmark under [Development](../development/index.md#building-and-testing)). All bots together expand at most 2000 path nodes a tick (`PathBudget`): a bot whose path search runs out of them waits where it stands and goes on with it the next tick, so many bots planning at once spread their searches over several ticks rather than stall one. A search asks the world about each block once, and reads blocks from the loaded chunks without asking the chunk source each time. `find_nearest` and `scan` share a budget of 32768 blocks a tick the same way, and senses that touch the world at once (`craft`, `count`, `entities` and the like) count about their time in script instructions, so a loop of them cannot stall the server either.
