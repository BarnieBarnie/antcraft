# `Entity`

A mob, player, dropped item or other entity, as `entities()` and `items_near()` give it. Read live; equal by UUID.

- `id`: its UUID as a string.
- `type`: such as `"minecraft:cow"`.
- `pos`: its [`Pos`](pos.md).
- `health`: `None` for an entity without health.
- `valid`: false once it is gone: dead, picked up or unloaded.
- `item`: the [`Stack`](stack.md) of a dropped item, `None` for other entities.
