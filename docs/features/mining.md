# Mining

The mod's library modules `lib/shaft.py` and `lib/mine.py` dig a ladder mineshaft and mine its levels, built on the senses `mineshaft`, `mineshaft_at` and `ore_vein` (see the [API reference](../reference/index.md#mining)). `colony.miner` mines with them for a colony (see [Default colony scripts](colony-scripts.md)).

## lib/shaft.py

`lib/shaft.py` digs the ladder shaft `mineshaft` describes (see [`mineshaft`](../reference/mining/mineshaft.md)) and takes the bot up and down it:

| Function | |
|---|---|
| `dig_shaft(levels)` | digs the shaft down to the heights `levels`, top down: each block of the column with a ladder in it, a wooden trapdoor facing the ladders shut in the top half of the hatch over them, flush with the ground (bots open it to climb through, mobs and players do not fall in), and at each level its chamber with the chest and a torch. Before it mines a block it stands on it, and once in, it makes the blocks round it solid with a block it dug (cobblestone and the like): water, lava and air are walled off, falling blocks (gravel, sand) dug out first, and what fell in meanwhile is dug again. What flows into a block it dug before it got in, such as a pond beside the hatch, is walled off where it comes in from, and the block drains. A bot places no block where it stands, so the ladders go in two blocks above it as it goes down, and the last two from the deepest chamber, where it ends. A reflex that takes over (a mob, lava) is waited out and the step tried again. Bots fight the hostile mobs they see; a mob that keeps standing where a block goes (an animal, a creeper) stops it with `blocked` after some tries, and `dig_shaft` called again goes on once it moved. Gives a dict: `ok`, `reason` (`None`, or why it stopped: `no_site` where `mineshaft` finds none, `missing_ingredients`, `blocked`, or an action's reason), `at` (the block it stopped at, or `None`) and `missing` (a dict of item to how many it lacks: ladders, a trapdoor (`#minecraft:wooden_trapdoors`), torches and chests for what is left to do, a pickaxe (`#minecraft:pickaxes`), or `minecraft:cobblestone` when it has nothing left to wall off with). It checks what it carries before it digs at all, so a bot short of ladders stops at once and goes on once given them; making what it lacks is the caller's work |
| `to_level(level)`, `to_surface()` | climb to the chamber of a level, or out onto the ground beside the top; give `goto`'s result |
| `shaft(levels=None)` | the shaft as `mineshaft` gives it: the one kept in `memory["shaft"]` (its `top` and `levels`), else a new site for `levels`, which is kept there. Forget it with `memory.pop("shaft")` to dig another |

How far the shaft is dug is read from the world, not kept, so `dig_shaft` called again after a restart, a reload or a failure goes on where it stopped; a bot saved in the middle of it resumes in place (see [Save and resume](scripts.md#save-and-resume)).

## lib/mine.py

`lib/mine.py` mines the levels of the shaft `lib/shaft.py` dug, the one in `memory["shaft"]`:

| Function | |
|---|---|
| `mine_level(level, rounds=1)` | digs the hall of `level` from its landing and the next `rounds` rounds of branches off it (see `mineshaft_at`), 2 high, each block from the one before it. It mines every ore vein (`ore_vein`) in the walls and ceiling of what it digs, walls off what each block it digs opens up as `dig_shaft` does, puts torches at the level's torch spots, and puts the ore, the junk (gravel, flint, dirt, granite, diorite, andesite, tuff, calcite, sand, clay), and what else it dug beyond 64 blocks to wall off with, into the landing chest, when its inventory has 3 slots or fewer free and once done. It keeps at least 16 blocks to wall off with, of the junk that walls off (dirt, granite, diorite, andesite, tuff) where it has too few others, as on a level of granite. Where water or lava is in or beside the next block, it seals it and stops that hall or branch there, short of a fluid in the next block or once the block beside one is dug, and goes on with the next branch; a branch whose way in stopped short is left. Gives `dig_shaft`'s dict with `sealed` too, the fluid blocks where a hall or branch stopped: `reason` is `missing_ingredients` without a pickaxe (checked first) or without a block to seal with, `no_space` when the chest is full or putting away frees too little (it carries what it does not put away, such as food), or an action's reason |
| `site(r=0)` | the shaft in `memory["shaft"]` with its branches of round `r`, as `mineshaft_at` gives it. Besides `top` and `levels`, `memory["shaft"]` may hold `branches` and `length` for a shaft of another size |

How many rounds of a level are done is kept in `memory["mine"]` (level to rounds); how far a round is dug is read from the world, so `mine_level` called again goes on where it stopped.
