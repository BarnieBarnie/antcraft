# Output & logging

Every bot keeps a log, which `/antcraft bot [bots] log` shows. A script writes to it with five builtins, each at a level. A line goes to the log, and to the chat of the bot's owner when its level is at or above `scriptChatLevel` (see [Config](../features/config.md); default `error`).

| Call | Level | Chat (default) | Log |
|---|---|---|---|
| `print(...)` | always shown | yes | yes |
| `debug(...)` | `debug` | no | yes |
| `info(...)` | `info` | no | yes |
| `warn(...)` | `warn` | no | yes |
| `error(...)` | `error` | yes | yes |
| runtime and compile errors | `error` | yes | yes |
| action failures (`Result` with a reason) | `debug` | no | yes |

Each takes any number of values and writes them on one line, with a space between them, as Python's `print` does.

```python
print("starting with", count("#minecraft:logs"), "logs")   # starting with 3 logs
```

## Which one to use

- **`print`** is for the player: it always goes to both the log and the owner's chat, whatever the chat level.
- **`say(text)`**, from the bot API, does what `print` does and also floats the text above the bot for five seconds; a new `say` replaces it. Use it when the player should see which bot is talking.
- **`debug`** and **`info`** are for you while you write the script: what it is doing, step by step. They stay in the log, out of the chat, so leave them in.
- **`warn`** is for something odd that the script copes with.
- **`error`** is for something the player must fix. It only reports: it does not stop the script. Call `stop()` after it to end the script.

```python
chest = find_nearest("chest", radius=32)
for ore in scan("#c:ores/iron", radius=8):
    debug(f"mining {ore.x} {ore.y} {ore.z}")
    r = mine(ore)
    if not r:
        warn(f"skipped an ore: {r.reason}")
if chest is None:
    error("no chest to put the iron in")
    stop()
info(f"putting {count('#c:raw_materials/iron')} raw iron away")
put(chest, "#c:raw_materials/iron")
```

To see the `debug` lines in chat as well while you work on a script, lower `scriptChatLevel` to `debug`.

## What the log keeps

The log keeps a bot's last 200 lines and is saved with the bot, so `/antcraft bot log` still shows what happened before a restart. Every failed action is logged at `debug` with its reason, so the log shows why a bot stood still even where the script did not check. `/antcraft bot status` shows the last action the script finished with its result, such as `goto(Pos(4, 70, -2)) -> unreachable`. See [Output and logging](../features/scripts.md#output-and-logging).
