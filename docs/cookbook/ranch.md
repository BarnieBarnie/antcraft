# Keep a ranch

The job `jobs/ranch` keeps cows, sheep, pigs and chickens in a fenced pen, for meat, leather, wool, eggs and milk, and runs until a player stops it. It lures animals outside the pen in with their food, breeds pairs of each kind while it has fewer babies than its cap, kills the adults over the cap once the babies grow up (never a baby), shears the sheep, milks the cows and stores what they give. The pen is the player's: build a fence round it with a gate (#837, #843).

```text
/antcraft bot run jobs.ranch 10 64 10 15 64 14
/antcraft bot run jobs.ranch 10 64 10 15 64 14 6 16
```

The arguments are two opposite corners of the pen's inside `x y z x y z`, at the level the animals stand on, then the most adults of each kind it keeps (by default as many as one bot keeps up with, below) and how far to look for chests, in blocks (default 32).

## The pen

| What | Where |
|---|---|
| the pen | inside the fence, between the two corners; the bot walks in and out through the gate, which it shuts behind it |
| the depot, a row of chests added as they fill ([`lib.depot`](../reference/modules/lib/depot.md)) | from the pen's low corner `(0, 0, -3)` going east, so outside a fence on the north side |

Only the animals inside the pen count towards the cap; one outside it, within the chest radius, is lured in (below).

## The rounds

Each round, for each kind:

| Step | How |
|---|---|
| lure | while the kind is under the cap, one of it outside the pen is led in: the bot takes its food from a chest and holds it, and an animal follows a bot holding its food as it follows a player. The bot walks to it, on its side of the fence (#874), then to the block outside the gate, opens the gate and waits in the pen's corner farthest from it until the animal is in and two blocks clear of the gate (at most 15 seconds), then shuts it. An animal does not find its way round a fence, so the bot leads it to the gate first |
| cull | the oldest adults past the cap, by their [`age`](../reference/types/entity.md), are killed with [`attack`](../reference/combat/attack.md), with an axe the bot has or takes from a chest (a sword's sweep would hurt the babies beside), then their drops picked up; a baby is never attacked |
| breed | while the kind has fewer babies than the cap, pairs of the adults within the cap are fed with [`use`](../reference/blocks/use.md#on-a-mob): wheat to cows and sheep, carrots to pigs, seeds to chickens, from the chests; each pair makes one baby. A parent breeds again only after 5 minutes, and a baby takes 20 minutes to grow up, so the bot just tries again each round. At the cap it still breeds: the grown babies are the adults past the cap that the next cull kills, so a full pen keeps making meat, up to as many a kind each 20 minutes as the cap (#864) |
| shear | each sheep is sheared, with shears the bot has or takes from a chest, and the wool picked up |
| milk | each cow is milked into an empty bucket from the chests, so the buckets put there set how much milk the ranch makes |

Then the bot picks up what lies in the pen or its fence, such as the eggs the chickens lay, everything but the food, shears, buckets and weapons goes into the depot, and the bot waits 5 seconds.

The food comes first from the chests of the bot's other depots ([`lib.depot`](../reference/modules/lib/depot.md)), however far: a bot that also runs a [crop farm](crop-farm.md) feeds the pen wheat, seeds and carrots from its harvest. Then it comes from the chests within the radius, such as another bot's crop farm depot nearby.

## The cap

By default the pen keeps as many adults of each kind as the bot tends in the 5 minutes before a parent can breed again, so each pair is fed as soon as it can be: a round takes some 62 ticks an adult (measured with 4 and 8 adults of each kind, feeding, shearing and milking, #855), so (6000 - 100) / (4 kinds x 62) = 23 a kind. No more than the kinds can share the pen's floor, a block an animal, with room for as many babies as adults: a pen of 8 by 8 keeps 8 a kind, one of 6 by 3 keeps 2. Never under a pair, the fewest that breed, so a pen smaller than 4 by 4 holds more animals than blocks (#864); give such a pen a cap, or build it bigger.

## Animals nearby

Every cow, sheep, pig and chicken follows any bot that holds its food, also a [crop farm](crop-farm.md)'s bot planting seeds or carrots; it still finishes its rounds with animals round it (#855). Penned animals stay in while the gate stands open for a lure: they follow the bot to its corner, away from the gate. Nor do they get out while the bot walks through the gate, however they crowd against it: a gate a bot opened to walk through stays shut to animals until it shuts it behind it (#863), and so does a wooden door in a pen's wall (#878; trapdoors not yet). A gate opened otherwise, such as for a lure, is open to them as usual, but an animal following a bot's food goes through it only the way it swung open, away from whoever opened it: a lured animal follows the bot in, and a penned one does not follow a bot outside that opened the gate out (#879).
