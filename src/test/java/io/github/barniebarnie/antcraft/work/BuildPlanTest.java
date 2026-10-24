package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.StairsShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BuildPlanTest {
	private static final BlockPos A = new BlockPos(0, 1, 0);
	private static final BlockPos B = new BlockPos(1, 1, 0);
	private static final BlockPos C = new BlockPos(2, 1, 0);
	private static final BlockPos D = new BlockPos(3, 1, 0);

	private static BlockState stairs(Direction facing) {
		return Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing);
	}

	private static BuildPlan plan() {
		return new BuildPlan(List.of(
			BuildPlan.Step.clear(A),
			new BuildPlan.Step(B, Blocks.COBBLESTONE.defaultBlockState()),
			new BuildPlan.Step(C, Blocks.COBBLESTONE.defaultBlockState()),
			new BuildPlan.Step(D, stairs(Direction.EAST))));
	}

	/** An all-air world with the given blocks. */
	private static Map<BlockPos, BlockState> world(Object... posAndState) {
		Map<BlockPos, BlockState> world = new HashMap<>();
		for (int i = 0; i < posAndState.length; i += 2) {
			world.put((BlockPos) posAndState[i], (BlockState) posAndState[i + 1]);
		}
		return world;
	}

	private static Function<BlockPos, BlockState> at(Map<BlockPos, BlockState> world) {
		return pos -> world.getOrDefault(pos, Blocks.AIR.defaultBlockState());
	}

	private static Optional<BuildPlan.Step> next(Map<BlockPos, BlockState> world) {
		return plan().nextStep(pos -> world.getOrDefault(pos, Blocks.AIR.defaultBlockState()));
	}

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		// The vanilla tags, such as which blocks are dug with a pickaxe.
		VanillaData.recipeBook();
	}

	@Test
	void stepsAnySolidBlockWillDoAreListedApart() {
		BuildPlan plan = new BuildPlan(List.of(Terraform.fill(A), Terraform.fill(B), BuildPlan.Step.solid(C),
			new BuildPlan.Step(D, Blocks.COBBLESTONE.defaultBlockState())));
		// One is filled already, with sand.
		var world = at(world(A, Blocks.SAND.defaultBlockState()));
		assertEquals(Map.of(Items.DIRT, 1), plan.fillMaterials(world, true), "fills that rest on what is below");
		assertEquals(Map.of(Items.COBBLESTONE, 1), plan.fillMaterials(world, false), "fills that hold on their own");
	}

	@Test
	void wantedFillBlocksAreThoseTheOpenStepsCanUse() {
		BuildPlan fill = new BuildPlan(List.of(Terraform.fill(A), BuildPlan.Step.solid(B)));
		BuildPlan walls = new BuildPlan(List.of(BuildPlan.Step.solid(B)));
		BlockState stone = Blocks.STONE.defaultBlockState();
		assertEquals(BuildPlan.FILL_BLOCKS, fill.wantedFillBlocks(at(world())), "a terraform's fill takes sand and gravel too");
		assertEquals(BuildPlan.HOLDING_FILL_BLOCKS, fill.wantedFillBlocks(at(world(A, stone))), "only walls left");
		assertEquals(BuildPlan.HOLDING_FILL_BLOCKS, walls.wantedFillBlocks(at(world())));
		assertEquals(Set.of(), fill.wantedFillBlocks(at(world(A, stone, B, stone))), "nothing left to fill");
		assertEquals(Set.of(), new BuildPlan(List.of(new BuildPlan.Step(C, stone))).wantedFillBlocks(at(world())), "no step any solid block will do");
	}

	@Test
	void aBlockThatMustHoldOnItsOwnTakesNoFallingFiller() {
		Set<net.minecraft.world.item.Item> falling = Set.of(Items.SAND, Items.RED_SAND, Items.GRAVEL);
		Set<net.minecraft.world.item.Item> holding = Set.of(Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE);
		BlockState stone = Blocks.STONE.defaultBlockState();
		// A mineshaft's wall or ceiling: the cell below may be dug out later, so sand would fall into the shaft.
		BuildPlan.Step wall = BuildPlan.Step.solid(A);
		assertFalse(wall.mayFall());
		assertEquals(holding, wall.fillBlocks(stone));
		assertEquals(holding, wall.fillBlocks(Blocks.AIR.defaultBlockState()));
		// A terraform's fill rests on the ground below it, so sand and gravel do there, but not over a gap.
		BuildPlan.Step fill = Terraform.fill(A);
		assertTrue(fill.mayFall());
		assertEquals(BuildPlan.FILL_BLOCKS, fill.fillBlocks(stone));
		assertTrue(fill.fillBlocks(stone).containsAll(falling));
		for (BlockState gap : List.of(Blocks.AIR.defaultBlockState(), Blocks.WATER.defaultBlockState())) {
			assertEquals(holding, fill.fillBlocks(gap), "falls through " + gap);
		}
		// Saved and loaded with the step; saves from before keep to the blocks that hold.
		BuildPlan plan = new BuildPlan(List.of(fill, wall));
		var json = BuildPlan.CODEC.encodeStart(JsonOps.INSTANCE, plan).getOrThrow();
		assertEquals(plan, BuildPlan.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
		var old = BuildPlan.Step.CODEC.encodeStart(JsonOps.INSTANCE, fill).getOrThrow().getAsJsonObject();
		old.remove("may_fall");
		assertFalse(BuildPlan.Step.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow().mayFall());
	}

	@Test
	void stoneStairStepsTakeWhicheverStoneStairsTheColonyHas() {
		BuildPlan plan = new BuildPlan(List.of(BuildPlan.Step.stoneStairs(A, Blocks.COBBLESTONE_STAIRS.defaultBlockState()),
			BuildPlan.Step.stoneStairs(B, Blocks.COBBLESTONE_STAIRS.defaultBlockState())));
		Function<BlockPos, BlockState> air = pos -> Blocks.AIR.defaultBlockState();
		Map<net.minecraft.world.item.Item, Integer> materials = plan.materials(air);
		Map<net.minecraft.world.item.Item, Integer> stairs = plan.stoneStairMaterials(air);
		assertEquals(Map.of(Items.COBBLESTONE_STAIRS, 2), stairs);
		assertEquals(Map.of(), BuildPlan.shortfall(materials, Map.of(), Map.of(), stairs, Map.of(Items.STONE_BRICK_STAIRS, 2)),
			"stone brick stairs stand in for cobblestone stairs");
		assertEquals(Map.of(Items.COBBLESTONE_STAIRS, 1), BuildPlan.shortfall(materials, Map.of(), Map.of(), stairs, Map.of(Items.STONE_BRICK_STAIRS, 1, Items.OAK_STAIRS, 9)),
			"wooden stairs do not");
		assertEquals(Map.of(Items.COBBLESTONE_STAIRS, 2), BuildPlan.shortfall(Map.of(Items.COBBLESTONE_STAIRS, 2), Map.of(), Map.of(), Map.of(), Map.of(Items.STONE_BRICK_STAIRS, 2)),
			"cobblestone stairs placed as such take no other");
		assertEquals(Optional.empty(), plan.needs(air).item(), "a job of stone stairs declares no one kind to fetch");
	}

	@Test
	void fillStepsTakeWhicheverFillBlockTheColonyHas() {
		// Five fill steps of dirt, and two cobblestone a wall needs as such.
		Map<net.minecraft.world.item.Item, Integer> materials = Map.of(Items.DIRT, 5, Items.COBBLESTONE, 2);
		Map<net.minecraft.world.item.Item, Integer> fills = Map.of(Items.DIRT, 5);
		// The cobblestone beyond the wall's and the sand stand in for dirt; the stone is no fill block.
		Map<net.minecraft.world.item.Item, Integer> have = Map.of(Items.COBBLESTONE, 3, Items.SAND, 2, Items.STONE, 9);
		assertEquals(Map.of(Items.DIRT, 2), BuildPlan.shortfall(materials, Map.of(), fills, have));
		assertEquals(Map.of(Items.COBBLESTONE, 1), BuildPlan.shortfall(Map.of(Items.COBBLESTONE, 2), Map.of(), Map.of(), Map.of(Items.COBBLESTONE, 1, Items.SAND, 9)),
			"a block placed as such takes no other");
		assertEquals(Map.of(), BuildPlan.shortfall(materials, Map.of(), fills, Map.of(Items.COBBLESTONE, 2, Items.GRAVEL, 64)));
	}

	@Test
	void aMineshaftOrdersAFillerThatHoldsWhenTheColonyHasOnlyFallingOnes() {
		// Three walls of a mineshaft, which must hold on their own.
		Map<net.minecraft.world.item.Item, Integer> walls = Map.of(Items.COBBLESTONE, 3);
		assertEquals(walls, BuildPlan.shortfall(walls, walls, Map.of(), Map.of(Items.SAND, 64, Items.GRAVEL, 9, Items.RED_SAND, 9)));
		assertEquals(Map.of(Items.COBBLESTONE, 1), BuildPlan.shortfall(walls, walls, Map.of(), Map.of(Items.DIRT, 1, Items.COBBLED_DEEPSLATE, 1, Items.SAND, 64)));
		// With a terraform's fill as well, the blocks that hold go to the walls and the sand to the fill.
		Map<net.minecraft.world.item.Item, Integer> materials = Map.of(Items.COBBLESTONE, 2, Items.DIRT, 2);
		Map<net.minecraft.world.item.Item, Integer> holding = Map.of(Items.COBBLESTONE, 2);
		Map<net.minecraft.world.item.Item, Integer> resting = Map.of(Items.DIRT, 2);
		assertEquals(Map.of(), BuildPlan.shortfall(materials, holding, resting, Map.of(Items.COBBLED_DEEPSLATE, 2, Items.SAND, 2)));
		assertEquals(Map.of(Items.COBBLESTONE, 2), BuildPlan.shortfall(materials, holding, resting, Map.of(Items.SAND, 4)));
		assertEquals(Map.of(Items.DIRT, 1), BuildPlan.shortfall(materials, holding, resting, Map.of(Items.COBBLED_DEEPSLATE, 3)),
			"what holds is spare for the fill once the walls have theirs");
	}

	@Test
	void listsMaterialsForEveryPlacement() {
		assertEquals(Map.of(Items.COBBLESTONE, 2, Items.OAK_STAIRS, 1), plan().materials());
	}

	@Test
	void fluidSourcesArePlacedFromABucketAndOtherBlocksWithoutAnItemNotAtAll() {
		BuildPlan.Step water = new BuildPlan.Step(A, Blocks.WATER.defaultBlockState());
		BuildPlan.Step lava = new BuildPlan.Step(B, Blocks.LAVA.defaultBlockState());
		BuildPlan.Step flowing = new BuildPlan.Step(C, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 2));
		BuildPlan.Step portal = new BuildPlan.Step(D, Blocks.NETHER_PORTAL.defaultBlockState());
		assertEquals(Map.of(Items.WATER_BUCKET, 1, Items.LAVA_BUCKET, 1), new BuildPlan(List.of(water, lava, flowing, portal)).materials());
		assertTrue(water.isPlaceable());
		assertTrue(BuildPlan.Step.clear(A).isPlaceable());
		assertFalse(flowing.isPlaceable());
		assertFalse(portal.isPlaceable());
	}

	@Test
	void fireIsLitWithOneFlintAndSteelForTheWholePlan() {
		BuildPlan.Step fire = new BuildPlan.Step(A, Blocks.FIRE.defaultBlockState());
		BuildPlan.Step soulFire = new BuildPlan.Step(B, Blocks.SOUL_FIRE.defaultBlockState());
		BuildPlan.Step more = new BuildPlan.Step(C, Blocks.FIRE.defaultBlockState());
		BuildPlan plan = new BuildPlan(List.of(fire, soulFire, more, new BuildPlan.Step(D, Blocks.COBBLESTONE.defaultBlockState())));
		assertTrue(fire.isLit());
		assertTrue(soulFire.isLit());
		assertFalse(plan.steps().get(3).isLit());
		assertTrue(fire.isPlaceable());
		assertEquals(Items.FLINT_AND_STEEL, fire.item());
		assertEquals(Items.FLINT_AND_STEEL, soulFire.item(Blocks.AIR.defaultBlockState()));
		// The tool is not used up, so one is ordered however many fires there are.
		assertEquals(Map.of(Items.FLINT_AND_STEEL, 1, Items.COBBLESTONE, 1), plan.materials());
		// Until every fire is lit.
		assertEquals(Map.of(Items.FLINT_AND_STEEL, 1, Items.COBBLESTONE, 1),
			plan.materials(at(world(A, Blocks.FIRE.defaultBlockState(), B, Blocks.SOUL_FIRE.defaultBlockState()))));
		assertEquals(Map.of(Items.COBBLESTONE, 1),
			plan.materials(at(world(A, Blocks.FIRE.defaultBlockState(), B, Blocks.SOUL_FIRE.defaultBlockState(), C, Blocks.FIRE.defaultBlockState()))));
		// Fire spreads its age and sides by itself; any fire there will do.
		assertTrue(fire.isDone(Blocks.FIRE.defaultBlockState().setValue(BlockStateProperties.AGE_15, 7).setValue(BlockStateProperties.UP, true)));
		assertFalse(fire.isDone(Blocks.SOUL_FIRE.defaultBlockState()));
	}

	@Test
	void stepsABuilderCannotDoOrderNothing() {
		BuildPlan.Step fire = new BuildPlan.Step(A, Blocks.FIRE.defaultBlockState());
		BuildPlan.Step wet = new BuildPlan.Step(B, stairs(Direction.EAST).setValue(BlockStateProperties.WATERLOGGED, true));
		BuildPlan.Step cobble = new BuildPlan.Step(C, Blocks.COBBLESTONE.defaultBlockState());
		Function<BlockPos, BlockState> air = at(world());
		// A plan whose only step is blocked orders nothing for it.
		assertEquals(Map.of(), new BuildPlan(List.of(fire)).materials(air, step -> false));
		assertEquals(Map.of(), new BuildPlan(List.of(wet)).materials(air, step -> false));
		// The steps that can be done still order theirs.
		BuildPlan plan = new BuildPlan(List.of(fire, wet, cobble));
		assertEquals(Map.of(Items.COBBLESTONE, 1), plan.materials(air, step -> step == cobble));
		assertEquals(plan.materials(air), plan.materials(air, step -> true));
	}

	@Test
	void waterloggedBlocksArePlacedDryAndThenFilledFromAWaterBucket() {
		BlockState wet = stairs(Direction.EAST).setValue(BlockStateProperties.WATERLOGGED, true);
		BlockState dry = stairs(Direction.EAST);
		BuildPlan.Step step = new BuildPlan.Step(A, wet);
		BuildPlan plan = new BuildPlan(List.of(step, new BuildPlan.Step(B, dry)));

		assertEquals(Map.of(Items.OAK_STAIRS, 2, Items.WATER_BUCKET, 1), plan.materials());
		assertEquals(Map.of(Items.OAK_STAIRS, 1, Items.WATER_BUCKET, 1), plan.materials(at(world(A, dry))));
		assertEquals(Map.of(Items.OAK_STAIRS, 1), plan.materials(at(world(A, wet))));
		// First the block goes in dry, then the water is poured into it.
		assertEquals(Items.OAK_STAIRS, step.item(Blocks.AIR.defaultBlockState()));
		assertEquals(Items.WATER_BUCKET, step.item(dry));
		assertEquals(dry, step.dry());
		assertFalse(step.isDone(dry));
		assertFalse(step.needsClearing(dry));
		assertTrue(step.isDone(wet));
		assertTrue(step.isPlaceable());
	}

	@Test
	void dryBlocksAreNotDoneWhileWaterloggedAndHaveTheirWaterScoopedUp() {
		BlockState wet = stairs(Direction.EAST).setValue(BlockStateProperties.WATERLOGGED, true);
		BlockState dry = stairs(Direction.EAST);
		BuildPlan.Step step = new BuildPlan.Step(A, dry);
		BuildPlan plan = new BuildPlan(List.of(step));

		assertFalse(step.isDone(wet));
		assertTrue(step.isDone(dry));
		// The block is right, only its water goes: scooped up with an empty bucket, as a player would.
		assertTrue(step.isPlaced(wet));
		assertFalse(step.needsClearing(wet));
		assertEquals(Items.BUCKET, step.item(wet));
		assertEquals(Items.OAK_STAIRS, step.item(Blocks.AIR.defaultBlockState()));
		assertEquals(Map.of(Items.BUCKET, 1), plan.materials(at(world(A, wet))));
		assertEquals(Map.of(), plan.materials(at(world(A, dry))));
	}

	@Test
	void twoBlockBlocksSetTheirOtherHalfWhenPlaced() {
		BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
		BlockState bed = Blocks.BED.red().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
		BlockState grass = Blocks.TALL_GRASS.defaultBlockState();
		assertEquals(Optional.of(new BuildPlan.Step(A.above(), door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER))),
			new BuildPlan.Step(A, door).otherHalf());
		assertEquals(Optional.of(new BuildPlan.Step(A.north(), bed.setValue(BlockStateProperties.BED_PART, BedPart.HEAD))),
			new BuildPlan.Step(A, bed).otherHalf());
		assertEquals(Optional.of(new BuildPlan.Step(A.above(), grass.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER))),
			new BuildPlan.Step(A, grass).otherHalf());
		assertEquals(Optional.empty(), new BuildPlan.Step(A, stairs(Direction.EAST)).otherHalf());
		// The second half is set by the first, so it is no step of its own.
		assertTrue(BuildPlan.isSecondHalf(bed.setValue(BlockStateProperties.BED_PART, BedPart.HEAD)));
		assertTrue(BuildPlan.isSecondHalf(door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)));
		assertFalse(BuildPlan.isSecondHalf(door));
		assertFalse(BuildPlan.isSecondHalf(bed));
	}

	@Test
	void secondHalvesAreDroppedFromAPlanWithItsBatchesKeptAroundTheRest() {
		BlockState door = Blocks.OAK_DOOR.defaultBlockState();
		BlockState bed = Blocks.BED.red().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
		BuildPlan.Step doorLower = new BuildPlan.Step(A, door);
		BuildPlan.Step doorUpper = new BuildPlan.Step(A.above(), door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
		BuildPlan.Step bedFoot = new BuildPlan.Step(C, bed);
		BuildPlan.Step bedHead = new BuildPlan.Step(C.north(), bed.setValue(BlockStateProperties.BED_PART, BedPart.HEAD));
		BuildPlan.Step cobble = new BuildPlan.Step(D, Blocks.COBBLESTONE.defaultBlockState());

		assertEquals(List.of(doorLower, bedFoot), new BuildPlan(List.of(doorLower, doorUpper, bedHead, bedFoot)).steps());
		// Such as from an old save: each batch keeps the steps it had that remain.
		BuildPlan batched = new BuildPlan(List.of(doorLower, doorUpper, bedHead, bedFoot, cobble), List.of(
			new BuildPlan.Batch(0, 2, List.of()),
			new BuildPlan.Batch(2, 3, List.of(0), true),
			new BuildPlan.Batch(3, 5, List.of(0))));
		assertEquals(List.of(doorLower, bedFoot, cobble), batched.steps());
		assertEquals(List.of(
			new BuildPlan.Batch(0, 1, List.of()),
			new BuildPlan.Batch(1, 1, List.of(0), true),
			new BuildPlan.Batch(1, 3, List.of(0))), batched.batches());
	}

	@Test
	void firstStepIsNextWhileUndone() {
		assertEquals(Optional.of(BuildPlan.Step.clear(A)), next(world(A, Blocks.DIRT.defaultBlockState())));
	}

	@Test
	void doneStepsAreSkipped() {
		assertEquals(Optional.of(plan().steps().get(2)), next(world(B, Blocks.COBBLESTONE.defaultBlockState())));
	}

	@Test
	void stairFacingTheWrongWayIsNotDone() {
		var world = world(B, Blocks.COBBLESTONE.defaultBlockState(), C, Blocks.COBBLESTONE.defaultBlockState(), D, stairs(Direction.WEST));
		assertEquals(Optional.of(plan().steps().get(3)), next(world));
	}

	@Test
	void shapeFromNeighboursDoesNotUndoAStep() {
		// A stair's shape follows its neighbours; only how it was placed counts.
		BlockState cornered = stairs(Direction.EAST).setValue(StairBlock.SHAPE, StairsShape.INNER_LEFT);
		var world = world(B, Blocks.COBBLESTONE.defaultBlockState(), C, Blocks.COBBLESTONE.defaultBlockState(), D, cornered);
		assertEquals(Optional.empty(), next(world));
	}

	@Test
	void remainingMaterialsLeaveOutDoneSteps() {
		var world = world(B, Blocks.COBBLESTONE.defaultBlockState());
		assertEquals(Map.of(Items.COBBLESTONE, 1, Items.OAK_STAIRS, 1),
			plan().materials(pos -> world.getOrDefault(pos, Blocks.AIR.defaultBlockState())));
	}

	@Test
	void stepsThatCannotBeWorkedOnAreSkipped() {
		// The first step is blocked, so the rest of the plan goes on.
		var world = world(A, Blocks.BEDROCK.defaultBlockState());
		assertEquals(Optional.of(plan().steps().get(1)),
			plan().nextStep(pos -> world.getOrDefault(pos, Blocks.AIR.defaultBlockState()), step -> !step.pos().equals(A)));
	}

	@Test
	void whateverStandsInTheWayNeedsClearing() {
		BuildPlan.Step cobble = new BuildPlan.Step(B, Blocks.COBBLESTONE.defaultBlockState());
		assertTrue(BuildPlan.Step.clear(A).needsClearing(Blocks.DIRT.defaultBlockState()));
		assertTrue(new BuildPlan.Step(D, stairs(Direction.EAST)).needsClearing(stairs(Direction.WEST)));
		assertTrue(cobble.needsClearing(Blocks.DIRT.defaultBlockState()));
		assertFalse(cobble.needsClearing(Blocks.AIR.defaultBlockState()));
		// Placing replaces grass, so it need not be broken first.
		assertFalse(cobble.needsClearing(Blocks.SHORT_GRASS.defaultBlockState()));
	}

	@Test
	void fluidInAClearStepIsScoopedFilledLeftToSettleOrBlocked() {
		BuildPlan.Step clear = BuildPlan.Step.clear(A);
		var source = at(world(A, Blocks.WATER.defaultBlockState()));
		assertEquals(BuildPlan.Clearing.SCOOP, clear.clearing(source, false, true, true));
		assertEquals(BuildPlan.Clearing.FILL, clear.clearing(source, false, false, true));
		assertEquals(BuildPlan.Clearing.BLOCKED, clear.clearing(source, false, false, false));
		// Flowing fluid cannot be scooped: it dries up once its source is gone, unless it is fed from outside the plan.
		var flowing = at(world(A, Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 2)));
		assertEquals(BuildPlan.Clearing.SETTLE, clear.clearing(flowing, true, true, true));
		assertEquals(BuildPlan.Clearing.BLOCKED, clear.clearing(flowing, false, true, true));
	}

	@Test
	void blockDammingAFluidIsLeftStanding() {
		BuildPlan.Step clear = BuildPlan.Step.clear(A);
		// Breaking it would let the water in; it waits until the water beside or above it is gone.
		var beside = world(A, Blocks.DIRT.defaultBlockState(), B, Blocks.WATER.defaultBlockState());
		assertEquals(BuildPlan.Clearing.BLOCKED, clear.clearing(at(beside), false, true, true));
		var above = world(A, Blocks.DIRT.defaultBlockState(), A.above(), Blocks.WATER.defaultBlockState());
		assertEquals(BuildPlan.Clearing.BLOCKED, clear.clearing(at(above), false, true, true));
		var below = world(A, Blocks.DIRT.defaultBlockState(), A.below(), Blocks.WATER.defaultBlockState());
		assertEquals(BuildPlan.Clearing.BREAK, clear.clearing(at(below), false, true, true));
	}

	@Test
	void aSolidStepKeepsAnyBlockToStandOnAndFillsAirOrFluidWithCobblestone() {
		BuildPlan.Step floor = BuildPlan.Step.solid(A);
		for (BlockState rock : List.of(Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState(), Blocks.DEEPSLATE.defaultBlockState())) {
			assertTrue(floor.isDone(rock), "not done on " + rock);
			assertFalse(floor.needsClearing(rock), "clears " + rock);
		}
		for (BlockState gap : List.of(Blocks.AIR.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState())) {
			assertFalse(floor.isDone(gap), "done on " + gap);
			// Placed over, as a player would.
			assertFalse(floor.needsClearing(gap), "clears " + gap);
		}
		assertEquals(Items.COBBLESTONE, floor.item());
		BuildPlan plan = new BuildPlan(List.of(floor));
		assertEquals(Map.of(Items.COBBLESTONE, 1), plan.materials(at(world())));
		assertEquals(Map.of(), plan.materials(at(world(A, Blocks.STONE.defaultBlockState()))));
		var json = BuildPlan.CODEC.encodeStart(JsonOps.INSTANCE, plan).getOrThrow();
		assertEquals(plan, BuildPlan.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
	}

	@Test
	void survivesCodecRoundTrip() {
		var json = BuildPlan.CODEC.encodeStart(JsonOps.INSTANCE, plan()).getOrThrow();
		assertEquals(plan(), BuildPlan.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
	}
	@Test
	void aPlanIsOneBatchUnlessSplit() {
		assertEquals(List.of(new BuildPlan.Batch(0, 4, List.of())), plan().batches());
		assertEquals(plan(), plan().batch(0));
		assertEquals(List.of(0), plan().ready(Set.of()));
		assertEquals(List.of(), plan().ready(Set.of(0)));
	}

	/** {@link #plan} split in three: A and B first, then C and D each on their own once those are done. */
	private static BuildPlan batched() {
		return new BuildPlan(plan().steps(), List.of(
			new BuildPlan.Batch(0, 2, List.of()),
			new BuildPlan.Batch(2, 3, List.of(0)),
			new BuildPlan.Batch(3, 4, List.of(0))));
	}

	@Test
	void aBatchIsReadyOnceTheBatchesItNeedsAreDone() {
		BuildPlan plan = batched();
		assertEquals(List.of(0), plan.ready(Set.of()));
		assertEquals(List.of(1, 2), plan.ready(Set.of(0)));
		assertEquals(List.of(2), plan.ready(Set.of(0, 1)));
		assertEquals(List.of(), plan.ready(Set.of(0, 1, 2)));
	}

	@Test
	void aBatchWaitingForItemsLetsTheBatchesThatNeedItStart() {
		BuildPlan plan = batched();
		// Batch 0 is dug, but waits for an item: it stays to be finished, and the rest may start.
		assertEquals(List.of(0, 1, 2), plan.ready(Set.of(), Set.of(0)));
		assertEquals(List.of(1, 2), plan.ready(Set.of(0), Set.of()));
	}

	@Test
	void aBatchIsAPlanOfItsOwnSteps() {
		BuildPlan plan = batched();
		assertEquals(new BuildPlan(plan.steps().subList(0, 2)), plan.batch(0));
		assertEquals(new BuildPlan(List.of(plan.steps().get(2))), plan.batch(1));
		assertEquals(C, plan.batch(1).target());
	}

	@Test
	void batchesMustCoverEveryStepInOrderAndNeedOnlyEarlierBatches() {
		List<BuildPlan.Step> steps = plan().steps();
		assertThrows(IllegalArgumentException.class, () -> new BuildPlan(steps, List.of(new BuildPlan.Batch(0, 3, List.of()))));
		assertThrows(IllegalArgumentException.class, () -> new BuildPlan(steps,
			List.of(new BuildPlan.Batch(0, 2, List.of()), new BuildPlan.Batch(3, 4, List.of()))));
		assertThrows(IllegalArgumentException.class, () -> new BuildPlan(steps,
			List.of(new BuildPlan.Batch(0, 2, List.of(1)), new BuildPlan.Batch(2, 4, List.of()))));
	}

	@Test
	void batchesSurviveCodecRoundTrip() {
		var json = BuildPlan.CODEC.encodeStart(JsonOps.INSTANCE, batched()).getOrThrow();
		assertEquals(batched(), BuildPlan.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
	}

	@Test
	void plansSavedAsAListOfStepsStillLoad() {
		var json = BuildPlan.Step.CODEC.listOf().encodeStart(JsonOps.INSTANCE, plan().steps()).getOrThrow();
		assertEquals(plan(), BuildPlan.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
	}

	/** The stairs to the first of the default levels of a deep shaft, dug in stone: the biggest batch a builder works through. */
	private static BuildPlan deepStairs() {
		return new Mineshaft(new BlockPos(0, 64, 0), List.of(16, -16, -58), Mineshaft.DEFAULT_BRANCHES_PER_SIDE, Mineshaft.DEFAULT_BRANCH_LENGTH,
			Mineshaft.DEFAULT_TORCH_SPACING, Mineshaft.DEFAULT_ROUNDS, Mineshaft.Layout.STAIRCASE).plan().batch(0);
	}

	/** Stone below y=64, air above, with {@code changed} on top. */
	private static Function<BlockPos, BlockState> stone(Map<BlockPos, BlockState> changed) {
		return pos -> changed.getOrDefault(pos, pos.getY() >= 64 ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState());
	}

	/** Torch steps wait, as for a colony that has no torches yet. */
	private static boolean noTorches(BuildPlan.Step step) {
		return step.state().getBlock() != Blocks.TORCH;
	}

	@Test
	void nextStepWithProgressFindsWhatAFullScanFindsAsTheBuildGoesOn() {
		BuildPlan stairs = deepStairs();
		Map<BlockPos, BlockState> changed = new HashMap<>();
		BuildPlan.Progress progress = new BuildPlan.Progress();
		int steps = 0;
		while (true) {
			Optional<BuildPlan.Step> full = stairs.nextStep(stone(changed), BuildPlanTest::noTorches);
			assertEquals(full, stairs.nextStep(stone(changed), BuildPlanTest::noTorches, progress), "after " + steps + " steps");
			if (full.isEmpty()) {
				break;
			}
			changed.put(full.get().pos(), full.get().state());
			steps++;
		}
		assertTrue(steps > 100, "only " + steps + " steps");
	}

	@Test
	void nextStepWithProgressLooksOnlyAtStepsNotFoundDoneBefore() {
		BuildPlan stairs = deepStairs();
		Map<BlockPos, BlockState> changed = new HashMap<>();
		List<BuildPlan.Step> half = stairs.steps().subList(0, stairs.steps().size() / 2);
		half.forEach(step -> changed.put(step.pos(), noTorches(step) ? step.state() : Blocks.AIR.defaultBlockState()));
		int[] lookups = {0};
		Function<BlockPos, BlockState> counted = pos -> {
			lookups[0]++;
			return stone(changed).apply(pos);
		};
		BuildPlan.Progress progress = new BuildPlan.Progress();
		Optional<BuildPlan.Step> first = stairs.nextStep(counted, BuildPlanTest::noTorches, progress);
		assertTrue(lookups[0] > 200, "the first call looked at only " + lookups[0] + " blocks");
		lookups[0] = 0;
		assertEquals(first, stairs.nextStep(counted, BuildPlanTest::noTorches, progress));
		long torches = stairs.steps().subList(0, stairs.steps().indexOf(first.orElseThrow())).stream().filter(step -> !noTorches(step)).count();
		// The torch steps waiting before it, and the step found.
		assertEquals(torches + 1, lookups[0]);
	}

	@Test
	void aStepUndoneAfterItWasFoundDoneIsFoundAgain() {
		BuildPlan stairs = deepStairs();
		Map<BlockPos, BlockState> changed = new HashMap<>();
		List<BuildPlan.Step> steps = stairs.steps();
		steps.subList(0, 100).forEach(step -> changed.put(step.pos(), step.state()));
		BuildPlan.Progress progress = new BuildPlan.Progress();
		assertEquals(Optional.of(steps.get(100)), stairs.nextStep(stone(changed), step -> true, progress));
		// A block falls back into what was dug.
		BuildPlan.Step dug = steps.stream().filter(BuildPlan.Step::isClear).findFirst().orElseThrow();
		changed.put(dug.pos(), Blocks.STONE.defaultBlockState());
		Optional<BuildPlan.Step> next = Optional.empty();
		for (int call = 0; call < BuildPlan.Progress.REFRESH; call++) {
			next = stairs.nextStep(stone(changed), step -> true, progress);
		}
		assertEquals(Optional.of(dug), next, "not found again within " + BuildPlan.Progress.REFRESH + " calls");
		// Nor is the batch taken for finished while it is undone.
		BuildPlan.Progress fresh = new BuildPlan.Progress();
		steps.forEach(step -> changed.put(step.pos(), step.state()));
		assertEquals(Optional.empty(), stairs.nextStep(stone(changed), step -> true, fresh));
		changed.put(dug.pos(), Blocks.STONE.defaultBlockState());
		assertEquals(Optional.of(dug), stairs.nextStep(stone(changed), step -> true, fresh));
	}

	@Test
	void placingStepsDeclareTheOneItemTheyNeedOnceNothingElseIsLeft() {
		BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
		BuildPlan walls = new BuildPlan(List.of(new BuildPlan.Step(B, cobble), new BuildPlan.Step(C, cobble), Terraform.fill(D)));
		assertEquals(Optional.empty(), walls.needs(at(world())).item(), "a fill step takes any fill block, so it can go on without cobblestone");
		assertEquals(Optional.of(Items.COBBLESTONE), walls.needs(at(world(D, Blocks.DIRT.defaultBlockState()))).item(), "only cobblestone is left to place");
		assertEquals(Optional.empty(), walls.needs(at(world(B, cobble, C, cobble, D, Blocks.DIRT.defaultBlockState()))).item(), "nothing left to place");

		assertEquals(Optional.empty(), plan().needs(at(world())).item(), "two kinds of items");
		assertEquals(Optional.of(Items.COBBLESTONE), plan().needs(at(world(D, stairs(Direction.EAST)))).item(), "the stairs are in place");
		assertEquals(Optional.empty(), plan().needs(at(world(A, Blocks.DIRT.defaultBlockState(), D, stairs(Direction.EAST)))).item(),
			"dirt to dig first, which needs no cobblestone");
		assertEquals(Optional.empty(), plan().needs(at(world(D, stairs(Direction.EAST))), step -> step.pos() != B && step.pos() != C).item(),
			"what is not possible is not declared");

		BuildPlan pond = new BuildPlan(List.of(new BuildPlan.Step(B, Blocks.WATER.defaultBlockState())));
		assertEquals(Optional.empty(), pond.needs(at(world())).item(), "a bucket is filled at a source, so it is not declared");
	}

	@Test
	void diggingStepsDeclareThePickaxeTierOfWhatIsInTheWay() {
		assertEquals(Optional.empty(), BuildPlan.Step.clear(A).pickaxe(Blocks.DIRT.defaultBlockState()));
		assertEquals(Optional.of(Items.WOODEN_PICKAXE), BuildPlan.Step.clear(A).pickaxe(Blocks.STONE.defaultBlockState()));
		assertEquals(Optional.of(Items.STONE_PICKAXE), BuildPlan.Step.clear(A).pickaxe(Blocks.IRON_ORE.defaultBlockState()));
		assertEquals(Optional.empty(), BuildPlan.Step.clear(A).pickaxe(Blocks.AIR.defaultBlockState()), "nothing in the way");
		// Stone where cobblestone goes is in the way too.
		assertEquals(Optional.of(Items.WOODEN_PICKAXE), new BuildPlan.Step(B, Blocks.COBBLESTONE.defaultBlockState()).pickaxe(Blocks.STONE.defaultBlockState()));

		// A batch needs the best tier any of its steps does.
		assertEquals(Optional.of(Items.STONE_PICKAXE), plan().needs(at(world(A, Blocks.IRON_ORE.defaultBlockState(), B, Blocks.STONE.defaultBlockState()))).tool());
		assertEquals(Optional.empty(), plan().needs(at(world(A, Blocks.DIRT.defaultBlockState()))).tool());
	}

	/** The steps of a floor 6 by 6 at height {@code y}, row by row. */
	private static List<BuildPlan.Step> floor(int y) {
		List<BuildPlan.Step> steps = new java.util.ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(0, y, 0, 5, y, 5)) {
			steps.add(new BuildPlan.Step(pos, Blocks.COBBLESTONE.defaultBlockState()));
		}
		return steps;
	}

	@Test
	void splitsA6x1x6FloorBatchIntoCellsAndACellOnTopWaitsForTheOneUnderIt() {
		List<BuildPlan.Step> steps = new java.util.ArrayList<>(floor(1));
		steps.addAll(floor(2));
		BuildPlan plan = new BuildPlan(steps, List.of(new BuildPlan.Batch(0, 36, List.of()), new BuildPlan.Batch(36, 72, List.of(0), true))).inCells();

		// Each floor in four cells of 3x3 columns.
		assertEquals(8, plan.batches().size());
		for (int i = 0; i < 8; i++) {
			List<BuildPlan.Step> cell = plan.batch(i).steps();
			assertEquals(9, cell.size(), "cell " + i);
			int y = i < 4 ? 1 : 2;
			int x = cell.getFirst().pos().getX();
			int z = cell.getFirst().pos().getZ();
			assertTrue(cell.stream().allMatch(step -> step.pos().getY() == y && step.pos().getX() - x < 3 && step.pos().getZ() - z < 3), "cell " + i + ": " + cell);
			assertEquals(i >= 4, plan.batches().get(i).dig(), "cell " + i);
		}
		assertEquals(Set.copyOf(steps), Set.copyOf(plan.steps()));
		// The floor's cells at once; one on top once the cell under it is placed, whatever the rest of the floor.
		assertEquals(List.of(0, 1, 2, 3), plan.ready(Set.of()));
		assertEquals(List.of(3, 4, 5, 6), plan.ready(Set.of(0, 1, 2)));
		for (int i = 4; i < 8; i++) {
			assertEquals(List.of(i - 4), plan.batches().get(i).needs(), "cell " + i);
		}
	}

	@Test
	void aBatchThatFitsOneCellStaysOneBatch() {
		// A pillar 4 high in a square of 4x4 columns: one cell, so one job.
		List<BuildPlan.Step> steps = new java.util.ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 3, 4, 3)) {
			steps.add(new BuildPlan.Step(pos, Blocks.COBBLESTONE.defaultBlockState()));
		}
		BuildPlan plan = new BuildPlan(steps);

		assertEquals(plan, plan.inCells());
		assertEquals(plan(), plan().inCells());
	}

	@Test
	void waterIsClearedWithDirtWithoutAPickaxeAndWithCobblestoneWithOne() {
		Set<Item> both = Set.of(Items.DIRT, Items.COBBLESTONE);

		assertEquals(Optional.of(Items.DIRT), BuildPlan.waterFiller(both::contains, false));
		assertEquals(Optional.of(Items.COBBLESTONE), BuildPlan.waterFiller(both::contains, true));
		// Without dirt, cobblestone still does, however slow to break by hand.
		assertEquals(Optional.of(Items.COBBLESTONE), BuildPlan.waterFiller(Set.of(Items.COBBLESTONE)::contains, false));
		assertEquals(Optional.of(Items.DIRT), BuildPlan.waterFiller(Set.of(Items.DIRT)::contains, true));
		assertEquals(Optional.empty(), BuildPlan.waterFiller(Set.of(Items.SAND)::contains, false));
	}
}
