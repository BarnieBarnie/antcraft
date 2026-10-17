package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.colony.StockPlanner;
import io.github.barniebarnie.antcraft.colony.Stockkeeping;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.entity.Tools;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Mining;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.PickaxeTier;
import io.github.barniebarnie.antcraft.work.Storage;
import io.github.barniebarnie.antcraft.work.ToolKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
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
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Miner work loop: make sure it has a pickaxe (from the core, or by ordering one),
 * claim a mineshaft branch, dig it out with the ores in its walls its pickaxe can harvest, light it
 * with torches from the core as it goes, and bring the haul to the core. When it is full, it leaves
 * what the core does not want in a landing chest on the way; the core wants what open orders need,
 * their ingredients included, and what the colony is short of a stock target of. Now and then it
 * looks for a better pickaxe: it swaps to one in the core, leaving the old one there,
 * or orders the best one the colony can make. Before mining a level it makes sure a pickaxe
 * good enough for the ores the colony wants there is in hand or on its way. A branch with cells it
 * cannot get within reach of goes back on the board to retry later, and is given up after a few tries.
 * It also digs out dig jobs, such as the ground of a terraform above its height, with the haul going
 * to the core the same way (see {@link Digging}).
 */
public class MinerGoal extends ColonyWorkGoal {
	/** Scaffold blocks a miner keeps to climb out of its pit. */
	private static final int KEEP_SCAFFOLD = 8;
	private static final int MIN_FREE_SLOTS = 2;
	/** Torches a miner takes along. */
	private static final int CARRY_TORCHES = 16;
	/** A branch with cells it could not get within reach of goes back on the board for this long; say a player opens the way. */
	private static final int BRANCH_RETRY_TICKS = 200;
	/** How many tries a branch gets before its unreachable cells are given up, so its level can move on. */
	private static final int BRANCH_TRIES = 3;
	private enum State { TOOL, WORK, DEPOSIT }

