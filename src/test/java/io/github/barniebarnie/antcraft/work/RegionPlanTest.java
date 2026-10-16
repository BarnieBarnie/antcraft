package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
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
		BuildPlan plan = RegionPlan.copy(pos -> world.getOrDefault(pos, air), new BlockPos(11, 6, 20), new BlockPos(10, 5, 20), new BlockPos(0, 1, 0));

		assertEquals(List.of(
			new BuildPlan.Step(new BlockPos(0, 1, 0), cobble),
			new BuildPlan.Step(new BlockPos(1, 1, 0), cobble),
			new BuildPlan.Step(new BlockPos(0, 2, 0), stairs),
			BuildPlan.Step.clear(new BlockPos(1, 2, 0))), plan.steps());
	}
}
