# Commands

**Commands** (operators only, but any player may use `script list` and `script edit` (only operators may save from the editor), a bot's owner may use `bot run`, `stop`, `remove`, `status`, `log` and `edit` on their own bots within the [limits of players' scripts](scripts.md#players-scripts), and a colony's owner may use `colony trust`, `colony trust list` and `colony untrust` on it):

| Command | Effect |
|---------|--------|
| `/antcraft status` | Shows whether bots are enabled, and the bot count |
| `/antcraft script list` | Lists the scripts found in `config/antcraft/scripts/`, `<world>/antcraft/scripts/` and data packs, each with where it came from (`config`, `world` or `data pack`) and the error of each that does not compile. A script is named by its path without `.py`, with dots for folders (`lib/trees.py` is `lib.trees`), and a data pack script by its id with dots (`mypack:lib/trees` is `mypack.lib.trees`); the world folder wins on a name clash, and either folder over a data pack (see [Script folders](scripts.md#script-folders)) |
| `/antcraft script edit <script>` | Opens the script in the in-game editor. Only operators may save; the server checks the save and writes it to `<world>/antcraft/scripts/`, then reloads as `script reload` does (see [Editing in game](scripts.md#editing-in-game)) |
| `/antcraft script reload` | Loads the script folders again, with the data pack scripts of the last `/reload`; a bot running a script whose file changed starts the new version from the top (one that no longer compiles stops, with the error in its log and its owner's chat) |
| `/antcraft bot [bots] run <script> [args]` | Starts a script on bots (without `[bots]`, the nearest bot within 64 blocks), replacing what they ran; `[args]` are given to it as the list `ARGS`. A player who runs it becomes the bots' owner |
| `/antcraft bot [bots] stop` | Stops the bots' scripts (formerly `/antcraft stop`); they then run `idle.py`, walking home and waiting there |
| `/antcraft bot [bots] remove` | Removes the bots for good: stops their scripts, drops what they carry where they stand, takes them out of their colony (their jobs go back to the board) and drops any respawn they wait for, so they do not come back |
| `/antcraft bot [bots] status` | Each bot's script, whether it is running, finished, failed or stopped, the line it is at, its current action and how many instructions it ran |
| `/antcraft bot [bots] log [n] [level]` | The last `n` (default 10) lines of each bot's log at or above `level` (`debug`, the default, `info`, `warn`, `error` or `print`), each with the game time it was written at |
| `/antcraft bot [bots] edit` | Opens the script the bot runs (the first of `[bots]`) in the in-game editor, with its last log lines |
| `/antcraft core script <script> [args]` | Sets the script of the Bot Core in your main hand: the bot it makes starts that script with those args at once, owned by you, without `bot run`. The tooltip names it; cores with different scripts do not stack. A script that does not exist or does not compile is refused with its error. `/antcraft core script clear` takes it off, so the bot runs `idle.py` |
| `/antcraft profile start\|stop\|report` | Measures where server tick time goes: `start` forgets what was measured and starts, `stop` stops, `report` lists the ticks measured with their average and worst time and which tick was the worst, then each part of the bots' work (a bot's tick `bot.tick`, its script `bot.script`, pathfinding for a walk `path.find` and for `reachable` `path.plan`) with its time per tick, worst tick and calls, the costliest first. Parts nest, such as pathfinding inside a bot's tick, so they do not add up to the tick |
| `/antcraft colony info` | The nearest colony's core, members and radius, and its owner and trusted players |
| `/antcraft colony trust <player> [colony]` | Lets a player change the colony as its owner can. The colony's owner may run this, `untrust` and `trust list` without operator rights; trusted players may change the colony but not whom it trusts. Acts on the nearest colony within 64 blocks that you may trust (your own, or any for operators), so another player's colony nearer does not get in the way, or on the colony whose core is at `[colony]` (within 64 blocks), to pick one of two colonies close together; a named colony you may not trust is refused |
| `/antcraft colony untrust <player> [colony]` | Takes that back |
| `/antcraft colony trust list [colony]` | Names the owner and the trusted players of the same colony `trust` would act on, so an owner who is not an operator can see whom their colony trusts; another player's colony is refused |
| `/antcraft colony radius <blocks>` | Sets the nearest colony's radius (default 32) |

## Script commands

Commands (on a server, `script reload`, `core script` and saving from the editor are operator-only; the others players may use on their own bots, see [Players' scripts](scripts.md#players-scripts)):

```
/antcraft script list                       scripts found, with compile errors
/antcraft script edit <script>              view a script in game; operators may edit and save it
/antcraft script reload                     reload changed files; a bot whose script or imported module changed restarts it
/antcraft bot [bots] run <script> [args]    start a script on a bot (args are given to it as the list ARGS)
/antcraft bot [bots] stop
/antcraft bot [bots] remove                 remove for good: items dropped, colony left, no respawn
/antcraft bot [bots] status                 script, line, current action, last result
/antcraft bot [bots] log [n] [level]        last n log lines at or above level (default debug)
/antcraft bot [bots] edit                   the bot's script in the editor, with its log
/antcraft core script <script> [args]       the Bot Core in your main hand makes bots that run this script
/antcraft core script clear                 ...and back to idle.py
```

`[bots]` is an entity selector; without it, the command acts on the nearest bot. A script is named by its path in its folder without `.py`, with dots for folders (`lib/trees.py` is `lib.trees`). A bot's owner, who gets its chat, is the player who placed its Bot Core, or who last started a script on it.
