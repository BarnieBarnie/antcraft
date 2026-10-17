package io.github.barniebarnie.antcraft.work;

import io.github.barniebarnie.antcraft.entity.Tools;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Which pickaxe tier a mining level needs: like a player, a bot only gets drops from a block that
 * needs the correct tool when it has one (stone for iron, copper and lapis; iron for gold,
 * redstone and diamonds).
 */
public final class PickaxeTier {
	/** The ore blocks the items of {@link Mineshaft#ores} come from. */
	private static final Map<Item, Block> ORE_BLOCKS = Map.of(Items.COAL, Blocks.COAL_ORE, Items.RAW_COPPER, Blocks.COPPER_ORE,
		Items.RAW_IRON, Blocks.IRON_ORE, Items.LAPIS_LAZULI, Blocks.LAPIS_ORE, Items.RAW_GOLD, Blocks.GOLD_ORE,
		Items.REDSTONE, Blocks.REDSTONE_ORE, Items.DIAMOND, Blocks.DIAMOND_ORE);
	/** The block each tier's material is mined from; a wooden pickaxe needs none. */
	private static final Map<Item, Block> MADE_FROM = Map.of(Items.STONE_PICKAXE, Blocks.STONE, Items.COPPER_PICKAXE, Blocks.COPPER_ORE,
		Items.IRON_PICKAXE, Blocks.IRON_ORE, Items.DIAMOND_PICKAXE, Blocks.DIAMOND_ORE, Items.NETHERITE_PICKAXE, Blocks.ANCIENT_DEBRIS);

	/** What each ore block drops, deepslate ores included: the items of {@link Mineshaft#ores}, and emeralds. */
	private static final Map<Block, Item> ORE_ITEMS = Map.ofEntries(
		Map.entry(Blocks.COAL_ORE, Items.COAL), Map.entry(Blocks.DEEPSLATE_COAL_ORE, Items.COAL),
		Map.entry(Blocks.COPPER_ORE, Items.RAW_COPPER), Map.entry(Blocks.DEEPSLATE_COPPER_ORE, Items.RAW_COPPER),
		Map.entry(Blocks.IRON_ORE, Items.RAW_IRON), Map.entry(Blocks.DEEPSLATE_IRON_ORE, Items.RAW_IRON),
		Map.entry(Blocks.LAPIS_ORE, Items.LAPIS_LAZULI), Map.entry(Blocks.DEEPSLATE_LAPIS_ORE, Items.LAPIS_LAZULI),
		Map.entry(Blocks.GOLD_ORE, Items.RAW_GOLD), Map.entry(Blocks.DEEPSLATE_GOLD_ORE, Items.RAW_GOLD),
		Map.entry(Blocks.REDSTONE_ORE, Items.REDSTONE), Map.entry(Blocks.DEEPSLATE_REDSTONE_ORE, Items.REDSTONE),
		Map.entry(Blocks.DIAMOND_ORE, Items.DIAMOND), Map.entry(Blocks.DEEPSLATE_DIAMOND_ORE, Items.DIAMOND),
		Map.entry(Blocks.EMERALD_ORE, Items.EMERALD), Map.entry(Blocks.DEEPSLATE_EMERALD_ORE, Items.EMERALD));

	private PickaxeTier() {
	}

	/** What the ore {@code block} drops, or empty if it is no overworld ore. */
	public static Optional<Item> oreItem(Block block) {
		return Optional.ofNullable(ORE_ITEMS.get(block));
	}

	/** Whether breaking {@code state} with {@code tool} (null for none) drops anything. */
	public static boolean canHarvest(@Nullable Item tool, BlockState state) {
		return Tools.canHarvest(tool == null ? ItemStack.EMPTY : new ItemStack(tool), state);
	}

	/** The lowest pickaxe tier that harvests {@code block}, or the best one if none does. */
	public static Item lowestFor(Block block) {
		List<Item> tiers = ToolKind.PICKAXE.tiers();
		return tiers.stream().filter(tier -> canHarvest(tier, block.defaultBlockState())).findFirst().orElse(tiers.getLast());
	}

	/** The lowest pickaxe tier that harvests the ores the colony wants at height {@code y}, or every one there if it wants none. */
	public static Item forLevel(int y, Predicate<Item> wanted) {
		List<Item> ores = Mineshaft.ores(y);
		List<Item> want = ores.stream().filter(wanted).toList();
		List<Item> tiers = ToolKind.PICKAXE.tiers();
		return (want.isEmpty() ? ores : want).stream().map(ore -> lowestFor(ORE_BLOCKS.get(ore)))
			.max(Comparator.comparingInt(tiers::indexOf)).orElse(tiers.getFirst());
	}

	/**
	 * The tier to order on the way to {@code need} for a miner with {@code carried}: {@code need}
	 * itself once {@code carried} mines its material, else the step before it that does.
	 */
	public static Item toOrder(@Nullable Item carried, Item need) {
		Item tier = need;
		while (MADE_FROM.containsKey(tier) && !canHarvest(carried, MADE_FROM.get(tier).defaultBlockState())) {
			tier = lowestFor(MADE_FROM.get(tier));
		}
		return tier;
	}
}
