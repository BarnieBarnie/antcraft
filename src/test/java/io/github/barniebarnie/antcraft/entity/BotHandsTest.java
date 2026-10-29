package io.github.barniebarnie.antcraft.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.barniebarnie.antcraft.work.VanillaData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** How often a bot strikes with what it holds (#417): as a player's attack cooldown, from the item's attack speed. */
class BotHandsTest {
	@BeforeAll
	static void bindComponents() {
		// Loads the data the way a server does, which binds the items' components, their attribute modifiers among them.
		VanillaData.recipeBook();
	}

	@Test
	void strikeTicksFollowTheAttackSpeed() {
		// Bare hand 4 a second, a sword 1.6, a diamond axe 1.0, a wooden one 0.8; a part tick is waited out in full.
		assertEquals(5, BotHands.strikeTicks(4.0));
		assertEquals(13, BotHands.strikeTicks(1.6));
		assertEquals(20, BotHands.strikeTicks(1.0));
		assertEquals(25, BotHands.strikeTicks(0.8));
	}

	@Test
	void attackSpeedIsThatOfTheHeldItem() {
		// The modifiers are floats, so a sword gives 1.5999999.
		assertEquals(4.0, BotHands.attackSpeed(ItemStack.EMPTY), 1e-6);
		assertEquals(1.6, BotHands.attackSpeed(new ItemStack(Items.DIAMOND_SWORD)), 1e-6);
		assertEquals(1.0, BotHands.attackSpeed(new ItemStack(Items.DIAMOND_AXE)), 1e-6);
		assertEquals(0.8, BotHands.attackSpeed(new ItemStack(Items.WOODEN_AXE)), 1e-6);
		// An item without modifiers strikes as a bare hand.
		assertEquals(4.0, BotHands.attackSpeed(new ItemStack(Items.STICK)), 1e-6);
	}
}
