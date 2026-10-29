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
