# Settle a new base

The job `jobs/settle` settles a base with an empty-handed bot: it gets a kit, levels a plot, builds the bundled starter house, places a chest and a crafting table beside it, plants a small wheat field round a water source, puts the rest away and makes the house its home. It is one [`lib/project`](../reference/modules/lib/project.md), so a run stopped halfway (a stop, a reload, a death) goes on at the step it was on when it is run again.

```text
/antcraft bot run jobs.settle
/antcraft bot run jobs.settle 10 64 10 16
/antcraft bot run jobs.settle 10 64 10 16 my:hut
```

The arguments are the lowest corner of the house `x y z` (by default where the bot stands, or where a stopped run started) how far to look for what it needs, in blocks (default 32), and the house's blueprint (default `antcraft:starter_house`; another blueprint that fits in 5 by 5 will do, with home at its first work spot, or beside the chest where it has none). The bot says one line per step it starts, `settle 3/7: hut`, then `settled`, or why not, such as `not settled: no_logs at hut, left ['hut', 'furnish', 'field', 'store', 'home']`.

The plot, from the corner `x y z` east and south:

| What | Where, from the corner |
|---|---|
| the house, `antcraft:starter_house` (5 by 4 by 5, door to the north) | `(0, 0, 0)` to `(4, 3, 4)` |
| a chest and a crafting table | `(6, 0, 0)` and `(6, 0, 1)` |
| the field, 3 by 3 soil | `(6, -1, 2)` to `(8, -1, 4)` |
| its water source, in the middle | `(7, -1, 3)` |
| home, inside behind the door (the house's first [work spot](../features/blueprints.md); beside the chest, `(5, 0, 0)`, for a house with none) | `(2, 1, 1)` |
| the levelled plot, a block round all of it | `(-1, -1, -1)` to `(9, -1, 5)`, cleared 6 high |

The steps, from `config/antcraft/scripts/jobs/settle.py`:

```python
steps = [Step("kit", lib.bootstrap.kit, (KIT, radius)),
         Step("flatten", flatten),
         Step("hut", build_here, (hut, at, 0, False, radius)),
         Step("furnish", furnish),
         Step("field", plant, tries=2, optional=True),
         Step("store", lib.storage.deposit, (None, radius), optional=True),
         Step("home", home_in)]
r = lib.project.run("settle", steps)
```

## How it works

- **kit**: [`lib.bootstrap.kit`](../reference/modules/lib/bootstrap.md) with the job's own `KIT`: stone pickaxe, axe, shovel and hoe, a crafting table, a chest and 16 dirt to climb on while building. Each comes through [`lib.get`](../reference/modules/lib/get.md): from a chest within the radius (borrowed), else gathered and crafted.
- **flatten**: [`lib.terrain.flatten`](../reference/modules/lib/terrain.md) levels the plot at the height under the corner, digging up to 6 blocks above it and filling holes up to 3 deep with dirt, getting more dirt when the dug blocks fall short.
- **hut**: [`lib.build.build_here`](../reference/modules/lib/build.md) gets what the starter house lacks (cobblestone, oak planks, a door, three straw beds) and builds it.
- **furnish**: [`place`](../reference/blocks/place.md)s the chest and the crafting table.
- **field** (optional): first a water bucket by `get` (from a chest; none is crafted or filled): the bot digs the middle of the field and pours the water into the hole, so the 8 blocks of farmland round it stay wet. Without a water bucket the field is planted dry: wheat keeps its farmland, but a block left bare dries out and turns back into dirt, and the wheat grows slowly. Then a hoe by `ensure_tool`, wheat seeds by `get`, and [`lib.farm.tend`](../reference/modules/lib/farm.md) tills and plants it. Where a block is left bare though the bot still carries seeds (it failed to till it, or stood in the way), the step is tried once more. A field that fails does not stop the base.
- **store** (optional): [`lib.storage.deposit`](../reference/modules/lib/storage.md) gives back what was borrowed and puts the rest but tools, torches and food into the chests, the new one too.
- **home**: [`set_home`](../reference/movement/set_home.md) at the house's first work spot, inside the starter house, or beside the chest for a house without one, and goes there.
- `lib.project.run` keeps the names of the steps done in `memory["projects"]["settle"]` and gives back what was borrowed at the end, also when a step failed. Once settled, the job forgets the project, so the next run settles anew; to start a stopped one over instead, `lib.project.reset("settle")`.

`SettleGameTest` runs the job with `antcraft:cobble_step` as the house, to keep it short, and the kit, seeds, a water bucket and the cobble step's materials in a chest: stopped as it starts the house and run again, it does not do the kit and the flattening a second time, and ends with the house standing, the bot home beside the chest (the cobble step has no work spot), the field planted round its water on wet farmland and the borrowed tools back in the chest. With no seeds and no water bucket, the field step fails and the bot still settles. Settling from nothing (no chest, gathering and crafting it all) is not tested: it takes far longer than a game test should.
