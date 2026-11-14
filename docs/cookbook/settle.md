# Settle a new base

The job `jobs/settle` settles a base with an empty-handed bot: it gets a kit, levels a plot, builds the bundled starter house, places a chest and a crafting table beside it, plants a small wheat field, puts the rest away and makes the house its home. It is one [`lib/project`](../reference/modules/lib/project.md), so a run stopped halfway (a stop, a reload, a death) goes on at the step it was on when it is run again.

```text
/antcraft bot run jobs.settle
/antcraft bot run jobs.settle 10 64 10 16
```

The arguments are the lowest corner of the house `x y z` (by default where the bot stands, or where a stopped run started) and how far to look for what it needs, in blocks (default 32). The bot says one line per step it starts, `settle 3/7: hut`, then `settled`, or why not, such as `not settled: no_logs at hut, left ['hut', 'furnish', 'field', 'store', 'home']`.

The plot, from the corner `x y z` east and south:

| What | Where, from the corner |
|---|---|
| the house, `antcraft:starter_house` (5 by 4 by 5, door to the north) | `(0, 0, 0)` to `(4, 3, 4)` |
| a chest and a crafting table | `(6, 0, 0)` and `(6, 0, 1)` |
| the field, 3 by 3 soil | `(6, -1, 2)` to `(8, -1, 4)` |
| home, inside behind the door | `(2, 1, 1)` |
| the levelled plot, a block round all of it | `(-1, -1, -1)` to `(9, -1, 5)`, cleared 6 high |

The steps, from `config/antcraft/scripts/jobs/settle.py`:

```python
steps = [Step("kit", lib.bootstrap.kit, (KIT, radius)),
         Step("flatten", flatten),
         Step("hut", build_here, (HUT, at, 0, False, radius)),
         Step("furnish", furnish),
         Step("field", plant, optional=True),
         Step("store", lib.storage.deposit, (None, radius), optional=True),
         Step("home", home_in)]
r = lib.project.run("settle", steps)
```

## How it works

- **kit**: [`lib.bootstrap.kit`](../reference/modules/lib/bootstrap.md) with the job's own `KIT`: stone pickaxe, axe, shovel and hoe, a crafting table, a chest and 16 dirt to climb on while building. Each comes through [`lib.get`](../reference/modules/lib/get.md): from a chest within the radius (borrowed), else gathered and crafted.
- **flatten**: [`lib.terrain.flatten`](../reference/modules/lib/terrain.md) levels the plot at the height under the corner, digging up to 6 blocks above it and filling holes up to 3 deep with dirt, getting more dirt when the dug blocks fall short.
- **hut**: [`lib.build.build_here`](../reference/modules/lib/build.md) gets what the starter house lacks (cobblestone, oak planks, a door, three straw beds) and builds it.
- **furnish**: [`place`](../reference/blocks/place.md)s the chest and the crafting table.
- **field** (optional): a hoe by `ensure_tool`, wheat seeds by `get`, then [`lib.farm.tend`](../reference/modules/lib/farm.md) tills and plants the 3 by 3. It has no water, so the wheat grows slowly. A field that fails does not stop the base.
- **store** (optional): [`lib.storage.deposit`](../reference/modules/lib/storage.md) gives back what was borrowed and puts the rest but tools, torches and food into the chests, the new one too.
- **home**: [`set_home`](../reference/movement/set_home.md) inside the house, and goes there.
- `lib.project.run` keeps the names of the steps done in `memory["projects"]["settle"]` and gives back what was borrowed at the end, also when a step failed. Once settled, the job forgets the project, so the next run settles anew; to start a stopped one over instead, `lib.project.reset("settle")`.

`SettleGameTest` runs the job as it is, with the kit, seeds and the house's materials in a chest: stopped as it starts the house and run again, it does not do the kit and the flattening a second time, and ends with the house standing, the field planted and the borrowed tools back in the chest.
