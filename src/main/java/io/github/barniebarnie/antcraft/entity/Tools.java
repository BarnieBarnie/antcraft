package io.github.barniebarnie.antcraft.entity;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.block.state.BlockState;

/** Picking the right item from a bot's inventory for a job. */
public final class Tools {
	private Tools() {
	}

	/** Higher is better: being able to harvest the block matters most, then speed. */
	public static float toolScore(ItemStack stack, BlockState state) {
		boolean canHarvest = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
		return (canHarvest ? 1000.0F : 0.0F) + stack.getDestroySpeed(state);
	}

	public static float weaponScore(ItemStack stack) {
		return (float) stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
			.compute(Attributes.ATTACK_DAMAGE, 0.0, EquipmentSlot.MAINHAND);
	}

	/** The best item for breaking {@code state}: the held item or one from the inventory. */
	public static ItemStack bestToolFor(SimpleContainer inventory, ItemStack hand, BlockState state) {
		ItemStack best = hand;
		float bestScore = toolScore(hand, state);
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			float score = stack.isEmpty() ? -1 : toolScore(stack, state);
			if (score > bestScore) {
				best = stack;
				bestScore = score;
			}
		}
		return best;
	}

	/** Moves the best tool for {@code state} into the bot's hand, putting the old held item in its place. */
	public static void equipBestTool(AntBot bot, BlockState state) {
		equipBest(bot, stack -> toolScore(stack, state));
	}

	public static void equipBestWeapon(AntBot bot) {
		equipBest(bot, Tools::weaponScore);
	}

	private static void equipBest(AntBot bot, java.util.function.ToDoubleFunction<ItemStack> score) {
		SimpleContainer inventory = bot.getInventory();
		ItemStack hand = bot.getMainHandItem();
		double bestScore = score.applyAsDouble(hand);
		int bestSlot = -1;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty() && score.applyAsDouble(stack) > bestScore) {
				bestScore = score.applyAsDouble(stack);
				bestSlot = i;
			}
		}
		if (bestSlot >= 0) {
			ItemStack best = inventory.getItem(bestSlot);
			bot.setItemSlot(EquipmentSlot.MAINHAND, best);
			inventory.setItem(bestSlot, hand);
		}
	}
}
