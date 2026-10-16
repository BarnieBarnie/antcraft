package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.serialization.JsonOps;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
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
	void survivesCodecRoundTrip() {
		var json = BuildPlan.CODEC.encodeStart(JsonOps.INSTANCE, plan()).getOrThrow();
		assertEquals(plan(), BuildPlan.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
	}
}
