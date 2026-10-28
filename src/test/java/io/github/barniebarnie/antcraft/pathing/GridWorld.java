package io.github.barniebarnie.antcraft.pathing;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * In-memory block world for pathfinder tests. Unset positions are air.
 * '#' = stone (30 ticks to break), 'D' = dirt (10 ticks), 'B' = bedrock (unbreakable), 'L' = lava,
 * 'W' = water (a hazard like any fluid, but swimmable), 'S' = a stair block, set with {@link #stair} (stood on, and walked up onto without a jump in the way it faces),
 * 'O' = a door, set with {@link #door} (unbreakable, but opened by a bot walking through it along its axis),
 * 'H' = a ladder (climbed, unbreakable), 'T' = a trapdoor over a ladder (stood on shut, climbed through open),
 * 'F' = fire (walked through, but a hazard), 'P' = a nether portal (walked through, but a hazard unless it is the goal).
 */
public final class GridWorld implements WorldView {
	private final Map<BlockPos, Character> blocks = new HashMap<>();
	private final Map<BlockPos, Direction> stairs = new HashMap<>();
	private final Map<BlockPos, Direction.Axis> doors = new HashMap<>();
	private final Set<BlockPos> noPlacing = new HashSet<>();
	private boolean placingForbidden;

	/** Forbids placing blocks at {@code pos}. */
	GridWorld forbidPlacing(BlockPos pos) {
		this.noPlacing.add(pos);
		return this;
	}

	/** Forbids placing blocks anywhere. */
	GridWorld forbidAllPlacing() {
		this.placingForbidden = true;
		return this;
	}

	@Override
	public boolean mayPlace(BlockPos pos) {
		return !this.placingForbidden && !this.noPlacing.contains(pos);
	}

	public GridWorld set(int x, int y, int z, char block) {
		this.blocks.put(new BlockPos(x, y, z), block);
		return this;
	}

	/** A bottom stair block whose tall back is on its {@code facing} side. */
	GridWorld stair(int x, int y, int z, Direction facing) {
		this.stairs.put(new BlockPos(x, y, z), facing);
		return this.set(x, y, z, 'S');
	}

	/** A two-block-high door at feet height {@code y}, walked through along {@code axis}. */
	GridWorld door(int x, int y, int z, Direction.Axis axis) {
		this.doors.put(new BlockPos(x, y, z), axis);
		this.doors.put(new BlockPos(x, y + 1, z), axis);
		return this.set(x, y, z, 'O').set(x, y + 1, z, 'O');
	}

	/** Fills the box between two corners (inclusive). */
	GridWorld fill(int x1, int y1, int z1, int x2, int y2, int z2, char block) {
		for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
			for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
				for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
					this.set(x, y, z, block);
				}
			}
		}
		return this;
	}

	/** A stone floor at y=0 covering x and z from -size to size. */
	static GridWorld flat(int size) {
		return new GridWorld().fill(-size, 0, -size, size, 0, size, '#');
	}

	char at(BlockPos pos) {
		return this.blocks.getOrDefault(pos, ' ');
	}

	@Override
	public boolean isPassable(BlockPos pos) {
		char c = this.at(pos);
		return c == ' ' || c == 'F' || c == 'P';
	}

	@Override
	public boolean canStandOn(BlockPos pos) {
		char c = this.at(pos);
		return c == '#' || c == 'D' || c == 'B' || c == 'S' || c == 'T';
	}

	@Override
	public boolean isClimbable(BlockPos pos) {
		char c = this.at(pos);
		return c == 'H' || c == 'T';
	}

	@Override
	public boolean isHazard(BlockPos pos) {
		char c = this.at(pos);
		return c == 'L' || c == 'W' || c == 'F' || c == 'P';
	}

	@Override
	public boolean isPortal(BlockPos pos) {
		return this.at(pos) == 'P';
	}

	@Override
	public boolean stairUp(BlockPos pos, Direction dir) {
		return this.stairs.get(pos) == dir;
	}

	@Override
	public boolean canOpen(BlockPos pos, Direction dir) {
		return this.doors.get(pos) == dir.getAxis();
	}

	@Override
	public boolean isWater(BlockPos pos) {
		return this.at(pos) == 'W';
	}

	@Override
	public float breakTicks(BlockPos pos) {
		return switch (this.at(pos)) {
			case '#', 'S' -> 30;
			case 'D' -> 10;
			default -> -1;
		};
	}
}
