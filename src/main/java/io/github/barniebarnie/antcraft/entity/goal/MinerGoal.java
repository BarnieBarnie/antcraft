package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.Tools;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Mining;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.OreVein;
import io.github.barniebarnie.antcraft.work.PickaxeTier;
import io.github.barniebarnie.antcraft.work.Storage;
import io.github.barniebarnie.antcraft.work.ToolKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Mine work. As the executor of branch jobs for {@link WorkerGoal}, through {@link #begin} and {@link #work},
 * it gets any colony bot to dig a mineshaft branch out, posting a vein job for each ore in its walls its pickaxe
 * can harvest and the colony wants, and digging such veins out whole (see {@link OreVein}),
 * light it with torches as it goes, from the nearest landing chest that has some or else the core, and store the haul; a bot without a pickaxe
 * first takes the best one in the core. Before mining a level it makes sure a pickaxe good enough for the
 * ores the colony wants there is in hand or on its way. It stores its haul after each branch and when it is
 * full. Down in the shaft it leaves all but what open orders need, their ingredients included, in the nearest
 * landing chest, which it sees there, for haulers to bring up, and climbs out to the core only with what
 * orders need; nearer the core than any landing chest, it stores it all in the core. It digs a branch standing in
 * the mine, by a way that breaks only cells of the mine: down the shaft, not a hole of its own (see {@link #inMine}). Like a player,
 * it only breaks a block it can see from within reach, not one behind a wall. A branch with cells it cannot
 * get within reach and sight of goes back on the board to retry later, and is given up after a few tries. A
 * bot whose pickaxe is not good enough for a branch leaves it a while to a free member that carries one.
 * While mining it now and then looks for a better pickaxe: it swaps to one in the core, leaving the old one
 * there, or orders the best one the colony can make.
 */
public class MinerGoal extends ColonyWorkGoal implements WorkerGoal.Executor {
	/** Scaffold blocks a miner keeps to climb out of its pit. */
	private static final int KEEP_SCAFFOLD = 8;
	private static final int MIN_FREE_SLOTS = 2;
	/** Torches a miner takes along. */
	private static final int CARRY_TORCHES = 16;
	/** A branch with cells it could not get within reach of goes back on the board for this long; say a player opens the way. */
	private static final int BRANCH_RETRY_TICKS = 200;
	/** How many tries a branch gets before its unreachable cells are given up, so its level can move on. */
	private static final int BRANCH_TRIES = 3;
	/** How long after it is posted a branch waits for a free member with a good enough pickaxe, before a bot with a weaker one takes it. */
	private static final int BETTER_PICKAXE_TICKS = 200;
	private enum State { TOOL, WORK, DEPOSIT }

	private final Set<BlockPos> unreachable = new HashSet<>();
	/** The cells of the colony's mine, kept up to date each tick it works (see {@link #inMine}). */
	private Set<BlockPos> mineCells = Set.of();
	/** Whether a block is a cell of the mine; one for good, as goals compare by value. */
	private final Predicate<BlockPos> inMine = pos -> this.mineCells.contains(pos);
	private State state = State.TOOL;
	/** The job it last checked its pickaxe for, so it checks once per job. */
	private @Nullable UUID tierChecked;
	/** What the core wants of its haul, worked out once per trip to unload. */
	private @Nullable Predicate<Item> forCore;
	/** What open orders want of its haul, which goes straight up to the core; worked out once per trip to unload. */
	private @Nullable Set<Item> forOrders;
	/** The ores the colony wants, worked out once per job when it first sees an ore (see {@link #postVeins}). */
	private @Nullable Predicate<Item> oresWanted;
	/** Whether it left what no order wants in a landing chest on this trip. */
	private boolean unloaded;
	/** The branch job begun through {@link #begin}, until it is done. */
	private @Nullable Job job;
	/** What became of the job begun, for {@link WorkerGoal} to report to the board. */
	private WorkerGoal.@Nullable Outcome outcome;

	public MinerGoal(AntBot bot) {
		super(bot);
	}

	/**
	 * Between jobs, for any bot too full to take on more digging: unloads, leaving what the core does not want in
	 * a landing chest on the way (see {@link #deposit}).
	 */
	@Override
	protected boolean betweenJobs(ServerLevel level, Colony colony) {
		if (this.state != State.DEPOSIT) {
			Container core = Storage.containerAt(level, colony.core());
			// ponytail: a full core is taken as no room anywhere, landing chests included; check those too if bots idle full by a mine.
			if (freeSlots(this.bot.getInventory()) >= MIN_FREE_SLOTS || !this.hasItemsToStore() || core == null || freeSlots(core) == 0) {
				return false;
			}
			this.enter(State.DEPOSIT);
		}
		this.deposit(level, colony);
		return true;
	}

	/** On its way to unload: the trip is finished before the bot takes a job. Not while it waits after finding no path. */
	@Override
	protected boolean carriesLoad() {
		return this.cooldown == 0 && this.state == State.DEPOSIT;
	}

	@Override
	public boolean isReady(ServerLevel level, Colony colony, Job job) {
		if (job.type() == JobType.MINE_VEIN) {
			// Only a bot whose pickaxe harvests the ore, so none is lost.
			return job.tool().filter(tool -> ToolKind.PICKAXE.rank(ToolKind.PICKAXE.bestCarried(this.bot)) >= ToolKind.PICKAXE.rank(tool)).isPresent();
		}
		// A segment once the one before it is dug, so the bot can walk up to it.
		return colony.mineshaft().filter(shaft -> shaft.segmentReady(job.target(), pos -> Mining.isDug(level, pos))).isPresent()
			&& !this.betterPickaxeFree(level, colony, job);
	}

	/**
	 * Whether the job needs a better pickaxe than this bot carries, was posted a short while ago, and a
	 * member with a good enough one is free to take it: holding no job and with room for the haul.
	 */
	private boolean betterPickaxeFree(ServerLevel level, Colony colony, Job job) {
		ToolKind kind = ToolKind.PICKAXE;
		if (job.tool().isEmpty() || kind.rank(kind.bestCarried(this.bot)) >= kind.rank(job.tool().get())
				|| level.getGameTime() - job.posted() > BETTER_PICKAXE_TICKS) {
			return false;
		}
		return colony.members().stream().anyMatch(id -> !id.equals(this.bot.getUUID()) && level.getEntity(id) instanceof AntBot other
			&& kind.rank(kind.bestCarried(other)) >= kind.rank(job.tool().get()) && colony.board().jobOf(id).isEmpty()
			&& freeSlots(other.getInventory()) > 0);
	}

	@Override
	public boolean begin(ServerLevel level, Job job) {
		this.outcome = null;
		Optional<Colony> colony = this.colony(level);
		if (colony.isEmpty() || job.type() != JobType.MINE_VEIN && colony.get().mineshaft().flatMap(shaft -> shaft.segment(job.target())).isEmpty()) {
			return false;
		}
		this.job = job;
		this.tierChecked = null;
		this.oresWanted = null;
		this.unreachable.clear();
		this.cooldown = 0;
		this.enter(State.TOOL);
		return true;
	}

	@Override
	public boolean work(ServerLevel level) {
		Optional<Colony> colony = this.colony(level);
		if (colony.isEmpty()) {
			return false;
		}
		this.bot.setWorking(this.job != null);
		this.mineCells = colony.get().mineCells();
		this.lookForLava(level, colony.get());
		if (this.cooldown > 0) {
			this.cooldown--;
			return true;
		}
		if (this.job != null) {
			switch (this.state) {
				case TOOL -> this.fetchPickaxe(level, colony.get());
				case WORK -> this.mine(level, colony.get(), this.job);
				case DEPOSIT -> this.deposit(level, colony.get());
			}
			return true;
		}
		if (this.state == State.DEPOSIT) {
			this.deposit(level, colony.get());
			return true;
		}
		return false;
	}

	@Override
	public Optional<WorkerGoal.Outcome> takeOutcome() {
		Optional<WorkerGoal.Outcome> taken = Optional.ofNullable(this.outcome);
		this.outcome = null;
		return taken;
	}

	@Override
	public void reset() {
		this.job = null;
		this.outcome = null;
		this.enter(State.TOOL);
	}

	@Override
	public int retryTicks() {
		return BRANCH_RETRY_TICKS;
	}

	@Override
	public int tries() {
		return BRANCH_TRIES;
	}

	/** Ends the job begun, then stores its haul, which it would otherwise keep from the colony. */
	private void endJob(WorkerGoal.Outcome how) {
		this.outcome = how;
		this.job = null;
		this.unreachable.clear();
		this.bot.setWorking(false);
		this.enter(this.hasItemsToStore() ? State.DEPOSIT : State.WORK);
	}

	private Optional<Colony> colony(ServerLevel level) {
		return Optional.ofNullable(this.bot.getColonyId()).flatMap(id -> ColonyManager.get(level).byId(id));
	}

	private void enter(State next) {
		this.state = next;
		this.forCore = null;
		this.forOrders = null;
		this.unloaded = false;
		this.resetMovement();
	}

	/** For a branch job: takes a pickaxe from the core, or gives the job back when there is none. */
	private void fetchPickaxe(ServerLevel level, Colony colony) {
		if (this.hasPickaxe()) {
			this.enter(State.WORK);
		} else if (!this.takePickaxe(level, colony)) {
			this.endJob(WorkerGoal.Outcome.RELEASE);
		}
	}

	/**
	 * Walks to the core for the best pickaxe it may {@linkplain ToolKind#toTake take} and takes it, then gets to work.
	 * Returns false when the core has none.
	 */
	private boolean takePickaxe(ServerLevel level, Colony colony) {
		Container core = Storage.containerAt(level, colony.core());
		Map<Item, Integer> stock = core == null ? Map.of() : Storage.stock(core);
		Optional<Item> best = ToolKind.PICKAXE.toTake(level, this.bot, null, stock, colony);
		int slot = core == null ? -1 : findSlot(core, stack -> best.isPresent() ? stack.is(best.get()) : stack.is(ItemTags.PICKAXES));
		if (slot < 0) {
			return false;
		}
		if (this.reach(colony.core())) {
			this.bot.getInventory().addItem(core.removeItem(slot, 1));
			this.enter(State.WORK);
		}
		return true;
	}

	/** Digs out the branch of the job begun, lighting it, then completes the job, or gives it back to retry when cells are out of reach. */
	private void mine(ServerLevel level, Colony colony, Job job) {
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
		if (job.type() == JobType.MINE_VEIN) {
			this.mineVein(level, job);
			return;
		}
		Optional<Mineshaft.Branch> branch = colony.mineshaft().flatMap(shaft -> shaft.segment(job.target()));
		if (branch.isEmpty()) {
			// The mine moved.
			this.endJob(WorkerGoal.Outcome.COMPLETE);
			return;
		}
		if (!job.id().equals(this.tierChecked)) {
			this.tierChecked = job.id();
			if (this.getPickaxeFor(level, colony, job)) {
				return;
			}
		}
		if (this.light(level, colony, branch.get())) {
			return;
		}
		this.postVeins(level, colony, branch.get());
		List<BlockPos> blocks = this.toMine(level, branch.get());
		if (blocks.isEmpty()) {
			// A cell in the way it may not mine, such as bedrock: give up, so nothing beyond it is dug. Else cells left
			// that it could not reach, or a torch spot it could not see: try again later, then give them up.
			List<BlockPos> cells = branch.get().cells();
			boolean blocked = cells.stream().anyMatch(cell -> !Mining.isMinable(level, cell) && !Mining.isDug(level, cell));
			boolean left = cells.stream().anyMatch(cell -> Mining.isMinable(level, cell)) || branch.get().darkTorch(level).filter(this.unreachable::contains).isPresent();
			this.endJob(blocked ? WorkerGoal.Outcome.GIVE_UP : left ? WorkerGoal.Outcome.RETRY : WorkerGoal.Outcome.COMPLETE);
			return;
		}
		this.dig(level, blocks.getFirst(), this.inMine(this.inSight(blocks.getFirst())));
	}

	/**
	 * {@code goal}, reached standing in the mine by a path that breaks only cells of the mine, from inside it: so a miner
	 * climbs down the shaft rather than dig its own way down, and digs a branch out from inside it, not from above.
	 */
	private Goal inMine(Goal goal) {
		return new Goal.Within(goal, this.inMine);
	}

	/** Breaks {@code next} once {@code reach} is reached, in reach and sight of it, walking there first; a block it finds no way to is left. */
	private void dig(ServerLevel level, BlockPos next, Goal reach) {
		if (reach.isReached(this.bot.blockPosition()) && this.bot.onGround()) {
			this.breaker.tick(level, next, true);
		} else if (this.travel(reach) == PathFollower.Status.FAILED) {
			this.unreachable.add(next);
		}
	}

	/**
	 * Digs out the ore vein of the job begun, from the block that showed inwards, walling off with a scaffold block any
	 * fluid beside an ore before it breaks it. Like a player, it walls off only fluid it sees from within reach, walking
	 * there first; an ore beside fluid it sees from nowhere, such as water hidden in the rock, is left. The holes stay open.
	 * Completes the job, or gives it back to retry when ore is left it could not get at, or wall off.
	 */
	private void mineVein(ServerLevel level, Job job) {
		List<BlockPos> left = job.area().stream()
			.filter(pos -> OreVein.isOre(level.getBlockState(pos)) && this.canHarvest(level.getBlockState(pos)) && !this.unreachable.contains(pos)).toList();
		if (left.isEmpty()) {
			boolean ore = job.area().stream().anyMatch(pos -> OreVein.isOre(level.getBlockState(pos)));
			this.endJob(ore ? WorkerGoal.Outcome.RETRY : WorkerGoal.Outcome.COMPLETE);
			return;
		}
		BlockPos next = left.getFirst();
		for (Direction dir : Direction.values()) {
			BlockPos side = next.relative(dir);
			if (!level.getFluidState(side).isEmpty()) {
				if (!this.wallOff(level, side)) {
					this.unreachable.add(next);
				}
				return;
			}
		}
		if (!Mining.isMinable(level, next)) {
			this.unreachable.add(next);
			return;
		}
		this.dig(level, next, this.inSight(next));
	}

	/**
	 * Walls off the fluid at {@code pos} with a scaffold block it carries, once in reach and sight of it, walking there
	 * first. Returns false when it cannot: it has no such block or finds no place it sees the fluid from.
	 */
	private boolean wallOff(ServerLevel level, BlockPos pos) {
		int slot = findSlot(this.bot.getInventory(), stack -> stack.is(PathFollower.SCAFFOLD_BLOCKS) && stack.getItem() instanceof BlockItem);
		PathFollower.Status status = slot < 0 ? PathFollower.Status.FAILED : this.approach(this.inSight(pos), pos);
		if (status == PathFollower.Status.FAILED) {
			return false;
		}
		if (status == PathFollower.Status.ARRIVED) {
			ItemStack stack = this.bot.getInventory().getItem(slot);
			level.setBlockAndUpdate(pos, ((BlockItem) stack.getItem()).getBlock().defaultBlockState());
			stack.shrink(1);
			this.bot.getInventory().setChanged();
		}
		return true;
	}

	/**
	 * Posts a vein job (see {@link OreVein}) for each ore in the walls and ceiling of what is dug of {@code branch} that
	 * its pickaxe can harvest and the colony wants: the ores it sees, so no ore out of sight starts one.
	 */
	private void postVeins(ServerLevel level, Colony colony, Mineshaft.Branch branch) {
		for (BlockPos side : branch.walls(level::getBlockState)) {
			BlockState state = level.getBlockState(side);
			if (OreVein.isOre(state) && this.canHarvest(state) && !ColonyManager.isProtected(level, side)) {
				if (this.oresWanted == null) {
					this.oresWanted = Storage.wanted(level, colony);
				}
				OreVein.post(colony.board(), side, level::getBlockState, level.getGameTime(), this.oresWanted);
			}
		}
	}

	/**
	 * Before mining the branch of {@code job}, makes sure the pickaxe the job declares (see
	 * {@link PickaxeTier#forBranch(Job, java.util.function.Predicate)}) is coming: orders it, or the step towards it its
	 * pickaxe can mine the materials for, unless one is in stock or on order, and fetches the best one in the core right away.
	 * Returns true while it goes to fetch one. Without one it still digs the branch, leaving the ores
	 * it cannot harvest.
	 */
	private boolean getPickaxeFor(ServerLevel level, Colony colony, Job job) {
		ToolKind kind = ToolKind.PICKAXE;
		Item need = PickaxeTier.forBranch(job, Storage.wanted(level, colony));
		Item carried = kind.bestCarried(this.bot);
		if (kind.rank(carried) >= kind.rank(need)) {
			return false;
		}
		int step = kind.rank(PickaxeTier.toOrder(carried, need));
		Map<Item, Integer> stock = kind.spare(level, colony);
		boolean coming = kind.tiers().stream().filter(tier -> kind.rank(tier) >= step)
			.anyMatch(tier -> stock.getOrDefault(tier, 0) > 0 || colony.orders().getOrDefault(tier, 0) > 0);
		if (!coming) {
			colony.addOrder(kind.tiers().get(step), 1);
		}
		this.checkToolNow();
		return this.upgradeTool(level, colony, kind);
	}

	/**
	 * The branch's blocks left to dig, from the hall outwards up to the first cell in the way it may not mine; the ores
	 * in its walls are vein jobs (see {@link #postVeins}).
	 */
	private List<BlockPos> toMine(ServerLevel level, Mineshaft.Branch branch) {
		List<BlockPos> blocks = new ArrayList<>();
		for (BlockPos cell : branch.cells()) {
			boolean minable = Mining.isMinable(level, cell);
			if (!minable && !Mining.isDug(level, cell)) {
				break;
			}
			if (minable && !this.unreachable.contains(cell)) {
				blocks.add(cell);
			}
		}
		return blocks;
	}

	/**
	 * Puts a torch on the first dug torch spot that is still dark, fetching torches if it has none: from the
	 * nearest landing chest that has some when that is nearer than the core, else from the core. Returns true while busy with that. Like a player, it places the torch only where it
	 * sees the spot; one it finds no such place for is left for this job.
	 */
	private boolean light(ServerLevel level, Colony colony, Mineshaft.Branch branch) {
		// ponytail: only the first dark spot; one left out of sight leaves the spots behind it dark for this job too.
		Optional<BlockPos> dark = branch.darkTorch(level).filter(pos -> !this.unreachable.contains(pos));
		if (dark.isEmpty()) {
			return false;
		}
		if (this.bot.getInventory().countItem(Items.TORCH) == 0) {
			BlockPos chest = this.nearerThanCore(colony, Storage.landingChests(level, colony), container -> container.countItem(Items.TORCH) > 0);
			if (chest != null) {
				if (this.reach(chest)) {
					this.takeTorches(Objects.requireNonNull(Storage.containerAt(level, chest)));
				}
				return true;
			}
			Container core = Storage.containerAt(level, colony.core());
			if (core == null || core.countItem(Items.TORCH) == 0) {
				// ponytail: no torches anywhere, so the spot stays dark; the colony's torch stock target orders more.
				return false;
			}
			this.enter(State.DEPOSIT);
			return true;
		}
		PathFollower.Status status = this.approach(this.inMine(this.inSight(dark.get())), dark.get());
		if (status == PathFollower.Status.FAILED) {
			this.unreachable.add(dark.get());
			return false;
		}
		if (status == PathFollower.Status.ARRIVED) {
			level.setBlockAndUpdate(dark.get(), Blocks.TORCH.defaultBlockState());
			Storage.take(this.bot.getInventory(), Items.TORCH, 1);
			colony.protect(dark.get(), Blocks.TORCH);
		}
		return true;
	}

	/**
	 * Unloads its haul into the core, where it also tops up its torches. Nearer a landing chest with room
	 * than the core, it first leaves all but what open orders need there (see {@link #landingChest}).
	 */
	private void deposit(ServerLevel level, Colony colony) {
		if (this.forCore == null) {
			Map<Item, Integer> haul = Storage.stock(this.bot.getInventory());
			this.forCore = Storage.wanted(level, colony, haul);
			this.forOrders = Storage.wantedForOrders(level, colony, Storage.heldLess(level, colony, haul));
		}
		Predicate<Item> forCore = this.forCore;
		Set<Item> forOrders = Objects.requireNonNull(this.forOrders);
		BlockPos chest = this.landingChest(level, colony, forCore, forOrders);
		if (chest != null) {
			if (this.reach(chest)) {
				Container container = Objects.requireNonNull(Storage.containerAt(level, chest));
				this.storeHaul(container, forCore, item -> !forOrders.contains(item));
				this.takeTorches(container);
				if (this.hasItemsToStore()) {
					// What orders want, or what the chest had no room for, goes up to the core.
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
			this.takeTorches(core);
			this.enter(State.WORK);
		}
	}

	/** Tops up the torches it carries from {@code container}. */
	private void takeTorches(Container container) {
		int torches = CARRY_TORCHES - this.bot.getInventory().countItem(Items.TORCH);
		if (torches > 0) {
			Storage.moveItems(container, this.bot.getInventory(), stack -> stack.is(Items.TORCH), torches);
		}
	}

	/** The nearest of the {@code chests} that passes {@code test}, when it is nearer than the core; else null. */
	private @Nullable BlockPos nearerThanCore(Colony colony, Map<BlockPos, Container> chests, Predicate<Container> test) {
		BlockPos at = this.bot.blockPosition();
		// ponytail: straight-line distance; a core across a ravine from the shaft counts as near.
		return chests.entrySet().stream()
			.filter(chest -> test.test(chest.getValue()))
			.map(Map.Entry::getKey)
			.filter(pos -> pos.distSqr(at) < colony.core().distSqr(at))
			.min(Comparator.comparingDouble(pos -> pos.distSqr(at)))
			.orElse(null);
	}

	/**
	 * The nearest landing chest with room, when it is nearer than the core, the bot carries something no open
	 * order wants, and it has not been to one on this trip yet; else null, and it walks up to the core. Since
	 * bots open a container only where they see it, a core on top of the shaft is out of sight from below.
	 */
	private @Nullable BlockPos landingChest(ServerLevel level, Colony colony, Predicate<Item> forCore, Set<Item> forOrders) {
		SimpleContainer inventory = this.bot.getInventory();
		if (this.unloaded) {
			return null;
		}
		int[] store = this.toStore(forCore);
		boolean notOrdered = false;
		for (int i = 0; i < store.length; i++) {
			notOrdered |= store[i] > 0 && !forOrders.contains(inventory.getItem(i).getItem());
		}
		if (!notOrdered) {
			return null;
		}
		return this.nearerThanCore(colony, Storage.landingChests(level, colony), chest -> freeSlots(chest) > 0);
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
