# Auto-farm

A wheat farmer: it tends the farmland around the chest it starts by, harvests ripe wheat, plants seeds where nothing grows, tills soil that was trampled back to dirt, and stores the wheat in the chest. Start it standing by a chest, next to a field of farmland with water, and put a hoe and some seeds in the chest.

```python
# A wheat farm: harvests ripe wheat on the farmland around the chest the bot
# starts by, replants it, and stores the wheat in the chest.
RIPE = 7  # the age of ripe wheat
SOIL = ("minecraft:dirt", "minecraft:grass_block")

if "field" not in memory:
    chest = find_nearest("chest", radius=8)
    field = scan("farmland", radius=8, limit=128)
    if chest is None or not field:
        say("stand me by a chest and a field of farmland")
        stop()
    memory["chest"] = chest
    memory["field"] = field
chest = memory["chest"]
field = memory["field"]


def tend(soil):
    """Harvests the wheat on soil when it is ripe, tills the soil and plants seeds."""
    crop = block(soil.up())
    if crop is None:
        return  # not loaded
    if crop.id == "minecraft:wheat" and crop.state["age"] >= RIPE:
        mine(soil.up())
    if block(soil).id in SOIL and block(soil.up()).is_air:
        use(soil, "#minecraft:hoes")
    if block(soil).id == "minecraft:farmland" and block(soil.up()).is_air and has("wheat_seeds"):
        place(soil.up(), "wheat_seeds")


while True:
    if not has("#minecraft:hoes"):
        take(chest, "#minecraft:hoes", 1)
    if count("wheat_seeds") < len(field):
        take(chest, "wheat_seeds", len(field) - count("wheat_seeds"))
    for soil in field:
        tend(soil)
    if has("wheat"):
        put(chest, "wheat")
    spare = count("wheat_seeds") - len(field)
    if spare > 0:
        put(chest, "wheat_seeds", spare)
    wait(1200)  # a minute; wheat takes several to grow
```

## How it works

- The field is picked once, with [`scan`](../reference/world/scan.md), and kept in [`memory`](../reference/types/memory.md) with the chest. Farmland that dries out or is trampled turns back into dirt, which a new scan would miss; the remembered field still has it, so the bot tills it again.
- [`block`](../reference/world/block.md) reads what grows on a spot: its `id`, and its `state["age"]`, 0 to 7 for wheat (see [`Block`](../reference/types/block.md)). `None` means the chunk is not loaded.
- [`mine`](../reference/blocks/mine.md) harvests; the wheat and seeds go straight into the bot's inventory.
- [`use`](../reference/blocks/use.md) with the selector `"#minecraft:hoes"` holds a hoe and right-clicks the soil, as a player tills it. The hoe wears down; the bot takes a new one from the chest when it has none.
- [`place`](../reference/blocks/place.md) plants seeds on bare farmland.
- [`take`](../reference/crafting/take.md) and [`put`](../reference/crafting/put.md) keep about one seed per spot on the bot and store the rest, and the wheat, in the chest. Both fail with `no_item` when there is nothing to move; the farm goes on without.

For carrots, potatoes and beetroot, and for colonies, see the default `colony.farmer` script ([Default colony scripts](../features/colony-scripts.md)), which grows all four, or the bundled job below.

## Used here

[`find_nearest`](../reference/world/find_nearest.md), [`scan`](../reference/world/scan.md), [`block`](../reference/world/block.md), [`memory`](../reference/types/memory.md), [`mine`](../reference/blocks/mine.md), [`use`](../reference/blocks/use.md), [`place`](../reference/blocks/place.md), [`has`](../reference/inventory/has.md), [`count`](../reference/inventory/count.md), [`take`](../reference/crafting/take.md), [`put`](../reference/crafting/put.md), [`say`](../reference/bot/say.md), [`wait`](../reference/bot/wait.md), [`stop`](../reference/bot/stop.md), [`len`](../reference/python/len.md).

