package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.entity.navigation.BlockBreaker;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Goal;
import java.util.EnumSet;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Shared plumbing for colony roles: travelling, reaching blocks, the colony and its
 * job heartbeat, and a cooldown between idle checks.
 */
public abstract class ColonyWorkGoal extends net.minecraft.world.entity.ai.goal.Goal {
	protected static final double REACH = 4.0;
	protected static final int IDLE_TICKS = 20;

	protected final AntBot bot;
	protected final PathFollower follower;
	protected final BlockBreaker breaker;
	private final BotRole role;
	private @Nullable Goal travelGoal;
	protected boolean hasJob;
	protected int cooldown;

	protected ColonyWorkGoal(AntBot bot, BotRole role) {
		this.bot = bot;
		this.role = role;
		this.follower = new PathFollower(bot);
		this.breaker = new BlockBreaker(bot);
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		return this.bot.getRole() == this.role && this.bot.getWalkTarget() == null && this.bot.getColonyId() != null;
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void stop() {
		this.resetMovement();
	}

	@Override
	public final void tick() {
		if (!(this.bot.level() instanceof ServerLevel level)) {
			return;
		}
		Optional<Colony> colony = Optional.ofNullable(this.bot.getColonyId()).flatMap(id -> ColonyManager.get(level).byId(id));
		if (colony.isEmpty()) {
			return;
		}
		if (this.hasJob && level.getGameTime() % 20 == 0) {
			colony.get().board().heartbeat(this.bot.getUUID(), level.getGameTime());
		}
		if (this.cooldown > 0) {
			this.cooldown--;
			return;
		}
		this.work(level, colony.get());
	}

	protected abstract void work(ServerLevel level, Colony colony);

	protected void resetMovement() {
		this.follower.stop();
		this.breaker.reset();
		this.travelGoal = null;
	}

	protected PathFollower.Status travel(Goal goal) {
		if (!goal.equals(this.travelGoal)) {
			this.travelGoal = goal;
			this.follower.start(goal);
		}
		PathFollower.Status status = this.follower.tick();
		if (status != PathFollower.Status.RUNNING) {
			this.travelGoal = null;
		}
		return status;
	}

	/**
	 * Moves toward {@code pos} until it is within reach. Returns true once there.
	 * If no path can be found, waits a while before trying again.
	 */
	protected boolean reach(BlockPos pos) {
		Goal reach = new Goal.Reach(pos, REACH);
		if (reach.isReached(this.bot.blockPosition()) && this.bot.onGround()) {
			this.travelGoal = null;
			this.bot.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
			return true;
		}
		if (this.travel(reach) == PathFollower.Status.FAILED) {
			this.cooldown = IDLE_TICKS;
		}
		return false;
	}

	protected static int findSlot(Container container, Predicate<ItemStack> filter) {
		for (int i = 0; i < container.getContainerSize(); i++) {
			if (!container.getItem(i).isEmpty() && filter.test(container.getItem(i))) {
				return i;
			}
		}
		return -1;
	}

	protected static int freeSlots(SimpleContainer inventory) {
		int free = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (inventory.getItem(i).isEmpty()) {
				free++;
			}
		}
		return free;
	}
}
