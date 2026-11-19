# `fish`

```text
fish(n=1, at=None)
```

Fishes with a fishing rod by open water until it caught `n` items.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `n` | int | `1` | how many items to catch |
| `at` | [`Pos`](../types/pos.md), tuple or `None` | `None` | the water block to cast at; `None` chooses open water within 16 blocks |

## Returns

A [`Result`](../types/result.md); its `value` is how many items it caught, also when it failed.

## Pauses

Yes, until it caught `n` items or fails. A catch takes 5 to 30 seconds as a player's, less with Lure, and up to twice as long where the water does not see the sky.

## Cost

One instruction, the call itself. Its walk plans its path as [`goto`](../movement/goto.md) does, and its search for water reads blocks as [`scan`](../world/scan.md) does.

## Description

Without `at`, the bot looks for open water, a water source with air over it, among the 64 water blocks nearest it within 16 blocks, and chooses the one with the most open water around it, the nearest of those, so a cast a little off still lands in water. It walks to where it sees that block within 4 blocks of its eyes, as `goto` walks, and takes a fishing rod it carries in hand.

Then it fishes as a player does (#962): it faces the water and casts a real fishing hook of its own, as the rod casts a player's, at the pitch that lands it on the block. A fish bites as for a player, sooner with Lure and in the rain; as soon as one bites, the bot reels in and the catch, from the game's fishing loot table (fish, junk or treasure, with Luck of the Sea on the rod as for a player), flies to the bot, which picks it up. Each catch wears the rod by one, as a player's. It casts again until it caught `n` items. A hook that has not landed in water after 2 seconds it takes back without wearing the rod and casts again; after 3 casts in a row that miss, it gives up with `no_water`.

A rod that breaks is replaced by the next one the bot carries; when it carries none, the action fails with `rod_broke`. A catch counts once the bot picked it up; one it has no room for stays where it lands, and the action fails with `no_space` (#969). A bot gets no experience, statistics or advancements from fishing. A hook the bot reeled in out of a mob (the hook caught on it) pulls it, as a player's.

A bot at or below `botFleeHealth` with a hostile mob near it flees instead: the action ends with `interrupted` (see [Reflexes](../../features/bots.md#reflexes)).

An `n` under 1, or an `at` that is no position, stops the script with an error.

## Examples

```python
r = fish(5)
if not r:
    warn("fishing stopped after", r.value, "catches:", r.reason)
```

## Fails with

| Reason | When |
|---|---|
| `no_rod` | the bot carries no fishing rod |
| `no_water` | no open water within 16 blocks, `at` is no water, or 3 casts in a row missed the water |
| `rod_broke` | its last rod broke before it caught `n` items |
| `no_space` | the bot has no room for a catch, which stays where it landed |
| `wrong_dimension` | `at` is in another dimension than the bot |
| `unreachable`, `not_loaded`, `timeout` | the walk to the water stopped with it, as `goto` does |
| `interrupted` | a reflex took over, such as fleeing at low health |

## See also

[`jobs.fish`](../../cookbook/fish.md), [`eat`](../inventory/eat.md), [`lib/food`](../modules/lib/food.md).
