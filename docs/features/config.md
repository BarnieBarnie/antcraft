# Config

**Config** (`config/antcraft.properties`):

| Key | Default | Meaning |
|-----|---------|---------|
| `enableOnDedicatedServer` | `false` | Must be `true` for bots to work on a dedicated server. When `false`, bots cannot be placed, and any existing bots stand still |
| `maxBotsPerLevel` | `64` | Maximum number of bots per dimension |
| `colonyActionsRequireOp` | `false` | When `true`, only operators can change colonies, owners and trusted players included, and Bot Core bots of other players join no colony |
| `scriptInstructionsPerTick` | `2000` | How many instructions each bot's script may run a tick; a script that uses them up goes on the next tick, so an endless loop costs a busy bot, not a frozen server. At most `20000`: a higher value is lowered with a warning in the log, since every bot may use its whole budget every tick (30 bots at 20000 are 600000 instructions a tick on the server thread, which can lag the game). Raise it only for scripts that think a lot between actions, and measure with `/antcraft profile` |
| `playerInstructionsPerTick` | `10000` | What all the bots of one player who is not an operator may run a tick together, split evenly between those running a script and never more than `scriptInstructionsPerTick` each (see [Players' scripts](scripts.md#players-scripts)). At least `1`, at most `20000` (lowered with a warning). Operators, and everyone off a dedicated server, are exempt |
| `maxScriptsPerPlayer` | `8` | How many scripts a player who is not an operator may run on their bots at once with `/antcraft bot run` (`idle.py` not counted); `0` lets no such player run scripts. Operators, and everyone off a dedicated server, are exempt |
| `scriptChatLevel` | `error` | The lowest level of a script's output (`debug`, `info`, `warn` or `error`) that goes to the chat of its bot's owner; everything goes to the bot's log, and `print` and `say` always show in chat |
| `botRespawn` | `true` | Whether a dead bot comes back at its home |
| `botRespawnTicks` | `200` | Ticks a dead bot waits before it comes back |
| `botFleeHealth` | `6` | Health (of 20) at or below which a bot flees a hostile mob within 8 blocks instead of fighting (Reflexes); `0` never |
| `botSurfaceAir` | `100` | Air (of 300 ticks) below which a bot under water swims up to breathe (Reflexes); `0` never |
| `botsAttackPlayers` | `false` | Whether a script's [`attack`](../reference/combat/attack.md) may hit players; when `false` it fails with `protected` |
| `botsAttackBots` | `false` | Whether `attack` may hit other bots |
| `botsAttackTamed` | `false` | Whether `attack` may hit tamed or named animals; hostile mobs and untamed, unnamed animals it may always hit |
| `botsLoadChunks` | `true` | A bot running a script keeps the chunks around it loaded, so it works on when its owner walks away, and `goto` loads (and generates, if need be) the chunks on its way. When `false`, `goto` stops with `not_loaded` at the edge of loaded terrain. Idle bots (running their fallback `idle.py`) keep chunks loaded only while they walk |

The goal-driven colony's options, `mineshaftLevels` and `replantMatchWaitTicks`, are gone: a config file that still has them loads, and they are dropped when the file is written again.

