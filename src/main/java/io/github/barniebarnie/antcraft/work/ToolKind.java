package io.github.barniebarnie.antcraft.work;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.ToolUpgrade;
import io.github.barniebarnie.antcraft.entity.AntBot;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * A kind of tool bots work with (pickaxes, axes, hoes) and its tiers, worst first, with the
 * in-world side of {@link ToolUpgrade}: which one a bot carries, swapping it for a better one from
 * storage, and ordering one from the colony. Tiers the colony cannot craft yet (copper and iron need
 * smelting, netherite smithing) are listed so they rank right; they are only ordered once their
 * materials can be made from the core's stock.
 */
public record ToolKind(TagKey<Item> tag, List<Item> tiers) {
	public static final ToolKind PICKAXE = new ToolKind(ItemTags.PICKAXES, List.of(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE,
		Items.COPPER_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE));
	public static final ToolKind AXE = new ToolKind(ItemTags.AXES, List.of(Items.WOODEN_AXE, Items.STONE_AXE, Items.COPPER_AXE,
		Items.IRON_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE));
	public static final ToolKind HOE = new ToolKind(ItemTags.HOES, List.of(Items.WOODEN_HOE, Items.STONE_HOE, Items.COPPER_HOE,
		Items.IRON_HOE, Items.DIAMOND_HOE, Items.NETHERITE_HOE));

	/** The kind {@code item} is a tier of, if any. */
	public static Optional<ToolKind> of(Item item) {
		return Stream.of(PICKAXE, AXE, HOE).filter(kind -> kind.tiers().contains(item)).findFirst();
	}

	public boolean is(ItemStack stack) {
		return stack.is(this.tag);
	}

	public int rank(@Nullable Item tool) {
		return ToolUpgrade.rank(this.tiers, tool);
	}

	/** The best-ranked tool of this kind in the bot's hand or inventory, or null without one. */
	public @Nullable Item bestCarried(AntBot bot) {
		Item best = null;
		SimpleContainer inventory = bot.getInventory();
		for (int i = -1; i < inventory.getContainerSize(); i++) {
			ItemStack stack = i < 0 ? bot.getMainHandItem() : inventory.getItem(i);
			if (this.is(stack) && (best == null || this.rank(stack.getItem()) > this.rank(best))) {
				best = stack.getItem();
			}
		}
		return best;
	}

	/** The best tool in {@code stock} that beats {@code current}. */
	public Optional<Item> betterIn(Map<Item, Integer> stock, @Nullable Item current) {
		return ToolUpgrade.bestInStock(this.tiers, current, stock);
	}

	/**
	 * The best tool in {@code storage} that beats what the bot carries, to go and fetch. If there
	 * is none and the bot is in {@code colony}, orders the best tier the colony can make instead.
	 * A bot without a tool of this kind only fetches one, unless it {@code needs} one now: a job that
	 * can be done without one orders none. It leaves a tool another member's job declares and that
	 * member lacks, and orders no pickaxe above the best one a job on the board declares.
	 */
	public Optional<Item> findUpgrade(ServerLevel level, AntBot bot, Container storage, @Nullable Colony colony) {
		return this.findUpgrade(level, bot, storage, colony, () -> false);
	}

	public Optional<Item> findUpgrade(ServerLevel level, AntBot bot, Container storage, @Nullable Colony colony, BooleanSupplier needs) {
		Item current = this.bestCarried(bot);
		Map<Item, Integer> stock = Storage.stock(storage);
		if (colony == null) {
			return this.betterIn(stock, current);
		}
		// Not one another bot's job waits for.
		List<Item> waiting = colony.members().stream().filter(id -> !id.equals(bot.getUUID()))
			.flatMap(id -> colony.board().jobOf(id).flatMap(Job::tool).filter(this.tiers::contains)
				.filter(tool -> level.getEntity(id) instanceof AntBot other && this.rank(this.bestCarried(other)) < this.rank(tool)).stream())
			.toList();
		Optional<Item> better = this.betterIn(ToolUpgrade.unreserved(this.tiers, stock, waiting), current);
		if (better.isEmpty() && (current != null || needs.getAsBoolean())) {
			// A pickaxe's tier is what jobs need it for (the ores it harvests), so none better than a job on the board
			// declares; stock targets order better ones for the core. Axes and hoes upgrade as far as the colony can make.
			Item ceiling = this != PICKAXE ? null : colony.board().jobs().stream().flatMap(job -> job.tool().stream())
				.filter(this.tiers::contains).max(Comparator.comparingInt(this::rank)).orElse(this.tiers.getFirst());
			ToolUpgrade.toOrder(this.tiers, current, this.spare(level, colony), colony.orders(), GameRecipeBook.of(level.getServer()),
				Gatherables::isGatherable, ceiling).ifPresent(tool -> colony.addOrder(tool, 1));
		}
		return better;
	}

	/** What the colony {@linkplain Storage#spare holds} beyond the tool of this kind each member works with. */
	public Map<Item, Integer> spare(ServerLevel level, Colony colony) {
		return Storage.spare(level, colony, this::bestCarried);
	}

	/** Orders the best tier above {@code current} the colony can make, unless a better one is already in stock or on order. */
	public void order(ServerLevel level, Colony colony, @Nullable Item current, Map<Item, Integer> stock) {
		ToolUpgrade.toOrder(this.tiers, current, stock, colony.orders(), GameRecipeBook.of(level.getServer()), Gatherables::isGatherable)
			.ifPresent(tool -> colony.addOrder(tool, 1));
	}

	/**
	 * Takes one {@code target} from {@code storage} and leaves the tools of this kind it replaces
	 * there. Does nothing if someone else took it first.
	 */
	public void swap(AntBot bot, Container storage, Item target) {
		int slot = -1;
		for (int i = 0; i < storage.getContainerSize() && slot < 0; i++) {
			if (storage.getItem(i).is(target)) {
				slot = i;
			}
		}
		if (slot < 0) {
			return;
		}
		SimpleContainer inventory = bot.getInventory();
		ItemStack rest = inventory.addItem(storage.removeItem(slot, 1));
		if (!rest.isEmpty()) {
			HopperBlockEntity.addItem(null, storage, rest, null);
			storage.setChanged();
			return;
		}
		int rank = this.rank(target);
		ItemStack hand = bot.getMainHandItem();
		if (this.is(hand) && this.rank(hand.getItem()) < rank) {
			bot.setItemSlot(EquipmentSlot.MAINHAND, HopperBlockEntity.addItem(null, storage, hand, null));
		}
		Storage.moveItems(inventory, storage, stack -> this.is(stack) && this.rank(stack.getItem()) < rank);
	}
}
