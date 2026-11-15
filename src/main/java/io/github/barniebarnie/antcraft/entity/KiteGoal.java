package io.github.barniebarnie.antcraft.entity;

import java.util.Comparator;
import java.util.EnumSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Fights creepers by kiting (docs/features/bots.md, Fighting creepers): while a creeper near the bot has its fuse lit,
 * the bot steps in to strike it only if its weapon is ready and the fuse has just started, and otherwise backs off until
 * the fuse goes out, which a creeper's does only with its target more than 7 blocks away (SwellGoal). With every fuse out,
 * the melee goal fights on as against any mob, and {@code attack} waits while this goal backs off.
 */
public final class KiteGoal extends Goal {
	/** How near, in blocks, a lit creeper makes the bot kite: a little beyond the 7 blocks at which a fuse goes out. */
	static final double RANGE = 8;
	/** How far the fuse may have burnt, as {@link Creeper#getSwelling} gives it, for the bot still to step in and strike. */
	private static final float STRIKE_SWELL = 0.35F;
	/** How fast the bot steps in and backs off, as a factor of its speed: faster than the melee goal's 1.2. */
	private static final double SPEED = 1.5;

	private final AntBot bot;
	private @Nullable Creeper creeper;
	private @Nullable Vec3 away;

	KiteGoal(AntBot bot) {
		this.bot = bot;
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	/**
	 * The nearest creeper within {@link #RANGE} of {@code bot} whose fuse burns or has yet to go out, or null: the bot
	 * keeps clear of its blast meanwhile. Whichever mob it lit for, as a bot's sword sweep lights one beside its target.
	 * ponytail: an entity search a tick for each bot; skip it for bots with no creeper about if the benchmark shows it.
	 */
	public static @Nullable Creeper litNear(AntBot bot) {
		return bot.level().getEntitiesOfClass(Creeper.class, bot.getBoundingBox().inflate(RANGE),
				creeper -> creeper.isAlive() && (creeper.getSwellDir() > 0 || creeper.getSwelling(1) > 0) && creeper.distanceTo(bot) <= RANGE)
			.stream().min(Comparator.comparingDouble(creeper -> creeper.distanceToSqr(bot))).orElse(null);
	}

	@Override
	public boolean canUse() {
		this.creeper = litNear(this.bot);
		return this.creeper != null;
	}

	@Override
	public boolean canContinueToUse() {
		return this.canUse();
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void stop() {
		this.creeper = null;
		this.away = null;
		this.bot.getNavigation().stop();
	}

	@Override
	public void tick() {
		Creeper creeper = this.creeper;
		if (creeper == null) {
			return;
		}
		this.bot.getLookControl().setLookAt(creeper);
		boolean ready = this.bot.tickCount - this.bot.getLastHurtMobTimestamp() >= BotHands.strikeTicks(this.bot);
		if (ready && BotHands.inStrikingReach(this.bot, creeper)) {
			BotHands.strike(this.bot, (ServerLevel) this.bot.level(), creeper);
			return;
		}
		if (ready && creeper.getSwelling(1) < STRIKE_SWELL) {
			this.away = null;
			this.bot.getNavigation().moveTo(creeper, SPEED);
			return;
		}
		// Somewhere further from the creeper than the bot is, as vanilla's AvoidEntityGoal picks; planned again once there.
		if (this.away == null || this.bot.getNavigation().isDone()) {
			Vec3 to = DefaultRandomPos.getPosAway(this.bot, 16, 7, creeper.position());
			this.away = to != null && to.distanceToSqr(creeper.position()) > this.bot.distanceToSqr(creeper) ? to : null;
			if (this.away != null) {
				this.bot.getNavigation().moveTo(this.away.x, this.away.y, this.away.z, SPEED);
			}
		}
	}
}
