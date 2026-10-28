package io.github.barniebarnie.antcraft.entity;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Escape;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.pathing.LevelWorldView;
import io.github.barniebarnie.antcraft.pathing.WorldView;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A bot's reflexes (docs/scripting.md, Reflexes): each tick, before the script, they keep the bot out of fire and lava,
 * from under a falling block, from drowning and, at low health, away from hostile mobs, whatever its script does. While
 * one holds the bot, the script's current action ends with {@code interrupted} and the script waits (see
 * {@link io.github.barniebarnie.antcraft.script.ScriptRun}). A reflex takes over only where it has a way out.
 */
final class Reflexes {
	/** The reflexes, the nearest danger first: the one first in this order that applies takes over. */
	enum Kind {
		/** In fire or lava, or next to flowing lava: step aside. */
		HAZARD,
		/** A falling block (sand, gravel) above: step aside. */
		FALLING_BLOCK,
		/** Under water and short of air: swim up to air. */
		SURFACE,
		/** Low on health with a hostile mob near: flee home, or away from the mob. */
		FLEE
	}

	/** What the reflexes sense of the bot and around it. */
	record Senses(boolean inHazard, boolean blockFalling, boolean underWater, int air, float health, boolean hostileNear) {
	}

	/** How near, in blocks, a hostile mob makes a bot low on health flee. */
	static final double HOSTILE_RANGE = 8;
	/** How far above the bot, in blocks, a falling block makes it step aside. */
	private static final int FALL_RANGE = 16;
	/** How fast the bot steps aside, as the path follower walks. */
	private static final double STEP_SPEED = 1.0;
	/** How far from a mob a bot that does not flee home runs: a little beyond {@link #HOSTILE_RANGE}. */
	private static final double FLEE_RANGE = HOSTILE_RANGE + 2;
	/**
	 * How many nodes a flight's path may take to plan: a short search, so the bot starts running soon, along a partial
	 * path toward the goal if need be.
	 */
	private static final int FLEE_NODES = 1000;
	/** How many ticks a bot that found no way to flee waits before it plans again, rather than plan every tick. */
	static final int FLEE_RETRY_TICKS = 20;
	/** How far, in blocks, the mob may move from where it stood when the flight was planned before the bot plans again. */
	private static final double MOB_MOVED = 4;

	private final AntBot bot;
	private @Nullable Kind active;
	/** Where the bot steps or swims to, for every reflex but fleeing. */
	private @Nullable BlockPos target;
	/**
	 * Carries the bot away from a mob with its own pathfinder, as a script's {@code goto} does: digging, pillaring and
	 * bridging where it must. Not the script's, which the reflex interrupts.
	 */
	private final PathFollower flight;
	/** Whether {@link #flight} has a goal and is on its way. */
	private boolean fleeing;
	/** Where the mob stood when {@link #flight} was planned. */
	private @Nullable Vec3 fledFrom;
	/** The bot's tick count when its last flight failed. */
	private int flightFailedAt = -FLEE_RETRY_TICKS;
	/** How many flights the bot planned, for the game tests. */
	private int flightPlans;

	Reflexes(AntBot bot) {
		this.bot = bot;
		this.flight = new PathFollower(bot);
		this.flight.setMaxNodes(FLEE_NODES);
	}

	/**
	 * The reflex that takes over, or null for none: at or below {@code fleeHealth} with a hostile mob near the bot flees,
	 * and below {@code surfaceAir} under water it swims up; a threshold of 0 turns its reflex off.
	 */
	static @Nullable Kind choose(Senses senses, int fleeHealth, int surfaceAir) {
		if (senses.inHazard()) {
			return Kind.HAZARD;
		}
		if (senses.blockFalling()) {
			return Kind.FALLING_BLOCK;
		}
		if (senses.underWater() && senses.air() < surfaceAir) {
			return Kind.SURFACE;
		}
		if (senses.hostileNear() && senses.health() <= fleeHealth) {
			return Kind.FLEE;
		}
		return null;
	}

	/**
	 * Whether a bot fleeing a mob runs home: when home lies further from the mob than the bot does, and the bot is not
	 * there yet. Otherwise it runs away from the mob.
	 */
	static boolean fleesHome(double botToMob, double homeToMob, double botToHome) {
		return homeToMob > botToMob && botToHome > 1.5;
	}

