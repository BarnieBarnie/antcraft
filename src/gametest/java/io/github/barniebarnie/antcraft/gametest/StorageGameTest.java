package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * {@code lib/storage.py} and {@code jobs/store.py} (#572): deposit puts each item with its kind and keeps the tools, full
 * chests leave the items carried and named in missing, sort gathers each item into one chest, and the job deposits and
 * goes home. Each script passes radius 8 so a neighbouring test's chest is never one of its chests.
 */
public class StorageGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/**
	 * An empty chest at (2, 1, 1) next to the bot and one holding a cobblestone at (6, 1, 6): deposit puts the bot's ten
	 * cobblestone in the far chest that has cobblestone, its dirt in the near one, and keeps its pickaxe.
	 */
	// Measured: passes at tick 13.
	@GameTest(maxTicks = 30)
	public void depositPutsCobblestoneWithCobblestoneAndKeepsThePickaxe(GameTestHelper helper) {
		Container near = chest(helper, 2, 1, 1);
		Container far = chest(helper, 6, 1, 6);
		far.setItem(0, new ItemStack(Items.COBBLESTONE));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 10));
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 3));
		run(helper, bot, """
			import lib.storage
			r = lib.storage.deposit(radius=8)
			print(r["ok"], count("cobblestone"), count("dirt"), has("stone_pickaxe"))
			""");
		helper.succeedWhen(() -> {
			finished(helper, bot, List.of("True 0 0 True"));
			helper.assertTrue(far.countItem(Items.COBBLESTONE) == 11, "cobblestone in the far chest: " + far.countItem(Items.COBBLESTONE));
			helper.assertTrue(near.countItem(Items.COBBLESTONE) == 0, "cobblestone in the near chest");
			helper.assertTrue(near.countItem(Items.DIRT) == 3, "dirt in the near chest: " + near.countItem(Items.DIRT));
		});
	}

	/** The one chest is full of stone: deposit keeps the dirt and names it in missing. */
	// Measured: passes at tick 12.
	@GameTest(maxTicks = 30)
	public void fullChestsLeaveTheItemsCarried(GameTestHelper helper) {
		Container full = chest(helper, 6, 1, 6);
		for (int slot = 0; slot < full.getContainerSize(); slot++) {
			full.setItem(slot, new ItemStack(Items.STONE, 64));
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 5));
		run(helper, bot, """
			import lib.storage
			r = lib.storage.deposit(radius=8)
			print(r["ok"], r["reason"], r["missing"], count("dirt"))
			""");
		helper.succeedWhen(() -> finished(helper, bot, List.of("False no_space {'minecraft:dirt': 5} 5")));
	}

	/**
	 * Chest a at (2, 1, 1) holds 10 cobblestone and 5 dirt, chest b at (6, 1, 6) 20 cobblestone and 3 dirt: sort moves the
	 * cobblestone into b, which held more of it, and the dirt into a, and leaves the bot carrying nothing.
	 */
	// Measured: passes at tick 14.
	@GameTest(maxTicks = 30)
	public void sortGathersEachItemIntoOneChest(GameTestHelper helper) {
		Container a = chest(helper, 2, 1, 1);
		a.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
		a.setItem(1, new ItemStack(Items.DIRT, 5));
		Container b = chest(helper, 6, 1, 6);
		b.setItem(0, new ItemStack(Items.COBBLESTONE, 20));
		b.setItem(1, new ItemStack(Items.DIRT, 3));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 3);
		run(helper, bot, """
			import lib.storage
			r = lib.storage.sort([%s, %s])
			print(r["ok"], len(inventory()))
			""".formatted(printed(helper, 2, 1, 1), printed(helper, 6, 1, 6)));
		helper.succeedWhen(() -> {
			finished(helper, bot, List.of("True 0"));
			helper.assertTrue(a.countItem(Items.COBBLESTONE) == 0 && a.countItem(Items.DIRT) == 8,
				"a: " + a.countItem(Items.COBBLESTONE) + " cobblestone, " + a.countItem(Items.DIRT) + " dirt");
			helper.assertTrue(b.countItem(Items.COBBLESTONE) == 30 && b.countItem(Items.DIRT) == 0,
				"b: " + b.countItem(Items.COBBLESTONE) + " cobblestone, " + b.countItem(Items.DIRT) + " dirt");
		});
	}

	/** jobs.store 8 with dirt carried and a chest at (6, 1, 6): the dirt goes in the chest and the bot walks back home. */
	// Measured: passes at tick 43.
	@GameTest(maxTicks = 90)
	public void theJobDepositsAndGoesHome(GameTestHelper helper) {
		Container chest = chest(helper, 6, 1, 6);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 2);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 5));
		BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run jobs.store 8");
		failOnError(helper, bot);
		helper.onEachTick(() -> {
			if (!BotScripts.warnings(bot).isEmpty()) {
				helper.fail("warned: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getScript() != null && bot.getScript().state() == ScriptRun.State.FINISHED, "not finished");
			helper.assertTrue(chest.countItem(Items.DIRT) == 5, "dirt in the chest: " + chest.countItem(Items.DIRT));
			helper.assertTrue(bot.blockPosition().equals(helper.absolutePos(new BlockPos(2, 1, 2))), "not home: " + bot.blockPosition());
		});
	}

	private static Container chest(GameTestHelper helper, int x, int y, int z) {
		helper.setBlock(x, y, z, Blocks.CHEST);
		return (Container) Objects.requireNonNull(helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(x, y, z))));
	}

	private static void run(GameTestHelper helper, AntBot bot, String source) {
		BotScripts.run(bot, source);
		failOnError(helper, bot);
	}

	/** Fails the test as soon as the bot's script fails. */
	private static void failOnError(GameTestHelper helper, AntBot bot) {
		helper.onEachTick(() -> {
			ScriptRun run = bot.getScript();
			if (run != null && run.state() == ScriptRun.State.FAILED) {
				helper.fail("script failed: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
	}

	private static void finished(GameTestHelper helper, AntBot bot, List<String> expected) {
		helper.assertTrue(bot.getScript() != null && bot.getScript().state() == ScriptRun.State.FINISHED, "not finished");
		List<String> printed = BotScripts.log(bot, Output.Level.PRINT);
		helper.assertTrue(printed.equals(expected), "printed " + printed + ", expected " + expected);
	}

	/** The position {@code (x, y, z)} of the test area as a script prints it. */
	private static String printed(GameTestHelper helper, int x, int y, int z) {
		BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
		return "Pos(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
	}
}
