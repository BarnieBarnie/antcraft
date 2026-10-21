package io.github.barniebarnie.antcraft.pathing;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * In-memory block world for pathfinder tests. Unset positions are air.
 * '#' = stone (30 ticks to break), 'D' = dirt (10 ticks), 'B' = bedrock (unbreakable), 'L' = lava,
 * 'W' = water (a hazard like any fluid, but swimmable), 'S' = a stair block (stood on, and walked up onto without a jump).
 */
final class GridWorld implements WorldView {
	private final Map<BlockPos, Character> blocks = new HashMap<>();
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

	GridWorld set(int x, int y, int z, char block) {
		this.blocks.put(new BlockPos(x, y, z), block);
		return this;
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
		return this.at(pos) == ' ';
	}

	@Override
	public boolean canStandOn(BlockPos pos) {
		char c = this.at(pos);
		return c == '#' || c == 'D' || c == 'B' || c == 'S';
	}

	@Override
	public boolean isHazard(BlockPos pos) {
		char c = this.at(pos);
		return c == 'L' || c == 'W';
	}

	@Override
	public boolean isStair(BlockPos pos) {
		return this.at(pos) == 'S';
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