## The bundled job

The mod bundles a farm job over [`lib/farm`](../reference/modules/lib/farm.md) as `jobs/farm.py`. Start it with `/antcraft bot run jobs.farm 10 63 10 14 63 14 carrot 0`: the two corners of the field's soil, the crop to plant (a crop, seed or harvest such as `wheat`, `carrot`, `potato` or `beetroot`; wheat by default) and how many rounds to tend it: 1 by default, `0` for round after round until a round fails or you stop the bot.

```python
import lib.farm
import lib.stock
from lib.borrow import borrowed, give_back, turned
from lib.get import ensure_tool, get, make_cost

WAIT = 100  # ticks between looks at the field while the crops grow
# The most ticks an empty bucket may cost to make for the water: a chest's iron, walked to and smelted, not an ore dig.
BUCKET_TICKS = 1000


def bucket_at_hand(radius=32):
    """Whether an empty bucket is carried, in a chest within radius, or made for at most BUCKET_TICKS (a chest's iron),
    so watering a field never digs for iron ore, thousands of ticks (#615)."""
    return (count("bucket") > 0 or len(lib.stock.where("minecraft:bucket", radius)) > 0
            or make_cost("bucket", 1, radius, None, BUCKET_TICKS) is not None)


def pour(at, radius=32, keep=None):
    """Pours a water source at at, a spot of soil, with a water bucket from lib.get, else an empty bucket from lib.get (a
    chest's, or made of three iron ingots) filled at the nearest water source but for those at keep, positions such as
    the field's own (#592). With no water source within radius, or no bucket at hand, no bucket is got and nothing
    poured. A borrowed water bucket is given back empty (#629). Gives whether water is at at."""
    if block(at).id == lib.farm.WATER:
        return True
    if (count("water_bucket") or get("water_bucket", 1, radius)["ok"]
            or lib.farm.water(radius, keep) is not None and bucket_at_hand(radius) and get("bucket", 1, radius)["ok"]
            and lib.farm.fill_bucket(radius, keep)["ok"]):
        mine(at)
        if use(at, "water_bucket"):  # aimed at the hole itself, so the water goes in, not over its rim
            turned("water_bucket", "bucket")
    return block(at).id == lib.farm.WATER


def farm(low, high, c, kept):
    """Waters a dry field (pour, a source per dry patch until none is dry), gets the hoe and seeds for crop c (of
    lib.farm.CROPS), tends the field, gives back what it borrowed and stores the harvest beyond what kept, (item, count)
    pairs, says the bot carried. Gives result()."""
    spots = lib.farm.field(low, high)
    # One source wets up to 9 by 9 round it: pour one where it wets the most dry spots, never filling at a source poured
    # in the field, until it is all wet or no water is left (#629). Each pour takes a spot, so this ends.
    poured = []
    at = lib.farm.source(spots)
    while at is not None and pour(at, 32, poured):
        poured.append(at)
        spots = lib.farm.field(low, high)
        at = lib.farm.source(spots)
    till = len([s for s in spots if block(s).id in lib.farm.SOIL and block(s.up()).is_air])
    if till:
        r = ensure_tool("hoe", till)
        if not r["ok"]:
            return r
    bare = len([s for s in spots if block(s.up()).is_air])
    if bare and count(c[1]) < bare:
        # ponytail: a field short of seeds is still tended, its ripe crops replanted from their own drops; tend says
        # what it lacked.
        get(c[1], bare)
    r = lib.farm.tend(low, high, c[1])
    # Carrots and potatoes are their own seed: give the borrowed ones back from the harvest before storing it, or the
    # chest they came from loses them.
    give_back()
    chests = lib.stock.chests()
    for item, held in kept:
        n = count(item) - held
        if chests and n > 0:
            put(chests[0], item, n)
    return r


def ripened(low, high):
    """Waits until every crop of the field is ripe; at once where none grows. ponytail: looks every WAIT ticks."""
    while True:
        crops = [s.up() for s in lib.farm.field(low, high) if not block(s.up()).is_air]
        if all([lib.farm.ripe(p) for p in crops]):
            return
        wait(WAIT)


def run(low, high, crop="wheat", rounds=1):
    """Tends the field from low to high, the corners of its soil, rounds times (0: until a round fails), planting crop,
    and gives back what it borrowed. Gives the last round's result(), None for no crop."""
    c = lib.farm.crop(crop)
    if c is None:
        warn("farm: no crop", crop)
        return None
    # What the bot carried of the harvest before the job: it stores only what it carries beyond that (#600).
    kept = [(item, count(item)) for item in [c[2], lib.farm.POISON]]
    try:
        n = 0
        while rounds == 0 or n < rounds:
            if n > 0:
                ripened(low, high)
            r = farm(low, high, c, kept)
            n += 1
            if not r["ok"]:
                warn("farm:", r["reason"], r["missing"])
                break
    finally:
        give_back()
        if borrowed():
            warn("could not give back", borrowed())
    return r


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    run(Pos(int(args[0]), int(args[1]), int(args[2])), Pos(int(args[3]), int(args[4]), int(args[5])),
        args[6] if len(args) > 6 else "wheat", int(args[7]) if len(args) > 7 else 1)
```

