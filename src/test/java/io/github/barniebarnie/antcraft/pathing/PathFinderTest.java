package io.github.barniebarnie.antcraft.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

class PathFinderTest {
	private static final BlockPos START = new BlockPos(0, 1, 0);
	private static final PathFinder.Options WALK_ONLY = new PathFinder.Options(false, false, 10_000, 3);
	private static final PathFinder.Options FULL = new PathFinder.Options(true, true, 10_000, 3);

	private static Path find(GridWorld world, BlockPos goal, PathFinder.Options options) {
		return PathFinder.find(world, START, new Goal.Block(goal), options);
	}

	private static List<MoveType> types(Path path) {
		return path.steps().stream().map(Step::type).toList();
	}

	@Test
	void walksStraightOnFlatGround() {
		Path path = find(GridWorld.flat(10), new BlockPos(5, 1, 0), WALK_ONLY);

		assertTrue(path.complete());
		assertEquals(5, path.steps().size());
		assertTrue(types(path).stream().allMatch(t -> t == MoveType.WALK));
		assertEquals(new BlockPos(5, 1, 0), path.end());
	}

	/** A path says how many nodes its search expanded, which the per-tick pathfinding budget counts (see PathBudget). */
	@Test
	void aPathTellsHowManyNodesItsSearchExpanded() {
		// Walled in: the search gives up once it has expanded its nodes, also where it may drop past maxDrop: one search, not two (#684).
		GridWorld world = GridWorld.flat(10).fill(3, 1, -10, 3, 2, 10, '#');
		for (PathFinder.Options options : List.of(new PathFinder.Options(false, false, 50, 3), new PathFinder.Options(false, false, 50, 3, death(20, START)))) {
			Path stuck = find(world, new BlockPos(6, 1, 0), options);
			assertFalse(stuck.complete());
			assertEquals(50, stuck.expanded());
		}
		Path straight = find(GridWorld.flat(10), new BlockPos(2, 1, 0), WALK_ONLY);
		assertTrue(straight.expanded() >= 2 && straight.expanded() < 50, "expanded " + straight.expanded());
	}

	/**
	 * A search run a slice of nodes at a time, as bots share each tick's nodes (see PathBudget), expands no more than
	 * each slice and finds the path a search run at once does, also for a goal that may end afloat (two searches).
	 */
	@Test
	void aSearchRunInSlicesFindsThePathASearchRunAtOnceFinds() {
		GridWorld world = GridWorld.flat(12).fill(3, 1, -5, 3, 2, 5, '#').fill(-4, 0, -4, -2, 0, -2, 'W');
		for (Goal goal : List.of(new Goal.Block(new BlockPos(8, 1, 0)), new Goal.Block(new BlockPos(3, 1, 0)), new Goal.Afloat(new Goal.Near(new BlockPos(-3, 0, -3), 0)))) {
			Path whole = PathFinder.find(world, START, goal, FULL);
			PathFinder.Search search = PathFinder.search(world, START, goal, FULL);
			int slices = 0;
			while (search.path().isEmpty()) {
				int expanded = search.run(7);
				assertTrue(expanded <= 7, "expanded " + expanded + " in a slice of 7");
				slices++;
			}
			Path sliced = search.path().orElseThrow();
			assertTrue(slices > 1, "found in one slice");
			assertEquals(whole.steps(), sliced.steps());
			assertEquals(whole.complete(), sliced.complete());
			assertEquals(whole.expanded(), sliced.expanded());
		}
	}

	@Test
	void alreadyAtGoalGivesEmptyCompletePath() {
		Path path = find(GridWorld.flat(5), START, WALK_ONLY);

		assertTrue(path.complete());
		assertTrue(path.steps().isEmpty());
	}

	@Test
	void stepsUpOneBlock() {
		GridWorld world = GridWorld.flat(10).fill(2, 1, -10, 10, 1, 10, '#');
		Path path = find(world, new BlockPos(4, 2, 0), WALK_ONLY);

		assertTrue(path.complete());
		assertTrue(types(path).contains(MoveType.STEP_UP));
	}

	@Test
	void walksUpAStairBlockFromItsLowSideWithoutJumping() {
		assertEquals(PathFinder.WALK_COST, stepOntoStairFacing(Direction.EAST), 1e-9);
	}

	@Test
	void jumpsOntoAStairBlockFromItsTallBack() {
		assertEquals(PathFinder.WALK_COST + PathFinder.JUMP_COST, stepOntoStairFacing(Direction.WEST), 1e-9);
	}

	@Test
	void jumpsOntoAStairBlockFromItsSide() {
		assertEquals(PathFinder.WALK_COST + PathFinder.JUMP_COST, stepOntoStairFacing(Direction.NORTH), 1e-9);
	}

	/** The cost of stepping east from the start onto a stair block facing {@code facing}. */
	private static double stepOntoStairFacing(Direction facing) {
		GridWorld world = GridWorld.flat(10).stair(1, 1, 0, facing);
		Path path = find(world, new BlockPos(1, 2, 0), WALK_ONLY);

		assertTrue(path.complete());
		assertEquals(List.of(MoveType.STEP_UP), types(path));
		return path.cost();
	}

	/**
	 * On a bottom slab sunk in the floor, a block one up is a block and a half above the slab's top, past a jump (#647):
	 * the path steps onto the floor and up from there.
	 */
	@Test
	void stepsUpFromTheFloorRatherThanFromALowFloor() {
		GridWorld world = GridWorld.flat(10).set(0, 0, 0, 'l').set(1, 1, 0, '#');
		Path path = find(world, new BlockPos(1, 2, 0), WALK_ONLY);

		assertTrue(path.complete());
		assertEquals(List.of(MoveType.WALK, MoveType.WALK, MoveType.STEP_UP), types(path));
	}

	/**
	 * From honey a jump goes half as fast, about 0.38 blocks high (#659): no step up onto a full block or a slab one
	 * higher, nor a pillar; from thin snow, 0.125 high, a slab one up is 1.375 over it, past a jump's 1.25. Off the
	 * snow onto the floor beside it is 0.875 up, itself a jump (#794).
	 */
	@Test
	void stepsUpFromNeitherHoneyNorThinSnowWhereTheJumpFallsShort() {
		for (char from : new char[] {'h', 's'}) {
			for (char onto : from == 'h' ? new char[] {'#', 'l', 'h'} : new char[] {'l'}) {
				GridWorld world = GridWorld.flat(10).set(0, 0, 0, from).set(1, 1, 0, onto);
				Path path = find(world, new BlockPos(1, 2, 0), WALK_ONLY);

				assertTrue(path.complete(), from + " onto " + onto);
				MoveType off = from == 's' ? MoveType.STEP_UP : MoveType.WALK;
				assertEquals(List.of(off, MoveType.WALK, MoveType.STEP_UP), types(path), from + " onto " + onto);
			}
		}
		Path pillar = find(GridWorld.flat(10).set(0, 0, 0, 'h'), new BlockPos(0, 2, 0), new PathFinder.Options(false, true, 10_000, 3));
		assertEquals(MoveType.WALK, types(pillar).getFirst());
	}

	/**
	 * From a carpet onto a full block beside it is nearly a block up, past a step: a jump, planned as a step up with
	 * room over the head for it (#794).
	 */
	@Test
	void stepsUpFromACarpetOntoAFullBlockBesideIt() {
		GridWorld world = GridWorld.flat(10).set(0, 1, 0, 'c').set(1, 1, 0, '#');
		Path path = PathFinder.find(world, new BlockPos(0, 2, 0), new Goal.Block(new BlockPos(1, 2, 0)), WALK_ONLY);

		assertEquals(List.of(MoveType.STEP_UP), types(path));
		assertEquals(PathFinder.WALK_COST + PathFinder.JUMP_COST, path.cost(), 1e-9);
		// With no room to jump under a roof, it goes round by the floor.
		Path roofed = PathFinder.find(world.set(0, 4, 0, 'B'), new BlockPos(0, 2, 0), new Goal.Block(new BlockPos(1, 2, 0)), WALK_ONLY);
		assertFalse(types(roofed).equals(List.of(MoveType.STEP_UP)) || types(roofed).equals(List.of(MoveType.WALK)), "jumped under a roof: " + roofed.steps());
	}

	/** A slab one up from another slab is a block over its top, which a jump makes. */
	@Test
	void stepsUpFromASlabOntoASlab() {
		GridWorld world = GridWorld.flat(10).set(0, 0, 0, 'l').set(1, 1, 0, 'l');
		Path path = find(world, new BlockPos(1, 2, 0), WALK_ONLY);

		assertEquals(List.of(MoveType.STEP_UP), types(path));
	}

	/** Nor does it pillar up from a bottom slab: a jump from its top does not clear the block placed over it (#647). */
	@Test
	void pillarsFromNoLowFloor() {
		GridWorld world = GridWorld.flat(10).set(0, 0, 0, 'l');
		Path path = find(world, new BlockPos(0, 2, 0), new PathFinder.Options(false, true, 10_000, 3));

		assertTrue(path.complete());
		assertEquals(MoveType.WALK, types(path).getFirst());
	}

