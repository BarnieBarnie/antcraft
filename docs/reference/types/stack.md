# `Stack`

Items, as `inventory()`, `held()` and `container(pos)` give them. A snapshot.

- `item`: the item's id, such as `"minecraft:oak_log"`.
- `count`: how many.
- `damage`: how worn the item is, such as `10` for a stone pickaxe used ten times. 0 for an item that does not wear.
- `max_damage`: the damage at which the item breaks, such as `131` for a stone pickaxe. 0 for an item that does not wear. It lasts `max_damage - damage` more uses.
- `enchantments`: a dict of the item's enchantments by id with their level, such as `{"minecraft:fortune": 3}`; of an enchanted book, the ones it stores, which an anvil puts on a tool. Empty for an item with none.
