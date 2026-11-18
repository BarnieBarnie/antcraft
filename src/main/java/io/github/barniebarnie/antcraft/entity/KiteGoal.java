package io.github.barniebarnie.antcraft.entity;

import io.github.barniebarnie.antcraft.profile.TickProfiler;
import java.util.ArrayList;
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
	/** How far, in blocks, a creeper's blast hurts: twice its explosion's power of 3. */
	private static final double BLAST = 6;
	/** How many spots further from the creeper the bot weighs when it backs off: it takes the one that gains it most. */
	private static final int AWAY_TRIES = 10;
	/** In how many directions round the bot it looks for a spot out of every creeper's reach (#868). */
	private static final int RING = 16;
	/** How far, in blocks, it looks in each of them. */
	private static final int RING_REACH = 16;
	/** How many of those spots, nearest first, it plans a path to before it falls back to random ones. */
	private static final int RING_PATHS = 4;
	/** How near the bot, in blocks, a creeper counts when it backs off, lit or not: one about to light is in the way too (#725). */
	private static final double CREEPERS_RANGE = 2 * RANGE;
	/**
	 * How many ticks the bot waits to plan again where a plan found no way off and it is not cornered: a plan, 20 random
	 * spots and a path, costs about 2 ms, and the next can find a spot this one did not (#759).
	 */
	private static final int REPLAN_TICKS = 4;
	/** How fast the bot steps in and backs off, as a factor of its speed: faster than the melee goal's 1.2. */
	private static final double SPEED = 1.5;

	private final AntBot bot;
	private @Nullable Creeper creeper;
	private @Nullable Path away;
	/** Whether the bot, cornered, goes at the creeper to strike it. */
	private boolean cornered;
	/**
	 * Whether another mob kept the bot from stepping in since the goal started: it backs off and does not turn back to
	 * step in when the mob moves on, or it flips between the two every few ticks as mobs come and go (#793).
	 */
	private boolean crowded;
	/** The tick the last plan found no way off, for {@link #REPLAN_TICKS}. */
	private int noWayOffAt = -REPLAN_TICKS;

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
		this.bot.kitePlans++;
		List<Creeper> creepers = this.bot.level().getEntitiesOfClass(Creeper.class, this.bot.getBoundingBox().inflate(CREEPERS_RANGE), Creeper::isAlive);
		double now = nearest(creepers, this.bot.position());
		Path out = this.outOfReach(creepers, now);
		if (out != null) {
			return out;
		}
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
		return path != null && nearest(creepers, Vec3.atBottomCenterOf(path.getEndNode().asBlockPos())) >= now + 1 && !nearer(path, creepers, now) ? path : null;
	}

	/**
	 * The path to the nearest spot out of every creeper's reach, or null where none of the {@link #RING_PATHS} nearest such
	 * spots has one: in each of {@link #RING} directions round the bot, at its level or a block up or down, the nearest spot it can stand on at least
	 * {@link #RANGE} from {@code creepers}, tried nearest first and judged by where its path ends. Straight away gains most
	 * for each block walked, which near a corner of walls backs the bot into it, inside the blast (#868).
	 */
	private @Nullable Path outOfReach(List<Creeper> creepers, double now) {
		Vec3 at = this.bot.position();
		List<BlockPos> spots = new ArrayList<>();
		ray:
		for (int i = 0; i < RING; i++) {
			double angle = 2 * Math.PI * i / RING;
			for (int r = 1; r <= RING_REACH; r++) {
				BlockPos to = BlockPos.containing(at.add(r * Math.cos(angle), 0, r * Math.sin(angle)));
				if (nearest(creepers, Vec3.atBottomCenterOf(to)) >= RANGE + 0.5) {
					// The first spot of the ray it can stand on, at its level or a block up or down.
					for (int dy : new int[] {0, 1, -1}) {
						if (this.bot.getNavigation().isStableDestination(to.above(dy))) {
							spots.add(to.above(dy));
							continue ray;
						}
					}
				}
			}
		}
		spots.sort(Comparator.comparingDouble(to -> to.distToCenterSqr(at)));
		for (BlockPos to : spots.subList(0, Math.min(RING_PATHS, spots.size()))) {
			Path path = this.bot.getNavigation().createPath(to, 0);
			if (path != null && nearest(creepers, Vec3.atBottomCenterOf(path.getEndNode().asBlockPos())) >= RANGE && !nearer(path, creepers, now)) {
				return path;
			}
		}
		return null;
	}

	/**
	 * Whether {@code path} takes the bot more than a block nearer to {@code creepers} than {@code now}, on its way: one that
	 * ends further off can run past the creeper, through its blast (#785).
	 */
	private static boolean nearer(Path path, List<Creeper> creepers, double now) {
		for (int i = 0; i < path.getNodeCount(); i++) {
			if (nearest(creepers, Vec3.atBottomCenterOf(path.getNode(i).asBlockPos())) < now - 1) {
				return true;
			}
		}
		return false;
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
		this.crowded = false;
		this.noWayOffAt = -REPLAN_TICKS;
		this.bot.getNavigation().stop();
	}

	@Override
	public void tick() {
		Creeper creeper = this.creeper;
		if (creeper == null) {
			return;
		}
		this.bot.getLookControl().setLookAt(creeper);
		// The weapon out before it asks whether it is ready, as the reflexes do: not the bow the shoot goal left in hand (#734).
		Tools.equipBestWeapon(this.bot);
		boolean ready = this.bot.tickCount - this.bot.getLastHurtMobTimestamp() >= BotHands.strikeTicks(this.bot);
		if (ready && BotHands.inStrikingReach(this.bot, creeper)) {
			BotHands.strike(this.bot, (ServerLevel) this.bot.level(), creeper);
			this.cornered = false;
			return;
		}
		boolean stepIn = ready && creeper.getSwelling(1) < STRIKE_SWELL && creeper.distanceTo(this.bot) <= STEP_IN_RANGE;
		if (stepIn && !this.crowded) {
			this.crowded = this.crowded(creeper);
		}
		if (stepIn && !this.crowded) {
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
		// With no way off and not cornered, it plans again only every few ticks, not each tick (#759).
		if ((this.away == null || this.bot.getNavigation().isDone()) && this.bot.tickCount - this.noWayOffAt >= REPLAN_TICKS) {
			this.away = this.away(creeper);
			if (this.away != null) {
				this.bot.getNavigation().moveTo(this.away, SPEED);
				return;
			}
			this.noWayOffAt = this.bot.tickCount;
			// Only within the blast's reach, where it is caught anyway: from further off it would walk into the blast, as it
			// did 8 blocks from a creeper lit for another bot (#785). And only near it or with the fuse just started: from the
			// edge of the blast with the fuse far gone it gets within reach only as the creeper explodes, where the blast
			// hurts most, as it killed a bot at full health that set off 5.4 blocks off with the fuse at 0.82 (#793).
			double distance = creeper.distanceTo(this.bot);
			if (ready && distance <= BLAST && (distance <= STEP_IN_RANGE || creeper.getSwelling(1) < STRIKE_SWELL)) {
				this.cornered = true;
				this.bot.getNavigation().stop();
				this.bot.getMoveControl().setWantedPosition(creeper.getX(), creeper.getY(), creeper.getZ(), SPEED);
			}
		}
	}
}
