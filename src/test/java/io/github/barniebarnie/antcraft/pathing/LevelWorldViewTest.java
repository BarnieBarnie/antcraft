package io.github.barniebarnie.antcraft.pathing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Which blocks paths open, checked against vanilla block states. */
class LevelWorldViewTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void aWoodenDoorOpensAlongItsFacing() {
		BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
		assertTrue(LevelWorldView.canOpen(door, Direction.EAST));
		assertTrue(LevelWorldView.canOpen(door, Direction.WEST));
		assertFalse(LevelWorldView.canOpen(door, Direction.NORTH));
	}

	@Test
	void anIronDoorStaysShut() {
		BlockState door = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
		assertFalse(LevelWorldView.canOpen(door, Direction.EAST));
	}

	@Test
	void aFenceGateOpensAlongItsFacing() {
		BlockState gate = Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.NORTH);
		assertTrue(LevelWorldView.canOpen(gate, Direction.SOUTH));
		assertFalse(LevelWorldView.canOpen(gate, Direction.EAST));
	}

	@Test
	void otherBlocksDoNotOpen() {
		assertFalse(LevelWorldView.canOpen(Blocks.OAK_TRAPDOOR.defaultBlockState(), Direction.EAST));
		assertFalse(LevelWorldView.canOpen(Blocks.OAK_FENCE.defaultBlockState(), Direction.EAST));
	}

	@Test
	void aTrapdoorOpensByHandButAnIronOneDoesNot() {
		assertTrue(LevelWorldView.isOpenable(Blocks.OAK_TRAPDOOR.defaultBlockState()));
		assertFalse(LevelWorldView.isOpenable(Blocks.IRON_TRAPDOOR.defaultBlockState()));
	}
}
