package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.StockPlanner;
import io.github.barniebarnie.antcraft.colony.StockTarget;
import io.github.barniebarnie.antcraft.colony.Stockkeeping;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.work.Storage;
import io.github.barniebarnie.antcraft.work.StorageCategory;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Hauler work loop: pick up loose items around the colony and store them in the core, and keep
 * the core at its working stock. What the core holds above that goes to the storage chests, one
 * category per chest; what the core is short of (stock targets, ingredients for open orders) comes
 * back. Haulers place the storage chests themselves, from chests the colony orders.
 */
public class HaulerGoal extends ColonyWorkGoal {
	private static final int MIN_FREE_SLOTS = 1;
	/** A core with this few free slots left needs a hauler. */
	private static final int NEARLY_FULL_FREE_SLOTS = 4;
	/** A trip that takes longer than this is given up, for example when a chest cannot be reached. */
	private static final int TRIP_TICKS = 1200;

	/** Moving up to {@code count} of an item between containers; a chest carried to an empty spot is placed there. */
	private record Trip(BlockPos from, BlockPos to, Item item, int count) {
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

		Map<Item, Integer> toStore() {
			return Stockkeeping.toStore(this.inCore, this.targets, this.ingredients, Item::getDefaultMaxStackSize);
		}
	}

	private final Set<Integer> unreachableItems = new HashSet<>();
	private boolean depositing;
	private @Nullable Trip trip;
	private boolean loaded;
	private int tripTicks;

	public HaulerGoal(AntBot bot) {
		super(bot, BotRole.HAULER);
	}

	/** Whether the colony needs a hauler: its core is nearly full, or storage holds items the core is short of. */
	public static boolean needsHauler(ServerLevel level, Colony colony) {
		Stocks stocks = Stocks.of(level, colony);
		return stocks != null && (freeSlots(stocks.core()) <= NEARLY_FULL_FREE_SLOTS || !stocks.toFetch().isEmpty());
	}

	@Override
	protected void work(ServerLevel level, Colony colony) {
		if (this.trip != null) {
			this.runTrip(level);
			return;
		}
		if (this.depositing) {
			Container core = Storage.containerAt(level, colony.core());
			if (core == null) {
				this.depositing = false;
				this.cooldown = IDLE_TICKS;
			} else if (this.reach(colony.core())) {
				Storage.moveItems(this.bot.getInventory(), core, stack -> true);
				this.depositing = false;
				this.unreachableItems.clear();
			}
			return;
		}

		SimpleContainer inventory = this.bot.getInventory();
		ItemEntity nearest = freeSlots(inventory) < MIN_FREE_SLOTS ? null : level.getEntitiesOfClass(ItemEntity.class,
				new AABB(colony.core()).inflate(colony.radius()),
				item -> item.isAlive() && !this.unreachableItems.contains(item.getId()) && this.bot.wantsToPickUp(level, item.getItem()))
			.stream()
			.min(Comparator.comparingDouble(this.bot::distanceToSqr))
			.orElse(null);
		if (nearest == null) {
			Container core = Storage.containerAt(level, colony.core());
			// What does not fit in the core waits in the inventory while the hauler makes room.
			if (core != null && hasRoomForAny(core, inventory)) {
				this.depositing = true;
			} else {
				this.trip = this.planTrip(level, colony);
				this.loaded = false;
				this.tripTicks = 0;
				if (this.trip == null) {
					this.cooldown = IDLE_TICKS;
				}
			}
			return;
		}
		if (this.travel(new Goal.Near(nearest.blockPosition(), 0)) == PathFollower.Status.FAILED) {
			this.unreachableItems.add(nearest.getId());
		}
	}

	private static boolean hasRoomForAny(Container core, Container inventory) {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty() && Storage.hasRoomFor(core, stack)) {
				return true;
			}
		}
		return false;
	}

	/** The next item to bring back into the core, else the next to store, or null if the core is in order. */
	private @Nullable Trip planTrip(ServerLevel level, Colony colony) {
		Stocks stocks = colony.storage().isEmpty() ? null : Stocks.of(level, colony);
		if (stocks == null) {
			return null;
		}
		for (Map.Entry<Item, Integer> fetch : stocks.toFetch().entrySet()) {
			if (!Storage.hasRoomFor(stocks.core(), new ItemStack(fetch.getKey()))) {
				continue;
			}
			for (Map.Entry<BlockPos, Container> chest : stocks.chests().entrySet()) {
				if (chest.getValue().countItem(fetch.getKey()) > 0) {
					return new Trip(chest.getKey(), colony.core(), fetch.getKey(), fetch.getValue());
				}
			}
		}
		for (Map.Entry<Item, Integer> store : stocks.toStore().entrySet()) {
			BlockPos chest = chestFor(stocks.chests(), new ItemStack(store.getKey()));
			if (chest != null) {
				return new Trip(colony.core(), chest, store.getKey(), store.getValue());
			}
			BlockPos spot = colony.storage().get().chestSpots().stream()
				.filter(pos -> !stocks.chests().containsKey(pos) && level.getBlockState(pos).canBeReplaced())
				.findFirst().orElse(null);
			if (spot == null) {
				continue;
			}
			if (stocks.inCore().getOrDefault(Items.CHEST, 0) > 0) {
				return new Trip(colony.core(), spot, Items.CHEST, 1);
			}
			if (!colony.orders().containsKey(Items.CHEST)) {
				colony.addOrder(Items.CHEST, 1);
			}
		}
		return null;
	}

	/** A chest of the item's category with room for it, else an empty chest. */
	private static @Nullable BlockPos chestFor(Map<BlockPos, Container> chests, ItemStack stack) {
		StorageCategory category = StorageCategory.of(stack);
		BlockPos empty = null;
		for (Map.Entry<BlockPos, Container> chest : chests.entrySet()) {
			Container container = chest.getValue();
			if (container.isEmpty()) {
				empty = empty == null ? chest.getKey() : empty;
			} else if (category(container) == category && Storage.hasRoomFor(container, stack)) {
				return chest.getKey();
			}
		}
		return empty;
	}

	/** A storage chest holds the category of whatever is in it first. */
	private static StorageCategory category(Container chest) {
		for (int i = 0; i < chest.getContainerSize(); i++) {
			if (!chest.getItem(i).isEmpty()) {
				return StorageCategory.of(chest.getItem(i));
			}
		}
		return StorageCategory.MISC;
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
				Storage.moveItems(from, inventory, stack -> stack.is(trip.item()), trip.count());
				this.loaded = true;
			}
			return;
		}
		if (this.reach(trip.to())) {
			Container to = Storage.containerAt(level, trip.to());
			if (to != null) {
				// Whatever does not fit goes back to the core with the next deposit.
				Storage.moveItems(inventory, to, stack -> stack.is(trip.item()));
			} else if (trip.item() == Items.CHEST && level.getBlockState(trip.to()).canBeReplaced() && Storage.take(inventory, Items.CHEST, 1) == 1) {
				// ponytail: placed facing north, wherever the hauler stands; check for entities in the spot if bots get stuck in chests.
				level.setBlockAndUpdate(trip.to(), Blocks.CHEST.defaultBlockState());
			}
			this.trip = null;
		}
	}
}