- [`field`](../reference/modules/lib/farm.md) lists the soil of the box: in each column, the top farmland, dirt or grass with air or a crop over it.
- Where [`dry`](../reference/modules/lib/farm.md#dryspots) finds soil with no water within 4 blocks, `pour` digs the spot [`source`](../reference/modules/lib/farm.md#sourcespots) names, where a source wets the most dry spots (the middle of a field all dry), and pours a water source into it, which wets up to 9 by 9 round it; on a bigger field it pours again, one source per dry patch, until `source` finds none dry or no water is left. It pours with a water bucket by `get`, else an empty bucket by `get` (from a chest, or made of three iron ingots where that costs at most `BUCKET_TICKS`, never an ore dig, as `bucket_at_hand` checks) that [`fill_bucket`](../reference/modules/lib/farm.md#fill_bucketradius32-keepnone) fills at the nearest water source, never one it poured in the field. A water bucket borrowed from a chest goes back to it as the empty bucket it leaves ([`turned`](../reference/modules/lib/borrow.md#turneditem-into-n1)). With neither, the field is tended dry. `jobs.settle` waters its field with the same `pour`.
- [`ensure_tool("hoe", till)`](../reference/modules/lib/get.md) gets a hoe only where there is soil to till: carried, borrowed from a chest, or made. [`get`](../reference/modules/lib/get.md) brings the seeds for the bare spots; where there are too few, the field is still tended and its ripe crops are replanted from their own drops.
- [`tend`](../reference/modules/lib/farm.md) harvests the ripe crops and replants them, tills the dirt and plants the rest; it says `no_item` where farmland was left bare, and the job stops there.
- [`give_back`](../reference/modules/lib/borrow.md) returns the hoe and the seeds before the harvest is stored: carrots and potatoes are their own seed, so the borrowed ones go back to the chest they came from, not into the nearest chest of [`lib/stock`](../reference/modules/lib/stock.md) with the rest of the harvest. The job stores only what the bot carries beyond what it held when it started, so carrots or potatoes it brought along stay with it.
- Between rounds `ripened` looks at the field every 5 seconds (100 ticks) until every crop on it is ripe; with none growing it tends the field at once.

`FarmGameTest` runs the bundled job over a 3 by 3 field around water, with a wooden hoe and seeds in a chest beside it: once, twice as the crops ripen between rounds, and with carrots borrowed from a chest farther away than another; over a dry field with an empty bucket in the chest and a pool nearby, which it waters from the pool first; over a dry field with a water bucket in the chest, which goes back empty; and over a dry row 11 long, which takes a source per dry patch, the bucket filled at a pool each time.
