package io.github.barniebarnie.antcraft.colony;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * The area a colony works in: a square of {@code radius} blocks around the core,
 * at any height, matching the area the colony surveys for work. Game tests clip it sideways to {@code bounds}, their
 * test area, so tests running side by side cannot reach into each other; elsewhere {@code bounds} is null.
 * {@code mine} is where bots walk in the colony's mine (see {@link io.github.barniebarnie.antcraft.work.Mineshaft#keepOpen}),
 * whose branches reach far past the radius.
 */
public record Territory(BlockPos core, int radius, @Nullable BoundingBox bounds, Set<BlockPos> mine) {
	public Territory(BlockPos core, int radius) {
		this(core, radius, null, Set.of());
	}

	public static Territory of(Colony colony) {
		return new Territory(colony.core(), colony.radius(), colony.bounds(), colony.mineCells());
	}

	public boolean contains(BlockPos pos) {
		return Math.abs(pos.getX() - this.core.getX()) <= this.radius && Math.abs(pos.getZ() - this.core.getZ()) <= this.radius
			&& inBounds(this.bounds, pos);
	}

	/** Whether {@code pos} is inside {@code bounds} sideways, at any height; anywhere is when there are none. */
	static boolean inBounds(@Nullable BoundingBox bounds, BlockPos pos) {
		return bounds == null || pos.getX() >= bounds.minX() && pos.getX() <= bounds.maxX() && pos.getZ() >= bounds.minZ() && pos.getZ() <= bounds.maxZ();
	}

	/** Whether the whole box between the corners {@code from} and {@code to} is inside: the territory is a square, so both corners are. */
	public boolean contains(BlockPos from, BlockPos to) {
		return this.contains(from) && this.contains(to);
	}

	/** A bot with nothing to do, outside the colony and its mine, heads home. A fight or a job in progress is never cut short. */
	public boolean shouldReturnHome(BlockPos bot, boolean fighting, boolean working) {
		return !fighting && !working && !this.contains(bot) && !this.mine.contains(bot);
	}

	/**
	 * Bots only start fights with mobs inside the colony or its mine, wherever the bot stands, so they defend the
	 * members mining far branches. Mobs that attack a bot are fought back regardless.
	 */
	public boolean mayTarget(BlockPos target) {
		return this.contains(target) || this.mine.contains(target);
	}
}
