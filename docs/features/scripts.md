# Scripts in the game

**Scripts.** Every bot runs a script, and only a script decides what it does. Without one, or once its script ended, stopped or failed, it runs `idle.py` (walk home, then wait), whether or not it is in a colony; a bot that never ran a script does too. From another dimension than its home's, `idle.py` goes through a nether portal within 32 blocks, or else the nearest one it remembers, and without any waits where it is. When `idle.py` itself ends or fails, it is not started again. A bot's home is where it was first spawned, until a script moves it with `set_home(pos)`.

Scripts `import` modules from the script folders. The mod's default scripts, `idle.py`, the library modules `lib/home.py` (`go_home()`, `at_home()`, `to_home_dimension()`, `portal_out()`) `lib/shaft.py` (`dig_shaft(levels)` digs and ladders a mineshaft, `to_level(level)` and `to_surface()` climb it) and `lib/mine.py` (`mine_level(level)` mines a level's hall, branches and ore veins into the landing chest) and the colony scripts under `colony/` (see [Colonies](colonies.md)), are copied into `config/antcraft/scripts/` each time the server starts; the mod never overwrites one you changed, so you can give your bots other idle habits, and a deleted one comes back.

Running scripts are saved with the world: after a reload a bot resumes at the same instruction, inside the action it was doing. If the script or a module it imported changed, it starts again from the top with the same arguments, keeping `memory`.

## Script folders

Scripts are loaded from two folders; the world folder wins on a name clash:

- `config/antcraft/scripts/` for every world (share scripts by copying files here);
- `<world>/antcraft/scripts/` for one world.

The mod copies its default scripts (`idle.py`, the library modules `lib/home.py`, `lib/shaft.py` and `lib/mine.py` and the colony scripts under `colony/`) into `config/antcraft/scripts/` each time the server starts, and never overwrites a file the player changed: `config/antcraft/scripts/.antcraft-defaults` records what it last copied, and a file that differs from that is the player's. A deleted default comes back.

## Bot Cores that carry a script

