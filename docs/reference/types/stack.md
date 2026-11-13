# `Stack`

Items, as `inventory()`, `held()` and `container(pos)` give them. A snapshot.

- `item`: the item's id, such as `"minecraft:oak_log"`.
- `count`: how many.
- `damage`: how worn the item is, such as `10` for a stone pickaxe used ten times. 0 for an item that does not wear.
- `max_damage`: the damage at which the item breaks, such as `131` for a stone pickaxe. 0 for an item that does not wear. It lasts `max_damage - damage` more uses.
