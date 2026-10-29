# Cookbook

Recipes: complete scripts that combine builtins into useful bots, each with how it works and links to the reference pages it uses.

- [Recover items after death](recover-items.md): `deaths`, `last_death` and `memory` bring a lumberjack back for what it dropped.
- [Auto-farm](farm.md): harvest, till and replant a wheat field, and store the wheat.
- [Mining loop](mining-loop.md): a shaft dug with `lib/shaft`, its levels mined in turns with `lib/mine`, the ore brought up.
- [Keep a chest stocked](stock-chest.md): a hauler that tops a chest up from a barrel.
- [Survive reloads and respawns](memory.md): a tunnel digger that keeps its progress in `memory`.

Each recipe's script also runs as it is in a game test (`CraftGameTest` for the chest, `CookbookGameTest` for the farm, the item recovery and the tunnel), so a change of the API that breaks one shows. The mining loop only compiles: its shaft and levels go far deeper than a test area of 8 by 8 by 8 blocks, and `ShaftGameTest` and `MineGameTest` test `lib/shaft` and `lib/mine` that it runs on.

Two short examples:

Punch a tree, then make a wooden pickaxe:

```python
tree = find_nearest("#minecraft:logs")
if not tree:
    say("no tree near")
    stop()
goto(tree, near=2)
while count("#minecraft:logs") < 3:
    log = find_nearest("#minecraft:logs", radius=6)
    if not log or not mine(log):
        break
pick_up("#minecraft:logs")
craft("oak_planks", 9)
craft("stick", 2)
table_spot = pos().north(2)
craft("crafting_table") and place(table_spot, "crafting_table")
r = craft("wooden_pickaxe", table=table_spot)
say("pickaxe!" if r else f"no pickaxe: {r.reason}")
```

A miner with a library function (`lib.shaft`, see [Library modules](../reference/index.md#library-modules)):

```python
import lib.shaft

r = lib.shaft.dig_shaft([40, 20])
while not r["ok"]:
    say(f"shaft stopped: {r['reason']} {r['missing']}")
    wait(200)  # give it what it lacks meanwhile
    r = lib.shaft.dig_shaft([40, 20])
chest = lib.shaft.shaft()["level"][20]["chest"]
for ore in scan("#c:ores/iron", radius=8):
    mine(ore)
put(chest, "#c:raw_materials")
lib.shaft.to_surface()
```
