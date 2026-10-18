package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import io.github.barniebarnie.antcraft.colony.StockPlanner;
import io.github.barniebarnie.antcraft.colony.Stockkeeping;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MineshaftTest {
	private static final BlockPos TOP = new BlockPos(0, 10, 0);
	/** Down to y=6, one branch each side of a 3 long hall, branches 3 long, a torch every 4 blocks. */
	private static final Mineshaft SHAFT = new Mineshaft(TOP, List.of(6), 1, 3, 4);

	@BeforeAll
	static void bootstrap() {
		// Bootstraps the registries and loads the vanilla recipes and tags.
		VanillaData.recipeBook();
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
		Mineshaft wide = new Mineshaft(TOP, List.of(9, 7, 4, 2), 3, 16, 8);
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
		assertEquals(List.of(6, 2), new Mineshaft(TOP, List.of(2, 12, 10, 6), 1, 3, 4).levels());
	}

	@Test
	void levelsTooCloseToTheLevelAboveAreDropped() {
		assertEquals(List.of(8, 6, 3), new Mineshaft(TOP, List.of(8, 7, 6, 3), 1, 3, 4).levels());
		assertEquals(List.of(9, 7, 5), Mineshaft.spaced(List.of(5, 9, 8, 7, 6, 5)));
	}

	@Test
	void branchesOfEveryRoundKeepClearOfOtherLevelsWhenLevelsAreTwoApart() {
		List<Integer> levels = IntStream.iterate(48, y -> y > 20, y -> y - Mineshaft.MIN_LEVEL_GAP).boxed().toList();
		Mineshaft shaft = new Mineshaft(new BlockPos(0, 50, 0), levels, Mineshaft.DEFAULT_BRANCHES_PER_SIDE, Mineshaft.DEFAULT_BRANCH_LENGTH,
			Mineshaft.DEFAULT_TORCH_SPACING, Mineshaft.DEFAULT_ROUNDS);
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
		Mineshaft wide = new Mineshaft(TOP, List.of(6), 3, 16, 8);
		assertEquals(9, wide.hall(6).size());
		List<Integer> xs = wide.branches(6).stream().filter(b -> b.start().getZ() < 0).map(b -> b.start().getX()).toList();
		assertEquals(List.of(-3, -6, -9), xs);
		assertEquals(List.of(new BlockPos(-3, 6, -4), new BlockPos(-3, 6, -12)),
			wide.branches(6).getFirst().torches());
	}

	@Test
	void aMinedOutLevelGetsFurtherBranchesClearOfTheOldOnesAndTheShaft() {
		Mineshaft wide = new Mineshaft(TOP, List.of(6), 3, 16, 8, 3);
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
	void anOpenOrderMakesTheLevelOfItsIngredientsChosen() {
		List<Integer> levels = List.of(16, -16, -58);
		List<StockPlanner.Target<Item>> coal = List.of(new StockPlanner.Target<>(item -> item == Items.COAL, List.of(), 32));
		assertEquals(Optional.of(16), Mineshaft.chooseLevel(levels, Stockkeeping.wanted(Set.of(), coal, Map.of())));
		Set<Item> diamondPickaxe = Stockkeeping.wantedForOrders(Map.of(Items.DIAMOND_PICKAXE, 1), Map.of(), VanillaData.recipeBook(), VanillaData.gatherable());
		assertEquals(Optional.of(-58), Mineshaft.chooseLevel(levels, Stockkeeping.wanted(diamondPickaxe, coal, Map.of())));
		// An iron pickaxe wants raw iron, so a level with iron beats the deeper one without.
		List<StockPlanner.Target<Item>> gold = List.of(new StockPlanner.Target<>(item -> item == Items.RAW_GOLD, List.of(), 8));
		assertEquals(Optional.of(-58), Mineshaft.chooseLevel(List.of(16, -58), Stockkeeping.wanted(Set.of(), gold, Map.of())));
		Set<Item> ironPickaxe = Stockkeeping.wantedForOrders(Map.of(Items.IRON_PICKAXE, 1), Map.of(), VanillaData.recipeBook(), VanillaData.gatherable());
		assertEquals(Optional.of(16), Mineshaft.chooseLevel(List.of(16, -58), Stockkeeping.wanted(ironPickaxe, gold, Map.of())));
	}

	@Test
	void survivesCodecRoundTrip() {
		var json = Mineshaft.CODEC.encodeStart(JsonOps.INSTANCE, SHAFT).getOrThrow();
		assertEquals(SHAFT, Mineshaft.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
	}

	@Test
	void siteBesideTheCoreKeepsClearOfStorageAndField() {
		BlockPos core = new BlockPos(0, 64, 0);
		List<Integer> levels = List.of(40);
		Mineshaft first = Mineshaft.nextTo(core, levels, List.of(), ROCK);
		// Storage was chosen first, right where the shaft would go.
		StorageArea storage = new StorageArea(first.top().offset(-1, 0, 0));
		Field field = new Field(core.offset(-4, -1, -4));
		Mineshaft shaft = Mineshaft.nextTo(core, levels, List.of(storage.area(), field.area()), ROCK);
		assertFalse(shaft.area().intersects(storage.area()), "shaft overlaps storage at " + shaft.top());
		assertFalse(shaft.area().intersects(field.area()), "shaft overlaps the field at " + shaft.top());
	}

	/** The positions of a batch's steps, but for the walls and floors it keeps solid. */
	private static Set<BlockPos> batch(Mineshaft shaft, int batch) {
		return shaft.plan().batch(batch).steps().stream().filter(step -> !step.anySolid()).map(BuildPlan.Step::pos).collect(Collectors.toSet());
	}

	@Test
	void planIsBatchedPerStairSegmentAndPerHall() {
		Mineshaft shaft = new Mineshaft(TOP, List.of(8, 6), 1, 3, 4);
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
		BuildPlan plan = new Mineshaft(TOP, List.of(8, 6), 1, 3, 4).plan();
		assertEquals(List.of(0), plan.ready(Set.of()));
		// Once the stairs reach y=8, its hall and the stairs on down can be dug at the same time.
		assertEquals(List.of(1, 2), plan.ready(Set.of(0)));
		assertEquals(List.of(1, 3), plan.ready(Set.of(0, 2)));
	}

	/** Solid rock, but for some fluid and some bedrock. */
	private static WorldView rock(Predicate<BlockPos> fluid, Predicate<BlockPos> bedrock) {
		return new WorldView() {
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
				return bedrock.test(pos) ? -1 : 30;
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
		Mineshaft shaft = new Mineshaft(TOP, List.of(8, 6), 1, 3, 4);
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
		Mineshaft shaft = new Mineshaft(TOP, List.of(8, 6), 1, 3, 4);
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
		Mineshaft shaft = new Mineshaft(TOP, List.of(8, 6, 4), 1, 3, 4);
		// On the way from y=8 down to y=6.
		BlockPos stair = shaft.stairs().get(3);
		assertEquals(new Mineshaft(TOP, List.of(8), 1, 3, 4), shaft.reachable(List.of(stair)));
		assertEquals(List.of(8), shaft.reachable(List.of(stair.below())).levels());
		assertEquals(List.of(8, 4), shaft.reachable(List.of(shaft.hall(6).get(1))).levels());
		assertEquals(List.of(), shaft.reachable(List.of(shaft.stairs().get(1))).levels());
		assertEquals(shaft, shaft.reachable(List.of(new BlockPos(100, 0, 100))));
	}
}