A Bot Core can carry a script and its args (the item's `antcraft:script` component, named in its tooltip), so you hand out workers rather than commands: the bot it makes starts that script the moment it is activated, owned by the player who used the core, as `bot run` would start it. A core without one makes a bot that runs `idle.py`. `core script` refuses a script that does not exist or does not compile, with its error; one gone or broken since the core was set is refused the same way when the core is used, in the player's chat, and the bot runs `idle.py`. Cores with different scripts do not stack. Writing scripts in a book and quill is out of scope for v1.

## Output and logging

Every bot keeps a log (`/antcraft bot [bots] log`). Lines have a level: `debug`, `info`, `warn` or `error`. Each line goes to the log, and to the chat of the bot's owner when its level is at or above `scriptChatLevel` (config, default `error`).

| Call | Level | Chat (default) | Log |
|---|---|---|---|
| `print(...)` | always shown | yes | yes |
| `debug(...)` | `debug` | no | yes |
| `info(...)` | `info` | no | yes |
| `warn(...)` | `warn` | no | yes |
| `error(...)` | `error` | yes | yes |
| runtime and compile errors | `error` | yes | yes |
| action failures (`Result` with a reason) | `debug` | no | yes |

`print` is meant for the player, so it always goes to both, whatever the chat level. `error(...)` only reports; it does not stop the script.

The log keeps a bot's last 200 lines and is saved with the bot, so `/antcraft bot log` still shows what happened before a restart. `say(text)` goes to the log as a `print` line and to the owner's chat, and floats above the bot for five seconds; a new `say` replaces it. `/antcraft bot status` shows the last action the script finished with its result, such as `goto(Pos(4, 70, -2)) -> unreachable` or `craft('stick') -> ok, 4`; that result is saved with the bot too.

## Budget

Each bot gets `scriptInstructionsPerTick` (config, default 2000) VM instructions a tick. A script that uses them up simply continues on the next tick, so `while True: pass` costs a busy bot, not a frozen server. See [Config](config.md) for its limit. A call of a bot API function that is done at once and touches the world costs more than one instruction, about as many as the time it takes: `craft` 40, `drop` 20, `equip` 15, `inventory`, `block` and `container` 30, `count` and `has` 20, `entities` 120, `items_near` 10, `smelt` 60, `colony` 35, `free_slots` 20, `matches` 15, `blueprint` 100, `mineshaft_at` 1500, `take_job` 30, `drop_job` and `finish_job` 20, `post_job` 35, and `jobs` 10 and 8 a job it lists (measured in the benchmark, where a plain instruction takes about 20 ns of the tick, the bot's own tick included). So a script that loops on them uses up its budget in about the time a plain loop would. Functions that cost about an instruction (`pos`, `time`, `held`) and actions that pause the script cost one. Senses that scan the world (`scan`, `find_nearest`, `mineshaft`, and `ore_vein`, whose vein is at most 32 blocks) count extra instructions in proportion to the blocks they read, and all but `ore_vein` share a server-wide budget the way pathfinding shares `PathBudget`; a site `mineshaft` finds also counts 700 blocks of that budget for its dict (about its time, #399). The bot benchmark logs each case's average, 99th percentile (p99) and worst tick; judge costs on the average and the p99, as the single worst tick varies some 2 ms between runs of the same code (#399).

## Pausing

A script pauses when it calls an action (until the action ends), calls `wait(ticks)`, or runs out of budget. The action's `Result` is the call's return value when the script continues.

## Save and resume

Because the VM's state is plain data (frames, operand stacks, globals, the call into the running action), a bot saves its running script with the world and resumes on load at the same instruction. What is saved: every frame (its code, by module and place in it, not as code), its locals and operand stack, every module the script imported with its globals, `memory`, loops in progress (iterators and dict views) and how far the running action got. Values are saved as a graph, so a list two variables share comes back as one list. `Pos`, `Stack`, `Block` and `Result` are saved as plain data; an `Entity` by its UUID, found again once the script uses it, and not `valid` while it is not loaded.

The action a script was in starts again on load with the same arguments, as if the script had just called it: `goto` plans its path again from where the bot stands, `mine` walks back to its block, `build` goes on with the blocks still to place, keeping the count of those it placed, and so on. `wait` goes on with the ticks it had left. An action that is done at once when called again gives its result and the script goes on.

The save holds a fingerprint of the compiled code of the script and of each module it imported (which changes with the source, line numbers included). If one of them changed since, is gone or no longer compiles, the script starts again from the top instead, with its arguments and keeping `memory`, and the bot's log says why (`miner.py changed since the save: miner starts again from the top`). So does a script that held a value that cannot be saved. A bot saved before scripts were saved with it runs `idle.py`. A script that is gone from the script folders when the bot loads is not resumed: the bot runs `idle.py`, with a warning in its log. A bot resumes its script on its first tick after loading, once the script folders are read. A bot that goes through a portal resumes at once in the other dimension with the code it ran, also a script not from the library (see [Travelling between dimensions](nether.md#travelling-between-dimensions)). A bot that dies does not resume: it runs its script again from the top when it respawns (see [Idle, home and death](death.md#idle-home-and-death)), also when it died before it resumed the script it was saved running.

## Chunk loading

A bot running a script keeps the chunk it stands in loaded, with a small chunk ticket that moves with it, so it keeps working when its owner walks away. The ticket makes the bot's chunk and the eight around it tick entities (a mob that steps into a chunk that does not tick entities stops for good), and loads the two rings past them. `goto` loads the chunks its path needs one stretch at a time, just before the bot enters them: the chunks within three of the straight way towards the target, 48 blocks ahead, and waits until they are loaded before it plans on (giving up with `not_loaded` after a minute). `botsLoadChunks = false` (config) turns both off: bots then stop when their chunk unloads, and `goto` fails with `not_loaded` when a chunk the straight way towards the target crosses in the next 48 blocks is not loaded.

A bot running the `idle.py` it fell back to (it has no script of its own, or that script ended, stopped or failed) holds its own ticket only while it walks: its `goto` home loads chunks as any other, but once it waits at home it lets its chunk go, so it stays loaded only while players keep it so. When the chunk unloads, the bot is saved with it and stops; when it loads again the bot carries on. Any other script, also one named `idle.py` run with `bot run`, keeps its chunk loaded all the time it runs.

`goto` may load chunks that were never generated, which generates them, as a player walking there would.