	/** Whether a bot whose last flight failed {@code ticksSinceFailed} ago may plan a new one. */
	static boolean mayPlanFlight(int ticksSinceFailed) {
		return ticksSinceFailed >= FLEE_RETRY_TICKS;
	}

	/** Senses and decides, before the script runs this tick; whether a reflex holds the bot. */
	boolean tick(ServerLevel level) {
		int fleeHealth = AntCraftConfig.botFleeHealth();
		// Only a bot low on health flees, so only it looks for hostile mobs, the costlier of the two entity searches (#336).
		@Nullable Mob hostile = this.bot.getHealth() <= fleeHealth ? this.nearestHostile(level) : null;
		Senses senses = new Senses(this.inHazard(level), !this.fallingAbove(level, this.bot.getBoundingBox()).isEmpty(), this.bot.isUnderWater(),
			this.bot.getAirSupply(), this.bot.getHealth(), hostile != null);
		Kind kind = choose(senses, fleeHealth, AntCraftConfig.botSurfaceAir());
		Kind was = this.active;
		boolean holds = kind != null && this.plan(level, kind, kind == was, hostile);
		this.active = holds ? kind : null;
		if (was == Kind.FLEE && this.active != Kind.FLEE) {
			this.fleeing = false;
			this.flight.stop();
		}
		if (this.active == Kind.FLEE && was != Kind.FLEE) {
			this.bot.setTarget(null);
		}
		return holds;
	}

	/** How many flights from a hostile mob the bot planned so far. */
	int flightPlans() {
		return this.flightPlans;
	}

	/** Whether a reflex holds the bot. */
	boolean active() {
		return this.active != null;
	}

	/** Whether the bot flees a hostile mob: it does not take on a target while it does. */
	boolean fleeing() {
		return this.active == Kind.FLEE;
	}

	/** Plans where {@code kind} takes the bot; false when there is no way out. */
	private boolean plan(ServerLevel level, Kind kind, boolean same, @Nullable Mob hostile) {
		BlockPos feet = this.bot.blockPosition();
		WorldView world = new LevelWorldView(level, this.bot.getInventory(), this.bot.getMainHandItem(), Set.of());
		switch (kind) {
			case HAZARD -> {
				return this.stepAside(world, feet, same, pos -> nextToLava(level, pos));
			}
			case FALLING_BLOCK -> {
				return this.stepAside(world, feet, same, pos -> !this.fallingAbove(level, new AABB(pos)).isEmpty());
			}
			case SURFACE -> {
				List<BlockPos> way = Escape.wayToAir(world, feet);
				this.target = way.isEmpty() ? null : way.getFirst();
				return this.target != null;
			}
			case FLEE -> {
				// On its way, unless the mob has moved since the bot planned: then plan again, away from where the mob is now.
				if (same && this.fleeing && hostile != null && this.fledFrom != null && hostile.position().distanceTo(this.fledFrom) <= MOB_MOVED) {
					PathFollower.Status status = this.flight.tick();
					if (status == PathFollower.Status.RUNNING) {
						return true;
					}
					if (status == PathFollower.Status.FAILED) {
						return this.flightFailed();
					}
				}
				// Arrived with the mob still near, or the mob moved: plan again from here, but not every tick while there is no way.
				Goal goal = this.fleeGoal(hostile);
				if (goal == null || hostile == null || !mayPlanFlight(this.bot.tickCount - this.flightFailedAt)) {
					return this.fleeing = false;
				}
				this.flight.start(goal);
				this.flightPlans++;
				this.fledFrom = hostile.position();
				PathFollower.Status status = this.flight.tick();
				return status == PathFollower.Status.FAILED ? this.flightFailed() : (this.fleeing = status == PathFollower.Status.RUNNING);
			}
		}
		return false;
	}

	private boolean flightFailed() {
		this.flightFailedAt = this.bot.tickCount;
		return this.fleeing = false;
	}

	/** Keeps the block it steps to while it is still safe, so the bot does not turn about half way. */
	private boolean stepAside(WorldView world, BlockPos feet, boolean same, Predicate<BlockPos> avoid) {
		if (same && this.target != null && !this.target.equals(feet) && !avoid.test(this.target)) {
			return true;
		}
		Optional<BlockPos> to = Escape.stepAside(world, feet, avoid);
		this.target = to.orElse(null);
		return this.target != null;
	}