	@Test
	void cannotClimbTwoBlockWallWithoutTools() {
		// Wall of height 2 across the whole floor.
		GridWorld world = GridWorld.flat(10).fill(2, 1, -10, 2, 2, 10, '#');
		Path path = find(world, new BlockPos(4, 1, 0), WALK_ONLY);

		assertFalse(path.complete());
	}

	@Test
	void walksAroundShortWallInsteadOfDigging() {
		GridWorld world = GridWorld.flat(10).fill(2, 1, -1, 2, 2, 1, '#');
		Path path = find(world, new BlockPos(4, 1, 0), FULL);

		assertTrue(path.complete());
		assertTrue(path.steps().stream().allMatch(s -> s.breaks().isEmpty()), "should not dig");
	}

	@Test
	void digsThroughWallWhenNoWayAround() {
		GridWorld world = GridWorld.flat(10).fill(2, 1, -10, 2, 2, 10, '#');
		Path path = find(world, new BlockPos(4, 1, 0), new PathFinder.Options(true, false, 10_000, 3));

		assertTrue(path.complete());
		assertTrue(path.steps().stream().anyMatch(s -> !s.breaks().isEmpty()), "must break through the wall");
	}

	@Test
	void neverBreaksBedrock() {
		GridWorld world = GridWorld.flat(10).fill(2, 1, -10, 2, 3, 10, 'B');
		Path path = find(world, new BlockPos(4, 1, 0), new PathFinder.Options(true, false, 10_000, 3));

		assertFalse(path.complete());
	}

	@Test
	void doesNotDigNextToLava() {
		GridWorld world = GridWorld.flat(10).fill(2, 1, -10, 2, 2, 10, '#').fill(3, 1, -10, 3, 2, 10, 'L');
		// The only way through the wall exposes lava, and lava itself is never entered.
		Path path = find(world, new BlockPos(5, 1, 0), new PathFinder.Options(true, false, 10_000, 3));

		assertFalse(path.complete());
	}

	@Test
	void bridgesGapWhenPlacingAllowed() {
		// Floor with a 2-wide gap down to y=-20 at x=2..3.
		GridWorld world = GridWorld.flat(10).fill(2, 0, -10, 3, 0, 10, ' ');
		Path path = find(world, new BlockPos(5, 1, 0), FULL);

		assertTrue(path.complete());
		List<BlockPos> placed = path.steps().stream().flatMap(s -> s.places().stream()).toList();
		assertEquals(List.of(new BlockPos(2, 0, 0), new BlockPos(3, 0, 0)), placed);
	}

	@Test
	void cannotCrossGapWithoutPlacing() {
		GridWorld world = GridWorld.flat(10).fill(2, 0, -10, 3, 0, 10, ' ');
		Path path = find(world, new BlockPos(5, 1, 0), WALK_ONLY);

		assertFalse(path.complete());
	}

	@Test
	void dropsUpToMaxDropButNoFurtherWhereThereIsAnotherWay() {
		// Ledge at y=0 for x<=0; lower floor 3 down, and an even lower floor 4 down.
		GridWorld three = new GridWorld().fill(-5, 0, -5, 0, 0, 5, '#').fill(1, -3, -5, 5, -3, 5, '#');
		Path ok = find(three, new BlockPos(3, -2, 0), WALK_ONLY);
		assertTrue(ok.complete());
		assertTrue(types(ok).contains(MoveType.DROP));

		// The floor 4 down, and a step 2 down at z=5 to get down to it in two drops: the way round, not the leap.
		GridWorld four = new GridWorld().fill(-5, 0, -5, 0, 0, 5, '#').fill(1, -4, -5, 5, -4, 5, '#').set(1, -2, 5, '#');
		Path round = find(four, new BlockPos(3, -3, 0), WALK_ONLY);
		assertTrue(round.complete(), "found no way: " + round.steps());
		assertTrue(round.steps().contains(new Step(MoveType.DROP, new BlockPos(1, -1, 5), List.of(), List.of())), "leapt: " + round.steps());
	}

	/**
	 * Where there is no way down within maxDrop, a path drops as far as it must, hurt or not: a bot left on a floating
	 * platform of its own scaffold, its pillar taken back, stayed up there for good (#674).
	 */
	@Test
	void dropsFartherThanMaxDropWhereThereIsNoOtherWay() {
		Path path = find(platform(12), new BlockPos(5, -11, 0), walkOnly(death(20, START)));
		assertTrue(path.complete(), "stayed up: " + path.steps());
		assertTrue(path.steps().contains(new Step(MoveType.DROP, new BlockPos(2, -11, 0), List.of(), List.of())), "no drop: " + path.steps());
	}

	/** A bot's death for the path search: {@code health} left, its home at {@code home}, back 200 ticks after it died. */
	private static PathFinder.Death death(float health, @Nullable BlockPos home) {
		return new PathFinder.Death(health, home, 200);
	}

	private static PathFinder.Options walkOnly(PathFinder.Death death) {
		return new PathFinder.Options(false, false, 10_000, 3, death);
	}

	/** A floating 3x3 dirt platform at y=0 (the bot on it at START) over a stone floor {@code fall} blocks down. */
	private static GridWorld platform(int fall) {
		return new GridWorld().fill(-1, 0, -1, 1, 0, 1, 'D').fill(-10, -fall, -10, 10, -fall, 10, '#');
	}

	/**
	 * Of equal paths, a search finds the same one whether or not it may drop past maxDrop where it never does: a drop it
	 * did not take changed which, and with it how a giant spruce's felling went (#682).
	 */
	@Test
	void aDropItDoesNotTakeLeavesWhichOfEqualPathsItFindsAlone() {
		GridWorld world = platform(12).fill(-6, 0, -6, 6, 0, 6, 'D');
		for (BlockPos goal : BlockPos.betweenClosed(-6, 1, -6, 6, 1, 6)) {
			Path safe = find(world, goal.immutable(), WALK_ONLY);
			Path costed = find(world, goal.immutable(), walkOnly(death(20, START)));
			assertEquals(safe.steps(), costed.steps(), "to " + goal);
		}
	}

	/** A bot that would not come back from a death, or whose search is not told of it, never drops past maxDrop. */
	@Test
	void neverDropsFartherThanMaxDropWithoutADeathToCost() {
		Path path = find(platform(12), new BlockPos(5, -11, 0), WALK_ONLY);
		assertFalse(path.complete(), "dropped: " + path.steps());
	}

	/**
	 * A drop past maxDrop hurts a point of health per block past it (vanilla's fall damage). A bot does not heal, so what
	 * it loses is its share of a death: that share of the respawn and of the walk from home back to where it landed (#682).
	 */
	@Test
	void aDropThatHurtsCostsItsShareOfADeath() {
		BlockPos landing = new BlockPos(2, -11, 0);
		Path path = find(platform(12), landing, walkOnly(death(20, landing.south(10))));
		assertTrue(path.complete(), "stayed up: " + path.steps());
		double hurt = 9 / 20.0 * (200 + 10 * PathFinder.WALK_COST);
		assertEquals(2 * PathFinder.WALK_COST + 12 * PathFinder.FALL_COST_PER_BLOCK + hurt, path.cost(), 1e-9);
	}

	/**
	 * A bot whose home is in another dimension (no home here) still drops where there is no other way: its walk back is
	 * the fixed {@link PathFinder#AWAY_WALK} (#688).
	 */
	@Test
	void aDropWithItsHomeInAnotherDimensionCostsAFixedWalkBack() {
		BlockPos landing = new BlockPos(2, -11, 0);
		Path path = find(platform(12), landing, walkOnly(death(20, null)));
		assertTrue(path.complete(), "stayed up: " + path.steps());
		double hurt = 9 / 20.0 * (200 + PathFinder.AWAY_WALK);
		assertEquals(2 * PathFinder.WALK_COST + 12 * PathFinder.FALL_COST_PER_BLOCK + hurt, path.cost(), 1e-9);
	}

	/** A bot's death as {@link #death}, its food healing {@code quick} health at 10 ticks a point, then {@code slow} at 80. */
	private static PathFinder.Death fed(float health, @Nullable BlockPos home, float quick, float slow) {
		return new PathFinder.Death(health, home, 200, quick, slow);
	}

	/** What the food healing a point uses costs: 1.5 food points (6 exhaustion, 4 a point), 5 ticks each (#730). */
	private static final double HEAL_FOOD = 1.5 * 5;

