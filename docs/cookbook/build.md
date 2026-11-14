# Build a blueprint

The job `jobs/build` builds a blueprint at a spot, getting what the site lacks first with [`lib/build`](../reference/modules/lib/build.md). A bot by a tree, some stone and a chest with a wooden pickaxe builds the cobble step, its cobblestone mined with the borrowed pickaxe and its oak stairs crafted from logs:

```text
/antcraft bot run jobs.build antcraft:cobble_step 10 64 10
```

The arguments are the blueprint's id, the lowest corner `x y z`, the quarters it is turned clockwise (default 0) and how far to look for materials, in blocks (default 32): `/antcraft bot run jobs.build antcraft:farm 10 64 10 1 48`. The bot says `built antcraft:cobble_step`, or why not, such as `not built antcraft:cobble_step: no_logs {'minecraft:oak_stairs': 1}`.

The job, `config/antcraft/scripts/jobs/build.py`:

```python
from lib.build import build_here

def run(id, at, turn=0, radius=32):
    """Builds the blueprint id at at, turned turn, and says whether it built it. Gives build_here's result()."""
    r = build_here(id, at, turn, radius=radius)
    say(f"built {id}" if r["ok"] else f"not built {id}: {r['reason']} {r['missing']}")
    return r


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    if len(args) < 4:
        say("usage: jobs.build <blueprint> x y z [turn] [radius]")
        stop()
    run(args[0], Pos(int(args[1]), int(args[2]), int(args[3])), int(args[4]) if len(args) > 4 else 0,
        int(args[5]) if len(args) > 5 else 32)
```

## How it works

- [`materials(id, at, turn)`](../reference/modules/lib/build.md#materialsid-at-turn0-mirrorfalse) is what the site still lacks, so a half-built blueprint costs only the rest.
- [`build_here`](../reference/modules/lib/build.md#build_hereid-at-turn0-mirrorfalse-radius32) gets each of them with [`lib.get.get`](../reference/modules/lib/get.md#getitem-n1-radius32): from a chest within `radius` (borrowed), by gathering (cobblestone from stone, with a pickaxe it borrows or makes) or by crafting. Then it [`build`](../reference/building/build.md)s.
- An item `get` cannot get does not stop it: it gets the rest and builds what it has, as far as the first block it lacks, then fails with `get`'s reason and what the bot lacks in `missing`. Without wood the cobble step gets its two cobblestone and stops at the stairs: `not built antcraft:cobble_step: no_logs {'minecraft:oak_stairs': 1}`.
- What it borrowed goes back at the end, also on a failure: the pickaxe to its chest, and on a failure the materials it took.

`BuildGameTest` runs the job as it is: a cobblestone mined with a borrowed pickaxe and built, the pickaxe given back; `build_here` with no wood about building the cobble step's two cobblestone and failing on its oak stairs; an unknown blueprint failing with `no_blueprint`; and a round that places nothing failing with `no_progress`.