	private @Nullable Goal fleeGoal(@Nullable Mob hostile) {
		if (hostile == null) {
			return null;
		}
		Vec3 home = Vec3.atBottomCenterOf(this.bot.getScriptHome());
		Vec3 mob = hostile.position();
		// Home's coordinates in another dimension lead nowhere safe.
		if (this.bot.level().dimension() == this.bot.getHomeDimension() && fleesHome(this.bot.position().distanceTo(mob), home.distanceTo(mob), this.bot.position().distanceTo(home))) {
			return new Goal.Near(this.bot.getScriptHome(), 1);
		}
		return new Goal.Away(hostile.blockPosition(), FLEE_RANGE);
	}

	/**
	 * Moves the bot where its reflex takes it. Called after the goals and the navigation have set where the bot goes
	 * this tick, which this overrides; a fleeing bot runs with its path follower, which {@link #tick} ticked.
	 */
	void move() {
		if (this.active == null || this.active == Kind.FLEE || this.target == null) {
			return;
		}
		BlockPos feet = this.bot.blockPosition();
		Vec3 to = Vec3.atBottomCenterOf(this.target);
		if (this.active == Kind.SURFACE && this.bot.isInWater()) {
			// A mob's swim stroke is weak and random (FloatGoal): pull it along to the next block of the way.
			Vec3 v = this.bot.getDeltaMovement();
			double dx = to.x - this.bot.getX();
			double dz = to.z - this.bot.getZ();
			double dy = this.target.getY() > feet.getY() ? Math.max(v.y, 0.12) : v.y;
			this.bot.setDeltaMovement(Mth.clamp(dx * 0.5, -0.15, 0.15), dy, Mth.clamp(dz * 0.5, -0.15, 0.15));
			return;
		}
		this.bot.getMoveControl().setWantedPosition(to.x, to.y, to.z, STEP_SPEED);
		if (this.target.getY() > feet.getY()) {
			this.bot.getJumpControl().jump();
		}
	}

	/** In fire or lava, or with flowing lava beside it. */
	private boolean inHazard(ServerLevel level) {
		BlockPos feet = this.bot.blockPosition();
		return this.bot.isInLava() || burns(level.getBlockState(feet)) || burns(level.getBlockState(feet.above())) || nextToLava(level, feet);
	}

	private static boolean burns(BlockState state) {
		return state.is(BlockTags.FIRE) || state.getFluidState().is(FluidTags.LAVA);
	}

	/**
	 * Flowing lava beside the two blocks a bot standing at {@code feet} fills, which may flow onto it. Not a still pool's
	 * edge, which paths may pass.
	 */
	private static boolean nextToLava(ServerLevel level, BlockPos feet) {
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			if (flowingLava(level, feet.relative(dir)) || flowingLava(level, feet.above().relative(dir))) {
				return true;
			}
		}
		return false;
	}

	private static boolean flowingLava(ServerLevel level, BlockPos pos) {
		FluidState fluid = level.getFluidState(pos);
		return fluid.is(FluidTags.LAVA) && !fluid.isSource();
	}

	/** The falling blocks over {@code box}: those whose column overlaps it, from its bottom up to {@link #FALL_RANGE} above it. */
	private List<FallingBlockEntity> fallingAbove(ServerLevel level, AABB box) {
		AABB column = box.expandTowards(0, FALL_RANGE, 0);
		return level.getEntitiesOfClass(FallingBlockEntity.class, column, falling -> falling.isAlive() && falling.getY() >= box.minY);
	}

	/** The nearest hostile mob within {@link #HOSTILE_RANGE}, or null. */
	private @Nullable Mob nearestHostile(ServerLevel level) {
		return level.getEntitiesOfClass(Mob.class, this.bot.getBoundingBox().inflate(HOSTILE_RANGE),
				mob -> mob instanceof Enemy && mob.isAlive() && mob.distanceTo(this.bot) <= HOSTILE_RANGE)
			.stream().min(Comparator.comparingDouble(mob -> mob.distanceToSqr(this.bot))).orElse(null);
	}
}