	/**
	 * A bot whose food heals the health a drop takes counts the ticks healing it takes rather than its share of a death
	 * (#708), and the food that healing uses (#730): 9 health at 10 ticks a point, full with saturation, its home far enough off that a point's share of a death costs more.
	 */
	@Test
	void aDropThatHurtsAFedBotCostsTheTicksItTakesToHealAndTheFoodItUses() {
		BlockPos landing = new BlockPos(2, -11, 0);
		Path path = find(platform(12), landing, walkOnly(fed(20, landing.south(100), 20, 0)));
		assertEquals(2 * PathFinder.WALK_COST + 12 * PathFinder.FALL_COST_PER_BLOCK + 9 * (10 + HEAL_FOOD), path.cost(), 1e-9);
	}

	/**
	 * Healing a point costs at most its share of a death, what a point the bot does not heal costs: a bot whose home is
	 * near heals slowly (80 ticks a point) for more than dying would cost. Health its food does not heal is that share.
	 */
	@Test
	void aDropCostsEachPointTheLesserOfHealingItAndItsShareOfADeath() {
		BlockPos landing = new BlockPos(2, -11, 0);
		Path near = find(platform(12), landing, walkOnly(fed(20, landing.south(10), 0, 20)));
		assertEquals(2 * PathFinder.WALK_COST + 12 * PathFinder.FALL_COST_PER_BLOCK + 9 / 20.0 * (200 + 10 * PathFinder.WALK_COST), near.cost(), 1e-9);
		Path away = find(platform(12), landing, walkOnly(fed(20, null, 3, 2)));
		double share = (200 + PathFinder.AWAY_WALK) / 20;
		assertEquals(2 * PathFinder.WALK_COST + 12 * PathFinder.FALL_COST_PER_BLOCK + 3 * (10 + HEAL_FOOD) + 2 * (80 + HEAL_FOOD) + 4 * share, away.cost(), 1e-9);
	}

	/**
	 * On peaceful a bot heals a point every 20 ticks for free and its food refills (#738): a drop costs only the ticks
	 * healing takes, its quick points at 10, the rest at 20, and no food.
	 */
	@Test
	void onPeacefulADropThatHurtsCostsOnlyTheHealTicks() {
		BlockPos landing = new BlockPos(2, -11, 0);
		BlockPos home = landing.south(100);
		Path hungry = find(platform(12), landing, walkOnly(new PathFinder.Death(20, home, 200, 0, 0, true)));
		assertEquals(2 * PathFinder.WALK_COST + 12 * PathFinder.FALL_COST_PER_BLOCK + 9 * 20, hungry.cost(), 1e-9);
		Path fed = find(platform(12), landing, walkOnly(new PathFinder.Death(20, home, 200, 3, 20, true)));
		assertEquals(2 * PathFinder.WALK_COST + 12 * PathFinder.FALL_COST_PER_BLOCK + 3 * 10 + 6 * 20, fed.cost(), 1e-9);
	}

	/** Food makes a fatal drop no cheaper: it costs a whole death. */
	@Test
	void aFatalDropCostsAFedBotADeath() {
		BlockPos landing = new BlockPos(2, -11, 0);
		Path weak = find(platform(12), landing, walkOnly(fed(5, landing.south(10), 20, 20)));
		assertEquals(2 * PathFinder.WALK_COST + 12 * PathFinder.FALL_COST_PER_BLOCK + 200 + 10 * PathFinder.WALK_COST, weak.cost(), 1e-9);
	}

	/**
	 * Up a pillar as that of {@link #jumpsOffAPillarWhereThatCostsLessThanDiggingDown} but of stone, its home far off: a fed
	 * bot jumps off where a hungry one digs down. Of dirt, digging costs less than the food healing the fall takes (#730).
	 */
	@Test
	void aFedBotJumpsOffAPillarWhereAHungryOneDigsDown() {
		GridWorld world = new GridWorld().fill(0, -11, 0, 0, 0, 0, '#').fill(-10, -12, -10, 10, -12, 10, '#');
		BlockPos foot = new BlockPos(1, -11, 0);
		Path jump = find(world, foot, new PathFinder.Options(true, false, 10_000, 3, fed(20, foot.east(1000), 20, 0)));
		assertEquals(List.of(new Step(MoveType.DROP, foot, List.of(), List.of())), jump.steps());
		Path dig = find(world, foot, new PathFinder.Options(true, false, 10_000, 3, fed(20, foot.east(1000), 0, 0)));
		assertTrue(types(dig).contains(MoveType.DIG_DOWN), "jumped: " + dig.steps());
	}

	/** A drop that hurts the bot as much as the health it has or more costs a whole death, however far past that it falls. */
	@Test
	void aFatalDropCostsADeath() {
		BlockPos landing = new BlockPos(2, -11, 0);
		Path weak = find(platform(12), landing, walkOnly(death(5, landing.south(10))));
		assertEquals(2 * PathFinder.WALK_COST + 12 * PathFinder.FALL_COST_PER_BLOCK + 200 + 10 * PathFinder.WALK_COST, weak.cost(), 1e-9);
		BlockPos deep = new BlockPos(2, -39, 0);
		Path far = find(platform(40), deep, walkOnly(death(20, deep.south(10))));
		assertEquals(2 * PathFinder.WALK_COST + 40 * PathFinder.FALL_COST_PER_BLOCK + 200 + 10 * PathFinder.WALK_COST, far.cost(), 1e-9);
	}

	/**
	 * Up a dirt pillar 12 high, a bot jumps off where that costs less than digging its way down, as with its home near
	 * where it lands, and digs down where that costs less, as with its home far off (#682).
	 */
	@Test
	void jumpsOffAPillarWhereThatCostsLessThanDiggingDown() {
		GridWorld world = new GridWorld().fill(0, -11, 0, 0, 0, 0, 'D').fill(-10, -12, -10, 10, -12, 10, '#');
		BlockPos foot = new BlockPos(1, -11, 0);
		Path jump = find(world, foot, new PathFinder.Options(true, false, 10_000, 3, death(20, foot)));
		assertEquals(List.of(new Step(MoveType.DROP, foot, List.of(), List.of())), jump.steps());
		Path dig = find(world, foot, new PathFinder.Options(true, false, 10_000, 3, death(20, foot.east(100))));
		assertTrue(dig.complete(), "stayed up: " + dig.steps());
		assertTrue(types(dig).contains(MoveType.DIG_DOWN), "jumped: " + dig.steps());
		// It may dig part of the way and drop the rest, but never farther than maxDrop.
		BlockPos at = START;
		for (Step step : dig.steps()) {
			assertTrue(at.getY() - step.to().getY() <= 3, "jumped: " + dig.steps());
			at = step.to();
		}
	}

	/** A ledge at y=0 for x<=0 (feet at y=1) over water a block deep at x 1..5, its surface {@code fall} blocks down, and a bank at x=6. */
	private static GridWorld ledgeOverWater(int fall) {
		int surface = 1 - fall;
		return new GridWorld().fill(-5, 0, -5, 0, 0, 5, '#')
			.fill(1, surface - 1, -5, 6, surface - 1, 5, '#')
			.fill(1, surface, -5, 5, surface, 5, 'W')
			.fill(6, surface, -5, 6, surface, 5, '#');
	}

	@Test
	void dropsFartherThanMaxDropIntoWater() {
		// Water breaks the fall, so a drop into it may go past maxDrop (3 here).
		Path path = find(ledgeOverWater(PathFinder.MAX_WATER_DROP), new BlockPos(6, 2 - PathFinder.MAX_WATER_DROP, 0), WALK_ONLY);

		assertTrue(path.complete(), "did not drop into the water: " + path.steps());
		assertEquals(new Step(MoveType.SWIM, new BlockPos(1, 1 - PathFinder.MAX_WATER_DROP, 0), List.of(), List.of()), path.steps().getFirst());
	}

	@Test
	void dropsIntoWaterFartherThanMaxWaterDropWhereThereIsNoOtherWay() {
		int fall = PathFinder.MAX_WATER_DROP + 1;
		BlockPos bank = new BlockPos(6, 2 - fall, 0);
		Path leap = find(ledgeOverWater(fall), bank, walkOnly(death(20, START)));
		assertTrue(leap.complete(), "stayed up: " + leap.steps());
		assertFalse(find(ledgeOverWater(fall), bank, WALK_ONLY).complete(), "leapt with no death to cost");
	}

	@Test
	void swimsUpAWaterfallToTheTopOfACliff() {
		// A cliff 4 high at x>=3 with a waterfall down its face at x=2, z=0.
		GridWorld world = GridWorld.flat(10).fill(3, 1, -10, 10, 4, 10, '#').fill(2, 1, 0, 2, 4, 0, 'W');
		Path path = find(world, new BlockPos(4, 5, 0), WALK_ONLY);

		assertTrue(path.complete(), "cannot climb the waterfall: " + path.steps());
		assertEquals(List.of(new BlockPos(1, 1, 0), new BlockPos(2, 1, 0), new BlockPos(2, 2, 0), new BlockPos(2, 3, 0), new BlockPos(2, 4, 0),
			new BlockPos(3, 5, 0), new BlockPos(4, 5, 0)), positions(path));
	}

