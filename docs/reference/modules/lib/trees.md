# `lib/trees`

Finding and felling trees. A module of the mod's default library: `import lib.trees`, then `lib.trees.fell(lib.trees.next_tree())` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/trees.py`, written with [`scan`](../../world/scan.md), [`block`](../../world/block.md), [`mine`](../../blocks/mine.md), [`pick_up`](../../inventory/pick_up.md) and [`place`](../../blocks/place.md). The colony lumberjack (`colony.lumberjack`) is built on it.

## `next_tree(radius=32, where=None, remnants=False, around=None)`

The base, the lowest log, of the nearest tree within `radius` blocks of `around` (a [`Pos`](../../types/pos.md); `None`: the bot), or `None`. `where` is a function a base must pass, such as `in_colony` from `colony.common`; `None` takes any; [`lib.find.WHERE`](find.md#where) must pass too. With `remnants`, what is left of a tree whose bottom logs were taken (`is_remnant`) counts as a tree too. It looks within 8 blocks first, then 16, then `radius`, so a tree near by costs a few thousand blocks of the [block search budget](../../world/scan.md) rather than the 137,000 of the whole radius of 32; where there is none, it reads some 14% more than one search of the whole radius. Within each, it looks at the nearest 64 logs and, only where none of them is a tree that passes, at up to 4096, so a tree behind a dense forest's nearer logs is found too without every search reading the whole radius. It pauses only while its [`scan`](../../world/scan.md) does.

```python
import lib.trees

def near_home(p):
    return p.distance(home()) < 16

tree = lib.trees.next_tree(where=near_home)
giant = lib.trees.next_tree(where=lib.trees.is_giant, around=Pos(100, 64, -20))
```

## `is_tree(base)`

Whether the trunk with its lowest log at `base` is a tree: it stands on `GROUND` (dirt, or nylium for a huge fungus) and has a `CROWN` (leaves, or a huge fungus's wart blocks) at its top, so a log wall or a log cabin is not. A huge fungus's stem on netherrack is a tree too: the nylium under a stem turns to netherrack in the dark, though no fungus is replanted there. The top is reached log by log up, straight up first, else at an edge or corner over the last log, so the trunk of an acacia, which bends with its logs touching only at a corner, is a tree too. Done at once.

## `tree_logs(logs)`

The logs of the list `logs` that are part of a tree: those whose trunk, followed down log by log (straight down first, else at an edge or corner, as up a bending acacia), stands as a tree (`is_tree`) or is what is left of one (`is_remnant`). A log wall's or a cabin's logs are left out. Each log down to its base is read once, those passed on the way sharing the answer. The colony lumberjack counts the logs around a tree with it when it weighs an axe. Done at once.

## `fell(base, replant=True)`

Mines the trunk at `base` from the ground up, picks up the logs and saplings that fell and, with `replant`, plants a sapling the bot carries where the trunk stood. It stops at a log it cannot mine; whether `base` is still a log says so.

- **Returns** `None`.
- **Pauses** yes, while it walks, mines and picks up.

## `logs(n, radius=32, kind=LOGS)`

Gets logs that `kind` selects (a block id such as `"spruce_log"`, or a tag; any log by default) from trees within `radius` blocks until the bot carries `n` more, one tree at a time, the nearest first, so it does not walk to and fro between trees. Where it needs all of a tree's logs (`tree_blocks`), it fells the whole tree with `fell_whole`, replanting it; where it needs fewer, it mines only the bottom ones of one trunk column it needs, from the ground, and leaves the rest standing. What is left of such a tree it takes first the next time (`next_tree(remnants=True)`), before it starts on another tree. A log wall or cabin is no tree, so it is left alone; a tree it cannot get a log from is skipped, and so is a tree with its base in `KEEP`. Of a 2x2 tree it needs only part of, it takes a column at a time.

`KEEP` is a dict of positions `logs` and `low_log` leave alone, empty by default. A script that means to fell a tree itself puts the tree's logs in it while it gets its tools, so [`get`](get.md) or [`ensure_tool`](get.md) do not take the logs for an axe from that tree, and clears it after:

```python
for p in lib.trees.tree_blocks(base):
    lib.trees.KEEP[p] = True
try:
    ensure_tool("axe", 30)
finally:
    lib.trees.KEEP.clear()
```

- **Returns** a dict: `ok`, `reason` (`None`, `no_logs` once no tree it can take from is left, or `no_space` with a full inventory), `at` (where `no_space` struck) and `missing` (`{kind: k}`, the logs it still lacks, with `no_logs`).
- **Pauses** yes, while it walks, mines and picks up.

```python
import lib.trees

r = lib.trees.logs(3)
if not r["ok"]:
    say("no logs: " + r["reason"])
r = lib.trees.logs(6, kind="spruce_log")
```

## `tree_blocks(base)`

The logs of the tree at `base`, `base` first: those of `base`'s block (a spruce log for a spruce) reached from it log by log, diagonal steps too, as acacia and jungle branches touch only at a corner, at most 512. A tree of another kind next to it is left out; one of the same kind whose logs touch it is taken as the same tree. Done at once, in some 580 instructions a log (a `block` read is 30): 29 ticks of a bot's budget for a jungle giant of 100 logs.

## `is_giant(base)`, `square(base)`

Whether the trunk `base` is a bottom log of is 2x2, as a giant spruce's, jungle tree's or dark oak's; and its four bottom logs, or `None` for a trunk one log wide. Done at once.

## `stands(blocks)`

Where to stand to mine the logs in the list `blocks`: few spots that together reach every log, chosen greedily, each the spot that reaches the most logs not yet reached less the blocks the bot must place to stand there, a spot on one of the logs first on a tie, then the one that reaches more logs (so up a trunk it picks one stand high up, not one a block higher each time: 4 stands for two trunks of 12 logs, not 14), among the spots that reach a log not yet reached. The spots looked at are the air above and beside each log; a log none reaches is left out. A spot reaches a log as `mine` measures it: 4 blocks from the bot's eyes to the log's centre. Done at once; for a jungle giant of 100 logs (232 spots looked at) some 450,000 instructions, 225 ticks of a bot's budget at the default 2000 a tick: a few percent of the thousands of ticks felling it takes (#522).

## `fell_whole(base, replant=True, blocks=None, plan=None)`

Fells the whole tree at `base`, branches and 2x2 trunk too: it goes to each of `stands(tree_blocks(base))`, the highest first, so each stand on the tree's own logs is mined last, and mines the logs it reaches from there, the highest first. Then it picks up what fell, takes back the scaffold blocks its walks placed around the tree (those it pillared up to logs lying on the leaves too) and, with `replant`, plants a sapling of the tree's kind (`sapling` of its log, else any sapling it carries, or any fungus on nylium, where no sapling grows) on each spot of the trunk's bottom that is air over `GROUND`: four for a 2x2 trunk, so a giant tree grows again, and a crimson or warped fungus on nylium. `blocks` and `plan`, where given, are `tree_blocks(base)` and `stands(blocks)`, so a script that worked them out already, as the colony lumberjack does to know how high it climbs, does not again.

It does not fetch an axe: it uses the best one the bot carries, or its hand (see `lib.tools`). For a tree taller than it reaches from the ground it needs blocks to pillar with, those of the item tag `antcraft:scaffold_blocks` (see [`goto`](../../movement/goto.md)); it takes back exactly the blocks its walks placed that have not changed since, as [`placed_scaffold`](../../movement/placed_scaffold.md) gives them, also when it stops early with `no_space` or `out_of_reach`. A block someone else put by the tree meanwhile stays, even in a spot where its own block was until they mined it, and so does one that walks placed before `fell_whole` began: it calls `placed_scaffold()` first, which forgets those.

- **Returns** a dict: `ok`, `reason` (`None`, `out_of_reach` with `at` a log it could not mine, or `no_space` with `at` a log whose drops did not fit).
- **Pauses** yes, while it walks, mines, picks up and plants.

```python
import lib.trees
import lib.tools

base = lib.trees.next_tree()
if base is not None and lib.trees.is_giant(base):
    lib.tools.hold("axe")
    r = lib.trees.fell_whole(base)
    if not r["ok"]:
        say("left a log at " + str(r["at"]))
```

## `take_back()`

Mines the blocks the script's walks placed since it last called [`placed_scaffold`](../../movement/placed_scaffold.md), the highest first: only those unchanged since, so a block someone else mined or put there meanwhile stays. Then the blocks its walks to those placed, up to four rounds, so none is left; a block it could not reach (`out_of_reach`, once the blocks it was reached from were gone) is tried again in the next round, from where those walks take it. `fell_whole` ends with it.

- **Returns** `None`.
- **Pauses** yes, while it walks and mines.

## `low_log(radius=32, skip=None)`

The lowest log within 8 blocks, else within 16, else within `radius`, the nearest among those, leaving out the positions in the dict `skip` and in `KEEP`; `None` if there is none. Looking near first, a log by the bot costs some 2,000 blocks of the [block search budget](../../world/scan.md) rather than the 137,000 of the whole radius of 32.

## `is_remnant(base)`, `crowned(base)`

Whether the trunk with its lowest log at `base` hangs over air with a `CROWN` at its top, and no log of its block under it at an edge or corner (so a branch of a tree is no remnant), what `logs` leaves of a tree it took the bottom of; and whether it has a `CROWN` at its top, the top found as `is_tree` finds it. Done at once.

## `is_log(p)`, `is_kind(p, kind)`, `on_ground(p)`, `below(p)`, `trunk_base(log)`, `trunk(base)`, `reaches(feet, p)`, `pillar(spot)`

Whether the block at `p` is a log, whether the block at `p` is `GROUND`, the block of `p`'s kind under `p` (straight under first, else at an edge or corner) or `None`, and whether it is what `kind` selects (a block id or a tag); the lowest log of the trunk `log` is in, and the logs of the same block straight up from `base`, `base` first; whether a bot with its feet at `feet` reaches the block at `p`; and how many blocks a bot must place under `spot` to stand there (99 for more than 16). Done at once.

## `sapling(log)`

The item that grows the tree of `log`, a block id such as `"minecraft:spruce_log"`: `SAPLING`'s for the vanilla logs whose sapling is no `_sapling` (`mangrove_propagule`, `crimson_fungus`, `warped_fungus`), else the log's `_log` made `_sapling` where that item is in `#minecraft:saplings`, else None, as for a log of a mod whose sapling is named otherwise. A script adds its own: `lib.trees.SAPLING["mymod:ebony_log"] = "mymod:ebony_seed"`.

## `LOGS`, `SAPLINGS`, `FUNGI`, `SEEDLINGS`, `SCAFFOLD`, `GROUND`, `CROWN`

The selectors `"#minecraft:logs"` and `"#minecraft:saplings"`; `FUNGI`, the crimson and warped fungus, and `SEEDLINGS`, `SAPLINGS` and `FUNGI` together, what grows any tree, which the colony lumberjack keeps; `GROUND`, the tags a tree stands on (`#minecraft:dirt`, `#minecraft:nylium`), and `CROWN`, those that crown it (`#minecraft:leaves`, `#minecraft:wart_blocks`), lists a script may add a mod's tags to; and the blocks of the item tag `antcraft:scaffold_blocks` as the server's data packs have it when the module is imported ([`tag_items`](../../inventory/tag_items.md)), which walks pillar and bridge with.

## Example

Fell every tree within 16 blocks, then store the logs:

```python
import lib.trees

tree = lib.trees.next_tree(radius=16)
while tree is not None:
    lib.trees.fell(tree)
    if lib.trees.is_log(tree):
        break  # could not fell it
    tree = lib.trees.next_tree(radius=16)
put(memory["chest"], lib.trees.LOGS)
```

## See also

[`scan`](../../world/scan.md), [`mine`](../../blocks/mine.md), [Default colony scripts](../../../features/colony-scripts.md).
