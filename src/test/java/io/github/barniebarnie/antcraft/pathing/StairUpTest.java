package io.github.barniebarnie.antcraft.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Which way a bot walks up onto a stair without jumping, checked against vanilla stair shapes. */
class StairUpTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static Set<Direction> walkedUpMoving(StairsShape shape, Half half) {
		BlockState state = Blocks.OAK_STAIRS.defaultBlockState()
			.setValue(StairBlock.FACING, Direction.EAST)
			.setValue(StairBlock.SHAPE, shape)
			.setValue(StairBlock.HALF, half);
		return Direction.Plane.HORIZONTAL.stream()
			.filter(dir -> LevelWorldView.stairUp(state, dir))
			.collect(Collectors.toSet());
	}

	@Test
	void aStraightStairIsWalkedUpOnlyFromItsLowFront() {
		assertEquals(Set.of(Direction.EAST), walkedUpMoving(StairsShape.STRAIGHT, Half.BOTTOM));
	}

	@Test
	void anOuterCornerIsWalkedUpFromBothLowSidesAndJumpedOntoFromItsTallSides() {
		// Facing east, an outer left corner is tall in its north-east quarter only.
		assertEquals(Set.of(Direction.EAST, Direction.NORTH), walkedUpMoving(StairsShape.OUTER_LEFT, Half.BOTTOM));
		assertEquals(Set.of(Direction.EAST, Direction.SOUTH), walkedUpMoving(StairsShape.OUTER_RIGHT, Half.BOTTOM));
	}

	@Test
	void anInnerCornerIsJumpedOntoFromEverySide() {
		// Three quarters are tall, so every side the bot enters by has a tall quarter.
		assertEquals(Set.of(), walkedUpMoving(StairsShape.INNER_LEFT, Half.BOTTOM));
		assertEquals(Set.of(), walkedUpMoving(StairsShape.INNER_RIGHT, Half.BOTTOM));
	}

	@Test
	void anUpsideDownStairIsJumpedOntoFromEverySide() {
		assertEquals(Set.of(), walkedUpMoving(StairsShape.STRAIGHT, Half.TOP));
		assertEquals(Set.of(), walkedUpMoving(StairsShape.OUTER_LEFT, Half.TOP));
	}
}
