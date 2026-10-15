package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.entity.AntBot;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/** Walks the bot to its walk target, then clears the target. */
public class WalkToTargetGoal extends Goal {
	private static final double ARRIVED_DIST_SQR = 2.0 * 2.0;
	private static final int REPATH_INTERVAL = 20;
	private static final int MAX_FAILED_PATHS = 5;

	private final AntBot bot;
	private final double speed;
	private int repathCooldown;
	private int failedPaths;

	public WalkToTargetGoal(AntBot bot, double speed) {
		this.bot = bot;
		this.speed = speed;
		this.setFlags(EnumSet.of(Goal.Flag.MOVE));
	}

	@Override
	public boolean canUse() {
		BlockPos target = this.bot.getWalkTarget();
		if (target == null) {
			return false;
		}
		if (this.arrived(target)) {
			this.bot.setWalkTarget(null);
			return false;
		}
		return true;
	}

	@Override
	public boolean canContinueToUse() {
		return this.bot.getWalkTarget() != null;
	}

	@Override
	public void start() {
		this.repathCooldown = 0;
		this.failedPaths = 0;
	}

	@Override
	public void stop() {
		this.bot.getNavigation().stop();
	}

	@Override
	public void tick() {
		BlockPos target = this.bot.getWalkTarget();
		if (target == null) {
			return;
		}
		if (this.arrived(target)) {
			this.bot.setWalkTarget(null);
			return;
		}
		if (--this.repathCooldown > 0 && !this.bot.getNavigation().isDone()) {
			return;
		}

		this.repathCooldown = REPATH_INTERVAL;
		boolean pathFound = this.bot.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, this.speed);
		if (pathFound) {
			this.failedPaths = 0;
		} else if (++this.failedPaths >= MAX_FAILED_PATHS) {
			this.bot.setWalkTarget(null);
		}
	}

	private boolean arrived(BlockPos target) {
		return this.bot.distanceToSqr(Vec3.atBottomCenterOf(target)) <= ARRIVED_DIST_SQR;
	}
}
