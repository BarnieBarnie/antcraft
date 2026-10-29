# Cookbook

!!! note
    More recipes come later (#409).

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

A miner with a library function (`lib.shaft`, see Modules):

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
