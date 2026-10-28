package io.github.barniebarnie.antcraft.work;

import io.github.barniebarnie.antcraft.entity.AntBot;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/** A kind of tool bots work with (pickaxes, axes, hoes) and its tiers, worst first, and which one a bot carries. */
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

	/** Where {@code tool} ranks among the tiers, worst first: -1 for none, 0 for a tool of this kind that is no tier. */
	public int rank(@Nullable Item tool) {
		if (tool == null) {
			return -1;
		}
		return Math.max(this.tiers.indexOf(tool), 0);
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
}
