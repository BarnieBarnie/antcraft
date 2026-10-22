package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RegionPlanTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void copiesRegionBottomUpWithStatesAndAir() {
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
		BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST);
		Map<BlockPos, BlockState> world = Map.of(
			new BlockPos(10, 6, 20), stairs,
			new BlockPos(10, 5, 20), cobble,
			new BlockPos(11, 5, 20), cobble);

		// Corners given top-first and in any order: the plan still starts at the bottom.
		BuildPlan plan = RegionPlan.copy(pos -> world.getOrDefault(pos, air), new BlockPos(11, 6, 20), new BlockPos(10, 5, 20), new BlockPos(0, 1, 0)).orElseThrow();

		assertEquals(List.of(
			new BuildPlan.Step(new BlockPos(0, 1, 0), cobble),
			new BuildPlan.Step(new BlockPos(1, 1, 0), cobble),
			new BuildPlan.Step(new BlockPos(0, 2, 0), stairs),
			BuildPlan.Step.clear(new BlockPos(1, 2, 0))), plan.steps());
	}

	@Test
	void eachLayerIsABatchNeedingTheOneBelow() {
		BuildPlan plan = RegionPlan.copy(pos -> Blocks.COBBLESTONE.defaultBlockState(), new BlockPos(0, 0, 0), new BlockPos(2, 2, 1), new BlockPos(5, 1, 5)).orElseThrow();

		assertEquals(List.of(
			new BuildPlan.Batch(0, 6, List.of()),
			new BuildPlan.Batch(6, 12, List.of(0)),
			new BuildPlan.Batch(12, 18, List.of(1))), plan.batches());
		for (int layer = 0; layer < 3; layer++) {
			int y = 1 + layer;
			assertEquals(List.of(), plan.batch(layer).steps().stream().filter(step -> step.pos().getY() != y).toList(), "layer " + layer);
		}
	}

	@Test
	void splitsAWideLayerIntoSquaresThatEachNeedOnlyTheSquareUnderIt() {
		// 8 columns wide: two squares a layer, so two builders can work a layer at once.
		BuildPlan plan = RegionPlan.copy(pos -> Blocks.COBBLESTONE.defaultBlockState(), new BlockPos(0, 0, 0), new BlockPos(7, 1, 0), BlockPos.ZERO).orElseThrow();

		assertEquals(List.of(
			new BuildPlan.Batch(0, 4, List.of()),
			new BuildPlan.Batch(4, 8, List.of()),
			new BuildPlan.Batch(8, 12, List.of(0)),
			new BuildPlan.Batch(12, 16, List.of(1))), plan.batches());
		assertEquals(List.of(0, 1, 2, 3), plan.batch(1).steps().stream().map(step -> step.pos().getX() - 4).toList());
		assertEquals(List.of(0, 1), plan.ready(Set.of()));
	}

	@Test
	void aSquareOnTheSecondLayerOpensWhileADistantSquareBelowIsStillOpen() {
		// 12x12 columns, 2 high: nine squares a layer.
		BuildPlan plan = RegionPlan.copy(pos -> Blocks.COBBLESTONE.defaultBlockState(), new BlockPos(0, 0, 0), new BlockPos(11, 1, 11), BlockPos.ZERO).orElseThrow();

		assertEquals(18, plan.batches().size());
		// Only the corner square of the bottom layer is done; the far corner is still open.
		BuildPlan.Step corner = plan.batch(0).steps().getFirst();
		assertEquals(BlockPos.ZERO, corner.pos());
		assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9), plan.ready(Set.of(0)));
		assertEquals(new BlockPos(0, 1, 0), plan.batch(9).steps().getFirst().pos());
	}

	@Test
	void aLayerWithNothingToBuildIsSkipped() {
		BlockState flowing = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1);
		// The middle layer is only flowing water, so it has no steps and the top layer needs the bottom one.
		BuildPlan plan = RegionPlan.copy(pos -> pos.getY() == 1 ? flowing : Blocks.COBBLESTONE.defaultBlockState(),
			new BlockPos(0, 0, 0), new BlockPos(0, 2, 0), BlockPos.ZERO).orElseThrow();

		assertEquals(List.of(new BuildPlan.Batch(0, 1, List.of()), new BuildPlan.Batch(1, 2, List.of(0))), plan.batches());
	}

	@Test
	void leavesOutFlowingFluidThatFollowsFromItsSource() {
		BlockState source = Blocks.WATER.defaultBlockState();
		BlockState flowing = source.setValue(LiquidBlock.LEVEL, 1);
		Map<BlockPos, BlockState> world = Map.of(new BlockPos(0, 0, 0), source, new BlockPos(1, 0, 0), flowing);

		BuildPlan plan = RegionPlan.copy(world::get, new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(5, 1, 5)).orElseThrow();

		assertEquals(List.of(new BuildPlan.Step(new BlockPos(5, 1, 5), source)), plan.steps());
	}

	@Test
	void keepsOneStepPerTwoBlockBlock() {
		BlockState doorLower = Blocks.OAK_DOOR.defaultBlockState()
			.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
			.setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.RIGHT);
		BlockState grassLower = Blocks.TALL_GRASS.defaultBlockState();
		BlockState bedFoot = Blocks.BED.red().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
		Map<BlockPos, BlockState> world = Map.of(
			new BlockPos(0, 0, 0), doorLower,
			new BlockPos(0, 1, 0), doorLower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER),
			new BlockPos(1, 0, 0), grassLower,
			new BlockPos(1, 1, 0), grassLower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER),
			// A bed's head lies the way it faces.
			new BlockPos(2, 0, 1), bedFoot,
			new BlockPos(2, 0, 0), bedFoot.setValue(BlockStateProperties.BED_PART, BedPart.HEAD));

		BuildPlan plan = RegionPlan.copy(pos -> world.getOrDefault(pos, Blocks.AIR.defaultBlockState()),
			new BlockPos(0, 0, 0), new BlockPos(2, 1, 1), new BlockPos(0, 0, 0)).orElseThrow();

		// The other halves get neither a place step nor a clear step: placing the first half sets them.
		assertEquals(List.of(
			new BuildPlan.Step(new BlockPos(0, 0, 0), doorLower),
			new BuildPlan.Step(new BlockPos(1, 0, 0), grassLower),
			new BuildPlan.Step(new BlockPos(2, 0, 1), bedFoot)),
			plan.steps().stream().filter(step -> !step.isClear()).toList());
		assertEquals(List.of(), plan.steps().stream()
			.filter(step -> step.pos().equals(new BlockPos(0, 1, 0)) || step.pos().equals(new BlockPos(1, 1, 0)) || step.pos().equals(new BlockPos(2, 0, 0)))
			.toList());
		assertEquals(Map.of(Items.OAK_DOOR, 1, Items.TALL_GRASS, 1, Items.BED.red(), 1), plan.materials());
	}

	@Test
	void aBoxHoldingOnlyASecondHalfGivesNoPlan() {
		BlockState doorUpper = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
		BlockState bedHead = Blocks.BED.red().defaultBlockState().setValue(BlockStateProperties.BED_PART, BedPart.HEAD);

		assertEquals(Optional.empty(), RegionPlan.copy(pos -> doorUpper, BlockPos.ZERO, BlockPos.ZERO, new BlockPos(5, 1, 5)));
		assertEquals(Optional.empty(), RegionPlan.copy(pos -> bedHead, BlockPos.ZERO, BlockPos.ZERO, new BlockPos(5, 1, 5)));
		// Flowing fluid alone is nothing to build either.
		assertEquals(Optional.empty(), RegionPlan.copy(pos -> Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1),
			BlockPos.ZERO, BlockPos.ZERO, new BlockPos(5, 1, 5)));
	}

	@Test
	void aBlockHungOnTheSideComesAfterItsHolderEvenInTheNextCell() {
		BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
		// A row of two cells, x 0-3 and 4-7: the torch at x 3 hangs on the block at x 4 in the next cell, and the
		// ladder at x 5 on the block at x 6 in its own cell, after it in the order the region is read.
		BlockState torch = Blocks.WALL_TORCH.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST);
		BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST);
		Map<BlockPos, BlockState> world = Map.of(new BlockPos(3, 0, 0), torch, new BlockPos(5, 0, 0), ladder);

		BuildPlan plan = RegionPlan.copy(pos -> world.getOrDefault(pos, cobble), new BlockPos(0, 0, 0), new BlockPos(7, 0, 0), new BlockPos(0, 1, 0)).orElseThrow();

		assertPlacedAfter(plan, new BlockPos(4, 1, 0), new BlockPos(3, 1, 0));
		assertPlacedAfter(plan, new BlockPos(6, 1, 0), new BlockPos(5, 1, 0));
	}

	@Test
	void aBlockHungFromTheCeilingComesAfterTheBlockAbove() {
		BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
		BlockState lantern = Blocks.LANTERN.defaultBlockState().setValue(BlockStateProperties.HANGING, true);
		// A chain: a lantern under a lantern under the ceiling, and a ceiling button beside it.
		Map<BlockPos, BlockState> world = Map.of(new BlockPos(0, 0, 0), lantern, new BlockPos(0, 1, 0), lantern,
			new BlockPos(1, 1, 0), Blocks.STONE_BUTTON.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.CEILING));

		BuildPlan plan = RegionPlan.copy(pos -> world.getOrDefault(pos, cobble), new BlockPos(0, 0, 0), new BlockPos(1, 2, 0), new BlockPos(0, 1, 0)).orElseThrow();

		assertPlacedAfter(plan, new BlockPos(0, 3, 0), new BlockPos(0, 2, 0));
		assertPlacedAfter(plan, new BlockPos(0, 2, 0), new BlockPos(0, 1, 0));
		assertPlacedAfter(plan, new BlockPos(1, 3, 0), new BlockPos(1, 2, 0));
	}

	@Test
	void cocoaAndAWallHangingSignComeAfterTheirHolder() {
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
		// As above, across a cell edge at x 3-4: cocoa at x 3 on the jungle log at x 4, and in the next row a wall
		// hanging sign at x 3 facing north, held by the block at x 4 with air at x 2.
		Map<BlockPos, BlockState> world = Map.of(
			new BlockPos(3, 0, 0), Blocks.COCOA.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST),
			new BlockPos(4, 0, 0), Blocks.JUNGLE_LOG.defaultBlockState(),
			new BlockPos(2, 0, 1), air,
			new BlockPos(3, 0, 1), Blocks.OAK_WALL_HANGING_SIGN.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));

		BuildPlan plan = RegionPlan.copy(pos -> world.getOrDefault(pos, cobble), new BlockPos(0, 0, 0), new BlockPos(7, 0, 1), new BlockPos(0, 1, 0)).orElseThrow();

		assertPlacedAfter(plan, new BlockPos(4, 1, 0), new BlockPos(3, 1, 0));
		assertPlacedAfter(plan, new BlockPos(4, 1, 1), new BlockPos(3, 1, 1));
	}

	/** Asserts the step at {@code held} is in the batch of the step at {@code holder} and after it, or in a batch that needs that batch. */
	private static void assertPlacedAfter(BuildPlan plan, BlockPos holder, BlockPos held) {
		int holderStep = stepAt(plan, holder);
		int heldStep = stepAt(plan, held);
		int holderBatch = batchOf(plan, holderStep);
		int heldBatch = batchOf(plan, heldStep);
		assertTrue(heldBatch == holderBatch && holderStep < heldStep || plan.batches().get(heldBatch).needs().contains(holderBatch),
			held + " in batch " + heldBatch + " (step " + heldStep + "), its holder in batch " + holderBatch + " (step " + holderStep + "): " + plan.batches());
	}

	private static int stepAt(BuildPlan plan, BlockPos pos) {
		return java.util.stream.IntStream.range(0, plan.steps().size()).filter(i -> plan.steps().get(i).pos().equals(pos)).findFirst().orElseThrow();
	}

	private static int batchOf(BuildPlan plan, int step) {
		return java.util.stream.IntStream.range(0, plan.batches().size())
			.filter(i -> plan.batches().get(i).from() <= step && step < plan.batches().get(i).to()).findFirst().orElseThrow();
	}
}