	private final Set<BlockPos> unreachable = new HashSet<>();
	private State state = State.TOOL;
	/** The job it last checked its pickaxe for, so it checks once per job. */
	private @Nullable UUID tierChecked;
	/** What the core wants of its haul, worked out once per trip to unload. */
	private @Nullable Predicate<Item> forCore;
	/** Whether it left what the core does not want in a landing chest on this trip. */
	private boolean unloaded;

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
		this.forCore = null;
		this.unloaded = false;
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
			ToolKind.PICKAXE.order(level, colony, null, Storage.colonyStock(level, colony));
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
		Set<JobType> types = shaft.isEmpty() ? Set.of(JobType.CLEAR_AREA) : Set.of(JobType.MINE_BRANCH, JobType.CLEAR_AREA);
		Optional<Job> job = colony.board().claim(this.bot.getUUID(), types, this.bot.blockPosition(), level.getGameTime(),
			open -> open.type() != JobType.CLEAR_AREA || Digging.isReady(level, colony, open));
		this.hasJob = job.isPresent();
		if (job.isEmpty()) {
			if (this.hasItemsToStore()) {
				this.enter(State.DEPOSIT);
			} else {
				this.cooldown = IDLE_TICKS;
			}
			return;
		}
		if (job.get().type() == JobType.CLEAR_AREA) {
			this.dig(level, colony, job.get());
			return;
		}
		if (!job.get().id().equals(this.tierChecked)) {
			this.tierChecked = job.get().id();
			if (this.getPickaxeFor(level, colony, job.get().target().getY())) {
				return;
			}
		}
		Optional<Mineshaft.Branch> branch = shaft.get().branch(job.get().target());
		if (branch.isPresent() && this.light(level, colony, branch.get())) {
			return;
		}
		List<BlockPos> blocks = branch.map(b -> this.toMine(level, b)).orElse(List.of());
		if (blocks.isEmpty()) {
			if (branch.isPresent() && branch.get().cells().stream().anyMatch(cell -> Mining.isMinable(level, cell))) {
				// Cells left that it could not reach: try again later, then give them up.
				if (!colony.board().retryLater(this.bot.getUUID(), level.getGameTime() + BRANCH_RETRY_TICKS, BRANCH_TRIES)) {
					AntCraft.LOGGER.info("Colony at {} gave up on the branch at {}: cells out of reach after {} tries",
						colony.core().toShortString(), branch.get().start().toShortString(), BRANCH_TRIES);
				}
			} else {
				colony.board().complete(job.get().id());
			}
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

	/** Digs out the batch of dig job {@code job} block by block, from the top, leaving trees to lumberjacks (see {@link Digging}). */
	private void dig(ServerLevel level, Colony colony, Job job) {
		Optional<BuildPlan.Step> next = colony.build(job.id())
			.flatMap(batch -> batch.nextStep(level::getBlockState, step -> !this.unreachable.contains(step.pos()) && Digging.isDiggable(level, colony, step)));
		if (next.isEmpty()) {
			Digging.putAside(level, colony, job, this.bot.getUUID());
			this.hasJob = false;
			this.unreachable.clear();
			this.resetMovement();
			this.cooldown = IDLE_TICKS;
			return;
		}
		BlockPos pos = next.get().pos();
		Goal reach = new Goal.Reach(pos, REACH);
		if (reach.isReached(this.bot.blockPosition()) && this.bot.onGround()) {
			this.breaker.tick(level, pos, true);
		} else if (this.travel(reach) == PathFollower.Status.FAILED) {
			this.unreachable.add(pos);
		}
	}

	/**
	 * Before mining at height {@code y}, makes sure a good enough pickaxe for the ores the colony
	 * wants there is coming: orders it, or the step towards it its pickaxe can mine the materials
	 * for, unless one is in stock or on order, and fetches the best one in the core right away.
	 * Returns true while it goes to fetch one. Without one it still digs the branch, leaving the ores
	 * it cannot harvest.
	 */
	private boolean getPickaxeFor(ServerLevel level, Colony colony, int y) {
		ToolKind kind = ToolKind.PICKAXE;
		Item need = PickaxeTier.forLevel(y, Storage.wanted(level, colony));
		Item carried = kind.bestCarried(this.bot);
		if (kind.rank(carried) >= kind.rank(need)) {
			return false;
		}
		int step = kind.rank(PickaxeTier.toOrder(carried, need));
		Map<Item, Integer> stock = Storage.colonyStock(level, colony);
		boolean coming = kind.tiers().stream().filter(tier -> kind.rank(tier) >= step)
			.anyMatch(tier -> stock.getOrDefault(tier, 0) > 0 || colony.orders().getOrDefault(tier, 0) > 0);
		if (!coming) {
			colony.addOrder(kind.tiers().get(step), 1);
		}
		this.checkToolNow();
		return this.upgradeTool(level, colony, kind);
	}

	/**
	 * The branch's blocks left to dig, from the hall outwards, then the ores in the walls and ceiling
	 * of what is dug that its pickaxe can harvest.
	 */
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
						&& level.getBlockState(side).is(ConventionalBlockTags.ORES) && this.canHarvest(level.getBlockState(side))
						&& Mining.isMinable(level, side)) {
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
		Optional<BlockPos> dark = branch.darkTorch(level);
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
			level.setBlockAndUpdate(dark.get(), Blocks.TORCH.defaultBlockState());
			Storage.take(this.bot.getInventory(), Items.TORCH, 1);
			colony.protect(dark.get(), Blocks.TORCH);
		}
		return true;
	}

	/**
	 * Unloads its haul into the core, where it also tops up its torches. When it is full, it first
	 * leaves what the core does not want in the nearest landing chest with room.
	 */
	private void deposit(ServerLevel level, Colony colony) {
		if (this.forCore == null) {
			Map<Item, Integer> stock = Storage.colonyStock(level, colony);
			Set<Item> forOrders = Storage.wantedForOrders(level, colony, stock);
			List<StockPlanner.Target<Item>> targets = Storage.targets(colony);
			this.forCore = item -> Stockkeeping.toCore(item, forOrders, targets, stock);
		}
		Predicate<Item> forCore = this.forCore;
		BlockPos chest = this.landingChest(level, colony, forCore);
		if (chest != null) {
			if (this.reach(chest)) {
				this.storeHaul(Storage.containerAt(level, chest), forCore, item -> !forCore.test(item));
				if (this.hasItemsToStore()) {
					// What the core wants, or what the chest had no room for, goes up to the core.
					this.unloaded = true;
					this.resetMovement();
				} else {
					this.enter(State.WORK);
				}
			}
			return;
		}
		Container core = Storage.containerAt(level, colony.core());
		if (core == null) {
			this.enter(State.WORK);
			this.cooldown = IDLE_TICKS;
			return;
		}
		if (this.reach(colony.core())) {
			this.storeHaul(core, forCore, item -> true);
			int torches = CARRY_TORCHES - this.bot.getInventory().countItem(Items.TORCH);
			if (torches > 0) {
				Storage.moveItems(core, this.bot.getInventory(), stack -> stack.is(Items.TORCH), torches);
			}
			this.enter(State.WORK);
		}
	}

	/**
	 * The nearest landing chest with room, when it unloads because it is full, carries something
	 * the core does not want, and has not been to one on this trip yet; else null, and it walks up
	 * to the core.
	 */
	private @Nullable BlockPos landingChest(ServerLevel level, Colony colony, Predicate<Item> forCore) {
		SimpleContainer inventory = this.bot.getInventory();
		if (this.unloaded || freeSlots(inventory) >= MIN_FREE_SLOTS) {
			return null;
		}
		int[] store = this.toStore(forCore);
		boolean unwanted = false;
		for (int i = 0; i < store.length; i++) {
			unwanted |= store[i] > 0 && !forCore.test(inventory.getItem(i).getItem());
		}
		if (!unwanted) {
			return null;
		}
		return Storage.landingChests(level, colony).entrySet().stream()
			.filter(chest -> freeSlots(chest.getValue()) > 0)
			.map(Map.Entry::getKey)
			.min(Comparator.comparingDouble(pos -> pos.distSqr(this.bot.blockPosition())))
			.orElse(null);
	}

	/**
	 * How many of each inventory slot to unload: everything except pickaxes, torches and a few
	 * scaffold blocks, which it keeps of what the core does not want first.
	 */
	private int[] toStore(Predicate<Item> forCore) {
		SimpleContainer inventory = this.bot.getInventory();
		int[] store = new int[inventory.getContainerSize()];
		for (int i = 0; i < store.length; i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty() && !stack.is(ItemTags.PICKAXES) && !stack.is(Items.TORCH)) {
				store[i] = stack.getCount();
			}
		}
		int keep = KEEP_SCAFFOLD;
		for (boolean wanted : new boolean[] {false, true}) {
			for (int i = 0; i < store.length && keep > 0; i++) {
				ItemStack stack = inventory.getItem(i);
				if (store[i] > 0 && stack.is(PathFollower.SCAFFOLD_BLOCKS) && forCore.test(stack.getItem()) == wanted) {
					int kept = Math.min(store[i], keep);
					store[i] -= kept;
					keep -= kept;
				}
			}
		}
		return store;
	}

	/** Puts its haul (see {@link #toStore}) of the {@code items} given in the container. */
	private void storeHaul(Container container, Predicate<Item> forCore, Predicate<Item> items) {
		SimpleContainer inventory = this.bot.getInventory();
		int[] store = this.toStore(forCore);
		for (int i = 0; i < store.length; i++) {
			ItemStack stack = inventory.getItem(i);
			if (store[i] > 0 && items.test(stack.getItem())) {
				ItemStack rest = HopperBlockEntity.addItem(null, container, stack.split(store[i]), null);
				stack.grow(rest.getCount());
			}
		}
		inventory.setChanged();
		container.setChanged();
	}

	private boolean hasItemsToStore() {
		return Arrays.stream(this.toStore(item -> false)).anyMatch(count -> count > 0);
	}

	/** Whether the best tool it carries for {@code state} gets its drops. */
	private boolean canHarvest(BlockState state) {
		return Tools.canHarvest(Tools.bestToolFor(this.bot.getInventory(), this.bot.getMainHandItem(), state), state);
	}

	private boolean hasPickaxe() {
		return this.bot.getMainHandItem().is(ItemTags.PICKAXES) || findSlot(this.bot.getInventory(), stack -> stack.is(ItemTags.PICKAXES)) >= 0;
	}
}