	@Test
	void swimmingUpABlockCostsTheTicksItTakes() {
		// A water column 4 deep in a pool: the bot at the bottom rises 3 blocks to the surface, then climbs out.
		GridWorld world = GridWorld.flat(10).fill(-1, 1, -10, 10, 4, 10, '#').fill(0, 1, 0, 0, 4, 0, 'W');
		Path path = PathFinder.find(world, START, new Goal.Block(new BlockPos(1, 5, 0)), WALK_ONLY);

		assertTrue(path.complete(), "cannot rise: " + path.steps());
		assertEquals(3 * PathFinder.SWIM_UP_COST + PathFinder.WALK_COST + PathFinder.JUMP_COST, path.cost(), 1e-9);
		// Measured at about 6 ticks a block (see PathingGameTest.swimUpCostMatchesTheTimeARiseTakes).
		assertTrue(PathFinder.SWIM_UP_COST >= 5 && PathFinder.SWIM_UP_COST <= 8, "SWIM_UP_COST " + PathFinder.SWIM_UP_COST);
	}

	@Test
	void takesStairsRatherThanAWaterfallThatIsSlowerToSwimUp() {
		// The waterfall of swimsUpAWaterfallToTheTopOfACliff, and stairs up the cliff 2 blocks along it. Swimming up the
		// waterfall is a few blocks shorter, but swimming in and rising 3 blocks in water takes longer than walking round
		// to the stairs.
		GridWorld world = GridWorld.flat(10).fill(3, 1, -10, 10, 4, 10, '#').fill(2, 1, 0, 2, 4, 0, 'W')
			.fill(0, 1, 2, 0, 1, 2, '#').fill(1, 1, 2, 1, 2, 2, '#').fill(2, 1, 2, 2, 3, 2, '#');
		Path path = find(world, new BlockPos(4, 5, 0), WALK_ONLY);

		assertTrue(path.complete(), "no way up: " + path.steps());
		assertFalse(types(path).contains(MoveType.SWIM), "swam up the waterfall: " + path.steps());
	}

	@Test
	void doesNotSwimSidewaysFromOneWaterfallIntoAnother() {
		// Two water columns side by side in bedrock. The bot is at the foot of one capped at the top; only the other
		// leads up to air and a ledge, and getting across means swimming under water.
		GridWorld world = new GridWorld().fill(-2, 0, -3, 6, 7, 4, 'B')
			.fill(2, 1, 0, 2, 4, 1, 'W')
			.fill(2, 5, 1, 3, 6, 1, ' ');
		Path path = PathFinder.find(world, new BlockPos(2, 1, 0), new Goal.Block(new BlockPos(3, 5, 1)), WALK_ONLY);

		assertFalse(path.complete(), "swam under water from one waterfall to the other: " + path.steps());
	}

	/**
	 * A step never stands on a block an earlier step of its path broke, nor walks through one it placed: felling a giant
	 * spruce, a path pillared up through the crown, breaking the block over its head, then stepped back up onto that
	 * block, and the bot jumped at the air there until the step timed out (#687). Paths through scattered dirt to goals
	 * all over it, each replayed block by block.
	 */
	@Test
	void aStepStandsOnNoBlockItsPathBrokeNorWalksThroughOneItPlaced() {
		java.util.Random random = new java.util.Random(687);
		GridWorld world = GridWorld.flat(8);
		for (BlockPos pos : BlockPos.betweenClosed(-6, 1, -6, 6, 9, 6)) {
			if (random.nextInt(10) < 3 && !pos.equals(START) && !pos.equals(START.above())) {
				world.set(pos.getX(), pos.getY(), pos.getZ(), 'D');
			}
		}
		for (int i = 0; i < 60; i++) {
			BlockPos goal = new BlockPos(random.nextInt(13) - 6, 1 + random.nextInt(10), random.nextInt(13) - 6);
			assertEveryStepCanBeTaken(world, find(world, goal, FULL));
		}
	}

	/** Each step, its breaks and places done as PathFollower does them, ends standing on a block with room for the bot. */
	private static void assertEveryStepCanBeTaken(GridWorld world, Path path) {
		java.util.Map<BlockPos, Boolean> solid = new java.util.HashMap<>();  // what the path broke (false) and placed (true)
		for (Step step : path.steps()) {
			step.breaks().forEach(pos -> solid.put(pos, false));
			step.places().forEach(pos -> solid.put(pos, true));
			BlockPos to = step.to();
			assertTrue(solid.getOrDefault(to.below(), world.canStandOn(to.below())), "nothing under " + to + ": " + path.steps());
			for (BlockPos pos : List.of(to, to.above())) {
				assertFalse(solid.getOrDefault(pos, !world.isPassable(pos)), "a block at " + pos + ": " + path.steps());
			}
		}
	}

	@Test
	void pillarsUpToHighGoal() {
		GridWorld world = GridWorld.flat(5);
		Path path = find(world, new BlockPos(0, 4, 0), FULL);

		assertTrue(path.complete());
		assertEquals(List.of(MoveType.PILLAR, MoveType.PILLAR, MoveType.PILLAR), types(path));
	}

	/**
	 * A bot carrying 2 scaffold blocks does not plan the 3 pillars up a tower 4 high, which it would run out of halfway
	 * (#749), but walks the long way round: along a bridge on top from a staircase 9 blocks off.
	 */
	@Test
	void placesNoMoreScaffoldThanTheBotCarries() {
		GridWorld world = GridWorld.flat(14).fill(1, 1, 0, 1, 4, 8, '#')
			.fill(1, 1, 9, 1, 3, 9, '#').fill(1, 1, 10, 1, 2, 10, '#').set(1, 1, 11, '#');
		BlockPos top = new BlockPos(1, 5, 0);

		assertEquals(3, find(world, top, FULL).steps().stream().mapToInt(s -> s.places().size()).sum());
		Path path = find(world, top, new PathFinder.Options(false, true, 10_000, 3, null, 2));
		assertTrue(path.complete());
		int placed = path.steps().stream().mapToInt(s -> s.places().size()).sum();
		assertTrue(placed <= 2, "placed " + placed + ": " + path.steps());
	}

	/**
	 * Nor does a cheaper way to a block that placed more keep the search from the way there that placed less: with 2
	 * scaffold blocks, the bot walks round a gap 2 wide rather than bridge it, to pillar once up a tower beyond (#749).
	 */
	@Test
	void walksRoundAGapToKeepScaffoldForAPillarBeyond() {
		GridWorld world = GridWorld.flat(14).fill(2, 0, -14, 3, 0, 8, ' ').fill(6, 1, 0, 6, 2, 0, '#');
		BlockPos top = new BlockPos(6, 3, 0);

		assertEquals(3, find(world, top, FULL).steps().stream().mapToInt(s -> s.places().size()).sum());
		Path path = find(world, top, new PathFinder.Options(false, true, 10_000, 3, null, 2));
		assertTrue(path.complete(), "partial: " + path.steps());
		int placed = path.steps().stream().mapToInt(s -> s.places().size()).sum();
		assertTrue(placed <= 2, "placed " + placed + ": " + path.steps());
	}

	/**
	 * A block the path breaks that drops a scaffold block, which the follower picks up, counts back: shut in a bedrock
	 * box with 1 dirt, the bot digs out through 2 dirt and pillars twice up a tower beyond (#771).
	 */
	@Test
	void countsBrokenDirtBackAsScaffold() {
		GridWorld world = GridWorld.flat(10).fill(-1, 0, -1, 1, 3, 1, 'B').set(0, 1, 0, ' ').set(0, 2, 0, ' ')
			.set(1, 1, 0, 'D').set(1, 2, 0, 'D').fill(5, 1, 0, 5, 3, 0, '#');
		BlockPos top = new BlockPos(5, 4, 0);

		Path path = find(world, top, new PathFinder.Options(true, true, 10_000, 3, null, 1));
		assertTrue(path.complete(), "partial: " + path.steps());
		long dirt = path.steps().stream().flatMap(s -> s.breaks().stream()).filter(p -> world.at(p) == 'D').count();
		int placed = path.steps().stream().mapToInt(s -> s.places().size()).sum();
		assertEquals(2, dirt);
		assertTrue(placed >= 2 && placed <= 1 + dirt, "placed " + placed + ": " + path.steps());
	}

