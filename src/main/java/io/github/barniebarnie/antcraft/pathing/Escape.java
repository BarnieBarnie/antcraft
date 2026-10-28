package io.github.barniebarnie.antcraft.pathing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Where a bot's reflexes take it (docs/scripting.md, Reflexes): a step aside, out of fire wider than a block or from under a falling block,
 * and the way up to air for a bot running out of it under water. Positions are those of the bot's feet.
 */
public final class Escape {
	/** How far {@link #stepAside} looks, in blocks from the bot each way. */
	static final int STEP_RANGE = 4;
	/** How far {@link #wayToAir} looks, in blocks from the bot each way. */
	static final int AIR_RANGE = 12;
	/** The eight blocks around, the four sides first. */
	private static final int[][] AROUND = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}, {1, -1}, {1, 1}, {-1, 1}, {-1, -1}};
	/** Up first, so in open water the way is straight up. */
	private static final Direction[] SWIM = {Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.DOWN};

	private Escape() {
	}

	/**
	 * The first step toward the nearest block the bot can stand in, safe from hazards and not one {@code avoid} tests
	 * true for, walking through fire and the like on its way, within {@link #STEP_RANGE}: a step to one of the eight
	 * around at the same height (a diagonal only past a side it fits through), a block up or a block down, the four
	 * sides first. Empty when there is none.
	 */
	public static Optional<BlockPos> stepAside(WorldView world, BlockPos feet, Predicate<BlockPos> avoid) {
		Map<BlockPos, BlockPos> from = new HashMap<>();
		ArrayDeque<BlockPos> open = new ArrayDeque<>();
		from.put(feet, feet);
		open.add(feet);
		while (!open.isEmpty()) {
			BlockPos at = open.poll();
			if (!at.equals(feet) && standable(world, at) && !avoid.test(at)) {
				BlockPos step = at;
				while (!from.get(step).equals(feet)) {
					step = from.get(step);
				}
				return Optional.of(step);
			}
			for (BlockPos next : steps(world, at)) {
				if (!from.containsKey(next) && Math.max(Math.max(Math.abs(next.getX() - feet.getX()), Math.abs(next.getY() - feet.getY())),
						Math.abs(next.getZ() - feet.getZ())) <= STEP_RANGE) {
					from.put(next, at);
					open.add(next);
				}
			}
		}
		return Optional.empty();
	}

	/** The blocks a bot at {@code feet} can step to and stand in, safe or not, in the order {@link #stepAside} tries them. */
	private static List<BlockPos> steps(WorldView world, BlockPos feet) {
		List<BlockPos> steps = new ArrayList<>();
		for (int dy : new int[] {0, 1, -1}) {
			if (dy == 1 && !world.isPassable(feet.above(2))) {
				continue; // no room to jump
			}
			for (int[] d : AROUND) {
				boolean diagonal = d[0] != 0 && d[1] != 0;
				if (diagonal && (dy != 0 || !(fits(world, feet.offset(d[0], 0, 0)) || fits(world, feet.offset(0, 0, d[1]))))) {
					continue;
				}
				BlockPos to = feet.offset(d[0], dy, d[1]);
				if (dy == -1 && !fits(world, to.above())) {
					continue;
				}
				if (fits(world, to) && world.canStandOn(to.below())) {
					steps.add(to);
				}
			}
		}
		return steps;
	}

	/** Whether a bot fits in the two blocks from {@code feet} up, hazards or not. */
	private static boolean fits(WorldView world, BlockPos feet) {
		return world.isPassable(feet) && world.isPassable(feet.above());
	}

	/** Whether a bot fits in the two blocks from {@code feet} up, with no hazard in them. */
	private static boolean open(WorldView world, BlockPos feet) {
		return world.isPassable(feet) && world.isPassable(feet.above()) && !world.isHazard(feet) && !world.isHazard(feet.above());
	}

	private static boolean standable(WorldView world, BlockPos feet) {
		return open(world, feet) && world.canStandOn(feet.below()) && !world.isHazard(feet.below());
	}

	/**
	 * The shortest swim from {@code feet}, under water, to where the bot's head is in air, a block at a time, through
	 * water and open blocks within {@link #AIR_RANGE}. Empty when there is no way to air.
	 */
	public static List<BlockPos> wayToAir(WorldView world, BlockPos feet) {
		Map<BlockPos, BlockPos> from = new HashMap<>();
		ArrayDeque<BlockPos> open = new ArrayDeque<>();
		from.put(feet, feet);
		open.add(feet);
		while (!open.isEmpty()) {
			BlockPos at = open.poll();
			if (!at.equals(feet) && breathes(world, at)) {
				List<BlockPos> way = new ArrayList<>();
				for (BlockPos step = at; !step.equals(feet); step = from.get(step)) {
					way.add(step);
				}
				Collections.reverse(way);
				return way;
			}
			for (Direction dir : SWIM) {
				BlockPos next = at.relative(dir);
				if (!from.containsKey(next) && feet.distManhattan(next) <= AIR_RANGE && swimmable(world, next)) {
					from.put(next, at);
					open.add(next);
				}
			}
		}
		return List.of();
	}

	private static boolean breathes(WorldView world, BlockPos feet) {
		return world.isPassable(feet.above()) && !world.isHazard(feet.above());
	}

	private static boolean swimmable(WorldView world, BlockPos feet) {
		return fluidOrOpen(world, feet) && fluidOrOpen(world, feet.above());
	}

	private static boolean fluidOrOpen(WorldView world, BlockPos pos) {
		return world.isWater(pos) || (world.isPassable(pos) && !world.isHazard(pos));
	}
}
