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

For carrots, potatoes and beetroot, and for colonies, see the default `colony.farmer` script ([Default colony scripts](../features/colony-scripts.md)), which grows all four.

## Used here

[`find_nearest`](../reference/world/find_nearest.md), [`scan`](../reference/world/scan.md), [`block`](../reference/world/block.md), [`memory`](../reference/types/memory.md), [`mine`](../reference/blocks/mine.md), [`use`](../reference/blocks/use.md), [`place`](../reference/blocks/place.md), [`has`](../reference/inventory/has.md), [`count`](../reference/inventory/count.md), [`take`](../reference/crafting/take.md), [`put`](../reference/crafting/put.md), [`say`](../reference/bot/say.md), [`wait`](../reference/bot/wait.md), [`stop`](../reference/bot/stop.md), [`len`](../reference/python/len.md).