	/**
	 * With no scaffold block, a path places only what it dug (#778), and so no move that places is generated for a node
	 * whose path has none to place (#806): shut in the same box, the bot digs out through the 2 dirt and pillars twice.
	 */
	@Test
	void placesOnlyWhatItDugWithNoScaffold() {
		GridWorld world = GridWorld.flat(10).fill(-1, 0, -1, 1, 3, 1, 'B').set(0, 1, 0, ' ').set(0, 2, 0, ' ')
			.set(1, 1, 0, 'D').set(1, 2, 0, 'D').fill(5, 1, 0, 5, 2, 0, '#');
		BlockPos top = new BlockPos(5, 3, 0);
		PathFinder.Options none = new PathFinder.Options(true, true, 10_000, 3, null, 0);

		Path path = find(world, top, none);
		assertTrue(path.complete(), "partial: " + path.steps());
		int dug = 0;
		for (Step step : path.steps()) {
			dug += (int) step.breaks().stream().filter(p -> world.at(p) == 'D').count();
			dug -= step.places().size();
			assertTrue(dug >= 0, "placed more than it dug by " + step + ": " + path.steps());
		}
		assertTrue(find(GridWorld.flat(10).fill(5, 1, 0, 5, 2, 0, '#'), top, none).steps().stream().allMatch(s -> s.places().isEmpty()));
	}

	/** It may place the block it digs in the same step: the dirt over its head as it pillars up a bedrock shaft (#778). */
	@Test
	void pillarsOnTheDirtItDigsOverItsHeadWithNoScaffold() {
		GridWorld world = GridWorld.flat(5).fill(-1, 0, -1, 1, 4, 1, 'B').set(0, 1, 0, ' ').set(0, 2, 0, ' ').set(0, 3, 0, 'D');

		Path path = PathFinder.find(world, new BlockPos(0, 1, 0), new Goal.Block(new BlockPos(0, 2, 0)), new PathFinder.Options(true, true, 10_000, 3, null, 0));
		assertTrue(path.complete(), "partial: " + path.steps());
		assertEquals(List.of(new Step(MoveType.PILLAR, new BlockPos(0, 2, 0), List.of(new BlockPos(0, 3, 0)), List.of(new BlockPos(0, 1, 0)))), path.steps());
	}

	@Test
	void climbsALadderRatherThanPillarBesideIt() {
		// A wall three high with a ladder on its west face; the goal is on top of the wall.
		GridWorld world = GridWorld.flat(5).fill(2, 1, -5, 2, 3, 5, '#').fill(1, 1, 0, 1, 3, 0, 'H');
		Path path = find(world, new BlockPos(2, 4, 0), FULL);

		assertTrue(path.complete());
		assertEquals(List.of(MoveType.WALK, MoveType.CLIMB_UP, MoveType.CLIMB_UP, MoveType.STEP_UP), types(path));
		assertTrue(path.steps().stream().allMatch(s -> s.places().isEmpty() && s.breaks().isEmpty()), "built or dug: " + path.steps());
	}

	@Test
	void climbsALadderUpThroughAHatch() {
		// A bedrock ceiling at y=4 with a trapdoor in it over a ladder.
		GridWorld world = GridWorld.flat(5).fill(-5, 4, -5, 5, 4, 5, 'B').fill(1, 1, 0, 1, 3, 0, 'H').set(1, 4, 0, 'T');
		Path path = find(world, new BlockPos(2, 5, 0), WALK_ONLY);

		assertTrue(path.complete());
		assertEquals(List.of(MoveType.WALK, MoveType.CLIMB_UP, MoveType.CLIMB_UP, MoveType.CLIMB_UP, MoveType.STEP_UP), types(path));
	}

	@Test
	void climbsALadderDownThroughAHatch() {
		GridWorld world = GridWorld.flat(5).fill(-5, 4, -5, 5, 4, 5, 'B').fill(1, 1, 0, 1, 3, 0, 'H').set(1, 4, 0, 'T');
		Path path = PathFinder.find(world, new BlockPos(2, 5, 0), new Goal.Block(START), WALK_ONLY);

		assertTrue(path.complete());
		// Down through the hatch and the ladder, then off it the last block or two.
		assertEquals(List.of(MoveType.WALK, MoveType.CLIMB_DOWN, MoveType.CLIMB_DOWN, MoveType.CLIMB_DOWN), types(path).subList(0, 4));
	}

	/**
	 * Vines from START down to y=-4 on a wall at x=1, ending in the air 7 blocks over a stone floor at y=-12: a bot
	 * slides off the bottom of anything it climbs (vanilla slides a mob down at 0.15 a tick), so climbing down to it is a
	 * fall from there (#714).
	 */
	private static GridWorld vinesOverADrop() {
		return new GridWorld().fill(0, -4, 0, 0, 1, 0, 'V').fill(1, -4, 0, 1, 1, 0, '#').fill(-10, -12, -10, 10, -12, 10, '#');
	}

	@Test
	void doesNotClimbDownVinesPastTheirEndIntoADropItWouldNotTake() {
		Path path = find(vinesOverADrop(), new BlockPos(0, -11, 0), WALK_ONLY);
		assertFalse(path.complete(), "fell: " + path.steps());
		assertTrue(path.steps().stream().allMatch(s -> s.to().getY() > -4), "climbed down to the end: " + path.steps());
	}

	@Test
	void theFallOffTheEndOfVinesIsCostedLikeAnyDrop() {
		BlockPos landing = new BlockPos(0, -11, 0);
		Path path = find(vinesOverADrop(), landing, walkOnly(death(20, landing.south(10))));
		assertEquals(List.of(MoveType.CLIMB_DOWN, MoveType.CLIMB_DOWN, MoveType.CLIMB_DOWN, MoveType.CLIMB_DOWN, MoveType.DROP), types(path));
		// The climb into the bottom vine, then 7 blocks down, 4 past maxDrop: 4/20 of a death 10 blocks from home.
		double hurt = 4 / 20.0 * (200 + 10 * PathFinder.WALK_COST);
		assertEquals(5 * PathFinder.CLIMB_COST + 7 * PathFinder.FALL_COST_PER_BLOCK + hurt, path.cost(), 1e-9);
	}

	/**
	 * Vines on the east face of a wall, on a dirt doorway that a path breaks to reach them: the two vines on the dirt drop
	 * with it, so the path does not climb them (#714). Only they hang there: vines above them on that face would hold them
	 * up, as in vanilla (#735).
	 */
	@Test
	void doesNotClimbVinesOnABlockItBroke() {
		GridWorld world = GridWorld.flat(5).fill(1, 1, -5, 1, 5, 5, 'B').fill(1, 1, 0, 1, 2, 0, 'D').fill(2, 1, 0, 2, 2, 0, 'V');
		Path path = find(world, new BlockPos(1, 6, 0), FULL);
		assertTrue(path.complete(), "found no way: " + path.steps());
		BlockPos from = START;
		for (Step step : path.steps()) {
			assertFalse(step.type() == MoveType.CLIMB_UP && from.getY() < 3 && from.getX() == 2, "climbed a vine on the broken dirt: " + path.steps());
			from = step.to();
		}
	}

	/** A partial path ends where the bot stands, not up a vine it would hang on and slide off (#726). */
	@Test
	void aPartialPathDoesNotEndHangingOnVines() {
		GridWorld world = GridWorld.flat(5).fill(1, 1, -5, 1, 8, 5, 'B').fill(0, 1, 0, 0, 5, 0, 'V');
		Path path = find(world, new BlockPos(0, 10, 0), WALK_ONLY);
		assertFalse(path.complete(), "reached the goal: " + path.steps());
		BlockPos end = path.end();
		assertFalse(world.isClimbable(end) && !world.canStandOn(end.below()), "ended hanging at " + end + ": " + path.steps());
	}

	/** From the bottom vine a bot lets go and falls straight down, rather than first stepping off sideways (#726). */
	@Test
	void fallsStraightOffTheBottomVine() {
		BlockPos landing = new BlockPos(0, -11, 0);
		Path path = PathFinder.find(vinesOverADrop(), new BlockPos(0, -4, 0), new Goal.Block(landing), walkOnly(death(20, landing.south(10))));
		assertEquals(List.of(MoveType.DROP), types(path), "" + path.steps());
		double hurt = 4 / 20.0 * (200 + 10 * PathFinder.WALK_COST);
		assertEquals(PathFinder.CLIMB_COST + 7 * PathFinder.FALL_COST_PER_BLOCK + hurt, path.cost(), 1e-9);
	}

	/**
	 * Vines hanging from a vine on the dirt a path breaks drop with it, as vines with nothing to hang on do (#726): in
	 * bedrock, a corridor whose dirt head block the path breaks, beside the top of a vine shaft that is the only way up to
	 * the goal ledge, climbed one vine up from its foot.
	 */
	@Test
	void doesNotClimbVinesHangingFromVinesOnABlockItBroke() {
		GridWorld world = new GridWorld().fill(-3, 0, -2, 5, 6, 2, 'B')
			.fill(-1, 2, 0, 1, 3, 0, ' ').set(1, 3, 0, 'D')
			.fill(2, 1, 0, 2, 3, 0, 'V').set(2, 4, 0, ' ')
			.hangsOn(2, 1, 0, Direction.WEST).hangsOn(2, 2, 0, Direction.WEST).hangsOn(2, 3, 0, Direction.WEST)
			.fill(3, 3, 0, 3, 4, 0, ' ');
		Path path = PathFinder.find(world, new BlockPos(0, 2, 0), new Goal.Block(new BlockPos(3, 3, 0)), new PathFinder.Options(true, false, 10_000, 3));
		assertFalse(path.complete(), "climbed the vines under the one on the broken dirt: " + path.steps());
	}

