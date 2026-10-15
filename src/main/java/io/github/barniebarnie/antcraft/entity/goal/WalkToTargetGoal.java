package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Goal;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;

/** Takes the bot to its walk target, digging, bridging and pillaring as needed, then clears the target. */
public class WalkToTargetGoal extends net.minecraft.world.entity.ai.goal.Goal {
	private final AntBot bot;
	private final PathFollower follower;
	private BlockPos activeTarget;

	public WalkToTargetGoal(AntBot bot) {
		this.bot = bot;
		this.follower = new PathFollower(bot);
		this.setFlags(EnumSet.of(Flag.MOVE));
	}

	@Override
	public boolean canUse() {
		return this.bot.getWalkTarget() != null;
	}

	@Override
	public boolean canContinueToUse() {
		return this.bot.getWalkTarget() != null;
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void start() {
		this.activeTarget = null;
	}

	@Override
	public void stop() {
		this.follower.stop();
		this.activeTarget = null;
	}

	@Override
	public void tick() {
		BlockPos target = this.bot.getWalkTarget();
		if (target == null) {
			return;
		}
		if (!target.equals(this.activeTarget)) {
			this.activeTarget = target;
			this.follower.start(new Goal.Near(target, 1));
		}
		if (this.follower.tick() != PathFollower.Status.RUNNING) {
			this.bot.setWalkTarget(null);
		}
	}
}
