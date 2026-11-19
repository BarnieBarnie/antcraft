package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * {@code fish} (#962; docs/reference/blocks/fish.md) and {@code jobs/fish.py} (docs/cookbook/fish.md). Each test's
 * ground is stone up to y 1, the bot walking at y 2; the pool is water at y 1 over x 3 to 5, z 3 to 5. The rod has
 * Lure III, so a bite comes within some 400 ticks, and the sky is open, as fish bite half as often under a roof.
 */
public class FishGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** The ground, with the pool where {@code pool}, and a bot at (1, 2, 4). */
	private static AntBot ground(GameTestHelper helper, boolean pool) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				boolean water = pool && x >= 3 && x <= 5 && z >= 3 && z <= 5;
				helper.setBlock(x, 1, z, water ? Blocks.WATER : Blocks.STONE);
			}
		}
		return helper.spawn(ModEntities.ANT_BOT, 1, 2, 4);
	}

	private static ItemStack rod(GameTestHelper helper) {
		ItemStack rod = new ItemStack(Items.FISHING_ROD);
		rod.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.LURE), 3);
		return rod;
	}

	/** How many items the bot carries but the one in its hand, its rod. */
	private static int caught(AntBot bot) {
		int n = 0;
		for (ItemStack stack : bot.getInventory().getItems()) {
			n += stack.getCount();
		}
		return n;
	}

	/** Runs {@code source} and succeeds once it printed {@code expected}, no action ever started: it fails at once. */
	private static void refuses(GameTestHelper helper, AntBot bot, String expected) {
		ScriptRun run = BotScripts.run(bot, "r = fish()\nprint(r.ok, r.reason)\n");
		BotScripts.failOnError(helper, bot);
		helper.onEachTick(() -> {
			if (run.action() != null) {
				helper.fail(run.action() + " started instead of failing at once");
			}
		});
		helper.succeedWhen(() -> helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of(expected), "printed"));
	}

	/** A bot with a rod beside the pool casts, reels in on a bite and has the catch, its rod worn by one. */
	// Measured: passes at tick 85 to 307 (red: name 'fish' is not defined); a bite takes up to some 380 ticks with Lure III.
	@GameTest(maxTicks = 900, skyAccess = true)
	public void aBotWithARodBesideWaterCatchesSomething(GameTestHelper helper) {
		AntBot bot = ground(helper, true);
		bot.getInventory().addItem(rod(helper));
		ScriptRun run = BotScripts.run(bot, "r = fish()\nprint(r.ok, r.value)\n");
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "script " + run.state());
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of("True 1"), "printed");
			helper.assertTrue(caught(bot) >= 1, "carries nothing caught");
			helper.assertValueEqual(bot.getMainHandItem().getDamageValue(), 1, "the rod's damage");
		});
	}

	/** A bot with no room for its catch counts none of it and stops with no_space; the catch lies by the pool (#969). */
	// Measured: passes at tick 166 to 240 (red: printed True None 1); a bite takes up to some 380 ticks with Lure III.
	@GameTest(maxTicks = 900, skyAccess = true)
	public void aFullBotCountsNoCatchItHasNoRoomFor(GameTestHelper helper) {
		AntBot bot = ground(helper, true);
		bot.setItemSlot(EquipmentSlot.MAINHAND, rod(helper));
		for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
			bot.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
		}
		int full = caught(bot);
		ScriptRun run = BotScripts.run(bot, "r = fish()\nprint(r.ok, r.reason, r.value)\n");
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "script " + run.state());
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of("False no_space 0"), "printed");
			helper.assertValueEqual(caught(bot), full, "items carried");
		});
	}

	@GameTest(maxTicks = 20)
	public void noRodIsNoRodAtOnce(GameTestHelper helper) {
		refuses(helper, ground(helper, true), "False no_rod");
	}

	@GameTest(maxTicks = 20)
	public void noWaterIsNoWaterAtOnce(GameTestHelper helper) {
		AntBot bot = ground(helper, false);
		bot.getInventory().addItem(rod(helper));
		refuses(helper, bot, "False no_water");
	}

	/** jobs.fish fishes the pool and puts the catch in the chest beside it, keeping its rod. */
	// Measured: passes at tick 130 to 340 (red: No module named 'jobs.fish').
	@GameTest(maxTicks = 1000, skyAccess = true)
	public void theJobStoresItsCatchInAChestBesideTheSpot(GameTestHelper helper) {
		AntBot bot = ground(helper, true);
		bot.getInventory().addItem(rod(helper));
		helper.setBlock(1, 2, 6, Blocks.CHEST);
		BotScripts.run(bot, "import jobs.fish\njobs.fish.run()\n");
		BotScripts.failOnWarning(helper, bot);
		helper.succeedWhen(() -> {
			ChestBlockEntity chest = helper.getBlockEntity(new BlockPos(1, 2, 6), ChestBlockEntity.class);
			helper.assertFalse(chest.isEmpty(), "the chest is empty; said " + BotScripts.log(bot, Output.Level.PRINT));
			helper.assertTrue(bot.getMainHandItem().is(Items.FISHING_ROD), "the bot does not hold its rod");
		});
	}
}
