# `Entity`

A mob, player, dropped item or other entity, as `entities()` and `items_near()` give it. Read live; equal by UUID.

- `id`: its UUID as a string.
- `type`: such as `"minecraft:cow"`.
- `pos`: its [`Pos`](pos.md).
- `health`: `None` for an entity without health.
- `baby`: true for a young animal or mob, such as a calf, which drops nothing when killed.
- `age`: the ticks since it was spawned or last loaded (`Entity.tickCount`). It is not saved, so it starts again from 0 when its chunk loads: among entities loaded together, a larger age is an older one (#871).
- `neutral`: true for a mob bots leave alone until it hurts them: an enderman, zombified piglin or piglin, which a blow turns on the bot with their kin, the warden, the wither or the ender dragon. The bot's own goals and [`jobs.guard`](../../cookbook/guard.md) both go by it (#772).
- `valid`: false once it is gone: dead, picked up or unloaded.
- `item`: the [`Stack`](stack.md) of a dropped item, `None` for other entities.
