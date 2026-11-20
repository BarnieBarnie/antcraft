package io.github.barniebarnie.antcraft.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * When a goal whose last plan found no way off from a creeper ({@link KiteGoal#away}) plans again: a plan, 20 random
 * spots and a path, costs about 2 ms, and the next can find a spot this one did not (#759). It waits {@link #FIRST} ticks
 * after the first plan that found none, twice as long after each more, up to {@link #MOST}, as a bot cornered for good
 * never finds one (#975). Once the bot or the creeper stands on another block it waits only {@link #FIRST} again, as a
 * creeper walking at the bot changes block every few ticks. A way opening otherwise, such as a block broken, it finds
 * within {@link #MOST} ticks.
 */
final class Replan {
	static final int FIRST = 4;
	static final int MOST = 20;

	/** How long it waits after the last plan that found none; 0 where that plan found one. */
	private int wait;
	private int failedAt;
	private @Nullable BlockPos bot;
	private @Nullable BlockPos creeper;

	/** Whether {@code bot} plans its way off from {@code creeper} now. */
	boolean due(Entity bot, Entity creeper) {
		return this.wait == 0 || bot.tickCount - this.failedAt >= (this.moved(bot, creeper) ? FIRST : this.wait);
	}

	/** Where the plan just made found no way off. */
	void failed(Entity bot, Entity creeper) {
		this.wait = this.wait == 0 || this.moved(bot, creeper) ? FIRST : Math.min(2 * this.wait, MOST);
		this.failedAt = bot.tickCount;
		this.bot = bot.blockPosition();
		this.creeper = creeper.blockPosition();
	}

	/** Whether the bot or the creeper stands on another block than at the last plan that found no way off. */
	private boolean moved(Entity bot, Entity creeper) {
		return !bot.blockPosition().equals(this.bot) || !creeper.blockPosition().equals(this.creeper);
	}

	/** Where a plan found a way off, or the goal let go. */
	void reset() {
		this.wait = 0;
	}
}
