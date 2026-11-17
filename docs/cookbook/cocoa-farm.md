# Grow a cocoa farm

The job `jobs/cocoa_farm` grows a cocoa farm, for brown dye and cookies, and farms it until a player stops it. A pod grows on the side of a jungle log and is planted from its own beans, so the farm keeps itself in beans; each time the bot has or can get the jungle logs and beans one more cell takes, it builds it. It is a spec of [`lib/cellfarm`](../reference/modules/lib/cellfarm.md) (#833), the engine the [wood farm](wood-farm.md) runs on too, so its layout, depot, growth and rounds are the same; it is one [`lib/project`](../reference/modules/lib/project.md), so a stopped run goes on where it was.

```text
/antcraft bot run jobs.cocoa_farm
/antcraft bot run jobs.cocoa_farm 10 64 10
/antcraft bot run jobs.cocoa_farm 10 64 10 4 16
```

The arguments are the farm's corner `x y z` (by default where the bot stands, or where the last run started; another corner moves the farm there), the most cells it grows to (by default as many as keep growing while the bot stands at its corner, [`limit`](../reference/modules/lib/cellfarm.md#limitf-name-size)) and how far to look for what it needs, in blocks (default 32). The bot says `built cell 2` and `cells 2/12` as the farm grows, and `farm full` once it has all its cells.

## A cell

A cell is three floor blocks in a row going east, the floor being the block under the corner: a column of two jungle logs on the middle one, a cocoa pod on its west and east side at each height, four pods in all.

| What | Where, from the cell's low corner |
|---|---|
| jungle logs | `(1, 1, 0)` and `(1, 2, 0)` |
| pods, facing the logs | `(0, 1, 0)`, `(0, 2, 0)`, `(2, 1, 0)` and `(2, 2, 0)` |
| the next cells | 4 to a row going east, rows going south, a block of path between cells ([`lib.cells`](../reference/modules/lib/cells.md)) |
| the depot, a row of chests added as they fill ([`lib.depot`](../reference/modules/lib/depot.md)) | from the corner `(0, 0, -2)` going east |

To build one, the bot clears the two blocks over the floor ([`lib.terrain.flatten`](../reference/modules/lib/terrain.md)), stands the two logs, and places a bean on each side of them with [`place`](../reference/blocks/place.md)'s `facing`, the way the pod faces its log. A cell takes 2 jungle logs and 4 beans, from what the bot carries, the chests hold or [`lib.get`](../reference/modules/lib/get.md) gets, such as logs felled from a jungle tree or kept by a jungle [wood farm](wood-farm.md).

## The rounds

Each round, the ripe pods (age 2) are broken, the bot picks up their 3 beans each and places one back on the log; unripe pods are left to grow. The beans and the logs go in the depot. A log face that lost its pod gets one again from the depot. Once the bot has or can get a cell's 2 logs and 4 beans, the next round builds the next cell. A pod ripens in some 14000 ticks, about 12 minutes; a round that broke nothing waits in the corner's chunk, where the cells get the random ticks they grow on.
