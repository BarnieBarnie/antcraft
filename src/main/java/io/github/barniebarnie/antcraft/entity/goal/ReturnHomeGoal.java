package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.Territory;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Goal;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/**
 * Walks a colony member with nothing to do back to the core when it is outside the
 * colony, for example after chasing a mob. Runs ahead of the worker goal, which stays
 * in charge while it has work.
 */
public class ReturnHomeGoal extends net.minecraft.world.entity.ai.goal.Goal {
	/** How close to the core the bot walks, at most; small colonies need it closer. */
	private static final int ARRIVE_RANGE = 3;
	/** How long to wait before trying again when no path home was found. */
	private static final int RETRY_TICKS = 100;
	/** Gives the worker goal time to find work before the bot counts as idle, e.g. right after a fight. */
	private static final int IDLE_GRACE_TICKS = 40;

	private final AntBot bot;
	private final PathFollower follower;
	private PathFollower.Status status = PathFollower.Status.RUNNING;
	private long retryAt;

	public ReturnHomeGoal(AntBot bot) {
		this.bot = bot;
		this.follower = new PathFollower(bot);
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		if (!(this.bot.level() instanceof ServerLevel level) || level.getGameTime() < this.retryAt || !this.available()) {
			return false;
		}
		return this.colony(level)
			.map(colony -> Territory.of(colony).shouldReturnHome(this.bot.blockPosition(), this.bot.getTarget() != null, this.bot.getIdleTicks() < IDLE_GRACE_TICKS))
			.orElse(false);
	}

	@Override
	public boolean canContinueToUse() {
		return this.available() && !this.bot.isWorking() && this.status == PathFollower.Status.RUNNING;
	}

	/** Not sent anywhere, not fighting, not running a script. */
	private boolean available() {
		return this.bot.getColonyId() != null && this.bot.getWalkTarget() == null && this.bot.getTarget() == null && !this.bot.isRunningScript();
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void start() {
		this.status = PathFollower.Status.RUNNING;
		if (this.bot.level() instanceof ServerLevel level) {
			this.colony(level).ifPresent(colony ->
				this.follower.start(new Goal.Near(colony.core(), Math.min(ARRIVE_RANGE, colony.radius()))));
		}
	}

	@Override
	public void stop() {
		this.follower.stop();
	}

	@Override
	public void tick() {
		this.status = this.follower.tick();
		if (this.status == PathFollower.Status.FAILED) {
			this.retryAt = this.bot.level().getGameTime() + RETRY_TICKS;
		}
	}

	private Optional<Colony> colony(ServerLevel level) {
		UUID id = this.bot.getColonyId();
		return id == null ? Optional.empty() : ColonyManager.get(level).byId(id);
	}
}
