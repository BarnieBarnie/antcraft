package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.work.Mining;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.Storage;
import io.github.barniebarnie.antcraft.work.ToolKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * Miner work loop: make sure it has a pickaxe (from the core, or by ordering one),
 * claim a mineshaft branch, dig it out with the ores in its walls, light it with torches from
 * the core as it goes, and bring the haul to the core. Now and then it
 * looks for a better pickaxe: it swaps to one in the core, leaving the old one there,
 * or orders the best one the colony can make.
 */
public class MinerGoal extends ColonyWorkGoal {
	/** Scaffold blocks a miner keeps to climb out of its pit. */
	private static final int KEEP_SCAFFOLD = 8;
	private static final int MIN_FREE_SLOTS = 2;
	/** Torches a miner takes along. */
	private static final int CARRY_TORCHES = 16;
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
		Optional<Mineshaft> shaft = colony.mineshaft();
		Optional<Job> job = shaft.isEmpty() ? Optional.empty()
			: colony.board().claim(this.bot.getUUID(), Set.of(JobType.MINE_BRANCH), this.bot.blockPosition(), level.getGameTime());
		this.hasJob = job.isPresent();
		if (job.isEmpty()) {
			if (this.hasItemsToStore()) {
				this.enter(State.DEPOSIT);
			} else {
				this.cooldown = IDLE_TICKS;
			}
			return;
		}
		Optional<Mineshaft.Branch> branch = shaft.get().branch(job.get().target());
		if (branch.isPresent() && this.light(level, colony, branch.get())) {
			return;
		}
		List<BlockPos> blocks = branch.map(b -> this.toMine(level, b)).orElse(List.of());
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

	/** The branch's blocks left to dig, from the hall outwards, then the ores in the walls and ceiling of what is dug. */
	private List<BlockPos> toMine(ServerLevel level, Mineshaft.Branch branch) {
		List<BlockPos> blocks = new ArrayList<>();
		for (BlockPos cell : branch.cells()) {
			if (Mining.isMinable(level, cell) && !this.unreachable.contains(cell)) {
				blocks.add(cell);
			}
		}
		for (BlockPos cell : branch.cells()) {
			if (!level.getBlockState(cell).isAir()) {
				continue;
			}
			for (Direction dir : Direction.values()) {
				BlockPos side = cell.relative(dir);
				// Not below the floor, which would leave holes to trip over.
				if (dir != Direction.DOWN && !branch.cells().contains(side) && !blocks.contains(side) && !this.unreachable.contains(side)
						&& level.getBlockState(side).is(ConventionalBlockTags.ORES) && Mining.isMinable(level, side)) {
					blocks.add(side);
				}
			}
		}
		return blocks;
	}

	/**
	 * Puts a torch on the first dug torch spot that is still dark, fetching torches from the core if
	 * it has none. Returns true while busy with that.
	 */
	private boolean light(ServerLevel level, Colony colony, Mineshaft.Branch branch) {
		BlockState torch = Blocks.TORCH.defaultBlockState();
		Optional<BlockPos> dark = branch.torches().stream()
			.filter(pos -> level.getBlockState(pos).isAir() && torch.canSurvive(level, pos)).findFirst();
		if (dark.isEmpty()) {
			return false;
		}
		if (this.bot.getInventory().countItem(Items.TORCH) == 0) {
			Container core = Storage.containerAt(level, colony.core());
			if (core == null || core.countItem(Items.TORCH) == 0) {
				// ponytail: no torches anywhere, so the spot stays dark; the colony's torch stock target orders more.
				return false;
			}
			this.enter(State.DEPOSIT);
			return true;
		}
		if (this.reach(dark.get())) {
			level.setBlockAndUpdate(dark.get(), torch);
			Storage.take(this.bot.getInventory(), Items.TORCH, 1);
			colony.protect(dark.get());
		}
		return true;
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
			int torches = CARRY_TORCHES - this.bot.getInventory().countItem(Items.TORCH);
			if (torches > 0) {
				Storage.moveItems(core, this.bot.getInventory(), stack -> stack.is(Items.TORCH), torches);
			}
			this.enter(State.WORK);
		}
	}

	/** Puts everything in the core except pickaxes, torches and a few scaffold blocks. */
	private void storeHaul(Container core) {
		SimpleContainer inventory = this.bot.getInventory();
		int keptScaffold = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty() || stack.is(ItemTags.PICKAXES) || stack.is(Items.TORCH)) {
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
			if (stack.isEmpty() || stack.is(ItemTags.PICKAXES) || stack.is(Items.TORCH)) {
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
