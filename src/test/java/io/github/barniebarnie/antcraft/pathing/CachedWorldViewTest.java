package io.github.barniebarnie.antcraft.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/** A search asks the live world about each block once per question, as the world's lookups are what a node costs. */
class CachedWorldViewTest {
	/** Counts what is asked of a world, per question and block. */
	private static final class Counting implements WorldView {
		private final WorldView world;
		final Map<String, Integer> asked = new HashMap<>();

		Counting(WorldView world) {
			this.world = world;
		}

		private void ask(String question, BlockPos pos) {
			this.asked.merge(question + " " + pos.toShortString(), 1, Integer::sum);
		}

		@Override
		public boolean isPassable(BlockPos pos) {
			this.ask("passable", pos);
			return this.world.isPassable(pos);
		}

		@Override
		public boolean canStandOn(BlockPos pos) {
			this.ask("standOn", pos);
			return this.world.canStandOn(pos);
		}

		@Override
		public boolean isHazard(BlockPos pos) {
			this.ask("hazard", pos);
			return this.world.isHazard(pos);
		}

		@Override
		public boolean isWater(BlockPos pos) {
			this.ask("water", pos);
			return this.world.isWater(pos);
		}

		@Override
		public float breakTicks(BlockPos pos) {
			this.ask("breakTicks", pos);
			return this.world.breakTicks(pos);
		}

		@Override
		public boolean canOpen(BlockPos pos, Direction dir) {
			this.ask("canOpen " + dir, pos);
			return this.world.canOpen(pos, dir);
		}

		@Override
		public boolean isClimbable(BlockPos pos) {
			this.ask("climbable", pos);
			return this.world.isClimbable(pos);
		}

		@Override
		public boolean stairUp(BlockPos pos, Direction dir) {
			this.ask("stairUp " + dir, pos);
			return this.world.stairUp(pos, dir);
		}

		@Override
		public boolean isBedrock(BlockPos pos) {
			this.ask("bedrock", pos);
			return this.world.isBedrock(pos);
		}

		@Override
		public boolean mayPlace(BlockPos pos) {
			this.ask("mayPlace", pos);
			return this.world.mayPlace(pos);
		}
	}

	@Test
	void aSearchAsksTheWorldAboutEachBlockOncePerQuestion() {
		// A walled-in goal: the search spends all its nodes, breaking allowed, over stone, water and a door.
		GridWorld grid = GridWorld.flat(12).fill(-12, -3, -12, 12, -1, 12, '#').fill(4, -3, -12, 4, 3, 12, 'B').fill(-3, 0, -3, -2, 0, -2, 'W')
			.door(-5, 1, 0, Direction.Axis.X);
		Counting world = new Counting(grid);
		Path path = PathFinder.find(world, new BlockPos(0, 1, 0), new Goal.Block(new BlockPos(8, 1, 0)), new PathFinder.Options(true, false, 2000, 3));

		assertFalse(path.complete());
		Map.Entry<String, Integer> most = world.asked.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
		assertEquals(1, most.getValue(), "asked " + most.getKey() + " " + most.getValue() + " times");
	}

	@Test
	void theViewAnswersAsTheWorldDoes() {
		GridWorld grid = GridWorld.flat(4).set(1, 1, 0, 'D').set(2, 1, 0, 'L').set(3, 1, 0, 'W').stair(0, 1, 1, Direction.EAST).door(1, 1, 1, Direction.Axis.X)
			.forbidPlacing(new BlockPos(0, 2, 0));
		CachedWorldView view = new CachedWorldView(grid);
		for (BlockPos pos : BlockPos.betweenClosed(-1, 0, -1, 4, 3, 2)) {
			// Twice, so the second answer comes from the cache.
			for (int i = 0; i < 2; i++) {
				assertEquals(grid.isPassable(pos), view.isPassable(pos), "passable " + pos);
				assertEquals(grid.canStandOn(pos), view.canStandOn(pos), "standOn " + pos);
				assertEquals(grid.isHazard(pos), view.isHazard(pos), "hazard " + pos);
				assertEquals(grid.isWater(pos), view.isWater(pos), "water " + pos);
				assertEquals(grid.breakTicks(pos), view.breakTicks(pos), "breakTicks " + pos);
				assertEquals(grid.isClimbable(pos), view.isClimbable(pos), "climbable " + pos);
				assertEquals(grid.isBedrock(pos), view.isBedrock(pos), "bedrock " + pos);
				assertEquals(grid.mayPlace(pos), view.mayPlace(pos), "mayPlace " + pos);
				for (Direction dir : Direction.values()) {
					assertEquals(grid.canOpen(pos, dir), view.canOpen(pos, dir), "canOpen " + dir + " " + pos);
					assertEquals(grid.stairUp(pos, dir), view.stairUp(pos, dir), "stairUp " + dir + " " + pos);
				}
			}
		}
		assertTrue(view.canOpen(new BlockPos(1, 1, 1), Direction.EAST));
	}
}
