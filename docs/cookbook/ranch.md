# Keep a ranch

The job `jobs/ranch` keeps cows, sheep, pigs and chickens in a fenced pen, for meat, leather and wool, and runs until a player stops it. It breeds a pair of each kind with its food while the kind is under its cap, kills the adults over the cap (never a baby), shears the sheep and stores what they drop. The pen is the player's: build a fence round it with a gate, and put the animals in (#837).

```text
/antcraft bot run jobs.ranch 10 64 10 15 64 14
/antcraft bot run jobs.ranch 10 64 10 15 64 14 6 16
```

The arguments are two opposite corners of the pen's inside `x y z x y z`, at the level the animals stand on, then the most adults of each kind it keeps (4 by default) and how far to look for chests, in blocks (default 32).

## The pen

| What | Where |
|---|---|
| the pen | inside the fence, between the two corners; the bot walks in and out through the gate, which it shuts behind it |
| the depot, a row of chests added as they fill ([`lib.depot`](../reference/modules/lib/depot.md)) | from the pen's low corner `(0, 0, -3)` going east, so outside a fence on the north side |

Only the animals inside the pen count: one that got out is left alone.

## The rounds

Each round, for each kind:

| Step | How |
|---|---|
| cull | the adults past the cap are killed with [`attack`](../reference/combat/attack.md), with an axe the bot has or takes from a chest (a sword's sweep would hurt the babies beside), then their drops picked up; a baby is never attacked |
| breed | while adults and babies are under the cap, pairs of adults are fed with [`use`](../reference/blocks/use.md#on-a-mob): wheat to cows and sheep, carrots to pigs, seeds to chickens, from the chests; each pair makes one baby. A parent breeds again only after 5 minutes, and a baby takes 20 minutes to grow up, so the bot just tries again each round |
| shear | each sheep is sheared, with shears the bot has or takes from a chest, and the wool picked up |

Then everything but the food, shears and weapons goes into the depot, and the bot waits 5 seconds. The food comes from the chests, so a [crop farm](crop-farm.md) storing wheat nearby keeps the cows and sheep breeding.

Not yet done: luring animals into the pen with their food, collecting eggs (chickens lay them on the ground; `pick_up` within the pen would do), milking, and feeding the pen from a farm's depot directly.
