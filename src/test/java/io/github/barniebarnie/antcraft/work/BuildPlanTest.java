package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
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
	void survivesCodecRoundTrip() {
		var json = BuildPlan.CODEC.encodeStart(JsonOps.INSTANCE, plan()).getOrThrow();
		assertEquals(plan(), BuildPlan.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
	}
}
