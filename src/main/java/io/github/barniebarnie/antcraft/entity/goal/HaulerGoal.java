package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.colony.StockPlanner;
import io.github.barniebarnie.antcraft.colony.StockTarget;
import io.github.barniebarnie.antcraft.colony.Stockkeeping;
import io.github.barniebarnie.antcraft.colony.TaskPlanner;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.work.GameRecipeBook;
import io.github.barniebarnie.antcraft.work.Gatherables;
import io.github.barniebarnie.antcraft.work.Storage;
import io.github.barniebarnie.antcraft.work.StorageCategory;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Hauling, which any colony bot does between jobs: pick up loose items around the colony and store them in
 * the core, and keep the core at its working stock. What the core holds above that goes to the storage
 * chests, one category per chest; what the core is short of (stock targets, ingredients for open orders)
 * comes back. Bots place the storage chests themselves, from chests the colony survey orders. While
 * the core is full and the storage area still waits for a chest, a bot holds a stack of surplus
 * so the chest has a slot to be crafted into, and another each time the core fills up again; it
 * puts that back once the core has room. A full core where everything is wanted still frees a slot,
 * and bots keep a reserve slot free so what they stored does not come straight back. Tools and
 * weapons a bot carries stay with it.
 * <p>
 * As the executor of haul jobs for {@link WorkerGoal}, it gets any colony bot to empty a mineshaft landing
 * chest into the core, a load at a time (see {@link #haulsFrom} for what it takes).
 */
public class HaulerGoal extends ColonyWorkGoal implements WorkerGoal.Executor {
	private static final int MIN_FREE_SLOTS = 1;
	/** A core with this few free slots left is nearly full: it keeps only what it needs. */
	private static final int NEARLY_FULL_FREE_SLOTS = 4;
	/**
	 * Free slots a hauler leaves in the core: it fetches nothing from storage and puts back no held
	 * surplus that would take them, so what it stored to make room does not come straight back.
	 */
	private static final int RESERVE_SLOTS = 1;
	/** A trip that takes longer than this is given up, for example when a chest cannot be reached. */
	private static final int TRIP_TICKS = 1200;

	/**
	 * Moving up to {@code count} of an item, or of everything when {@code item} is null, between
	 * containers; a chest carried to an empty spot is placed there.
	 */
	private record Trip(BlockPos from, BlockPos to, @Nullable Item item, int count) {
		boolean carries(ItemStack stack) {
			return this.item == null || stack.is(this.item);
		}

		/** What two haulers must not both plan a trip for: the chest emptied whole, else the item moved. */
		Object source() {
			return this.item == null ? this.from : this.item;
		}
	}

	/** The core, the storage chests and what the core should keep, as one snapshot. */
	private record Stocks(Container core, Map<BlockPos, Container> chests, Map<Item, Integer> inCore, Map<Item, Integer> stored,
			List<StockPlanner.Target<Item>> targets, Map<Item, Integer> ingredients) {
		static @Nullable Stocks of(ServerLevel level, Colony colony) {
			Container core = Storage.containerAt(level, colony.core());
			if (core == null) {
				return null;
			}
			Map<BlockPos, Container> chests = Storage.storageChests(level, colony);
			Map<Item, Integer> inCore = Storage.stock(core);
			Map<Item, Integer> stored = StockPlanner.total(chests.values().stream().map(Storage::stock).toList());
			return new Stocks(core, chests, inCore, stored, colony.stockTargets().stream().map(StockTarget::toPlannerTarget).toList(),
				Storage.neededInCore(level, colony, StockPlanner.total(List.of(inCore, stored))));
		}

		Map<Item, Integer> toFetch() {
			return Stockkeeping.toFetch(this.inCore, this.stored, this.targets, this.ingredients);
		}

		/**
		 * What the core holds above its working stock. A nearly full core with nothing above that keeps
		 * only what stock targets and open orders need, not a stack of everything, so a core full of
		 * different items still has surplus to make room with. A full core where all of that is wanted
		 * gives up one kind (see {@link Stockkeeping#toMakeRoom}), but never its chests, which storage
		 * needs placed.
		 */
		Map<Item, Integer> toStore() {
			Map<Item, Integer> store = Stockkeeping.toStore(this.inCore, this.targets, this.ingredients, Item::getDefaultMaxStackSize);
			if (store.isEmpty() && freeSlots(this.core) <= NEARLY_FULL_FREE_SLOTS) {
				store = Stockkeeping.toStore(this.inCore, this.targets, this.ingredients, item -> 0);
			}
			if (store.isEmpty() && freeSlots(this.core) == 0) {
				Map<Item, Integer> kinds = new LinkedHashMap<>(this.inCore);
				kinds.remove(Items.CHEST);
				store = Stockkeeping.toMakeRoom(kinds, this.ingredients);
			}
			return store;
		}

		/** Whether some of the item fits in the core without taking its reserve slots. */
		boolean hasRoomBeyondReserve(Item item) {
			return freeSlots(this.core) > RESERVE_SLOTS || hasPartialStack(this.core, new ItemStack(item));
		}

		/** The surplus a hauler may hold to make room for a storage chest: all of it but what crafting the chest uses. */
		Map<Item, Integer> toHold(ServerLevel level) {
			TaskPlanner.Plan<Item> chest = TaskPlanner.plan(Items.CHEST, 1, this.inCore, GameRecipeBook.of(level.getServer()), Gatherables::isGatherable);
			Map<Item, Integer> forChest = Stockkeeping.ingredients(chest.crafts(), this.inCore);
			Map<Item, Integer> hold = new LinkedHashMap<>();
			this.toStore().forEach((item, count) -> {
				int spare = count - forChest.getOrDefault(item, 0);
				if (spare > 0) {
					hold.put(item, spare);
				}
			});
			return hold;
		}
	}

	private final Set<Integer> unreachableItems = new HashSet<>();
	private boolean depositing;
	private @Nullable Trip trip;
	private boolean loaded;
	private int tripTicks;
	/** Surplus the hauler is on its way to take out of a full core, or null. */
	private @Nullable Item clearing;
	/** Surplus the hauler took out of a full core, which it keeps out of the core until storage takes it. */
	private final Set<Item> holding = new HashSet<>();

	/** The haul job begun through {@link #begin}, until it is done. */
	private @Nullable Job haul;
	private WorkerGoal.@Nullable Outcome haulOutcome;
	/** Whether the bot carries a load of the haul job to the core. */
	private boolean haulLoaded;
	/** What the bot took out of the haul job's container, to put in the core. */
	private final Set<Item> hauled = new HashSet<>();

	public HaulerGoal(AntBot bot) {
		super(bot);
		// Hauling only walks: a pillar to a drop up high, or out of a mine, would be left standing in the way.
		this.follower.setMayPlace(false);
	}

	/**
	 * What a haul job takes out of {@code chest}: everything while the colony has a storage area, where bots store
	 * what the core does not need; else only what the colony wants, so bots do not carry up what a miner left below.
	 */
	public static Predicate<ItemStack> haulsFrom(ServerLevel level, Colony colony, Container chest) {
		if (colony.storage().isPresent()) {
			return stack -> true;
		}
		Predicate<Item> wanted = Storage.wanted(level, colony, Storage.stock(chest));
		return stack -> wanted.test(stack.getItem());
	}

	/** The mineshaft landing chests a haul job should empty into the core, by position. */
	public static List<BlockPos> chestsToHaul(ServerLevel level, Colony colony) {
		return Storage.landingChests(level, colony).entrySet().stream()
			.filter(chest -> !chest.getValue().isEmpty() && holds(chest.getValue(), haulsFrom(level, colony, chest.getValue())))
			.map(Map.Entry::getKey)
			.toList();
	}

	private static boolean holds(Container container, Predicate<ItemStack> filter) {
		return findSlot(container, filter) >= 0;
	}

	@Override
	public boolean isReady(ServerLevel level, Colony colony, Job job) {
		Container chest = Storage.containerAt(level, job.target());
		Container core = Storage.containerAt(level, colony.core());
		return job.type() == JobType.HAUL && chest != null && core != null && freeSlots(core) > 0 && freeSlots(this.bot.getInventory()) > 0
			&& holds(chest, haulsFrom(level, colony, chest));
	}

	@Override
	public boolean begin(ServerLevel level, Job job) {
		this.haulOutcome = null;
		Container chest = Storage.containerAt(level, job.target());
		if (chest == null || chest.isEmpty()) {
			return false;
		}
		this.haul = job;
		this.haulLoaded = false;
		this.hauled.clear();
		this.resetMovement();
		return true;
	}

	/** Carries loads from the job's container to the core until it holds nothing more to haul, or the core is full. */
	@Override
	public boolean work(ServerLevel level) {
		Optional<Colony> colony = Optional.ofNullable(this.bot.getColonyId()).flatMap(id -> ColonyManager.get(level).byId(id));
		Job job = this.haul;
		if (colony.isEmpty() || job == null) {
			return false;
		}
		this.bot.setWorking(true);
		if (this.cooldown > 0) {
			this.cooldown--;
			return true;
		}
		Container chest = Storage.containerAt(level, job.target());
		Container core = Storage.containerAt(level, colony.get().core());
		if (core == null) {
			this.endHaul(WorkerGoal.Outcome.RELEASE);
			return true;
		}
		SimpleContainer inventory = this.bot.getInventory();
		if (!this.haulLoaded) {
			if (chest == null) {
				this.endHaul(WorkerGoal.Outcome.COMPLETE);
			} else if (this.reach(job.target())) {
				Predicate<ItemStack> filter = haulsFrom(level, colony.get(), chest);
				Storage.moveItems(chest, inventory, stack -> {
					boolean take = filter.test(stack);
					if (take) {
						this.hauled.add(stack.getItem());
					}
					return take;
				});
				this.haulLoaded = true;
				if (this.hauled.isEmpty()) {
					this.endHaul(WorkerGoal.Outcome.COMPLETE);
				}
			}
			return true;
		}
		if (this.reach(colony.get().core())) {
			Storage.moveItems(inventory, core, stack -> this.hauled.contains(stack.getItem()));
			this.haulLoaded = false;
			this.hauled.clear();
			// Another trip while there is more and room for it.
			if (chest == null || !holds(chest, haulsFrom(level, colony.get(), chest)) || freeSlots(core) == 0 || freeSlots(inventory) == 0) {
				this.endHaul(WorkerGoal.Outcome.COMPLETE);
			}
		}
		return true;
	}

	@Override
	public Optional<WorkerGoal.Outcome> takeOutcome() {
		Optional<WorkerGoal.Outcome> taken = Optional.ofNullable(this.haulOutcome);
		this.haulOutcome = null;
		return taken;
	}

	@Override
	public void reset() {
		this.haul = null;
		this.haulOutcome = null;
		this.haulLoaded = false;
		this.hauled.clear();
		this.resetMovement();
	}

	private void endHaul(WorkerGoal.Outcome how) {
		this.haulOutcome = how;
		this.haul = null;
		this.bot.setWorking(false);
	}

	/**
	 * Work between jobs, for any bot: picking up loose items around the colony and storing them in the core,
	 * keeping the core at its working stock with the storage chests, and placing those chests.
	 */
	@Override
	protected boolean betweenJobs(ServerLevel level, Colony colony) {
		SimpleContainer inventory = this.bot.getInventory();
		this.holding.removeIf(item -> inventory.countItem(item) == 0);
		if (this.trip != null) {
			this.runTrip(level);
			return true;
		}
		if (this.clearing != null) {
			Container core = Storage.containerAt(level, colony.core());
			if (core == null) {
				this.clearing = null;
				this.cooldown = IDLE_TICKS;
			} else if (this.reach(colony.core())) {
				Item surplus = this.clearing;
				Stocks stocks = Stocks.of(level, colony);
				// Only the surplus: what the core keeps for orders and the chest stays.
				int count = Math.min(stocks == null ? 0 : stocks.toHold(level).getOrDefault(surplus, 0), surplus.getDefaultMaxStackSize());
				Storage.moveItems(core, inventory, stack -> stack.is(surplus), count);
				this.holding.add(surplus);
				this.clearing = null;
			}
			return true;
		}
		if (this.depositing) {
			Container core = Storage.containerAt(level, colony.core());
			if (core == null) {
				this.depositing = false;
				this.cooldown = IDLE_TICKS;
			} else if (this.reach(colony.core())) {
				Storage.moveItems(inventory, core, this::deposits);
				this.returnHeld(core);
				this.depositing = false;
				this.unreachableItems.clear();
			}
			return true;
		}

		ItemEntity nearest = freeSlots(inventory) < MIN_FREE_SLOTS ? null : level.getEntitiesOfClass(ItemEntity.class,
				new AABB(colony.core()).inflate(colony.radius()),
				item -> item.isAlive() && !this.unreachableItems.contains(item.getId()) && this.bot.wantsToPickUp(level, item.getItem()))
			.stream()
			.min(Comparator.comparingDouble(this.bot::distanceToSqr))
			.orElse(null);
		if (nearest == null) {
			Container core = Storage.containerAt(level, colony.core());
			if (core != null && this.hasRoomForAny(core, inventory)) {
				this.depositing = true;
				return true;
			}
			// What does not fit in the core goes to storage; without room there it waits for a chest.
			Trip leftovers = this.leftoverTrip(level, colony);
			this.trip = leftovers != null ? leftovers : this.planTrip(level, colony);
			this.loaded = leftovers != null;
			this.tripTicks = 0;
			if (this.trip == null) {
				colony.tripClaims().release(this.bot.getUUID());
				this.clearing = this.surplusToHold(level, colony);
				if (this.clearing == null) {
					return false;
				}
			} else if (leftovers == null) {
				// Kept until the hauler plans its next trip, so it goes on with this one.
				colony.tripClaims().claim(this.bot.getUUID(), this.trip.source(), level.getGameTime() + TRIP_TICKS);
			}
			return true;
		}
		if (this.travel(new Goal.Near(nearest.blockPosition(), 0)) == PathFollower.Status.FAILED) {
			this.unreachableItems.add(nearest.getId());
		}
		return true;
	}

	/**
	 * On its way to the core or a storage chest with a load: the trip is finished before the bot takes a job. Not
	 * while it waits after finding no path there, so a load it cannot deliver does not keep it from jobs.
	 */
	@Override
	protected boolean carriesLoad() {
		return this.cooldown == 0 && (this.depositing || this.trip != null && this.loaded);
	}

	/** Whether the bot puts the stack in the core when it deposits: not what it works with, nor surplus it holds. */
	private boolean deposits(ItemStack stack) {
		return !this.holds(stack) && !isWorkingKit(stack);
	}

	/** What a bot keeps to work with rather than store: tools, weapons, the scaffold blocks it pillars with and a crafting table or furnace it crafts at. */
	private static boolean isWorkingKit(ItemStack stack) {
		return stack.isDamageableItem() || stack.is(PathFollower.FAST_SCAFFOLD_BLOCKS) || stack.is(Items.CRAFTING_TABLE) || stack.is(Items.FURNACE);
	}

	/** Whether the stack is surplus the bot took out of a full core to keep a slot free. */
	private boolean holds(ItemStack stack) {
		return this.holding.contains(stack.getItem());
	}

	private boolean hasRoomForAny(Container core, Container inventory) {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty() && (this.deposits(stack) ? Storage.hasRoomFor(core, stack) : this.holds(stack) && freeSlots(core) > RESERVE_SLOTS)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Puts held surplus back into a core that has room again, a stack at a time while that leaves its
	 * reserve slots free, so the hauler does not take it straight out again.
	 */
	private void returnHeld(Container core) {
		SimpleContainer inventory = this.bot.getInventory();
		for (int i = 0; i < inventory.getContainerSize() && freeSlots(core) > RESERVE_SLOTS; i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty() && this.holds(stack)) {
				inventory.setItem(i, HopperBlockEntity.addItem(null, core, stack, null));
			}
		}
		inventory.setChanged();
		core.setChanged();
	}

	private static boolean hasPartialStack(Container container, ItemStack stack) {
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack slot = container.getItem(i);
			if (ItemStack.isSameItemSameComponents(slot, stack) && slot.getCount() < slot.getMaxStackSize()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Surplus to take out of the core and hold, so the chest the storage area waits for can be crafted:
	 * when the core has no free slot, the area has a free chest spot and the core holds no chest. Null
	 * if there is no need, or the hauler has no room to spare (it keeps a slot for the chest). When
	 * something fills the freed slot before the chest is crafted, the core is full again and the
	 * hauler takes more.
	 */
	private @Nullable Item surplusToHold(ServerLevel level, Colony colony) {
		Stocks stocks = colony.storage().isEmpty() ? null : Stocks.of(level, colony);
		if (stocks == null || freeSlots(stocks.core()) > 0 || stocks.inCore().getOrDefault(Items.CHEST, 0) > 0
				|| freeSlots(this.bot.getInventory()) < 2 || freeSpots(level, colony, stocks).findFirst().isEmpty()) {
			return null;
		}
		// Rather a kind that frees a slot when taken: all of it may be held, or a stack of it.
		return stocks.toHold(level).entrySet().stream()
			.min(Comparator.comparing((Map.Entry<Item, Integer> store) ->
				store.getValue() < Math.min(stocks.inCore().get(store.getKey()), store.getKey().getDefaultMaxStackSize())))
			.map(Map.Entry::getKey)
			.orElse(null);
	}

	/** A storage chest for something the hauler carries, to take it straight there; null if none has room. */
	private @Nullable Trip leftoverTrip(ServerLevel level, Colony colony) {
		Map<BlockPos, Container> chests = Storage.storageChests(level, colony);
		SimpleContainer inventory = this.bot.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			BlockPos chest = stack.isEmpty() || isWorkingKit(stack) ? null : Storage.chestFor(chests, stack);
			if (chest != null) {
				return new Trip(chest, chest, stack.getItem(), Integer.MAX_VALUE);
			}
		}
		return null;
	}

	/** Whether no other hauler is on a trip from the same source. */
	private boolean isFree(ServerLevel level, Colony colony, Trip trip) {
		return colony.tripClaims().isFree(this.bot.getUUID(), trip.source(), level.getGameTime());
	}

	/** The next item to bring back into the core, else the next to store, or null if there is nothing to do. */
	private @Nullable Trip planTrip(ServerLevel level, Colony colony) {
		Stocks stocks = colony.storage().isEmpty() ? null : Stocks.of(level, colony);
		Trip trip = stocks == null ? null : this.planStorageTrip(level, colony, stocks);
		if (trip != null) {
			return trip;
		}
		// Mineshaft landing chests are emptied through haul jobs (see haulsFrom).
		return null;
	}

	private @Nullable Trip planStorageTrip(ServerLevel level, Colony colony, Stocks stocks) {
		for (Map.Entry<Item, Integer> fetch : stocks.toFetch().entrySet()) {
			// Not into the reserve: what was stored to make room stays stored.
			if (!stocks.hasRoomBeyondReserve(fetch.getKey())) {
				continue;
			}
			for (Map.Entry<BlockPos, Container> chest : stocks.chests().entrySet()) {
				Trip trip = new Trip(chest.getKey(), colony.core(), fetch.getKey(), fetch.getValue());
				if (chest.getValue().countItem(fetch.getKey()) > 0 && this.isFree(level, colony, trip)) {
					return trip;
				}
			}
		}
		for (Map.Entry<Item, Integer> store : stocks.toStore().entrySet()) {
			BlockPos chest = Storage.chestFor(stocks.chests(), new ItemStack(store.getKey()));
			if (chest != null) {
				Trip trip = new Trip(colony.core(), chest, store.getKey(), store.getValue());
				if (this.isFree(level, colony, trip)) {
					return trip;
				}
				continue;
			}
			// The colony survey orders the chest if there is none (see chestsToOrder).
			BlockPos spot = freeSpots(level, colony, stocks).findFirst().orElse(null);
			Trip place = spot == null ? null : new Trip(colony.core(), spot, Items.CHEST, 1);
			if (place != null && stocks.inCore().getOrDefault(Items.CHEST, 0) > 0 && this.isFree(level, colony, place)) {
				return place;
			}
		}
		return null;
	}

	private static Stream<BlockPos> freeSpots(ServerLevel level, Colony colony, Stocks stocks) {
		// Not where a plan, such as the storage area's own, is still at work.
		Set<BlockPos> open = colony.keepOpen();
		return colony.storage().orElseThrow().chestSpots().stream()
			.filter(pos -> !stocks.chests().containsKey(pos) && level.getBlockState(pos).canBeReplaced() && !open.contains(pos));
	}

	/** How many chests the storage area still needs ordered, beyond the {@code held} chests the colony has to place. */
	public static int chestsToOrder(ServerLevel level, Colony colony, int held) {
		Stocks stocks = colony.storage().isEmpty() ? null : Stocks.of(level, colony);
		if (stocks == null) {
			return 0;
		}
		Set<Item> surplus = new LinkedHashSet<>(stocks.toStore().keySet());
		// What bots carry to store that the core has no room for is surplus too.
		for (UUID id : colony.members()) {
			if (level.getEntity(id) instanceof AntBot bot) {
				SimpleContainer inventory = bot.getInventory();
				for (int i = 0; i < inventory.getContainerSize(); i++) {
					ItemStack stack = inventory.getItem(i);
					if (!stack.isEmpty() && !isWorkingKit(stack) && !Storage.hasRoomFor(stocks.core(), stack)) {
						surplus.add(stack.getItem());
					}
				}
			}
		}
		Set<StorageCategory> full = new HashSet<>();
		for (Item item : surplus) {
			ItemStack stack = new ItemStack(item);
			StorageCategory category = StorageCategory.of(stack);
			if (stocks.chests().values().stream().noneMatch(chest -> !chest.isEmpty() && Storage.category(chest) == category && Storage.hasRoomFor(chest, stack))) {
				full.add(category);
			}
		}
		int empty = (int) stocks.chests().values().stream().filter(Container::isEmpty).count();
		return Stockkeeping.chestsToOrder(full.size(), empty, (int) freeSpots(level, colony, stocks).count(), held);
	}

	private void runTrip(ServerLevel level) {
		Trip trip = this.trip;
		if (++this.tripTicks > TRIP_TICKS) {
			this.trip = null;
			return;
		}
		SimpleContainer inventory = this.bot.getInventory();
		if (!this.loaded) {
			Container from = Storage.containerAt(level, trip.from());
			if (from == null) {
				this.trip = null;
			} else if (this.reach(trip.from())) {
				Storage.moveItems(from, inventory, trip::carries, trip.count());
				this.loaded = true;
			}
			return;
		}
		if (this.reach(trip.to())) {
			Container to = Storage.containerAt(level, trip.to());
			if (to != null) {
				// Whatever does not fit goes back to the core with the next deposit.
				Storage.moveItems(inventory, to, trip::carries);
			} else if (trip.item() == Items.CHEST && level.getBlockState(trip.to()).canBeReplaced()) {
				if (!this.isFreeToPlace(level, trip.to(), Blocks.CHEST.defaultBlockState())) {
					// Waits for the spot to clear; the trip times out if it never does.
					return;
				}
				// ponytail: placed facing north, wherever the hauler stands.
				if (Storage.take(inventory, Items.CHEST, 1) == 1) {
					level.setBlockAndUpdate(trip.to(), Blocks.CHEST.defaultBlockState());
				}
			}
			this.trip = null;
		}
	}
}
