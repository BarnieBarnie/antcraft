package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FireSpreadTest {
	private static final BlockPos FIRE = new BlockPos(0, 1, 0);
	private static BlockState PLANKS;
	private static BlockState STONE;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		PLANKS = Blocks.OAK_PLANKS.defaultBlockState();
		STONE = Blocks.STONE.defaultBlockState();
	}

	private static Function<BlockPos, BlockState> at(Map<BlockPos, BlockState> world) {
		return pos -> world.getOrDefault(pos, Blocks.AIR.defaultBlockState());
	}

	/**
	 * Fire on netherrack in a stone hearth: the 3x3 columns around it are stone from the floor up to
	 * five blocks above the fire, but for the fire and a chimney of air above it, with oak planks
	 * walls two blocks from the fire, against the stone.
	 */
	private static Map<BlockPos, BlockState> fireplace() {
		Map<BlockPos, BlockState> world = new HashMap<>();
		for (BlockPos pos : BlockPos.betweenClosed(-1, 0, -1, 1, 6, 1)) {
			world.put(pos.immutable(), STONE);
		}
		world.put(new BlockPos(0, 0, 0), Blocks.NETHERRACK.defaultBlockState());
		for (int y = 1; y <= 6; y++) {
			world.remove(new BlockPos(0, y, 0));
		}
		for (int y = 1; y <= 4; y++) {
			for (int i = -2; i <= 2; i++) {
				world.put(new BlockPos(2, y, i), PLANKS);
				world.put(new BlockPos(-2, y, i), PLANKS);
				world.put(new BlockPos(i, y, 2), PLANKS);
				world.put(new BlockPos(i, y, -2), PLANKS);
			}
		}
		return world;
	}

	@Test
	void aFireplaceInsideWoodenWallsDoesNotSpread() {
		assertFalse(FireSpread.reachesFlammable(at(fireplace()), FIRE));
	}

	@Test
	void fireWithAFlammableBlockTouchingItSpreads() {
		Map<BlockPos, BlockState> world = fireplace();
		world.put(FIRE.east(), PLANKS);
		assertTrue(FireSpread.reachesFlammable(at(world), FIRE), "planks beside the fire");
		world = fireplace();
		world.put(FIRE.above(5), PLANKS);
		assertTrue(FireSpread.reachesFlammable(at(world), FIRE), "planks capping the chimney, beside air fire spreads to");
	}

	@Test
	void fireInTheOpenSpreadsToPlanksTwoBlocksAway() {
		Map<BlockPos, BlockState> world = new HashMap<>(Map.of(FIRE.below(), Blocks.NETHERRACK.defaultBlockState(), FIRE.east(2), PLANKS));
		assertTrue(FireSpread.reachesFlammable(at(world), FIRE));
		world.put(FIRE.east(3), PLANKS);
		world.remove(FIRE.east(2));
		assertFalse(FireSpread.reachesFlammable(at(world), FIRE), "three blocks away");
	}

	@Test
	void aFlammableBlockIsNotPlacedWhereLitFireSpreadsToIt() {
		Map<BlockPos, BlockState> world = new HashMap<>(Map.of(FIRE.below(), Blocks.NETHERRACK.defaultBlockState(), FIRE, Blocks.FIRE.defaultBlockState()));
		assertTrue(FireSpread.endangers(at(world), FIRE.east(), PLANKS), "touching the fire");
		assertTrue(FireSpread.endangers(at(world), FIRE.east(2), PLANKS), "beside air the fire spreads to");
		assertTrue(FireSpread.endangers(at(world), FIRE.above(5), PLANKS), "above the fire");
		assertFalse(FireSpread.endangers(at(world), FIRE.east(), STONE), "stone does not burn");
		assertFalse(FireSpread.endangers(at(world), FIRE.east(3), PLANKS), "out of reach");
		// Inside the fireplace, lit, the walls are safe.
		Map<BlockPos, BlockState> lit = fireplace();
		lit.put(FIRE, Blocks.FIRE.defaultBlockState());
		assertFalse(FireSpread.endangers(at(lit), new BlockPos(2, 1, 0), PLANKS), "wall beside the hearth");
	}
}
