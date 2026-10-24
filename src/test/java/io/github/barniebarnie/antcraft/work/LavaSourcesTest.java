package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.barniebarnie.antcraft.pathing.Goal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LavaSourcesTest {
	private static final double REACH = 4.0;
	private static BlockState AIR;
	private static BlockState STONE;
	private static BlockState LAVA;
	private static BlockState FLOWING_LAVA;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		AIR = Blocks.AIR.defaultBlockState();
		STONE = Blocks.STONE.defaultBlockState();
		LAVA = Blocks.LAVA.defaultBlockState();
		FLOWING_LAVA = Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 2);
	}

	/** Stone ground with its top at y=0 from -8 to 8 sideways, two blocks deep, and air above. */
	private static Map<BlockPos, BlockState> ground() {
		Map<BlockPos, BlockState> world = new HashMap<>();
		for (int x = -8; x <= 8; x++) {
			for (int z = -8; z <= 8; z++) {
				world.put(new BlockPos(x, 0, z), STONE);
				world.put(new BlockPos(x, -1, z), STONE);
			}
		}
		return world;
	}

	private static Function<BlockPos, BlockState> view(Map<BlockPos, BlockState> world) {
		return pos -> world.getOrDefault(pos, AIR);
	}

	@Test
	void theRimOfAPoolInTheGroundIsSafe() {
		Map<BlockPos, BlockState> world = ground();
		world.put(BlockPos.ZERO, LAVA);

		assertTrue(LavaSources.isSafeSpot(view(world), new BlockPos(1, 1, 0)));
		assertTrue(LavaSources.isSafeSpot(view(world), new BlockPos(1, 1, 1)));
	}

	@Test
	void standingOverLavaIsNotSafe() {
		Map<BlockPos, BlockState> world = ground();
		world.put(BlockPos.ZERO, LAVA);

		assertFalse(LavaSources.isSafeSpot(view(world), new BlockPos(0, 1, 0)));
	}

	@Test
	void standingInOrBesideLavaIsNotSafe() {
		Map<BlockPos, BlockState> world = ground();
		world.put(new BlockPos(0, 1, 0), LAVA);
		world.put(new BlockPos(5, 1, 0), FLOWING_LAVA);

		assertFalse(LavaSources.isSafeSpot(view(world), new BlockPos(0, 1, 0)), "in a source");
		assertFalse(LavaSources.isSafeSpot(view(world), new BlockPos(1, 1, 0)), "beside a source at its feet");
		assertFalse(LavaSources.isSafeSpot(view(world), new BlockPos(4, 1, 0)), "beside flowing lava");
		assertFalse(LavaSources.isSafeSpot(view(world), new BlockPos(5, 1, 0)), "in flowing lava");
		assertTrue(LavaSources.isSafeSpot(view(world), new BlockPos(3, 1, 0)), "a block away from flowing lava");
	}

	@Test
	void lavaBesideOrAboveTheHeadIsNotSafe() {
		Map<BlockPos, BlockState> world = ground();
		world.put(new BlockPos(1, 2, 0), FLOWING_LAVA);
		world.put(new BlockPos(4, 3, 0), FLOWING_LAVA);

		assertFalse(LavaSources.isSafeSpot(view(world), new BlockPos(0, 1, 0)), "beside the head");
		assertFalse(LavaSources.isSafeSpot(view(world), new BlockPos(4, 1, 0)), "above the head");
	}

	@Test
	void aSpotWithoutFloorOrRoomIsNotSafe() {
		Map<BlockPos, BlockState> world = ground();
		world.put(new BlockPos(2, 2, 0), STONE);

		assertFalse(LavaSources.isSafeSpot(view(world), new BlockPos(0, 2, 0)), "no floor");
		assertFalse(LavaSources.isSafeSpot(view(world), new BlockPos(0, 0, 0)), "in stone");
		assertFalse(LavaSources.isSafeSpot(view(world), new BlockPos(2, 1, 0)), "no room for the head");
	}

	@Test
	void choosesTheNearestSourceWithASafeSpotWithinReach() {
		Map<BlockPos, BlockState> world = ground();
		// The nearer source is buried: stone all around it and a block of stone over each cell within reach above it.
		BlockPos buried = new BlockPos(-2, -1, 0);
		world.put(buried, LAVA);
		for (int x = -7; x <= 3; x++) {
			for (int z = -5; z <= 5; z++) {
				world.put(new BlockPos(x, 1, z), STONE);
				world.put(new BlockPos(x, 2, z), STONE);
			}
		}
		BlockPos open = new BlockPos(6, 0, 0);
		world.put(open, LAVA);

		Optional<LavaSources.Fill> fill = LavaSources.choose(view(world), BlockPos.ZERO.above(3), List.of(buried, open), REACH);

		assertEquals(open, fill.orElseThrow().source());
		BlockPos spot = fill.get().spot();
		assertTrue(LavaSources.isSafeSpot(view(world), spot), "unsafe spot " + spot);
		assertTrue(new Goal.Reach(open, REACH).isReached(spot), "out of reach: " + spot);
		// Of the safe spots, the one nearest where the bot comes from: on the near side of the pool.
		assertEquals(new BlockPos(4, 1, 0), spot);
	}

	@Test
	void choosesNoSourceWithoutASafeSpot() {
		Map<BlockPos, BlockState> world = ground();
		// A lava lake: the ground is lava everywhere within reach, so every spot stands over or beside it.
		for (int x = -8; x <= 8; x++) {
			for (int z = -8; z <= 8; z++) {
				world.put(new BlockPos(x, 0, z), LAVA);
			}
		}

		assertEquals(Optional.empty(), LavaSources.choose(view(world), BlockPos.ZERO.above(), List.of(BlockPos.ZERO), REACH));
		assertEquals(Optional.empty(), LavaSources.choose(view(world), BlockPos.ZERO.above(), List.of(), REACH));
	}

	@Test
	void everySideOfTheRimIsSafe() {
		Map<BlockPos, BlockState> world = ground();
		world.put(BlockPos.ZERO, LAVA);
		for (Direction side : Direction.Plane.HORIZONTAL) {
			assertTrue(LavaSources.isSafeSpot(view(world), BlockPos.ZERO.above().relative(side)), side.toString());
		}
	}
}
