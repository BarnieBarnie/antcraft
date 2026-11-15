package io.github.barniebarnie.antcraft.entity;

import java.util.Comparator;
import java.util.EnumSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Fights creepers by kiting (docs/features/bots.md, Fighting creepers): while a creeper near the bot has its fuse lit,
 * the bot steps in to strike it only if its weapon is ready, the fuse has just started and no other mob is in the way,
 * and otherwise backs off, then waits until the fuse goes out, which a creeper's does only with its target more than 7
 * blocks away (SwellGoal). With every fuse out,
 * the melee goal fights on as against any mob, and {@code attack} waits while this goal backs off.
 */
public final class KiteGoal extends Goal {
	/** How near, in blocks, a lit creeper makes the bot kite: a little beyond the 7 blocks at which a fuse goes out. */
	static final double RANGE = 8;
	/** How near, in blocks, the creeper's target must stay for its fuse to burn on (SwellGoal). */
	private static final double FUSE_RANGE = 7;
	/** How far the fuse may have burnt, as {@link Creeper#getSwelling} gives it, for the bot still to step in and strike. */
	private static final float STRIKE_SWELL = 0.35F;
	/**
	 * How near the bot, in blocks, another hostile mob keeps it from stepping in: the mob stands in its way to the creeper
	 * and back, and the bot loses the fuse's time on it (#717).
	 */
	private static final double CROWD = 3;
	/**
	 * How near, in blocks, the creeper must be for the bot to step in: where its fuse lights. From further off, the bot would
	 * spend the fuse walking there and back (#717).
	 */
	private static final double STEP_IN_RANGE = 3;
	/** How many spots further from the creeper the bot weighs when it backs off: it takes the one that gains it most. */
	private static final int AWAY_TRIES = 10;
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

	/**
	 * Of {@link #AWAY_TRIES} spots that vanilla's AvoidEntityGoal would pick, any of them up to a right angle off straight
	 * away from {@code creeper}, the one that takes the bot furthest from it for each block walked, or null where none
	 * does. One off to the side gains too little before the fuse burns out (#717).
	 */
	private @Nullable Vec3 away(Creeper creeper) {
		Vec3 best = null;
		double bestGain = 0;
		for (int i = 0; i < AWAY_TRIES; i++) {
			Vec3 to = DefaultRandomPos.getPosAway(this.bot, 16, 7, creeper.position());
			double walk = to == null ? 0 : to.distanceTo(this.bot.position());
			if (walk > 0) {
				double gain = (to.distanceTo(creeper.position()) - this.bot.distanceTo(creeper)) / walk;
				if (gain > bestGain) {
					best = to;
					bestGain = gain;
				}
			}
		}
		return best;
	}

	/** Whether a hostile mob other than {@code creeper} is within {@link #CROWD} of the bot. */
	private boolean crowded(Creeper creeper) {
		return !this.bot.level().getEntitiesOfClass(Mob.class, this.bot.getBoundingBox().inflate(CROWD),
			mob -> mob != creeper && mob instanceof Enemy && mob.isAlive() && mob.distanceTo(this.bot) <= CROWD).isEmpty();
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
		if (ready && creeper.getSwelling(1) < STRIKE_SWELL && creeper.distanceTo(this.bot) <= STEP_IN_RANGE && !this.crowded(creeper)) {
			this.away = null;
			// Straight at it, so near: a path ends a block short of the creeper's, which can be out of reach (#717).
			this.bot.getNavigation().stop();
			this.bot.getMoveControl().setWantedPosition(creeper.getX(), creeper.getY(), creeper.getZ(), SPEED);
			return;
		}
		// Out of the fuse's reach, which goes out: waits there rather than walk on, off to the edge of the fight (#717).
		if (creeper.getSwellDir() < 0 && creeper.distanceTo(this.bot) > FUSE_RANGE) {
			this.away = null;
			this.bot.getNavigation().stop();
			return;
		}
		// Somewhere further from the creeper than the bot is; planned again once there.
		if (this.away == null || this.bot.getNavigation().isDone()) {
			this.away = this.away(creeper);
			if (this.away != null) {
				this.bot.getNavigation().moveTo(this.away.x, this.away.y, this.away.z, SPEED);
			}
		}
	}
}
