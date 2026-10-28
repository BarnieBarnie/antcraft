package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import io.github.barniebarnie.antcraft.pathing.WorldView;
import java.util.HashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MineshaftTest {
	private static final BlockPos TOP = new BlockPos(0, 10, 0);
	/**
	 * An old shaft, from before the spiral (see {@link #old}): down to y=6, one branch each side of a 3 long hall,
	 * branches 3 long, a torch every 4 blocks.
	 */
	// Set in bootstrap(): building a Mineshaft touches the item registry, which needs the bootstrap first.
	private static Mineshaft SHAFT;
	/** Like {@link #SHAFT}, but the 2-wide spiral. */
	private static Mineshaft SPIRAL;
	/** A new shaft, the 1x1 ladder shaft, down to landings at y=8 and y=5, one branch each side of a 3 long hall. */
	private static Mineshaft LADDER;

	@BeforeAll
	static void bootstrap() {
		// Bootstraps the registries and loads the vanilla recipes and tags.
		VanillaData.recipeBook();
		SHAFT = old(TOP, List.of(6), 1, 3, 4);
		SPIRAL = spiral(TOP, List.of(6), 1, 3, 4);
		LADDER = new Mineshaft(TOP, List.of(8, 5), 1, 3, 4);
	}

	/** A shaft of the old layout, a 1-wide staircase round a 2x2 column, which colonies that dug one keep. */
	private static Mineshaft old(BlockPos top, List<Integer> levels, int branchesPerSide, int branchLength, int torchSpacing) {
		return new Mineshaft(top, levels, branchesPerSide, branchLength, torchSpacing, Mineshaft.DEFAULT_ROUNDS, Mineshaft.Layout.STAIRCASE);
	}

	/** A spiral shaft, which colonies that dug one before the ladder shaft keep. */
	private static Mineshaft spiral(BlockPos top, List<Integer> levels, int branchesPerSide, int branchLength, int torchSpacing) {
		return new Mineshaft(top, levels, branchesPerSide, branchLength, torchSpacing, Mineshaft.DEFAULT_ROUNDS, Mineshaft.Layout.SPIRAL);
	}

	private static Map<BlockPos, BuildPlan.Step> steps() {
		return SHAFT.plan().steps().stream().collect(Collectors.toMap(BuildPlan.Step::pos, step -> step));
	}

	@Test
	void stairsSpiralDownAroundATwoByTwoColumn() {
		assertEquals(List.of(new BlockPos(0, 10, 0), new BlockPos(1, 9, 0), new BlockPos(1, 8, 1), new BlockPos(0, 7, 1), new BlockPos(0, 6, 0)),
			SHAFT.stairs());
	}

	@Test
	void everyStairHasThreeBlocksOfHeadroomInThePlan() {
		Map<BlockPos, BuildPlan.Step> steps = steps();
		for (BlockPos stair : SHAFT.stairs()) {
			for (int up = 0; up < 3; up++) {
				BuildPlan.Step step = steps.get(stair.above(up));
				assertTrue(step != null && (step.isClear() || step.state().is(Blocks.TORCH)), "no headroom at " + stair.above(up));
			}
			assertTrue(steps.get(stair.below()).anySolid(), "the floor of " + stair + " is not kept solid");
		}
	}

	@Test
	void pathsKeepTheStairsHallsAndEveryRoundOfBranchesOpen() {
		Set<BlockPos> open = SPIRAL.keepOpen();
		SPIRAL.stairs().forEach(stair -> assertTrue(open.contains(stair.above()), "stair headroom " + stair.above()));
		SPIRAL.hallWork(6).orElseThrow().cells().forEach(cell -> assertTrue(open.contains(cell), "hall " + cell));
		for (int round = 0; round < SPIRAL.rounds(); round++) {
			SPIRAL.branches(6, round).forEach(branch -> branch.cells().forEach(cell -> assertTrue(open.contains(cell), "branch " + cell)));
		}
		assertFalse(open.contains(SPIRAL.landing(6).below()), "the landing's floor");
	}

	@Test
	void eachPositionHasOneStep() {
		List<BlockPos> positions = SHAFT.plan().steps().stream().map(BuildPlan.Step::pos).toList();
		assertEquals(positions.size(), new HashSet<>(positions).size());
	}

	@Test
	void planDigsDownInOrder() {
		List<BlockPos> positions = SHAFT.plan().steps().stream().map(BuildPlan.Step::pos).toList();
		// Each stair is dug before the next one down, so the builder can always stand above its work.
		for (int i = 1; i < SHAFT.stairs().size(); i++) {
			assertTrue(positions.indexOf(SHAFT.stairs().get(i - 1)) < positions.indexOf(SHAFT.stairs().get(i).above(2)));
		}
		assertTrue(SHAFT.plan().steps().stream().filter(BuildPlan.Step::isClear).allMatch(step -> step.pos().getY() >= 6), "dug below the deepest level");
	}

	@Test
	void landingHallLeadsAwayFromTheColumn() {
		// The stair at y=6 is in the column's west half, so its hall heads west.
		assertEquals(new BlockPos(0, 6, 0), SHAFT.landing(6));
		assertEquals(List.of(new BlockPos(-1, 6, 0), new BlockPos(-2, 6, 0), new BlockPos(-3, 6, 0)), SHAFT.hall(6));
		Map<BlockPos, BuildPlan.Step> steps = steps();
		for (BlockPos cell : SHAFT.hall(6)) {
			assertTrue(steps.get(cell).isClear() && steps.get(cell.above()).isClear(), "hall not dug 2 high at " + cell);
		}
	}

	@Test
	void torchesEveryFewStairsAndAtEachLanding() {
		assertEquals(List.of(new BlockPos(0, 10, 0), new BlockPos(0, 6, 0)), SHAFT.torches());
		// In solid rock the walls and floors are there already.
		assertEquals(Map.of(Items.TORCH, 2, Items.CHEST, 1), SHAFT.plan().materials(pos -> Blocks.STONE.defaultBlockState()));
		assertTrue(steps().get(new BlockPos(0, 6, 0)).state().is(Blocks.TORCH));
	}

	@Test
	void aChestBesideEachLandingClearOfStairsHallsAndBranches() {
		// The landing at y=6 is on the column's north side, so its chest goes north of it.
		BlockPos chest = SHAFT.landingChest(6);
		assertEquals(new BlockPos(0, 6, -1), chest);
		List<BuildPlan.Step> steps = SHAFT.plan().steps();
		assertTrue(steps.stream().anyMatch(step -> step.pos().equals(chest) && step.state().is(Blocks.CHEST)), "no chest in the plan");
		// Placed once the landing and its hall are dug, so the builder can stand there.
		List<BlockPos> positions = steps.stream().map(BuildPlan.Step::pos).toList();
		assertTrue(positions.indexOf(chest) > positions.indexOf(SHAFT.hall(6).getLast()));
		// A landing on each side of the column.
		Mineshaft wide = old(TOP, List.of(9, 7, 4, 2), 3, 16, 8);
		for (int level : wide.levels()) {
			BlockPos spot = wide.landingChest(level);
			assertEquals(1, spot.distManhattan(wide.landing(level)), "not beside the landing at " + level);
			assertFalse(wide.area().isInside(spot), "in the stair column at " + level);
			for (int other : wide.levels()) {
				assertFalse(wide.hall(other).contains(spot) || wide.hall(other).contains(spot.below()), "in a hall at " + level);
				assertTrue(wide.branches(other).stream().noneMatch(branch -> branch.cells().contains(spot)), "in a branch at " + level);
			}
		}
	}

	@Test
	void stairFloorsAndTorchesAreProtected() {
		Set<BlockPos> protectedBlocks = SHAFT.protectedBlocks();
		for (BlockPos stair : SHAFT.stairs()) {
			assertTrue(protectedBlocks.contains(stair.below()), "floor under " + stair);
		}
		for (BlockPos cell : SHAFT.hall(6)) {
			assertTrue(protectedBlocks.contains(cell.below()), "hall floor under " + cell);
		}
		assertTrue(protectedBlocks.containsAll(SHAFT.torches()));
	}

	@Test
	void levelsAtOrAboveTheTopAreDropped() {
		assertEquals(List.of(6, 2), old(TOP, List.of(2, 12, 10, 6), 1, 3, 4).levels());
	}

	@Test
	void levelsTooCloseToTheLevelAboveAreDropped() {
		assertEquals(List.of(8, 6, 3), old(TOP, List.of(8, 7, 6, 3), 1, 3, 4).levels());
		assertEquals(List.of(9, 7, 5), Mineshaft.spaced(List.of(5, 9, 8, 7, 6, 5)));
	}

	@Test
	void branchesOfEveryRoundKeepClearOfOtherLevelsWhenLevelsAreTwoApart() {
		for (Mineshaft.Layout layout : Mineshaft.Layout.values()) {
			branchesOfEveryRoundKeepClearOfOtherLevelsWhenLevelsAreTwoApart(layout);
		}
	}

	private static void branchesOfEveryRoundKeepClearOfOtherLevelsWhenLevelsAreTwoApart(Mineshaft.Layout layout) {
		List<Integer> levels = IntStream.iterate(48, y -> y > 20, y -> y - Mineshaft.MIN_LEVEL_GAP).boxed().toList();
		Mineshaft shaft = new Mineshaft(new BlockPos(0, 50, 0), levels, Mineshaft.DEFAULT_BRANCHES_PER_SIDE, Mineshaft.DEFAULT_BRANCH_LENGTH,
			Mineshaft.DEFAULT_TORCH_SPACING, Mineshaft.DEFAULT_ROUNDS, layout);
		assertEquals(levels, shaft.levels());
		// What the stairs and every hall dig out, with the floors under them, and each landing chest.
		Set<BlockPos> stairs = new HashSet<>();
		for (BlockPos stair : shaft.stairs()) {
			for (int up = -1; up <= 2; up++) {
				stairs.add(stair.above(up));
			}
		}
		for (int level : levels) {
			Set<BlockPos> keepClear = new HashSet<>(stairs);
			for (int other : levels) {
				if (other != level) {
					for (BlockPos cell : shaft.hall(other)) {
						keepClear.addAll(List.of(cell.below(), cell, cell.above()));
					}
					BlockPos landing = shaft.landing(other);
					keepClear.addAll(List.of(landing.below(), landing, landing.above()));
					keepClear.add(shaft.landingChest(other));
				}
			}
			for (int round = 0; round < shaft.rounds(); round++) {
				for (Mineshaft.Branch branch : shaft.branches(level, round)) {
					for (BlockPos cell : branch.cells()) {
						// Nor the floor under it, which would open the branch onto a hall below.
						assertFalse(keepClear.contains(cell) || keepClear.contains(cell.below()),
							"round " + round + " at y=" + level + " cuts into another level at " + cell);
					}
				}
			}
		}
	}

	@Test
	void branchesRunBothWaysFromTheHall() {
		List<Mineshaft.Branch> branches = SHAFT.branches(6);
		assertEquals(2, branches.size());
		Mineshaft.Branch north = branches.stream().filter(b -> b.start().getZ() < 0).findFirst().orElseThrow();
		assertEquals(new BlockPos(-3, 6, -1), north.start());
		assertEquals(List.of(new BlockPos(-3, 6, -1), new BlockPos(-3, 7, -1), new BlockPos(-3, 6, -2), new BlockPos(-3, 7, -2),
			new BlockPos(-3, 6, -3), new BlockPos(-3, 7, -3)), north.cells());
		// Halfway between torches, so the start is lit from the hall.
		assertEquals(List.of(new BlockPos(-3, 6, -2)), north.torches());
		Mineshaft.Branch south = branches.stream().filter(b -> b.start().getZ() > 0).findFirst().orElseThrow();
		assertEquals(new BlockPos(-3, 6, 1), south.start());
		assertEquals(Optional.of(south), SHAFT.branch(south.start()));
	}

	@Test
	void branchesAreAFewBlocksApart() {
		Mineshaft wide = old(TOP, List.of(6), 3, 16, 8);
		assertEquals(9, wide.hall(6).size());
		List<Integer> xs = wide.branches(6).stream().filter(b -> b.start().getZ() < 0).map(b -> b.start().getX()).toList();
		assertEquals(List.of(-3, -6, -9), xs);
		assertEquals(List.of(new BlockPos(-3, 6, -4), new BlockPos(-3, 6, -12)),
			wide.branches(6).getFirst().torches());
	}

	@Test
	void aMinedOutLevelGetsFurtherBranchesClearOfTheOldOnesAndTheShaft() {
		Mineshaft wide = new Mineshaft(TOP, List.of(6), 3, 16, 8, 3, Mineshaft.Layout.STAIRCASE);
		Set<BlockPos> taken = new HashSet<>(wide.protectedBlocks());
		wide.plan().steps().forEach(step -> taken.add(step.pos()));
		List<Mineshaft.Branch> before = List.of();
		for (int round = 0; round < wide.rounds(); round++) {
			List<Mineshaft.Branch> branches = wide.branches(6, round);
			assertEquals(6, branches.size());
			for (int i = 0; i < branches.size(); i++) {
				Mineshaft.Branch branch = branches.get(i);
				for (BlockPos cell : branch.cells()) {
					assertTrue(taken.add(cell), "round " + round + " overlaps at " + cell);
				}
				assertEquals(Optional.of(branch), wide.branch(branch.start()));
				if (round > 0) {
					// Carries on where the branch before ends, so miners walk there through it.
					List<BlockPos> last = before.get(i).cells();
					assertEquals(1, branch.start().distManhattan(last.get(last.size() - 2)), "not at the end of the branch before");
					// The torches keep their spacing across rounds: 4 and 12 blocks in, floor cells being every other cell.
					assertEquals(List.of(branch.cells().get(6), branch.cells().get(22)), branch.torches());
				}
			}
			before = branches;
		}
	}

	@Test
	void picksTheDeepestLevelWithWantedOres() {
		List<Integer> levels = List.of(16, -16, -58);
		assertEquals(Optional.of(-16), Mineshaft.chooseLevel(levels, item -> item == Items.RAW_GOLD));
		assertEquals(Optional.of(16), Mineshaft.chooseLevel(levels, item -> item == Items.RAW_COPPER));
		assertEquals(Optional.of(-58), Mineshaft.chooseLevel(levels, item -> item == Items.DIAMOND));
		// Nothing in particular wanted: the deepest has the best ores.
		assertEquals(Optional.of(-58), Mineshaft.chooseLevel(levels, item -> false));
		assertEquals(Optional.empty(), Mineshaft.chooseLevel(List.of(), item -> true));
	}

	@Test
	void minesOnlyWhileTheColonyWantsWhatMiningYields() {
		assertFalse(Mineshaft.yieldsWanted(item -> false));
		assertFalse(Mineshaft.yieldsWanted(item -> item == Items.OAK_LOG || item == Items.WHEAT));
		assertTrue(Mineshaft.yieldsWanted(item -> item == Items.COBBLESTONE));
		assertTrue(Mineshaft.yieldsWanted(item -> item == Items.COBBLED_DEEPSLATE));
		assertTrue(Mineshaft.yieldsWanted(item -> item == Items.RAW_IRON));
		assertTrue(Mineshaft.yieldsWanted(item -> item == Items.DIAMOND));
	}

	@Test
	void noBranchJobsWithNothingMinedWantedAndWantingRawIronPostsTheIronLevel() {
		Mineshaft shaft = new Mineshaft(new BlockPos(0, 40, 0), List.of(16, -16, -58));
		Map<Integer, List<Mineshaft.Branch>> work = new HashMap<>();
		shaft.levels().forEach(y -> work.put(y, shaft.branches(y)));

		assertEquals(List.of(), Mineshaft.branchesToPost(work, item -> false));
		assertEquals(List.of(), Mineshaft.branchesToPost(work, item -> item == Items.OAK_LOG));
		// Iron is found at 16 and -16; the deepest of those.
		assertEquals(shaft.branches(-16), Mineshaft.branchesToPost(work, item -> item == Items.RAW_IRON));
		// Stone is everywhere: the deepest level.
		assertEquals(shaft.branches(-58), Mineshaft.branchesToPost(work, item -> item == Items.COBBLESTONE));
	}

	@Test
	void survivesCodecRoundTrip() {
		for (Mineshaft shaft : List.of(SHAFT, SPIRAL, LADDER)) {
			var json = Mineshaft.CODEC.encodeStart(JsonOps.INSTANCE, shaft).getOrThrow();
			assertEquals(shaft, Mineshaft.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
		}
	}

	@Test
	void aShaftSavedBeforeTheSpiralKeepsTheOldLayout() {
		var json = Mineshaft.CODEC.encodeStart(JsonOps.INSTANCE, SHAFT).getOrThrow().getAsJsonObject();
		json.remove("layout");
		json.remove("spiral");
		assertEquals(SHAFT, Mineshaft.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
	}

	@Test
	void aSpiralSavedBeforeTheLadderShaftKeepsItsLayout() {
		var json = Mineshaft.CODEC.encodeStart(JsonOps.INSTANCE, SPIRAL).getOrThrow().getAsJsonObject();
		json.remove("layout");
		json.addProperty("spiral", true);
		assertEquals(SPIRAL, Mineshaft.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
		json.addProperty("spiral", false);
		assertEquals(Mineshaft.Layout.STAIRCASE, Mineshaft.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().layout());
	}

	@Test
	void aShaftWithoutBranchesGetsOneASide() {
		var json = Mineshaft.CODEC.encodeStart(JsonOps.INSTANCE, SPIRAL).getOrThrow().getAsJsonObject();
		json.addProperty("branches_per_side", 0);
		Mineshaft loaded = Mineshaft.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
		assertEquals(1, loaded.branchesPerSide());
		assertEquals(SPIRAL.hallWork(6), loaded.hallWork(6));
		// An old shaft: ColonyManager waits for the end of its hall to be dug.
		assertEquals(SHAFT.hall(6).getLast(), old(TOP, List.of(6), 0, 3, 4).hall(6).getLast());
	}

	@Test
	void aShaftWithoutBranchLengthGetsOneBlock() {
		var json = Mineshaft.CODEC.encodeStart(JsonOps.INSTANCE, SPIRAL).getOrThrow().getAsJsonObject();
		json.addProperty("branch_length", 0);
		Mineshaft loaded = Mineshaft.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
		assertEquals(1, loaded.branchLength());
		assertEquals(2, loaded.branches(6).getFirst().cells().size());
	}

	@Test
	void siteBesideTheCoreKeepsClearOfTheAreasToAvoid() {
		BlockPos core = new BlockPos(0, 64, 0);
		List<Integer> levels = List.of(40);
		Mineshaft first = Mineshaft.nextTo(core, levels, List.of(), ROCK);
		// An area right where the shaft would go, and another beside the core.
		BoundingBox taken = BoundingBox.fromCorners(first.top().offset(-1, 0, -1), first.top().offset(1, 1, 1));
		BoundingBox other = BoundingBox.fromCorners(core.offset(-6, -1, -6), core.offset(-2, -1, -2));
		Mineshaft shaft = Mineshaft.nextTo(core, levels, List.of(taken, other), ROCK);
		assertFalse(shaft.area().intersects(taken), "shaft overlaps the first area at " + shaft.top());
		assertFalse(shaft.area().intersects(other), "shaft overlaps the second area at " + shaft.top());
	}

	/** The positions of a batch's steps, but for the walls and floors it keeps solid. */
	private static Set<BlockPos> batch(Mineshaft shaft, int batch) {
		return shaft.plan().batch(batch).steps().stream().filter(step -> !step.anySolid()).map(BuildPlan.Step::pos).collect(Collectors.toSet());
	}

	@Test
	void planIsBatchedPerStairSegmentAndPerHall() {
		Mineshaft shaft = old(TOP, List.of(8, 6), 1, 3, 4);
		// Stairs down to y=8, its hall, the stairs on down to y=6, its hall.
		assertEquals(List.of(List.of(), List.of(0), List.of(0), List.of(2)), shaft.plan().batches().stream().map(BuildPlan.Batch::needs).toList());

		Set<BlockPos> firstStairs = new HashSet<>();
		shaft.stairs().subList(0, 3).forEach(stair -> IntStream.range(0, 3).forEach(up -> firstStairs.add(stair.above(up))));
		assertEquals(firstStairs, batch(shaft, 0));
		Set<BlockPos> firstHall = new HashSet<>(Set.of(shaft.landingChest(8)));
		shaft.hall(8).forEach(cell -> firstHall.addAll(List.of(cell, cell.above())));
		assertEquals(firstHall, batch(shaft, 1));
		assertTrue(batch(shaft, 2).contains(shaft.landing(6)), "the second stair segment does not reach the landing");
		assertTrue(batch(shaft, 3).containsAll(shaft.hall(6)) && batch(shaft, 3).contains(shaft.landingChest(6)), "second hall not one batch");
	}

	@Test
	void aHallWaitsForTheStairsThatReachIt() {
		BuildPlan plan = old(TOP, List.of(8, 6), 1, 3, 4).plan();
		assertEquals(List.of(0), plan.ready(Set.of()));
		// Once the stairs reach y=8, its hall and the stairs on down can be dug at the same time.
		assertEquals(List.of(1, 2), plan.ready(Set.of(0)));
		assertEquals(List.of(1, 3), plan.ready(Set.of(0, 2)));
	}

	/** Solid rock, but for some fluid and some bedrock. */
	private static WorldView rock(Predicate<BlockPos> fluid, Predicate<BlockPos> bedrock) {
		return rock(fluid, bedrock, pos -> false);
	}

	/** Solid rock, but for some fluid, some bedrock and some barriers, which cannot be broken either. */
	private static WorldView rock(Predicate<BlockPos> fluid, Predicate<BlockPos> bedrock, Predicate<BlockPos> barrier) {
		return new WorldView() {
			@Override
			public boolean isBedrock(BlockPos pos) {
				return bedrock.test(pos);
			}

			@Override
			public boolean isPassable(BlockPos pos) {
				return false;
			}

			@Override
			public boolean canStandOn(BlockPos pos) {
				return true;
			}

			@Override
			public boolean isHazard(BlockPos pos) {
				return fluid.test(pos);
			}

			@Override
			public float breakTicks(BlockPos pos) {
				return bedrock.test(pos) || barrier.test(pos) ? -1 : 30;
			}
		};
	}

	private static final WorldView ROCK = rock(pos -> false, pos -> false);

	@Test
	void siteSkipsASpotWhoseColumnHitsFluidOrBedrockAboveTheFirstLevel() {
		BlockPos core = new BlockPos(0, 64, 0);
		List<Integer> levels = List.of(40, 20);
		Mineshaft first = Mineshaft.nextTo(core, levels, List.of(), ROCK);
		BlockPos water = first.top().offset(1, -10, 0);
		Mineshaft second = Mineshaft.nextTo(core, levels, List.of(), rock(water::equals, pos -> false));
		assertFalse(second.area().isInside(water), "shaft at " + second.top() + " runs into water");
		BlockPos bedrock = second.top().offset(0, -20, 1);
		Mineshaft third = Mineshaft.nextTo(core, levels, List.of(), rock(water::equals, bedrock::equals));
		assertFalse(third.area().isInside(water) || third.area().isInside(bedrock), "shaft at " + third.top() + " runs into water or bedrock");
		assertEquals(levels, third.levels());
		// Below the first level the builder walls fluid off, so it does not move the site.
		BlockPos deep = first.top().offset(0, -30, 0);
		assertEquals(first, Mineshaft.nextTo(core, levels, List.of(), rock(deep::equals, pos -> false)));
	}

	@Test
	void levelsAtOrBelowBedrockInTheColumnAreDropped() {
		BlockPos core = new BlockPos(0, 64, 0);
		List<Integer> levels = List.of(40, 30, 25, 20);
		Mineshaft first = Mineshaft.nextTo(core, levels, List.of(), ROCK);
		BlockPos bedrock = first.top().atY(25).offset(1, 0, 1);
		Mineshaft shaft = Mineshaft.nextTo(core, levels, List.of(), rock(pos -> false, bedrock::equals));
		assertEquals(first.top(), shaft.top());
		assertEquals(List.of(40, 30), shaft.levels());
		// Bedrock everywhere above the first level: no spot is good, so the first one gets no levels.
		Mineshaft none = Mineshaft.nextTo(core, List.of(40), List.of(), rock(pos -> false, pos -> pos.getY() == 50));
		assertEquals(first.top(), none.top());
		assertEquals(List.of(), none.levels());
	}

	/** Every block the plan digs out, to stand or walk in. */
	private static Set<BlockPos> dug(Mineshaft shaft) {
		Set<BlockPos> dug = new HashSet<>();
		shaft.stairs().forEach(stair -> IntStream.range(0, 3).forEach(up -> dug.add(stair.above(up))));
		shaft.levels().forEach(level -> shaft.hall(level).forEach(cell -> dug.addAll(List.of(cell, cell.above()))));
		return dug;
	}

	@Test
	void wallsFloorsAndCeilingsOfTheStairsAndHallsAreKeptSolidBeforeTheyAreDug() {
		Mineshaft shaft = old(TOP, List.of(8, 6), 1, 3, 4);
		List<BuildPlan.Step> steps = shaft.plan().steps();
		Map<BlockPos, Integer> index = new HashMap<>();
		for (int i = 0; i < steps.size(); i++) {
			index.put(steps.get(i).pos(), i);
		}
		Set<BlockPos> dug = dug(shaft);
		Set<BlockPos> branches = new HashSet<>();
		Set<BlockPos> open = new HashSet<>();
		for (int level : shaft.levels()) {
			shaft.branches(level).forEach(branch -> branches.addAll(branch.cells()));
			open.add(shaft.landingChest(level));
		}
		for (BlockPos cell : dug) {
			for (Direction side : Direction.values()) {
				BlockPos wall = cell.relative(side);
				if (dug.contains(wall) || branches.contains(wall) || open.contains(wall) || wall.getY() >= TOP.getY()) {
					continue;
				}
				assertTrue(index.containsKey(wall) && steps.get(index.get(wall)).anySolid(), "not kept solid beside " + cell + ": " + wall);
				assertTrue(index.get(wall) < index.get(cell), "wall " + wall + " goes up after " + cell + " is dug");
			}
		}
		// Miners dig the branches; the entrance stays open.
		assertTrue(steps.stream().noneMatch(step -> branches.contains(step.pos())), "the plan walls off a branch");
		assertTrue(steps.stream().filter(BuildPlan.Step::anySolid).allMatch(step -> step.pos().getY() < TOP.getY()), "walls above ground");
	}

	@Test
	void theBlockPastEachHallsEndIsWalled() {
		Mineshaft shaft = old(TOP, List.of(8, 6), 1, 3, 4);
		Map<BlockPos, BuildPlan.Step> steps = shaft.plan().steps().stream().collect(Collectors.toMap(BuildPlan.Step::pos, step -> step));
		for (int level : shaft.levels()) {
			List<BlockPos> hall = shaft.hall(level);
			BlockPos past = hall.getLast().offset(hall.getLast().subtract(hall.get(hall.size() - 2)));
			for (BlockPos end : List.of(past, past.above())) {
				assertTrue(steps.containsKey(end) && steps.get(end).anySolid(), "not walled past the hall at y=" + level + ": " + end);
			}
		}
	}

	@Test
	void wallStepsAreDoneWithAnyFillerAndOrderOnlyWhatTheFillersLack() {
		BuildPlan plan = SHAFT.plan();
		BuildPlan.Step wall = plan.steps().stream().filter(BuildPlan.Step::anySolid).findFirst().orElseThrow();
		for (BlockState filler : List.of(Blocks.DIRT.defaultBlockState(), Blocks.COBBLED_DEEPSLATE.defaultBlockState())) {
			assertTrue(wall.isDone(filler), "a wall of " + filler + " is not done");
		}
		// Dug through open air, every wall is to be filled; dirt and cobbled deepslate stand in for the cobblestone.
		Function<BlockPos, BlockState> air = pos -> Blocks.AIR.defaultBlockState();
		Map<Item, Integer> materials = plan.materials(air);
		Map<Item, Integer> fills = plan.fillMaterials(air, false);
		int walls = fills.get(Items.COBBLESTONE);
		assertEquals(walls, materials.get(Items.COBBLESTONE));
		Map<Item, Integer> has = Map.of(Items.DIRT, walls / 2, Items.COBBLED_DEEPSLATE, walls - walls / 2);
		assertEquals(Map.of(Items.TORCH, 2, Items.CHEST, 1), BuildPlan.shortfall(materials, fills, Map.of(), has));
	}

	@Test
	void wantedFillBlocksForAMineshaftExcludeSandAndGravel() {
		Function<BlockPos, BlockState> air = pos -> Blocks.AIR.defaultBlockState();
		Set<Item> wanted = SHAFT.plan().wantedFillBlocks(air);
		assertEquals(BuildPlan.HOLDING_FILL_BLOCKS, wanted);
		for (Item falling : List.of(Items.SAND, Items.RED_SAND, Items.GRAVEL)) {
			assertFalse(wanted.contains(falling), falling + " is wanted for the walls");
		}
	}

	@Test
	void siteIsFoundFartherOutWhenTheFourSpotsBesideTheCoreAreUnusable() {
		BlockPos core = new BlockPos(0, 64, 0);
		List<Integer> levels = List.of(40, 20);
		// Water under every spot within a few blocks of the core.
		Predicate<BlockPos> nearWater = pos -> pos.getY() == 50 && Math.abs(pos.getX()) <= 4 && Math.abs(pos.getZ()) <= 4;
		Mineshaft wet = Mineshaft.nextTo(core, levels, List.of(), rock(nearWater, pos -> false));
		assertFalse(wet.area().intersects(new BoundingBox(-4, 50, -4, 4, 50, 4)), "shaft at " + wet.top() + " runs into water");
		assertEquals(levels, wet.levels());
		// Storage and the field all around the core.
		List<BoundingBox> avoid = List.of(new BoundingBox(-4, 60, -4, 4, 70, 4));
		Mineshaft clear = Mineshaft.nextTo(core, levels, avoid, ROCK);
		assertFalse(clear.area().intersects(avoid.getFirst()), "shaft at " + clear.top() + " overlaps the storage");
		assertTrue(clear.top().distManhattan(core) <= 24, "shaft at " + clear.top() + " is far from the core");
	}

	@Test
	void aBlockedStairDropsTheLevelsBelowItAndABlockedHallOnlyItsLevel() {
		Mineshaft shaft = old(TOP, List.of(8, 6, 4), 1, 3, 4);
		// On the way from y=8 down to y=6.
		BlockPos stair = shaft.stairs().get(3);
		assertEquals(old(TOP, List.of(8), 1, 3, 4), shaft.reachable(List.of(stair)));
		assertEquals(List.of(8), shaft.reachable(List.of(stair.below())).levels());
		assertEquals(List.of(8, 4), shaft.reachable(List.of(shaft.hall(6).get(1))).levels());
		assertEquals(List.of(), shaft.reachable(List.of(shaft.stairs().get(1))).levels());
		assertEquals(shaft, shaft.reachable(List.of(new BlockPos(100, 0, 100))));
	}

	/** The cells of the spiral at the heights of one full turn from the top: 4 heights, each one side further round. */
	private static List<BlockPos> oneTurn(Mineshaft shaft) {
		return shaft.stairs().stream().filter(cell -> cell.getY() > shaft.top().getY() - 4).toList();
	}

	@Test
	void spiralStairsOfOneTurnFillASixBySixRingAroundATwoByTwoCentre() {
		List<BlockPos> turn = oneTurn(SPIRAL);
		Set<BlockPos> footprint = turn.stream().map(cell -> cell.atY(0)).collect(Collectors.toSet());
		assertEquals(turn.size(), footprint.size(), "a cell twice in one turn");
		Set<BlockPos> ring = new HashSet<>();
		for (int x = 0; x < 6; x++) {
			for (int z = 0; z < 6; z++) {
				// The centre column stays solid.
				if (x < 2 || x > 3 || z < 2 || z > 3) {
					ring.add(new BlockPos(x, 0, z));
				}
			}
		}
		assertEquals(ring, footprint);
		// Each height is a flat 2x2 corner with the 2-wide rows either side of it, one corner further round than the height above.
		Map<Integer, Long> perHeight = turn.stream().collect(Collectors.groupingBy(BlockPos::getY, Collectors.counting()));
		assertEquals(Map.of(10, 8L, 9, 8L, 8, 8L, 7, 8L), perHeight);
		Mineshaft deep = spiral(TOP, List.of(9, 7, 5), 1, 3, 4);
		assertEquals(List.of(new BlockPos(5, 9, 0), new BlockPos(0, 7, 5), new BlockPos(5, 5, 0)), deep.levels().stream().map(deep::landing).toList());
	}

	@Test
	void everySpiralStepHasThreeBlocksOfHeadroomAndAFloor() {
		Map<BlockPos, BuildPlan.Step> steps = SPIRAL.plan().steps().stream().collect(Collectors.toMap(BuildPlan.Step::pos, step -> step));
		for (BlockPos cell : SPIRAL.stairs()) {
			for (int up = 0; up < 3; up++) {
				BuildPlan.Step step = steps.get(cell.above(up));
				assertTrue(step != null && (step.isClear() || step.state().is(Blocks.TORCH)), "no headroom at " + cell.above(up));
			}
			BuildPlan.Step floor = steps.get(cell.below());
			assertTrue(floor != null && (floor.anySolid() || floor.state().getBlock() instanceof StairBlock), "no floor under " + cell);
		}
		assertTrue(SPIRAL.protectedBlocks().containsAll(SPIRAL.stairs().stream().map(BlockPos::below).toList()), "stair floors not protected");
	}

	@Test
	void theSpiralPlanHasAStairBlockFacingUpOnEveryStepALandingAtEachLevelAndNoHallOrBranch() {
		Mineshaft shaft = spiral(TOP, List.of(8, 5), 1, 3, 4);
		Map<BlockPos, BuildPlan.Step> steps = shaft.plan().steps().stream().collect(Collectors.toMap(BuildPlan.Step::pos, step -> step));
		Set<BlockPos> cells = new HashSet<>(shaft.stairs());
		int stairs = 0;
		for (BlockPos lower : cells) {
			for (Direction up : Direction.Plane.HORIZONTAL) {
				BlockPos upper = lower.relative(up).above();
				if (cells.contains(upper)) {
					// A step up: walked onto its stair block, which faces the way up, rather than jumped.
					BlockState floor = steps.get(upper.below()).state();
					assertTrue(floor.is(Blocks.COBBLESTONE_STAIRS), "no stair block under " + upper + ": " + floor);
					assertEquals(up, floor.getValue(StairBlock.FACING), "the stair under " + upper + " faces the wrong way");
					assertEquals(Half.BOTTOM, floor.getValue(StairBlock.HALF));
					// Any stone stairs will do, the same way round; wooden ones will not.
					BuildPlan.Step step = steps.get(upper.below());
					assertTrue(step.isDone(floor), "cobblestone stairs do not count under " + upper);
					assertTrue(step.isDone(Blocks.STONE_BRICK_STAIRS.withPropertiesOf(floor)), "stone brick stairs do not count under " + upper);
					assertFalse(step.isDone(Blocks.OAK_STAIRS.withPropertiesOf(floor)), "oak stairs count under " + upper);
					assertFalse(step.isDone(Blocks.STONE_BRICK_STAIRS.withPropertiesOf(floor).setValue(StairBlock.FACING, up.getOpposite())),
						"stone brick stairs the wrong way round count under " + upper);
					stairs++;
				}
			}
		}
		// Each side of the spiral has one 2-wide stair, down to y=5: 5 sides.
		assertEquals(10, stairs);
		for (int level : shaft.levels()) {
			BlockPos landing = shaft.landing(level);
			assertEquals(level, landing.getY());
			// A flat 2x2 corner, for several bots, with its chest beside it.
			List<BlockPos> corner = cells.stream().filter(cell -> cell.getY() == level && Math.abs(cell.getX() - landing.getX()) <= 1
				&& Math.abs(cell.getZ() - landing.getZ()) <= 1 && steps.get(cell.below()).anySolid()).toList();
			assertEquals(4, corner.size(), "no flat 2x2 landing at " + level);
			corner.forEach(cell -> assertTrue(steps.get(cell).isClear() || steps.get(cell).state().is(Blocks.TORCH), "landing not dug at " + cell));
			assertTrue(steps.get(shaft.landingChest(level)).state().is(Blocks.CHEST), "no chest at landing " + level);
			assertEquals(1, shaft.landingChest(level).distManhattan(landing));
			// The hall and branches are mining work, not part of the shaft.
			assertTrue(shaft.hall(level).stream().noneMatch(cell -> steps.containsKey(cell) || steps.containsKey(cell.above())), "hall in the plan at " + level);
			assertTrue(shaft.branches(level).stream().flatMap(branch -> branch.cells().stream()).noneMatch(steps::containsKey), "branch in the plan at " + level);
			assertTrue(shaft.hallWork(level).isPresent(), "no hall to mine at " + level);
		}
		assertEquals(Items.COBBLESTONE_STAIRS, shaft.plan().materials(pos -> Blocks.STONE.defaultBlockState()).keySet().stream()
			.filter(item -> item != Items.CHEST && item != Items.TORCH).findFirst().orElseThrow());
		assertEquals(10, shaft.plan().materials(pos -> Blocks.STONE.defaultBlockState()).get(Items.COBBLESTONE_STAIRS));
		assertEquals(2, shaft.plan().materials(pos -> Blocks.STONE.defaultBlockState()).get(Items.CHEST));
	}

	@Test
	void theHallOfASpiralLevelIsMiningWorkLeadingAwayFromTheLanding() {
		Mineshaft.Branch hall = SPIRAL.hallWork(6).orElseThrow();
		assertEquals(List.of(new BlockPos(-1, 6, 0), new BlockPos(-2, 6, 0), new BlockPos(-3, 6, 0)), SPIRAL.hall(6));
		assertEquals(SPIRAL.hall(6).getFirst(), hall.start());
		assertEquals(Set.of(new BlockPos(-1, 6, 0), new BlockPos(-1, 7, 0), new BlockPos(-2, 6, 0), new BlockPos(-2, 7, 0),
			new BlockPos(-3, 6, 0), new BlockPos(-3, 7, 0)), Set.copyOf(hall.cells()));
		assertEquals(Optional.of(hall), SPIRAL.branch(hall.start()));
		// An old shaft's plan digs its halls itself.
		assertEquals(Optional.empty(), SHAFT.hallWork(6));
	}

	@Test
	void aBranchOf12BlocksGivesThreeSegmentJobsEachReadyAfterTheOneBefore() {
		Mineshaft shaft = old(TOP, List.of(6), 1, 12, 8);
		Mineshaft.Branch branch = shaft.branches(6).getFirst();
		List<Mineshaft.Branch> segments = branch.segments();

		assertEquals(3, segments.size());
		assertEquals(branch.cells(), segments.stream().flatMap(segment -> segment.cells().stream()).toList());
		assertEquals(branch.torches(), segments.stream().flatMap(segment -> segment.torches().stream()).toList());
		assertEquals(Optional.of(segments.get(1)), shaft.segment(segments.get(1).start()));
		Set<BlockPos> dug = new HashSet<>();
		for (int i = 0; i < segments.size(); i++) {
			for (int j = 0; j < segments.size(); j++) {
				assertEquals(j <= i, shaft.segmentReady(segments.get(j).start(), dug::contains), "segment " + j + " with " + i + " dug");
			}
			dug.addAll(segments.get(i).cells());
		}
		// A new shaft's hall, 12 long with 4 branches a side, is split the same way.
		assertEquals(3, new Mineshaft(TOP, List.of(6)).hallWork(6).orElseThrow().segments().size());
	}

	@Test
	void aSiteWhoseHallCrossesWaterIsRejected() {
		BlockPos core = new BlockPos(0, 64, 0);
		List<Integer> levels = List.of(40, 20);
		Mineshaft first = Mineshaft.nextTo(core, levels, List.of(), ROCK);
		// Under the first level, where the column alone is not checked.
		BlockPos water = first.hall(20).get(2).above();
		Mineshaft other = Mineshaft.nextTo(core, levels, List.of(), rock(water::equals, pos -> false));
		assertFalse(other.top().equals(first.top()), "the hall at y=20 runs into water");
		assertEquals(levels, other.levels());
	}

	@Test
	void aBarrierIsNotTakenForBedrock() {
		BlockPos core = new BlockPos(0, 64, 0);
		List<Integer> levels = List.of(40, 20);
		Mineshaft first = Mineshaft.nextTo(core, levels, List.of(), ROCK);
		BlockPos barrier = first.top().offset(1, -30, 1);
		assertEquals(first, Mineshaft.nextTo(core, levels, List.of(), rock(pos -> false, pos -> false, barrier::equals)));
	}

	private static Map<BlockPos, BuildPlan.Step> steps(Mineshaft shaft) {
		return shaft.plan().steps().stream().collect(Collectors.toMap(BuildPlan.Step::pos, step -> step));
	}

	private static int index(Mineshaft shaft, BlockPos pos) {
		return shaft.plan().steps().stream().map(BuildPlan.Step::pos).toList().indexOf(pos);
	}

	@Test
	void aNewShaftIsALadderShaft() {
		assertEquals(Mineshaft.Layout.LADDER, LADDER.layout());
		BlockPos core = new BlockPos(0, 64, 0);
		Mineshaft site = Mineshaft.nextTo(core, List.of(40), List.of(), ROCK);
		assertEquals(Mineshaft.Layout.LADDER, site.layout());
		// A block between the shaft and the core.
		assertFalse(site.area().inflatedBy(1).isInside(core), "shaft at " + site.top() + " touches the core");
	}

	@Test
	void theLadderShaftIsAOneByOneColumnUnderAHatchWithALadderAtEveryHeight() {
		// From the hatch in the ground, just below the top, down to the deepest landing.
		assertEquals(IntStream.rangeClosed(5, 9).map(y -> 14 - y).mapToObj(y -> new BlockPos(0, y, 0)).toList(), LADDER.stairs());
		Map<BlockPos, BuildPlan.Step> steps = steps(LADDER);
		BlockState hatch = steps.get(new BlockPos(0, 9, 0)).state();
		assertTrue(hatch.is(Blocks.OAK_TRAPDOOR), "no hatch: " + hatch);
		assertEquals(Direction.NORTH, hatch.getValue(TrapDoorBlock.FACING));
		assertEquals(Half.TOP, hatch.getValue(TrapDoorBlock.HALF));
		for (int y = 5; y <= 8; y++) {
			BlockPos cell = new BlockPos(0, y, 0);
			BlockState ladder = steps.get(cell).state();
			assertTrue(ladder.is(Blocks.LADDER), "no ladder at " + cell + ": " + ladder);
			// Facing the way the hatch does, so the hatch counts as part of the ladder.
			assertEquals(Direction.NORTH, ladder.getValue(LadderBlock.FACING));
			// The wall behind it is made solid before it goes up.
			BlockPos wall = cell.south();
			assertTrue(steps.get(wall).anySolid(), "no wall behind the ladder at " + cell);
			assertTrue(index(LADDER, wall) < index(LADDER, cell), "the wall behind " + cell + " goes up after the ladder");
			// Its other sides are walled too, where no landing opens off it.
			for (BlockPos side : List.of(cell.north(), cell.east(), cell.west())) {
				boolean chamber = LADDER.levels().stream().anyMatch(level -> LADDER.landing(level).equals(side) || LADDER.landing(level).above().equals(side));
				assertTrue(steps.get(side).anySolid() || chamber,
					"column not walled at " + side);
			}
		}
		assertTrue(steps.get(new BlockPos(0, 4, 0)).anySolid(), "no floor under the column");
		// The entrance above the hatch is kept clear.
		assertTrue(steps.get(TOP).isClear() && steps.get(TOP.above()).isClear(), "the entrance is not cleared");
		// Dug from the top down: each ladder before the next cell down.
		for (int y = 8; y > 5; y--) {
			assertTrue(index(LADDER, new BlockPos(0, y, 0)) < index(LADDER, new BlockPos(0, y - 1, 0)), "dug out of order at " + y);
		}
		assertTrue(LADDER.area().isInside(new BlockPos(0, 5, 0)) && LADDER.area().isInside(new BlockPos(1, 8, 0)), "column outside the area");
	}

	@Test
	void theLadderShaftHatchTakesAnyWoodenTrapdoorTheSameWayRound() {
		BuildPlan.Step hatch = steps(LADDER).get(new BlockPos(0, 9, 0));
		BlockState spruce = Blocks.SPRUCE_TRAPDOOR.withPropertiesOf(hatch.state());
		assertTrue(hatch.isDone(spruce), "a spruce trapdoor is no hatch");
		assertTrue(hatch.isDone(Blocks.CRIMSON_TRAPDOOR.withPropertiesOf(hatch.state())), "a crimson trapdoor is no hatch");
		assertFalse(hatch.isDone(spruce.setValue(TrapDoorBlock.HALF, Half.BOTTOM)), "a trapdoor the wrong way round is a hatch");
		assertFalse(hatch.isDone(Blocks.IRON_TRAPDOOR.withPropertiesOf(hatch.state())), "an iron trapdoor, which no bot opens, is a hatch");
		// Saved and loaded with the step.
		BuildPlan plan = new BuildPlan(List.of(hatch));
		var json = BuildPlan.CODEC.encodeStart(JsonOps.INSTANCE, plan).getOrThrow();
		assertEquals(plan, BuildPlan.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
	}

	@Test
	void eachLadderShaftLevelHasALandingChamberWithAChestAndATorchAndTheHallLeavesFromIt() {
		Map<BlockPos, BuildPlan.Step> steps = steps(LADDER);
		// The landings take turns either side of the column, so one level's chamber is never right under another's.
		assertEquals(new BlockPos(1, 8, 0), LADDER.landing(8));
		assertEquals(new BlockPos(-1, 5, 0), LADDER.landing(5));
		assertEquals(List.of(new BlockPos(2, 8, 0), new BlockPos(3, 8, 0), new BlockPos(4, 8, 0)), LADDER.hall(8));
		assertEquals(List.of(new BlockPos(-2, 5, 0), new BlockPos(-3, 5, 0), new BlockPos(-4, 5, 0)), LADDER.hall(5));
		for (int level : LADDER.levels()) {
			BlockPos landing = LADDER.landing(level);
			assertTrue(steps.get(landing).state().is(Blocks.TORCH), "no torch in the chamber at " + level);
			assertTrue(steps.get(landing.above()).isClear(), "chamber not 2 high at " + level);
			assertTrue(steps.get(landing.below()).anySolid(), "no chamber floor at " + level);
			BlockPos chest = LADDER.landingChest(level);
			assertEquals(1, chest.distManhattan(landing));
			assertTrue(steps.get(chest).state().is(Blocks.CHEST), "no chest at " + level);
			assertEquals(Direction.SOUTH, steps.get(chest).state().getValue(ChestBlock.FACING), "the chest does not face the chamber");
			// Miners dig the hall and branches, as in the spiral.
			assertTrue(LADDER.hallWork(level).isPresent(), "no hall to mine at " + level);
			assertTrue(LADDER.hall(level).stream().noneMatch(cell -> steps.containsKey(cell) || steps.containsKey(cell.above())), "hall in the plan at " + level);
			assertTrue(LADDER.branches(level).stream().flatMap(branch -> branch.cells().stream()).noneMatch(steps::containsKey), "branch in the plan at " + level);
		}
		assertEquals(List.of(new BlockPos(1, 8, 0), new BlockPos(-1, 5, 0)), LADDER.torches());
	}

	@Test
	void theLadderShaftIsBatchedPerLevelAndPutsTheHatchOnOnceTheLadderUnderItIsUp() {
		BuildPlan plan = LADDER.plan();
		assertEquals(List.of(List.of(), List.of(0)), plan.batches().stream().map(BuildPlan.Batch::needs).toList());
		Set<BlockPos> first = batch(LADDER, 0);
		assertTrue(first.containsAll(List.of(new BlockPos(0, 9, 0), new BlockPos(0, 8, 0), LADDER.landing(8), LADDER.landingChest(8))), "first batch " + first);
		assertTrue(batch(LADDER, 1).containsAll(List.of(new BlockPos(0, 7, 0), new BlockPos(0, 5, 0), LADDER.landing(5), LADDER.landingChest(5))));
		// The builder digs through the ground where the hatch goes on its way down, so the hatch goes in once the top
		// ladder is up, before the next cell is dug: the hatch is how bots get in and out.
		int hatch = index(LADDER, new BlockPos(0, 9, 0));
		assertTrue(index(LADDER, new BlockPos(0, 8, 0)) < hatch, "the hatch goes in before the ladder under it");
		assertTrue(hatch < index(LADDER, new BlockPos(0, 7, 0)), "the hatch goes in after the next cell is dug");
		// In solid rock: a ladder per height, the hatch, a torch and chest a level.
		assertEquals(Map.of(Items.LADDER, 4, Items.OAK_TRAPDOOR, 1, Items.TORCH, 2, Items.CHEST, 2),
			plan.materials(pos -> pos.getY() < TOP.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState()));
		// A blocked ladder drops its level and those below.
		assertEquals(List.of(8), LADDER.reachable(List.of(new BlockPos(0, 6, 0))).levels());
	}

	@Test
	void theLaddersTheWallsBehindThemAndTheFloorsAreProtectedAndTheColumnKeptOpen() {
		Set<BlockPos> protectedBlocks = LADDER.protectedBlocks();
		for (int y = 5; y <= 8; y++) {
			BlockPos cell = new BlockPos(0, y, 0);
			assertTrue(protectedBlocks.contains(cell), "ladder at " + cell);
			assertTrue(protectedBlocks.contains(cell.south()), "the wall behind " + cell);
		}
		assertTrue(protectedBlocks.contains(new BlockPos(0, 4, 0)), "the column's floor");
		for (int level : LADDER.levels()) {
			assertTrue(protectedBlocks.contains(LADDER.landing(level).below()), "landing floor at " + level);
		}
		assertTrue(protectedBlocks.containsAll(LADDER.torches()));
		// Not the ground the hatch goes in, which the builder digs through first.
		assertFalse(protectedBlocks.contains(new BlockPos(0, 9, 0)));
		Set<BlockPos> open = LADDER.keepOpen();
		LADDER.stairs().forEach(cell -> assertTrue(open.contains(cell), "column " + cell));
		LADDER.levels().forEach(level -> assertTrue(open.containsAll(List.of(LADDER.landing(level), LADDER.landing(level).above(), LADDER.landingChest(level)))));
	}
}
