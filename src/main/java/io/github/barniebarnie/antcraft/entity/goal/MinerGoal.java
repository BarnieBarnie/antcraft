package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.entity.navigation.BlockBreaker;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.work.Mining;
import io.github.barniebarnie.antcraft.work.Quarry;
import io.github.barniebarnie.antcraft.work.Storage;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * Miner work loop: make sure it has a pickaxe (from the core, or by ordering one),
 * claim a quarry layer, mine it out, and bring the haul to the core.
 */
public class MinerGoal extends net.minecraft.world.entity.ai.goal.Goal {
	private static final double REACH = 4.0;
	/** Scaffold blocks a miner keeps to climb out of its pit. */
	private static final int KEEP_SCAFFOLD = 8;
	private static final int MIN_FREE_SLOTS = 2;
	private static final int IDLE_TICKS = 20;

	private enum State { TOOL, WORK, DEPOSIT }

	private final AntBot bot;
	private final PathFollower follower;
	private final BlockBreaker breaker;
	private final Set<BlockPos> unreachable = new HashSet<>();
	private State state = State.TOOL;
	private @Nullable Goal travelGoal;
	private @Nullable UUID jobId;
	private int cooldown;

	public MinerGoal(AntBot bot) {
		this.bot = bot;
		this.follower = new PathFollower(bot);
		this.breaker = new BlockBreaker(bot);
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		return this.bot.getRole() == BotRole.MINER && this.bot.getWalkTarget() == null && this.bot.getColonyId() != null;
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void start() {
		this.state = State.TOOL;
	}

	@Override
	public void stop() {
		this.follower.stop();
		this.breaker.reset();
		this.travelGoal = null;
	}

	@Override
	public void tick() {
		if (!(this.bot.level() instanceof ServerLevel level)) {
			return;
		}
		Optional<Colony> colony = Optional.ofNullable(this.bot.getColonyId()).flatMap(id -> ColonyManager.get(level).byId(id));
		if (colony.isEmpty()) {
			return;
		}
		if (this.jobId != null && level.getGameTime() % 20 == 0) {
			colony.get().board().heartbeat(this.bot.getUUID(), level.getGameTime());
		}
		if (this.cooldown > 0) {
			this.cooldown--;
			return;
		}
		switch (this.state) {
			case TOOL -> this.getTool(level, colony.get());
			case WORK -> this.work(level, colony.get());
			case DEPOSIT -> this.deposit(level, colony.get());
		}
	}

	private void enter(State next) {
		this.state = next;
		this.travelGoal = null;
		this.follower.stop();
		this.breaker.reset();
	}

	private void getTool(ServerLevel level, Colony colony) {
		if (this.hasPickaxe()) {
			this.enter(State.WORK);
			return;
		}
		Container core = Storage.containerAt(level, colony.core());
		int slot = core == null ? -1 : findSlot(core, stack -> stack.is(ItemTags.PICKAXES));
		if (slot < 0) {
			if (!colony.orders().containsKey(Items.WOODEN_PICKAXE)) {
				colony.addOrder(Items.WOODEN_PICKAXE, 1);
			}
			this.cooldown = IDLE_TICKS;
			return;
		}
		if (this.atCore(colony)) {
			this.bot.getInventory().addItem(core.removeItem(slot, 1));
			this.enter(State.WORK);
		}
	}

	private void work(ServerLevel level, Colony colony) {
		if (!this.hasPickaxe()) {
			this.enter(State.TOOL);
			return;
		}
		if (freeSlots(this.bot.getInventory()) < MIN_FREE_SLOTS) {
			this.enter(State.DEPOSIT);
			return;
		}
		Optional<Quarry> quarry = colony.quarry();
		Optional<Job> job = quarry.isEmpty() ? Optional.empty()
			: colony.board().claim(this.bot.getUUID(), Set.of(JobType.MINE_LAYER), this.bot.blockPosition(), level.getGameTime());
		if (job.isEmpty()) {
			this.jobId = null;
			if (this.hasItemsToStore()) {
				this.enter(State.DEPOSIT);
			} else {
				this.cooldown = IDLE_TICKS;
			}
			return;
		}
		this.jobId = job.get().id();

		List<BlockPos> blocks = quarry.get().blocksInLayer(job.get().target().getY(),
			pos -> Mining.isMinable(level, pos) && !this.unreachable.contains(pos), this.bot.blockPosition());
		if (blocks.isEmpty()) {
			colony.board().complete(job.get().id());
			this.jobId = null;
			this.unreachable.clear();
			return;
		}
		BlockPos next = blocks.getFirst();
		Goal reach = new Goal.Reach(next, REACH);
		if (reach.isReached(this.bot.blockPosition()) && this.bot.onGround()) {
			this.travelGoal = null;
			this.breaker.tick(level, next, true);
		} else if (this.travel(reach) == PathFollower.Status.FAILED) {
			this.unreachable.add(next);
		}
	}

	private void deposit(ServerLevel level, Colony colony) {
		Container core = Storage.containerAt(level, colony.core());
		if (core == null) {
			this.enter(State.WORK);
			this.cooldown = IDLE_TICKS;
			return;
		}
		if (this.atCore(colony)) {
			this.storeHaul(core);
			this.enter(State.WORK);
		}
	}

	/** Puts everything in the core except pickaxes and a few scaffold blocks. */
	private void storeHaul(Container core) {
		SimpleContainer inventory = this.bot.getInventory();
		int keptScaffold = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty() || stack.is(ItemTags.PICKAXES)) {
				continue;
			}
			int keep = 0;
			if (stack.is(io.github.barniebarnie.antcraft.entity.navigation.PathFollower.SCAFFOLD_BLOCKS)) {
				keep = Math.min(stack.getCount(), KEEP_SCAFFOLD - keptScaffold);
				keptScaffold += keep;
			}
			ItemStack toStore = stack.split(stack.getCount() - keep);
			ItemStack rest = HopperBlockEntity.addItem(null, core, toStore, null);
			stack.grow(rest.getCount());
		}
		inventory.setChanged();
		core.setChanged();
	}

	private boolean hasItemsToStore() {
		SimpleContainer inventory = this.bot.getInventory();
		int scaffold = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty() || stack.is(ItemTags.PICKAXES)) {
				continue;
			}
			if (stack.is(io.github.barniebarnie.antcraft.entity.navigation.PathFollower.SCAFFOLD_BLOCKS)) {
				scaffold += stack.getCount();
			} else {
				return true;
			}
		}
		return scaffold > KEEP_SCAFFOLD;
	}

	private boolean atCore(Colony colony) {
		BlockPos core = colony.core();
		Goal reach = new Goal.Reach(core, REACH);
		if (reach.isReached(this.bot.blockPosition())) {
			this.travelGoal = null;
			this.bot.getLookControl().setLookAt(core.getX() + 0.5, core.getY() + 0.5, core.getZ() + 0.5);
			return true;
		}
		if (this.travel(reach) == PathFollower.Status.FAILED) {
			this.cooldown = IDLE_TICKS;
		}
		return false;
	}

	private PathFollower.Status travel(Goal goal) {
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

	private boolean hasPickaxe() {
		return this.bot.getMainHandItem().is(ItemTags.PICKAXES) || findSlot(this.bot.getInventory(), stack -> stack.is(ItemTags.PICKAXES)) >= 0;
	}

	private static int findSlot(Container container, java.util.function.Predicate<ItemStack> filter) {
		for (int i = 0; i < container.getContainerSize(); i++) {
			if (!container.getItem(i).isEmpty() && filter.test(container.getItem(i))) {
				return i;
			}
		}
		return -1;
	}

	private static int freeSlots(SimpleContainer inventory) {
		int free = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (inventory.getItem(i).isEmpty()) {
				free++;
			}
		}
		return free;
	}
}
