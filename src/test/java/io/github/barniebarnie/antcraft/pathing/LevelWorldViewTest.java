package io.github.barniebarnie.antcraft.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;
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

	/** Honey is a low floor whose jump, at half speed, lifts a bot well under a block (#659); stone's about 1.25. */
	@Test
	void aJumpFromHoneyFallsShortOfABlock() {
		assertTrue(LevelWorldView.isLowFloor(Blocks.HONEY_BLOCK.defaultBlockState().getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)));
		assertTrue(WorldView.jumpHeight(Blocks.HONEY_BLOCK.getJumpFactor()) < 0.5);
		assertEquals(1.25, WorldView.jumpHeight(Blocks.STONE.getJumpFactor()), 0.01);
	}

	/**
	 * Jump Boost adds 0.1 a level to a jump's speed after the block's factor, as vanilla's getJumpBoostPower does
	 * (#665): with Jump Boost II a jump from honey clears a block over the honey's top, without it falls well short.
	 */
	@Test
	void aJumpFromHoneyWithJumpBoostTwoClearsABlock() {
		assertTrue(WorldView.jumpHeight(Blocks.HONEY_BLOCK.getJumpFactor(), 0.2f) >= 1);
		assertTrue(WorldView.jumpHeight(Blocks.HONEY_BLOCK.getJumpFactor(), 0) < 1);
		assertEquals(WorldView.jumpHeight(1), WorldView.jumpHeight(1, 0));
	}

	@Test
	void aTrapdoorOpensByHandButAnIronOneDoesNot() {
		assertTrue(LevelWorldView.isOpenable(Blocks.OAK_TRAPDOOR.defaultBlockState()));
		assertFalse(LevelWorldView.isOpenable(Blocks.IRON_TRAPDOOR.defaultBlockState()));
	}

	/** Leaves with no log in range (distance 7) and not placed by hand decay at a random tick, so are no floor (#673). */
	@Test
	void leavesWithNoLogInRangeDecay() {
		BlockState leaves = Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false);
		assertTrue(LevelWorldView.decays(leaves.setValue(LeavesBlock.DISTANCE, LeavesBlock.DECAY_DISTANCE)));
		assertFalse(LevelWorldView.decays(leaves.setValue(LeavesBlock.DISTANCE, 6)));
		assertFalse(LevelWorldView.decays(leaves.setValue(LeavesBlock.DISTANCE, 7).setValue(LeavesBlock.PERSISTENT, true)));
		assertFalse(LevelWorldView.decays(Blocks.STONE.defaultBlockState()));
	}

	/** A ladder holds on to the block behind it, a vine to the faces it clings to, scaffolding to none (#735). */
	@Test
	void aClimbableHoldsOnToItsFaces() {
		BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH);
		assertEquals(1 << Direction.SOUTH.get3DDataValue(), LevelWorldView.climbFaces(ladder));
		BlockState vine = Blocks.VINE.defaultBlockState().setValue(VineBlock.WEST, true).setValue(VineBlock.UP, true);
		assertEquals(1 << Direction.WEST.get3DDataValue() | 1 << Direction.UP.get3DDataValue(), LevelWorldView.climbFaces(vine));
		assertEquals(0, LevelWorldView.climbFaces(Blocks.SCAFFOLDING.defaultBlockState()));
	}
}
