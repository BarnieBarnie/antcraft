package io.github.barniebarnie.antcraft.entity;

import io.github.barniebarnie.antcraft.profile.TickProfiler;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.pathfinder.Path;
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
	/** How near the bot, in blocks, a creeper counts when it backs off, lit or not: one about to light is in the way too (#725). */
	private static final double CREEPERS_RANGE = 2 * RANGE;
	/** How fast the bot steps in and backs off, as a factor of its speed: faster than the melee goal's 1.2. */
	private static final double SPEED = 1.5;

	private final AntBot bot;
	private @Nullable Creeper creeper;
	private @Nullable Path away;
	/** Whether the bot, cornered, goes at the creeper to strike it. */
	private boolean cornered;

	KiteGoal(AntBot bot) {
		this.bot = bot;
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	/**
	 * The nearest creeper within {@link #RANGE} of {@code bot} whose fuse burns or has yet to go out, or null: the bot
	 * keeps clear of its blast meanwhile. Whichever mob it lit for, as a bot's sword sweep lights one beside its target.
	 * ponytail: an entity search a tick for each bot, and again in its reflexes; the profiler's {@code bot.kite} part
	 * measures it: in every scenario of the 30-bot benchmark at most 0.01 ms a tick in all, 15 calls a tick (the goal
	 * selector checks every other tick), under 1% of the tick, so no cheaper check is needed (#732).
	 */
	public static @Nullable Creeper litNear(AntBot bot) {
		return TickProfiler.time("bot.kite", () -> bot.level().getEntitiesOfClass(Creeper.class, bot.getBoundingBox().inflate(RANGE),
				creeper -> creeper.isAlive() && lit(creeper) && creeper.distanceTo(bot) <= RANGE)
			.stream().min(Comparator.comparingDouble(creeper -> creeper.distanceToSqr(bot))).orElse(null));
	}

	/** Whether {@code creeper}'s fuse burns or has yet to go out. */
	public static boolean lit(Creeper creeper) {
		return creeper.getSwellDir() > 0 || creeper.getSwelling(1) > 0;
	}

	/**
	 * Of {@link #AWAY_TRIES} spots that vanilla's AvoidEntityGoal would pick, any of them up to a right angle off straight
	 * away from {@code creeper}, and as many anywhere around the bot, the one that takes the bot furthest from the nearest
	 * creeper within {@link #CREEPERS_RANGE} for each block walked, and the path there, or null where none does or the path
	 * ends less than a block further from the creepers than the bot stands. One off to the side gains too
	 * little before the fuse burns out (#717); straight away from one creeper can run into another, or into a corner, where
	 * only a spot to the side gets out (#725).
	 */
	private @Nullable Path away(Creeper creeper) {
		List<Creeper> creepers = this.bot.level().getEntitiesOfClass(Creeper.class, this.bot.getBoundingBox().inflate(CREEPERS_RANGE), Creeper::isAlive);
		double now = nearest(creepers, this.bot.position());
		Vec3 best = null;
		double bestGain = 0;
		for (int i = 0; i < 2 * AWAY_TRIES; i++) {
			Vec3 to = i < AWAY_TRIES ? DefaultRandomPos.getPosAway(this.bot, 16, 7, creeper.position()) : DefaultRandomPos.getPos(this.bot, 16, 7);
			double walk = to == null ? 0 : to.distanceTo(this.bot.position());
			if (walk > 0) {
				double gain = (nearest(creepers, to) - now) / walk;
				if (gain > bestGain) {
					best = to;
					bestGain = gain;
				}
			}
		}
		if (best == null) {
			return null;
		}
		// The spot's straight-line gain can lie behind a wall, as all do in a dead end: only a path whose end gains a block
		// or more counts, as one ending on the bot's own block gains nothing (#733).
		Path path = this.bot.getNavigation().createPath(BlockPos.containing(best), 0);
		return path != null && nearest(creepers, Vec3.atBottomCenterOf(path.getEndNode().asBlockPos())) >= now + 1 ? path : null;
	}

	/** How far {@code pos} is from the nearest of {@code creepers}. */
	private static double nearest(List<Creeper> creepers, Vec3 pos) {
		return creepers.stream().mapToDouble(creeper -> creeper.position().distanceTo(pos)).min().orElse(Double.MAX_VALUE);
	}

	/** Whether a hostile mob other than {@code creeper} is within {@link #CROWD} of the bot. */
	private boolean crowded(Creeper creeper) {
		return !this.bot.level().getEntitiesOfClass(Mob.class, this.bot.getBoundingBox().inflate(CROWD),
			mob -> mob != creeper && mob instanceof Enemy && mob.isAlive() && mob.distanceTo(this.bot) <= CROWD).isEmpty();
	}

	@Override
	public boolean canUse() {
		// Yields to every reflex but fleeing, which yields to it (#725).
		this.creeper = this.bot.reflexes().letKite() ? litNear(this.bot) : null;
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
		this.cornered = false;
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
			this.cornered = false;
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
		// Cornered, no spot gained: at it until it strikes, which knocks it back, rather than stand there (#725). It does not
		// plan again on the way, where the dead end behind it gains again and would turn it back (#733).
		if (this.cornered) {
			this.bot.getMoveControl().setWantedPosition(creeper.getX(), creeper.getY(), creeper.getZ(), SPEED);
			return;
		}
		// Somewhere further from the creeper than the bot is; planned again once there.
		if (this.away == null || this.bot.getNavigation().isDone()) {
			this.away = this.away(creeper);
			if (this.away != null) {
				this.bot.getNavigation().moveTo(this.away, SPEED);
			} else if (ready) {
				// ponytail: plans again each tick while cornered and not ready, 20 spots and a path a tick.
				this.cornered = true;
				this.bot.getNavigation().stop();
				this.bot.getMoveControl().setWantedPosition(creeper.getX(), creeper.getY(), creeper.getZ(), SPEED);
			}
		}
	}
}
