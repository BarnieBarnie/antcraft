package io.github.barniebarnie.antcraft.colony;

import net.minecraft.core.BlockPos;

/**
 * The area a colony works in: a square of {@code radius} blocks around the core,
 * at any height, matching the area the colony surveys for work.
 */
public record Territory(BlockPos core, int radius) {
	public static Territory of(Colony colony) {
		return new Territory(colony.core(), colony.radius());
	}

	public boolean contains(BlockPos pos) {
		return Math.abs(pos.getX() - this.core.getX()) <= this.radius && Math.abs(pos.getZ() - this.core.getZ()) <= this.radius;
	}

	/** A bot with nothing to do, outside the colony, heads home. A fight or a job in progress is never cut short. */
	public boolean shouldReturnHome(BlockPos bot, boolean fighting, boolean working) {
		return !fighting && !working && !this.contains(bot);
	}

	/**
	 * Bots only start fights with mobs inside the colony, wherever the bot stands.
	 * Mobs that attack a bot are fought back regardless.
	 */
	public boolean mayTarget(BlockPos target) {
		return this.contains(target);
	}
}
