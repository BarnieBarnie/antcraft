# Bots

Use a Bot Core on a block to activate a bot there. Bots pick up items into a 27-slot inventory. They fight hostile mobs (except creepers, for now), but flee at low health instead (see [Reflexes](#reflexes)).

Bots find their own way: they walk, jump, swim, drop up to 3 blocks, dig through blocks in the way (keeping what they dig), and bridge or pillar with scaffold blocks (dirt, cobblestone and similar, see the `antcraft:scaffold_blocks` item tag) from their inventory. They never break chests or other block entities, or blocks next to lava or water, never plan through lava, fire or a fall that hurts, and walk round what players build with (glass, fences, walls, doors) rather than through it. They walk round nether portals too, and never dig at a portal's frame: only `travel` takes a bot through one.

## Reflexes

**Reflexes** run every tick before the script and cannot be turned off: a bot steps out of fire and lava, swims up when its air runs low (`botSurfaceAir`), steps out from under falling blocks, and at or below `botFleeHealth` it flees a hostile mob near it, home or away, striking back only at a mob in its reach. An action a reflex cuts short ends with `interrupted`.

Reflexes run every tick in Java, before the script, and cannot be turned off by a script:

- the pathfinder never plans through lava, fire, or a fall that hurts (as today);
- in fire or lava, or next to flowing lava: step aside, to the nearest block within 4 where it can stand clear of fire and lava, through the fire if need be;
- under water with less than `botSurfaceAir` air left (config, default 100 of 300 ticks): swim the shortest way, within 12 blocks, to where its head is out of the water, along under a ceiling if need be;
- at or below `botFleeHealth` health (config, default 6 of 20) with a hostile mob within 8 blocks: flee home when the bot is in its home's dimension and home lies further from the mob than the bot, and away from the mob otherwise, with its own pathfinder as `goto` does, digging or pillaring out where it must, planning again when the mob moves more than 4 blocks and waiting a second after finding no way out; a fleeing bot does not chase a mob, but strikes the one it flees while that one is in its reach (once a second, as in a fight), such as a mob climbing the ladders of a shaft right under it, which it cannot outrun; it does not stop on the ladders to fight such a mob, as both strike once a second and a bot at flee health loses that fight, so it keeps climbing and striking;
- a falling block (sand, gravel) over it: step aside, out from under it.
- standing where another bot's `build` puts a block, when that build asks it to make way: step aside, to the nearest block within 4 out of the build's spots (back to the middle of its own block when only its edge is in one), for as long as the build keeps asking and a second after, or until its hold (below) ends, whichever comes first, so a bot does not make way again on its way back. A bot running `idle.py` keeps off the build's spots, standing where it stepped to, while the building bot holds the job the build was called with (`job=`, as `colony.builder` passes the `build` job it took); a build called without one holds it while the building bot holds any `build` job off its colony's job board and else until ten seconds after its last `build` call, so it does not walk back home onto the site between calls, such as while the builder fetches what it missed.

The nearer danger comes first, in the order of the list. A reflex takes over only where it has a way out: a bot walled in by fire, or under water with no air within reach, is left to its script.

When a reflex takes over, the running action ends with `interrupted`; a [`mineshaft`](../reference/mining/mineshaft.md) search, which does not move the bot, only pauses and goes on once the reflex lets go. While the reflex holds the bot the script does not run; once the danger is past, the script goes on with that result and decides what to do next. Thresholds are config options, not script options, in v1; 0 turns the drowning or the fleeing reflex off.