	/**
	 * In bedrock, a column of climbables at x=2, y=1 to 5, each holding on to {@code face} only, at the end of a corridor;
	 * the goal is on the ledge east of its third block.
	 */
	private static Path upAColumnHoldingOn(Direction face) {
		GridWorld world = new GridWorld().fill(-2, -1, -2, 5, 8, 2, 'B').fill(0, 1, 0, 1, 2, 0, ' ')
			.fill(2, 1, 0, 2, 5, 0, 'C').fill(3, 4, 0, 3, 5, 0, ' ');
		for (int y = 1; y <= 5; y++) {
			world.hangsOn(2, y, 0, face);
		}
		return PathFinder.find(world, new BlockPos(0, 1, 0), new Goal.Block(new BlockPos(3, 4, 0)), WALK_ONLY);
	}

	/**
	 * Weeping and cave vines hang from the block over the top one, through the vines above; a vine of them whose
	 * column the path broke the top of drops (#752).
	 */
	@Test
	void climbsVinesHangingFromTheVinesAbove() {
		Path path = upAColumnHoldingOn(Direction.UP);
		assertTrue(path.complete(), "did not climb the vines: " + path.steps());
	}

	/** Twisting vines and scaffolding stand on the block under the bottom one, through those below (#752). */
	@Test
	void climbsVinesStandingOnTheVinesBelow() {
		Path path = upAColumnHoldingOn(Direction.DOWN);
		assertTrue(path.complete(), "did not climb the vines: " + path.steps());
	}

	/** A vine on the dirt the path breaks still hangs from the vine above it on that face, on the bedrock (#752). */
	@Test
	void climbsAVineOnABlockItBrokeHangingFromTheVineAbove() {
		GridWorld world = besideADoorway('V').hangsOn(2, 1, 0, Direction.WEST).hangsOn(2, 2, 0, Direction.WEST).hangsOn(2, 3, 0, Direction.WEST);
		Path path = upBesideADoorway(world);
		assertTrue(path.complete(), "did not climb the vines: " + path.steps());
	}

	/**
	 * In bedrock, a dirt doorway a path breaks to reach a climbable column three high against the bedrock east of it, the
	 * only way up to the goal on the ledge at its top; the doorway is beside the bottom two (#735).
	 */
	private static GridWorld besideADoorway(char climbable) {
		return new GridWorld().fill(-2, 0, -2, 5, 7, 2, 'B').fill(0, 1, 0, 0, 2, 0, ' ').fill(1, 1, 0, 1, 2, 0, 'D')
			.fill(2, 1, 0, 2, 3, 0, climbable).fill(2, 4, 0, 4, 5, 0, ' ');
	}

	private static Path upBesideADoorway(GridWorld world) {
		return PathFinder.find(world, new BlockPos(0, 1, 0), new Goal.Block(new BlockPos(3, 4, 0)), new PathFinder.Options(true, false, 10_000, 3));
	}

	/** A ladder holds on to the block behind it only, so one beside a block the path broke is still climbed (#735). */
	@Test
	void climbsALadderBesideABlockItBroke() {
		GridWorld world = besideADoorway('H').hangsOn(2, 1, 0, Direction.EAST).hangsOn(2, 2, 0, Direction.EAST);
		Path path = upBesideADoorway(world);
		assertTrue(path.complete(), "did not climb the ladder: " + path.steps());
	}

	/** A vine on the dirt and the bedrock beyond holds on to the bedrock once the dirt is broken (#735). */
	@Test
	void climbsAVineOnTwoBlocksWhenItBrokeOne() {
		GridWorld world = besideADoorway('V').hangsOn(2, 1, 0, Direction.WEST, Direction.EAST).hangsOn(2, 2, 0, Direction.WEST, Direction.EAST);
		Path path = upBesideADoorway(world);
		assertTrue(path.complete(), "did not climb the vine: " + path.steps());
	}

	@Test
	void digsDownToLowerGoal() {
		GridWorld world = GridWorld.flat(5).fill(-5, -5, -5, 5, -1, 5, 'D');
		Path path = find(world, new BlockPos(0, -2, 0), FULL);

		assertTrue(path.complete());
		assertEquals(List.of(MoveType.DIG_DOWN, MoveType.DIG_DOWN, MoveType.DIG_DOWN), types(path));
	}

	@Test
	void reachesNearGoal() {
		Path path = PathFinder.find(GridWorld.flat(10), START, new Goal.Near(new BlockPos(6, 1, 0), 2), WALK_ONLY);

		assertTrue(path.complete());
		assertTrue(path.end().distManhattan(new BlockPos(6, 1, 0)) <= 2);
	}

	@Test
	void givesUpAfterNodeLimitWithPartialPathTowardGoal() {
		GridWorld world = GridWorld.flat(100);
		Path path = PathFinder.find(world, START, new Goal.Block(new BlockPos(90, 1, 0)), new PathFinder.Options(false, false, 30, 3));

		assertFalse(path.complete());
		assertTrue(path.end().getX() > 0, "partial path should head toward the goal");
	}

	@Test
	void pillarsElsewhereWhenPlacingIsForbiddenAtTheStart() {
		GridWorld world = GridWorld.flat(10).forbidPlacing(START);
		Path path = find(world, new BlockPos(0, 3, 0), FULL);

		assertTrue(path.complete());
		assertTrue(path.steps().stream().noneMatch(s -> s.places().contains(START)), "placed at a forbidden spot: " + path.steps());
	}

	@Test
	void cannotClimbWhenPlacingIsForbiddenEverywhere() {
		GridWorld world = GridWorld.flat(10).forbidAllPlacing();
		Path path = find(world, new BlockPos(0, 3, 0), FULL);

		assertFalse(path.complete());
	}

	/** Stone ground up to y=1 (feet at y=2) with a pool of {@code depth} water whose surface is at y=1, x and z from -2 to 2. */
	private static GridWorld pond(int depth) {
		return new GridWorld().fill(-10, -depth, -10, 10, 1, 10, '#').fill(-2, 2 - depth, -2, 2, 1, 2, 'W');
	}

	private static List<BlockPos> positions(Path path) {
		return path.steps().stream().map(Step::to).toList();
	}

	@Test
	void swimsToTheShoreFromTheMiddleOfAPoolWithoutScaffold() {
		GridWorld world = pond(2).forbidAllPlacing();
		Path path = PathFinder.find(world, new BlockPos(0, 1, 0), new Goal.Block(new BlockPos(5, 2, 0)), new PathFinder.Options(true, false, 10_000, 3));

		assertTrue(path.complete(), "no way out of the pool: " + path.steps());
		assertTrue(types(path).contains(MoveType.SWIM));
		assertTrue(path.steps().stream().allMatch(s -> s.places().isEmpty() && s.breaks().isEmpty()));
	}

	@Test
	void neverDivesBelowTheSurface() {
		GridWorld world = pond(2);
		Path path = PathFinder.find(world, new BlockPos(0, 1, 0), new Goal.Block(new BlockPos(5, 2, 0)), WALK_ONLY);

		assertTrue(positions(path).stream().noneMatch(p -> p.getY() < 1), "dived: " + path.steps());
	}

	@Test
	void risesToTheSurfaceWhenUnderwater() {
		GridWorld world = pond(2);
		Path path = PathFinder.find(world, new BlockPos(0, 0, 0), new Goal.Block(new BlockPos(5, 2, 0)), WALK_ONLY);

		assertTrue(path.complete(), "no way up: " + path.steps());
		assertEquals(new BlockPos(0, 1, 0), path.steps().getFirst().to());
	}

	@Test
	void doesNotSwimUnderABlockWithNoAirAbove() {
		// A bedrock wall across the floor with water under it: crossing means diving.
		GridWorld world = GridWorld.flat(10).fill(2, 2, -10, 2, 4, 10, 'B').fill(2, 1, -10, 2, 1, 10, 'W');
		Path path = find(world, new BlockPos(4, 1, 0), WALK_ONLY);

		assertFalse(path.complete(), "dived under the wall: " + path.steps());
	}

	@Test
	void entersWaterFromAHigherBankAndClimbsOutOnTheOtherSide() {
		// A moat 2 wide and 2 deep across the whole ground, its surface a block below the banks.
		GridWorld world = new GridWorld().fill(-10, -1, -10, 10, 1, 10, '#').fill(2, 0, -10, 3, 1, 10, 'W');
		Path path = PathFinder.find(world, new BlockPos(0, 2, 0), new Goal.Block(new BlockPos(5, 2, 0)), WALK_ONLY);

		assertTrue(path.complete(), "cannot cross the moat: " + path.steps());
		assertTrue(types(path).contains(MoveType.SWIM));
	}

