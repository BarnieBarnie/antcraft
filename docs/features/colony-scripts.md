# Default colony scripts

- `colony.lumberjack` fells the trees within the colony's radius, replants their saplings and puts the logs into the core.
- `colony.builder` takes `build` jobs (`post_job("build", {"blueprint": "antcraft:farm", "at": colony()["core"] + (4, -1, 0)})`), fetches the materials from the core and builds; what the core lacks it asks for with `haul` jobs, and waits. A building with a role is noted in `colony_memory["buildings"]`.
- `colony.hauler` takes `haul` jobs (an item, how many, and optionally from and to which container) and brings the items from the colony's chests and barrels to the core.
- `colony.farmer` tills, plants and harvests the work spots of the colony's farms, with a hoe and seeds from the core: wheat, carrots, potatoes and beetroot. It replants what it harvests and stores the harvest there.
- `colony.miner` mines through the colony's one shaft (`colony_memory["mineshaft"]`): the first miner picks a site beside the core and digs it with `lib/shaft.py`, then miners take turns mining its levels with `lib/mine.py` (`mine` jobs) and bring the ore to the core, with ladders, a trapdoor, torches, chests and pickaxes from the core.

The mod's default colony scripts do what the goal-driven colony did, with the API every script has, so a player gets a working colony out of the box and an example to change. Each runs on a colony member (`/antcraft bot run colony.lumberjack`, or a Bot Core with `/antcraft core script colony.lumberjack`), works alone, and works with the others through the job board and the core. In no colony, each says so and stops.

| Script | Does |
|---|---|
| `colony.lumberjack` | Fells the trees within the colony's radius: a trunk of logs standing on dirt with leaves at its top, so log walls are left alone. Mines it from the ground up, picks up what fell, plants a sapling it carries where the trunk stood and puts the logs into the core. With no tree left it waits 10 seconds and looks again |
| `colony.hauler` | Takes `haul` jobs and brings the items to the core. Without jobs it waits where it is; a build that needs its spot asks it to make way (see [Reflexes](bots.md#reflexes)) |
| `colony.builder` | Takes `build` jobs: takes the blueprint's materials from the core and builds. While the core lacks some, it posts a `haul` job for each (one per item and build) and tries again every two seconds; alone, it waits for a player to fill the core. A building with a role is added to `colony_memory["buildings"]` (the job's data). Another reason than `missing_ingredients` (such as `blocked`) gives the job back to the board, with a warning in its log, and it tries again 10 seconds later |
| `colony.farmer` | Farms the work spots (the soil blocks) of each building in `colony_memory["buildings"]` whose blueprint has the role `farm`: harvests ripe wheat, carrots, potatoes and beetroot, tills dirt and grass, replants each harvested spot with the same crop and other bare spots with the seeds it carries (wheat first), picks up what lies around and stores the harvest, the seeds the bare spots do not need and any poisonous potato in the core, keeping one carrot and one potato as seed, every 10 seconds. It takes a hoe and the seeds the bare spots need from the core |
| `colony.miner` | Mines through the colony's one shaft, kept in `colony_memory["mineshaft"]`: the first miner claims the site through the board (it posts a `shaft_site` job and takes it; of two posted at once the older wins, and others wait while one is taken), picks a site beside the core (`mineshaft(levels, near=core)`), posts a `shaft` job and digs it with `lib/shaft.py`; once dug, a `mine` job goes on the board for each level. A miner takes a `shaft` job before a `mine` job, mines one round of the level's branches with `lib/mine.py`, takes the ore from the landing chest to the core and posts the level again with the next round, so miners take turns and the level mined least comes first; others wait. Ladders, a wooden trapdoor for the hatch, torches, chests and a pickaxe come from the core; what the core lacks it logs as a warning and tries again 10 seconds later, posting no job for it. Set `colony_memory["mineshaft"]` before the first miner runs to choose the `levels` (default 16, -16 and -54), `branches` and `length` |

Their jobs' data, dicts of plain data:

| Kind | Data |
|---|---|
| `mine` | `level` (a height of the shaft) and `round` (optional, 0: the round of branches to mine). `shaft` and `shaft_site` jobs carry no data |
| `build` | `blueprint` (an id), `at` (the lowest corner), `turn` and `mirror` (optional, as `build` takes them) |
| `haul` | `item` (a selector: an item id, a tag such as `"#minecraft:logs"` or a list of these), `n` (optional; all found without it), `from` (optional: the container to take from; else every chest and barrel within the colony's radius that holds the item, nearest first) and `to` (optional: the container to bring to; else the core). The builder adds `for`, the id of its build job |

```python
post_job("build", {"blueprint": "antcraft:farm", "at": colony()["core"] + (4, -1, 0)})
post_job("haul", {"item": "minecraft:oak_log", "n": 16})
```

They share `colony/common.py`: `core()` (the core, or say and stop outside a colony), `in_colony(pos)`, `store(item)` (put all carried into the core) and `fetch(item, n)` (take from the core up to `n` carried; `item` may be a selector such as `"#minecraft:planks"`).
