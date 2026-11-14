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
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * {@code lib/depot.py} (#604): a row of chests from (1, 1, 1) east, one category each. store puts each item in a chest of
 * its category and, when they are full, places a new chest at the next slot of the row. Radius 8 keeps a neighbouring
 * test's chests out of lib.get.
 */
public class DepotGameTest implements CustomTestMethodInvoker {
	private static final String DEPOT = """
		import lib.depot
		CATS = {"logs": ["#minecraft:logs"], "saplings": ["#minecraft:saplings"], "sticks": ["minecraft:stick"], "apples": ["minecraft:apple"]}
		lib.depot.depot("wood", %s, "east", CATS)
		""";

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/**
	 * The bot carries logs, saplings, sticks, apples and four chests: store places a chest a category at (1..4, 1, 1) and
	 * puts each item in its own.
	 */
	// Measured: passes at tick 11.
	@GameTest(maxTicks = 30)
	public void storePutsEachCategoryInItsOwnChest(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 5));
		bot.getInventory().addItem(new ItemStack(Items.OAK_SAPLING, 3));
		bot.getInventory().addItem(new ItemStack(Items.STICK, 4));
		bot.getInventory().addItem(new ItemStack(Items.APPLE, 2));
		bot.getInventory().addItem(new ItemStack(Items.CHEST, 4));
		run(helper, bot, DEPOT.formatted(printed(helper, 1, 1, 1)) + """
			r = lib.depot.store("wood", radius=8)
			print(r["ok"], len(inventory()), len(lib.depot.chests("wood")), len(lib.depot.chests("wood", "sticks")))
			""");
		helper.succeedWhen(() -> {
			finished(helper, bot, List.of("True 0 4 1"));
			List<Item> kinds = List.of(Items.OAK_LOG, Items.OAK_SAPLING, Items.STICK, Items.APPLE);
			int[] counts = {5, 3, 4, 2};
			for (int i = 0; i < kinds.size(); i++) {
				Container c = chest(helper, i + 1);
				Item kind = kinds.stream().filter(k -> c.countItem(k) > 0).findFirst().orElse(null);
				helper.assertTrue(kind != null, "chest " + i + " is empty");
				helper.assertTrue(kinds.stream().filter(k -> c.countItem(k) > 0).count() == 1, "chest " + i + " holds more than one kind");
				helper.assertTrue(c.countItem(kind) == counts[kinds.indexOf(kind)], "chest " + i + ": " + c.countItem(kind) + " " + kind);
			}
		});
	}

	/**
	 * A chest full of oak logs at the depot's first slot (1, 1, 1) and ten oak logs carried: store takes the chest as the
	 * logs chest, finds it full, crafts a chest from some of the logs with lib.get and places it at (2, 1, 1) as a second,
	 * single logs chest, and puts the rest there.
	 */
	// Measured: passes at tick 93.
	@GameTest(maxTicks = 190)
	public void aFullChestGrowsTheRow(GameTestHelper helper) {
		helper.setBlock(1, 1, 1, Blocks.CHEST);
		Container full = chest(helper, 1);
		for (int slot = 0; slot < full.getContainerSize(); slot++) {
			full.setItem(slot, new ItemStack(Items.OAK_LOG, 64));
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 10));
		run(helper, bot, DEPOT.formatted(printed(helper, 1, 1, 1)) + """
			r = lib.depot.store("wood", radius=8)
			print(r["ok"], count("oak_log"), len(lib.depot.chests("wood", "logs")))
			""");
		helper.succeedWhen(() -> {
			finished(helper, bot, List.of("True 0 2"));
			helper.assertBlockPresent(Blocks.CHEST, 2, 1, 1);
			helper.assertTrue(helper.getBlockState(new BlockPos(2, 1, 1)).getValue(ChestBlock.TYPE) == ChestType.SINGLE, "the new chest is part of a double chest");
			helper.assertTrue(chest(helper, 2).countItem(Items.OAK_LOG) > 0, "no logs in the new chest");
			helper.assertTrue(full.countItem(Items.OAK_LOG) == 27 * 64, "the full chest: " + full.countItem(Items.OAK_LOG));
		});
	}

	/** The chest at (x, 1, 1). */
	private static Container chest(GameTestHelper helper, int x) {
		helper.assertBlockPresent(Blocks.CHEST, x, 1, 1);
		return (Container) helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(x, 1, 1)));
	}

	private static void run(GameTestHelper helper, AntBot bot, String source) {
		BotScripts.run(bot, source);
		helper.onEachTick(() -> {
			ScriptRun run = bot.getScript();
			if (run != null && run.state() == ScriptRun.State.FAILED || !BotScripts.warnings(bot).isEmpty()) {
				helper.fail("script failed or warned: " + BotScripts.log(bot, Output.Level.DEBUG));
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