	@Test
	void crossesAShallowWaterStripWhenThereIsNoWayRound() {
		GridWorld world = GridWorld.flat(10).fill(2, 1, -10, 2, 1, 10, 'W');
		Path path = find(world, new BlockPos(4, 1, 0), WALK_ONLY);

		assertTrue(path.complete(), "cannot cross the strip: " + path.steps());
		assertEquals(List.of(MoveType.WALK, MoveType.SWIM, MoveType.WALK, MoveType.WALK), types(path));
	}

	@Test
	void walksRoundWaterRatherThanSwimmingWhenTheDetourIsShort() {
		GridWorld world = GridWorld.flat(10).set(2, 1, 0, 'W');
		Path path = find(world, new BlockPos(4, 1, 0), WALK_ONLY);

		assertTrue(path.complete());
		assertFalse(types(path).contains(MoveType.SWIM), "swam: " + path.steps());
	}

	/**
	 * A cactus hurts a bot that brushes it from beside (#830): a path keeps off the blocks beside it, at its feet or its
	 * head, where a detour of a few blocks goes round, and still goes beside one where that is the only way.
	 */
	@Test
	void keepsOffTheBlocksBesideACactus() {
		for (int y : new int[] {1, 2}) {
			GridWorld world = GridWorld.flat(10).set(3, y, 0, 'K').set(3, 1, 0, y == 2 ? '#' : 'K');
			Path path = find(world, new BlockPos(6, 1, 0), WALK_ONLY);

			assertTrue(path.complete());
			assertTrue(positions(path).stream().noneMatch(p -> beside(world, p, 'K')), "beside a cactus: " + positions(path));
		}
		GridWorld corridor = GridWorld.flat(10).fill(0, 1, 1, 6, 2, 1, '#').fill(0, 1, -1, 6, 2, -1, '#').set(3, 2, 1, 'K');
		assertTrue(find(corridor, new BlockPos(6, 1, 0), WALK_ONLY).complete());
	}

	private static boolean beside(GridWorld world, BlockPos feet, char block) {
		return Direction.Plane.HORIZONTAL.stream().anyMatch(d -> world.at(feet.relative(d)) == block || world.at(feet.above().relative(d)) == block);
	}

	@Test
	void neverGoesThroughLava() {
		GridWorld world = GridWorld.flat(10).fill(2, 1, -10, 2, 1, 10, 'L');
		Path path = find(world, new BlockPos(4, 1, 0), new PathFinder.Options(true, false, 10_000, 3));

		assertFalse(path.complete(), "crossed lava: " + path.steps());
		assertTrue(positions(path).stream().noneMatch(p -> world.at(p) == 'L'));
	}

	@Test
	void leavesTheWaterEvenWhenTheGoalIsInReachFromIt() {
		GridWorld world = pond(2);
		Path path = PathFinder.find(world, new BlockPos(0, 1, 0), new Goal.Near(new BlockPos(0, 1, 0), 4), WALK_ONLY);

		assertTrue(path.complete());
		assertFalse(world.isWater(path.end()), "ends in the water: " + path.steps());
	}

	@Test
	void partialPathTowardAGoalInTheWaterStopsOnTheShore() {
		GridWorld world = pond(2);
		Path path = PathFinder.find(world, new BlockPos(6, 2, 0), new Goal.Block(new BlockPos(0, 1, 0)), WALK_ONLY);

		assertFalse(path.complete());
		assertFalse(world.isWater(path.end()), "ends in the water: " + path.steps());
		assertEquals(new BlockPos(3, 2, 0), path.end());
	}

	@Test
	void anAfloatGoalMayEndSwimmingWhenTheBlockIsOutOfReachFromTheBank() {
		GridWorld world = pond(2).forbidAllPlacing();
		// The bottom of the middle of the pool, out of reach from every spot on the bank.
		Goal.Reach bottom = new Goal.Reach(new BlockPos(0, 0, 0), 4.0);
		PathFinder.Options walkOnly = new PathFinder.Options(false, false, 10_000, 3);

		assertFalse(PathFinder.find(world, new BlockPos(5, 2, 0), bottom, walkOnly).complete(), "reached it from the bank");
		Path path = PathFinder.find(world, new BlockPos(5, 2, 0), new Goal.Afloat(bottom), walkOnly);
		assertTrue(path.complete(), "no way to it: " + path.steps());
		assertTrue(world.isWater(path.end()) && !world.isWater(path.end().above()), "not afloat at the surface: " + path.steps());
	}

	@Test
	void anAfloatGoalInReachFromTheBankEndsOnTheBank() {
		// A pool 5 wide and 13 long: a block by its west bank is in reach from that bank, a walk of 20 blocks round the pool,
		// and from the water a swim of two blocks out from the east bank, the cheaper way.
		GridWorld world = new GridWorld().fill(-10, -2, -10, 10, 1, 10, '#').fill(-2, 0, -6, 2, 1, 6, 'W').forbidAllPlacing();
		Goal.Reach floor = new Goal.Reach(new BlockPos(-2, 0, 0), 4.0);
		Path path = PathFinder.find(world, new BlockPos(3, 2, 0), new Goal.Afloat(floor), new PathFinder.Options(false, false, 10_000, 3));

		assertTrue(path.complete(), "no way to it: " + path.steps());
		assertFalse(world.isWater(path.end()), "ended afloat: " + path.steps());
	}

	@Test
	void anAfloatGoalSwimsOutRatherThanDigIntoTheBank() {
		// Out of reach from the dirt bank, but not from a spot dug down into it, a block from the water.
		GridWorld world = new GridWorld().fill(-10, -3, -10, 10, 1, 10, 'D').fill(-2, -1, -2, 2, 1, 2, 'W').forbidAllPlacing();
		Goal.Reach bottom = new Goal.Reach(new BlockPos(0, -1, 0), 5.0);
		PathFinder.Options digging = new PathFinder.Options(true, false, 10_000, 3);

		// Digging down into the bank beside the pool is quicker than swimming out.
		assertTrue(PathFinder.find(world, new BlockPos(5, 2, 0), bottom, digging).steps().stream().anyMatch(s -> !s.breaks().isEmpty()));
		Path path = PathFinder.find(world, new BlockPos(5, 2, 0), new Goal.Afloat(bottom), digging);
		assertTrue(path.complete(), "no way to it: " + path.steps());
		assertTrue(path.steps().stream().allMatch(s -> s.breaks().isEmpty()), "dug: " + path.steps());
	}

	@Test
	void anAfloatGoalDigsInWhereThereIsNoWaterToSwimIn() {
		// A pool under a stone lid as high as the dirt bank: its bottom is out of reach from the lid and the bank,
		// but not from a spot dug down into the bank beside the lid.
		GridWorld world = new GridWorld().fill(-10, -3, -10, 10, 1, 10, 'D').fill(-2, -1, -2, 2, 0, 2, 'W').fill(-2, 1, -2, 2, 1, 2, '#')
			.forbidAllPlacing();
		Goal.Reach bottom = new Goal.Reach(new BlockPos(1, -1, 0), 4.0);
		Path path = PathFinder.find(world, new BlockPos(5, 2, 0), new Goal.Afloat(bottom), new PathFinder.Options(true, false, 10_000, 3));

		assertTrue(path.complete(), "no way to it: " + path.steps());
		assertTrue(path.steps().stream().anyMatch(s -> !s.breaks().isEmpty()), "did not dig: " + path.steps());
	}

	@Test
	void walksThroughADoorInAWallWithoutBreakingIt() {
		GridWorld world = GridWorld.flat(10).fill(2, 1, -10, 2, 3, 10, 'B').door(2, 1, 0, Direction.Axis.X);
		Path path = find(world, new BlockPos(4, 1, 0), FULL);

		assertTrue(path.complete(), "no way through: " + path.steps());
		assertEquals(4, path.steps().size(), "went round: " + path.steps());
		assertTrue(path.steps().stream().allMatch(s -> s.breaks().isEmpty() && s.places().isEmpty()), "dug or built: " + path.steps());
	}

	@Test
	void walksAroundANetherPortalInTheWay() {
		// A portal three blocks high at x=2, z=-1..1, in an obsidian frame, with room round it.
		GridWorld world = GridWorld.flat(10).fill(2, 1, -2, 2, 4, 2, 'B').fill(2, 1, -1, 2, 3, 1, 'P');
		Path path = find(world, new BlockPos(4, 1, 0), FULL);

		assertTrue(path.complete(), "no way: " + path.steps());
		assertTrue(path.steps().stream().noneMatch(s -> world.at(s.to()) == 'P' || world.at(s.to().above()) == 'P'), "went through the portal: " + path.steps());
	}

