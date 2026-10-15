package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.work.Mining;
import io.github.barniebarnie.antcraft.work.Quarry;
import io.github.barniebarnie.antcraft.work.Storage;
import io.github.barniebarnie.antcraft.work.ToolKind;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * Miner work loop: make sure it has a pickaxe (from the core, or by ordering one),
 * claim a quarry layer, mine it out, and bring the haul to the core. Now and then it
 * looks for a better pickaxe: it swaps to one in the core, leaving the old one there,
 * or orders the best one the colony can make.
 */
public class MinerGoal extends ColonyWorkGoal {
	/** Scaffold blocks a miner keeps to climb out of its pit. */
	private static final int KEEP_SCAFFOLD = 8;
	private static final int MIN_FREE_SLOTS = 2;
	private enum State { TOOL, WORK, DEPOSIT }

	private final Set<BlockPos> unreachable = new HashSet<>();
	private State state = State.TOOL;

	public MinerGoal(AntBot bot) {
		super(bot, BotRole.MINER);
	}

	@Override
	public void start() {
		this.state = State.TOOL;
	}

	@Override
	protected void work(ServerLevel level, Colony colony) {
		switch (this.state) {
			case TOOL -> this.getTool(level, colony);
			case WORK -> this.mine(level, colony);
			case DEPOSIT -> this.deposit(level, colony);
		}
	}

	private void enter(State next) {
		this.state = next;
		this.resetMovement();
	}

	private void getTool(ServerLevel level, Colony colony) {
		if (this.hasPickaxe()) {
			this.enter(State.WORK);
			return;
		}
		Container core = Storage.containerAt(level, colony.core());
		Map<Item, Integer> stock = core == null ? Map.of() : Storage.stock(core);
		Optional<Item> best = ToolKind.PICKAXE.betterIn(stock, null);
		int slot = core == null ? -1 : findSlot(core, stack -> best.isPresent() ? stack.is(best.get()) : stack.is(ItemTags.PICKAXES));
		if (slot < 0) {
			ToolKind.PICKAXE.order(level, colony, null, stock);
			this.cooldown = IDLE_TICKS;
			return;
		}
		if (this.reach(colony.core())) {
			this.bot.getInventory().addItem(core.removeItem(slot, 1));
			this.enter(State.WORK);
		}
	}

	private void mine(ServerLevel level, Colony colony) {
		if (!this.hasPickaxe()) {
			this.enter(State.TOOL);
			return;
		}
		if (this.upgradeTool(level, colony, ToolKind.PICKAXE)) {
			return;
		}
		if (freeSlots(this.bot.getInventory()) < MIN_FREE_SLOTS) {
			this.enter(State.DEPOSIT);
			return;
		}
		Optional<Quarry> quarry = colony.quarry();
		Optional<Job> job = quarry.isEmpty() ? Optional.empty()
			: colony.board().claim(this.bot.getUUID(), Set.of(JobType.MINE_LAYER), this.bot.blockPosition(), level.getGameTime());
		this.hasJob = job.isPresent();
		if (job.isEmpty()) {
			if (this.hasItemsToStore()) {
				this.enter(State.DEPOSIT);
			} else {
				this.cooldown = IDLE_TICKS;
			}
			return;
		}

		List<BlockPos> blocks = quarry.get().blocksInLayer(job.get().target().getY(),
			pos -> Mining.isMinable(level, pos) && !this.unreachable.contains(pos), this.bot.blockPosition());
		if (blocks.isEmpty()) {
			colony.board().complete(job.get().id());
			this.hasJob = false;
			this.unreachable.clear();
			return;
		}
		BlockPos next = blocks.getFirst();
		Goal reach = new Goal.Reach(next, REACH);
		if (reach.isReached(this.bot.blockPosition()) && this.bot.onGround()) {
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
		if (this.reach(colony.core())) {
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
			if (stack.is(PathFollower.SCAFFOLD_BLOCKS)) {
				keep = Math.min(stack.getCount(), KEEP_SCAFFOLD - keptScaffold);
				keptScaffold += keep;
			}
			ItemStack rest = HopperBlockEntity.addItem(null, core, stack.split(stack.getCount() - keep), null);
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
			if (stack.is(PathFollower.SCAFFOLD_BLOCKS)) {
				scaffold += stack.getCount();
			} else {
				return true;
			}
		}
		return scaffold > KEEP_SCAFFOLD;
	}

	private boolean hasPickaxe() {
		return this.bot.getMainHandItem().is(ItemTags.PICKAXES) || findSlot(this.bot.getInventory(), stack -> stack.is(ItemTags.PICKAXES)) >= 0;
	}
}
