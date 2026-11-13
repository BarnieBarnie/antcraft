# Strip-mine for an ore

A job that digs for an ore until the bot carries enough of it, then climbs back up and goes home. It starts with a small miner's kit, a stone pickaxe, a crafting table and 16 torches, taken from what is around, and keeps a pickaxe that lasts by making a new stone one from the cobblestone it dug. The mod bundles it as `jobs/strip_mine.py`; start it with `/antcraft bot run jobs.strip_mine raw_iron 12`. The words are the ore, a raw item such as `coal`, `raw_iron` or `diamond`; how many, 8 by default; the height to mine at, by default where the ore is most common ([`best_y`](../reference/modules/lib/dig.md)); how far to look for chests, 32 blocks; and the way it digs, `north` by default, or `south`, `east` or `west`. Emerald has no default height, as it is only in mountains: give it one.

```python
import lib.bootstrap
import lib.dig
import lib.tools
from lib.borrow import borrowed, give_back
from lib.get import ensure_tool
from lib.home import go_home
from lib.result import result

# The kit a miner starts with: a pickaxe, a crafting table to make the next one with, torches for the branches.
MINER = [("stone_pickaxe", 1), ("crafting_table", 1), ("torch", 16)]
# How long each branch is, one torch at its end, and how many it digs before it gives up.
LENGTH = 8
ROUNDS = 16
# The blocks a branch breaks: its tunnel two blocks tall and a few ore. ponytail: a guess, not counted from the veins.
USES = 2 * LENGTH + 8

item = lib.dig.short(ARGS[0])
n = int(ARGS[1]) if len(ARGS) > 1 else 8
y = int(ARGS[2]) if len(ARGS) > 2 else lib.dig.best_y(item)
radius = int(ARGS[3]) if len(ARGS) > 3 else 32
facing = ARGS[4] if len(ARGS) > 4 else "north"


def tier():
    """The pickaxe tier the ore of item needs ("iron" for diamond), stone at least: the kit's."""
    t = lib.tools.tier_for(item.replace("raw_", "").replace("_lazuli", "") + "_ore")
    return min([t, "stone"], key=lib.tools.TIERS.index)


def strip_mine():
    """Gets the kit, digs stairs down to y towards facing and branches on until the bot carries n of item, making sure before the stairs
    and each branch that its pickaxe lasts them, then climbs back up and goes home. Gives result()."""
    if item not in lib.dig.ORES:
        return result("no_recipe", None, {"minecraft:" + item: n})
    if y is None:
        return result("no_ore", None, {"minecraft:" + item: n})  # as lib.dig.ore: no height to dig to (emerald)
    r = lib.bootstrap.kit(MINER, radius)
    if r["ok"]:
        r = ensure_tool("pickaxe", 3 * max(pos().y - y, 0), tier(), radius)
    if r["ok"]:
        r = lib.dig.stairs_to(y, facing)
    for _ in range(ROUNDS):
        if not r["ok"] or count(item) >= n:
            break
        # A new pickaxe where the one it holds would break in the branch: stone from the cobblestone it dug.
        r = ensure_tool("pickaxe", USES, tier(), radius)
        if r["ok"]:
            r = lib.dig.branch(LENGTH, facing=facing)
    if lib.dig.stairs() is not None:
        up = lib.dig.stairs_up()
        r = r if not r["ok"] else up
    go_home()
    if r["ok"] and count(item) < n:
        return result("no_ore", None, {"minecraft:" + item: n - count(item)})
    return r


try:
    r = strip_mine()
    if r["ok"]:
        print(f"strip mined {count(item)} {item}")
    else:
        warn("strip mine:", r["reason"], r["at"], r["missing"])
finally:
    give_back()
    if borrowed():
        warn("could not give back", borrowed())
```

## How it works

- [`kit(MINER)`](../reference/modules/lib/bootstrap.md) makes sure the bot carries the kit: a carried or chest pickaxe of at least stone does, the rest it gets with [`get`](../reference/modules/lib/get.md), borrowing from chests or making it.
- [`stairs_to`](../reference/modules/lib/dig.md) digs a staircase down to the height, towards `facing`, and remembers its top; [`branch`](../reference/modules/lib/dig.md) digs a tunnel two blocks tall, 8 blocks at a time, mining each ore vein it uncovers and putting a torch at the end. The job branches on until the bot carries enough, at most 16 times.
- Before the stairs and before each branch, [`ensure_tool`](../reference/modules/lib/get.md) makes sure the pickaxes the bot carries last the blocks it will break: about three a step, and twice the branch's length plus a few for ore. Where they do not, it takes the cheapest that does, usually a new stone pickaxe from the cobblestone the stairs and branches gave and the crafting table of the kit. An ore that needs a better pickaxe, such as diamond, asks for that tier ([`tier_for`](../reference/modules/lib/tools.md)).
- [`stairs_up`](../reference/modules/lib/dig.md) walks back up the staircase, also when a branch failed, and [`go_home`](../reference/modules/lib/home.md) walks home.
- [`give_back`](../reference/modules/lib/borrow.md) in `finally` returns what was borrowed, also when a step failed or the script stopped with an error.
- A step that fails ends the job with a `warn` of its reason, such as `no_tool`, or `no_ore` with what is missing where 16 branches found too little or there is no height to dig to (emerald without `y`), which the bot's log shows (`/antcraft bot log`).

`StripMineGameTest` runs the bundled job in a test area with stone filled under its floor and three coal ore beside where the branch goes, as `jobs.strip_mine coal 3 <y> 8` with `y` three blocks under the bot: the bot ends at the top of its stairs with the coal. Another starts at the west edge with `east` and mines the coal beside its eastward branch. A third run starts with a pickaxe ten uses from breaking and two sticks: the job makes a new stone pickaxe before the branch, and the old one breaks. A fourth asks for emerald without `y` and gets `no_ore`. The bots carry the kit already, so the test does not bootstrap one.

## See also

- [Bootstrap a bot from nothing](bootstrap.md), the full starting kit
- [Library modules](../reference/index.md#library-modules) and the plan in [The script library](../design/library.md)