	@Test
	void findsNoWayWhenOnlyANetherPortalLeadsThere() {
		// A wall across the floor, of stone but for the portal's obsidian frame: the bot may dig, but not next to the portal.
		GridWorld world = GridWorld.flat(10).fill(2, 1, -10, 2, 4, 10, 'B').fill(2, 1, -2, 2, 4, 2, '#').fill(2, 1, -1, 2, 3, 1, 'P');
		Path path = find(world, new BlockPos(4, 1, 0), new PathFinder.Options(true, false, 10_000, 3));

		assertFalse(path.complete(), "went through the portal or its frame: " + path.steps());
	}

	/** travel(portal) walks to the portal's floor block, so a goal of that one block may be entered. */
	@Test
	void entersANetherPortalThatIsTheGoal() {
		GridWorld world = GridWorld.flat(10).fill(2, 1, -2, 2, 4, 2, 'B').fill(2, 1, -1, 2, 3, 1, 'P');
		Path path = PathFinder.find(world, START, new Goal.Near(new BlockPos(2, 1, 0), 0), FULL);

		assertTrue(path.complete(), "did not enter: " + path.steps());
		assertEquals(new BlockPos(2, 1, 0), path.end());
	}

	@Test
	void stepsUpThroughADoorOneBlockHigher() {
		// A wall at x=2 on a step one block up, with a shut door in it at the step's height.
		GridWorld world = GridWorld.flat(10).fill(2, 1, -10, 10, 1, 10, 'B').fill(2, 2, -10, 2, 4, 10, 'B').door(2, 2, 0, Direction.Axis.X);
		Path path = find(world, new BlockPos(4, 2, 0), WALK_ONLY);

		assertTrue(path.complete(), "no way up through: " + path.steps());
		assertEquals(MoveType.STEP_UP, path.steps().get(1).type(), "did not step up into the doorway: " + path.steps());
	}

	@Test
	void dropsIntoADoorwayOneBlockLower() {
		// A step one block up at x>=3, and a wall at x=2 with a shut door in it on the floor and a hole above the door.
		GridWorld world = GridWorld.flat(10).fill(3, 1, -10, 10, 1, 10, 'B').fill(2, 1, -10, 2, 4, 10, 'B')
			.door(2, 1, 0, Direction.Axis.X).set(2, 3, 0, ' ');
		Path path = PathFinder.find(world, new BlockPos(4, 2, 0), new Goal.Block(START), WALK_ONLY);

		assertTrue(path.complete(), "no way down through: " + path.steps());
		assertEquals(MoveType.DROP, path.steps().get(1).type(), "did not drop into the doorway: " + path.steps());
	}

	@Test
	void doesNotEnterADoorFromTheSide() {
		// A door in a wall running along x, walked through north-south: from the east it is a wall.
		GridWorld world = GridWorld.flat(10).fill(2, 1, -10, 2, 3, 10, 'B').door(2, 1, 0, Direction.Axis.Z);
		Path path = find(world, new BlockPos(4, 1, 0), WALK_ONLY);

		assertFalse(path.complete(), "went through: " + path.steps());
	}

	@Test
	void placesNoBlockTouchingItsSupportOnlyDiagonally() {
		// Standing on one block in the air, the step up east would place at (1,1,0), which only touches that block at an edge.
		GridWorld world = new GridWorld().set(0, 0, 0, '#');
		Path path = find(world, new BlockPos(1, 2, 0), FULL);

		assertTrue(path.complete(), "no way up: " + path.steps());
		assertEveryPlacementHasAFace(world, path);
	}

	@Test
	void placesNoBlockAgainstOneItBreaksFirst() {
		// The only face (1,1,0) could be placed against is the block the step up east breaks to stand in.
		GridWorld world = new GridWorld().set(0, 0, 0, '#').set(0, -1, 0, 'B').set(1, 2, 0, 'D');
		Path path = find(world, new BlockPos(1, 2, 0), FULL);

		assertTrue(path.complete(), "no way up: " + path.steps());
		assertEveryPlacementHasAFace(world, path);
	}

	@Test
	void aPathEndsWhereTheBotStandsNotHalfwayDownALadder() {
		// A ladder down through a hatch to a chamber at y=1..2, the block to reach beside it. The block is in reach from
		// the ladder at y=3 already, but a bot only arrives on its feet, so the path goes on down to the floor.
		GridWorld world = GridWorld.flat(5).fill(-5, 1, -5, 5, 4, 5, '#').fill(1, 1, 0, 1, 3, 0, 'H').set(1, 4, 0, 'T')
			.fill(2, 1, 0, 2, 2, 0, ' ');
		Path path = PathFinder.find(world, new BlockPos(2, 5, 0), new Goal.Reach(new BlockPos(3, 1, 0), 4.0), WALK_ONLY);

		assertTrue(path.complete(), "no way down: " + path.steps());
		assertTrue(world.canStandOn(path.end().below()), "ends at " + path.end() + ", not standing: " + path.steps());
	}

	/** A tunnel at y=1..2 along z=0 from x=0 to x=6 in dirt, its last cell (x=6) still dirt; the area is the tunnel's cells. */
	private static final Predicate<BlockPos> TUNNEL = pos -> pos.getZ() == 0 && pos.getX() >= 0 && pos.getX() <= 6
		&& pos.getY() >= 1 && pos.getY() <= 2;

	/** Dirt from y=1 up to {@code groundTop} round {@link #TUNNEL}. */
	private static GridWorld tunnel(int groundTop) {
		return GridWorld.flat(10).fill(-10, 1, -10, 10, groundTop, 10, 'D').fill(0, 1, 0, 5, 2, 0, ' ');
	}

	@Test
	void aGoalWithinAnAreaBreaksOnlyBlocksOfTheAreaFromInsideIt() {
		// A roof over the tunnel but at its west end, so the way in is a drop at x=0. Standing on the roof right above
		// the dirt cell, digging straight down is cheaper than going round, but breaks the roof.
		GridWorld world = tunnel(3).set(0, 3, 0, ' ');
		Goal.Within goal = new Goal.Within(new Goal.Block(new BlockPos(6, 1, 0)), TUNNEL);
		BlockPos start = new BlockPos(6, 4, 0);
		assertTrue(PathFinder.find(world, start, goal.goal(), FULL).steps().stream().anyMatch(step -> step.breaks().stream().anyMatch(TUNNEL.negate())),
			"without the area the path breaks nothing outside it either");

		Path path = PathFinder.find(world, start, goal, FULL);

		assertTrue(path.complete(), "no way in: " + path.steps());
		assertBreaksOnlyFromWithin(start, path, TUNNEL);
	}

	@Test
	void aGoalWithinAnAreaDoesNotDigIntoItFromAbove() {
		// No roof: the dirt cell's top is open to the sky, so digging straight down breaks only cells of the area, but
		// from outside it.
		GridWorld world = tunnel(2);
		Goal.Within goal = new Goal.Within(new Goal.Block(new BlockPos(6, 1, 0)), TUNNEL);
		BlockPos start = new BlockPos(6, 3, 0);
		assertTrue(PathFinder.find(world, start, goal.goal(), FULL).steps().getFirst().type() == MoveType.DIG_DOWN,
			"without the area the path does not dig down either");

		Path path = PathFinder.find(world, start, goal, FULL);

		assertTrue(path.complete(), "no way in: " + path.steps());
		assertBreaksOnlyFromWithin(start, path, TUNNEL);
	}

	@Test
	void aGoalWithinAnAreaIsReachedOnlyStandingInIt() {
		Goal goal = new Goal.Within(new Goal.Near(new BlockPos(6, 1, 0), 2), TUNNEL);

		assertTrue(goal.isReached(new BlockPos(5, 1, 0)));
		assertFalse(goal.isReached(new BlockPos(6, 3, 0)), "reached from above the area");
	}

	/** Every block the path breaks is in the area, broken from a spot in it. */
	private static void assertBreaksOnlyFromWithin(BlockPos start, Path path, Predicate<BlockPos> area) {
		BlockPos from = start;
		for (Step step : path.steps()) {
			for (BlockPos pos : step.breaks()) {
				assertTrue(area.test(pos), "broke " + pos + " outside the area: " + path.steps());
				assertTrue(area.test(from), "broke " + pos + " from " + from + ", outside the area: " + path.steps());
			}
			from = step.to();
		}
	}

	/** Each block the path places has a face to place it against: a block of the world it has not broken, or one it placed before. */
	private static void assertEveryPlacementHasAFace(GridWorld world, Path path) {
		java.util.Set<BlockPos> placed = new java.util.HashSet<>();
		java.util.Set<BlockPos> broken = new java.util.HashSet<>();
		for (Step step : path.steps()) {
			broken.addAll(step.breaks());
			for (BlockPos pos : step.places()) {
				boolean face = false;
				for (Direction dir : Direction.values()) {
					BlockPos n = pos.relative(dir);
					face |= placed.contains(n) || !broken.contains(n) && !world.isPassable(n) && !world.isHazard(n);
				}
				assertTrue(face, "placed " + pos + " against nothing: " + path.steps());
				placed.add(pos);
			}
		}
	}
}
