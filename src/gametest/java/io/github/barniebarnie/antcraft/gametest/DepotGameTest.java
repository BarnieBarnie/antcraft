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
 * its category and, when they are full, places a new chest at the next slot of the row. A neighbouring test's
 * chests are out of every scan (GameTests.hides).
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
		ScriptRun job = run(helper, bot, DEPOT.formatted(printed(helper, 1, 1, 1)) + """
			r = lib.depot.store("wood")
			print(r["ok"], len(inventory()), len(lib.depot.chests("wood")), len(lib.depot.chests("wood", "sticks")))
			""");
		helper.succeedWhen(() -> {
			finished(helper, job, bot, List.of("True 0 4 1"));
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
	 * The bot carries 100 dirt and a chest and keeps a stack of dirt: store puts the other 36 in a chest (#762); a second
	 * store keeping 32 puts 32 more in that chest. The module's room (#765) shadowed the room native store calls for a
	 * chest the depot already has, so every store after a depot's first crashed (#769's follow-up).
	 */
	// Measured: passes at tick 11.
	@GameTest(maxTicks = 30)
	public void storeKeepsAStackOfDirtAndStoresTheRest(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 64));
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 36));
		bot.getInventory().addItem(new ItemStack(Items.CHEST, 1));
		ScriptRun job = run(helper, bot, DEPOT.formatted(printed(helper, 1, 1, 1)) + """
			r = lib.depot.store("wood", [("minecraft:dirt", 64)])
			print(r["ok"], count("minecraft:dirt"))
			r = lib.depot.store("wood", [("minecraft:dirt", 32)])
			print(r["ok"], count("minecraft:dirt"))
			""");
		helper.succeedWhen(() -> {
			finished(helper, job, bot, List.of("True 64", "True 32"));
			helper.assertTrue(chest(helper, 1).countItem(Items.DIRT) == 68, "chest dirt " + chest(helper, 1).countItem(Items.DIRT));
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
		ScriptRun job = run(helper, bot, DEPOT.formatted(printed(helper, 1, 1, 1)) + """
			r = lib.depot.store("wood")
			print(r["ok"], count("oak_log"), len(lib.depot.chests("wood", "logs")))
			""");
		helper.succeedWhen(() -> {
			finished(helper, job, bot, List.of("True 0 2"));
			helper.assertBlockPresent(Blocks.CHEST, 2, 1, 1);
			helper.assertTrue(helper.getBlockState(new BlockPos(2, 1, 1)).getValue(ChestBlock.TYPE) == ChestType.SINGLE, "the new chest is part of a double chest");
			helper.assertTrue(chest(helper, 2).countItem(Items.OAK_LOG) > 0, "no logs in the new chest");
			helper.assertTrue(full.countItem(Items.OAK_LOG) == 27 * 64, "the full chest: " + full.countItem(Items.OAK_LOG));
		});
	}

	/**
	 * A chest full of oak logs at the depot's first slot and ten oak logs carried, but no chest to be got (lib.get
	 * fails): store gives the reason and the logs it kept, and the bot still carries them; none is dropped (#763).
	 */
	// Measured: passes at tick 5; it passed before the change too, as store already kept what found no room.
	@GameTest(maxTicks = 20)
	public void aFullDepotThatCannotGrowKeepsTheItemsCarried(GameTestHelper helper) {
		helper.setBlock(1, 1, 1, Blocks.CHEST);
		Container full = chest(helper, 1);
		for (int slot = 0; slot < full.getContainerSize(); slot++) {
			full.setItem(slot, new ItemStack(Items.OAK_LOG, 64));
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 10));
		ScriptRun job = run(helper, bot, DEPOT.formatted(printed(helper, 1, 1, 1)) + """
			import lib.get
			import lib.result
			lib.get.get = lambda item, n, radius: lib.result.result("no_item", None, {item: n})
			r = lib.depot.store("wood")
			print(r["ok"], r["reason"], r["missing"], count("oak_log"))
			""");
		helper.succeedWhen(() -> {
			finished(helper, job, bot, List.of("False no_item {'minecraft:oak_log': 10} 10"));
			helper.assertItemEntityNotPresent(Items.OAK_LOG);
			helper.assertTrue(full.countItem(Items.OAK_LOG) == 27 * 64, "the full chest: " + full.countItem(Items.OAK_LOG));
		});
	}

	/**
	 * One oak log carried, too few for a chest, and no chest to be got (lib.get counted and failing): of 9 stores only
	 * the 1st, 3rd and 6th try lib.get, each failure doubling the stores skipped (#787). Then the test gives the bot 9
	 * more logs: the next store, though within its backoff, gets the chest with the real lib.get and stores the rest.
	 */
	// Measured: passes at tick 97 (red: printed 9, every store tried lib.get).
	@GameTest(maxTicks = 190)
	public void aDepotThatCannotGetAChestBacksOffUntilItHasTheLogs(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 1));
		ScriptRun job = run(helper, bot, DEPOT.formatted(printed(helper, 1, 1, 1)) + """
			import lib.get
			import lib.result
			real = lib.get.get
			calls = []
			def failing(item, n, radius):
			    calls.append(item)
			    return lib.result.result("no_item", None, {item: n})
			lib.get.get = failing
			for _ in range(9):
			    lib.depot.store("wood")
			print(len(calls))
			lib.get.get = real
			while count("minecraft:oak_log") < 10:
			    wait(1)
			r = lib.depot.store("wood")
			print(r["ok"], count("minecraft:oak_log"), len(lib.depot.chests("wood", "logs")))
			""");
		boolean[] given = {false};
		helper.onEachTick(() -> {
			if (!given[0] && BotScripts.log(bot, Output.Level.PRINT).size() == 1) {
				given[0] = true;
				bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 9));
			}
		});
		helper.succeedWhen(() -> {
			finished(helper, job, bot, List.of("3", "True 0 1"));
			helper.assertTrue(chest(helper, 1).countItem(Items.OAK_LOG) > 0, "no logs in the chest");
		});
	}

	/**
	 * Dirt at the depot's first slot (1, 1, 1), which store may not dig: it skips the slot and places the logs chest at
	 * (2, 1, 1), leaving the dirt.
	 */
	// Measured: passes at tick 6.
	@GameTest(maxTicks = 20)
	public void aBlockedSlotIsSkipped(GameTestHelper helper) {
		helper.setBlock(1, 1, 1, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 5));
		bot.getInventory().addItem(new ItemStack(Items.CHEST, 1));
		ScriptRun job = run(helper, bot, DEPOT.formatted(printed(helper, 1, 1, 1)) + """
			r = lib.depot.store("wood")
			print(r["ok"], count("oak_log"), len(lib.depot.chests("wood")))
			""");
		helper.succeedWhen(() -> {
			finished(helper, job, bot, List.of("True 0 1"));
			helper.assertBlockPresent(Blocks.DIRT, 1, 1, 1);
			helper.assertTrue(chest(helper, 2).countItem(Items.OAK_LOG) == 5, "logs in the chest: " + chest(helper, 2).countItem(Items.OAK_LOG));
		});
	}

	/**
	 * Dirt in every slot of the row from (1, 1, 1) to (20, 1, 1), more than two calls skip: add_chest gives blocked and
	 * leaves the row's next slot where it was, so repeated calls do not creep the depot along the row (#765).
	 */
	// Measured: passes at tick 21 (red: next 9 then 18). The script finishes in the wide structure too: 5 of 5 runs (#774).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 45)
	public void aBlockedRowLeavesItsNextSlot(GameTestHelper helper) {
		for (int x = 1; x <= 20; x++) {
			helper.setBlock(x, 1, 1, Blocks.DIRT);
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.CHEST, 1));
		ScriptRun job = run(helper, bot, DEPOT.formatted(printed(helper, 1, 1, 1)) + """
			d = lib.depot.depot("wood")
			a = lib.depot.add_chest("wood", "logs")
			first = d.get("next")
			b = lib.depot.add_chest("wood", "logs")
			print(a["reason"], b["reason"], first, d.get("next"))
			""");
		helper.succeedWhen(() -> finished(helper, job, bot, List.of("blocked blocked 0 0")));
	}

	/**
	 * A chest of cobblestone at the depot's first slot (1, 1, 1): store does not take it over for the logs but skips it
	 * and places the logs chest at (2, 1, 1).
	 */
	// Measured: passes at tick 5.
	@GameTest(maxTicks = 20)
	public void aChestOfOtherItemsIsNotTakenOver(GameTestHelper helper) {
		helper.setBlock(1, 1, 1, Blocks.CHEST);
		Container foreign = chest(helper, 1);
		foreign.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 5));
		bot.getInventory().addItem(new ItemStack(Items.CHEST, 1));
		ScriptRun job = run(helper, bot, DEPOT.formatted(printed(helper, 1, 1, 1)) + """
			r = lib.depot.store("wood")
			print(r["ok"], count("oak_log"), len(lib.depot.chests("wood")))
			""");
		helper.succeedWhen(() -> {
			finished(helper, job, bot, List.of("True 0 1"));
			helper.assertTrue(foreign.countItem(Items.OAK_LOG) == 0, "logs in the cobblestone chest");
			helper.assertTrue(foreign.countItem(Items.COBBLESTONE) == 10, "cobblestone: " + foreign.countItem(Items.COBBLESTONE));
			helper.assertTrue(chest(helper, 2).countItem(Items.OAK_LOG) == 5, "logs in the new chest: " + chest(helper, 2).countItem(Items.OAK_LOG));
		});
	}

	/** The chest at (x, 1, 1). */
	private static Container chest(GameTestHelper helper, int x) {
		helper.assertBlockPresent(Blocks.CHEST, x, 1, 1);
		return (Container) helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(x, 1, 1)));
	}

	private static ScriptRun run(GameTestHelper helper, AntBot bot, String source) {
		ScriptRun job = BotScripts.run(bot, source);
		BotScripts.failOnWarning(helper, bot);
		return job;
	}

	private static void finished(GameTestHelper helper, ScriptRun job, AntBot bot, List<String> expected) {
		helper.assertTrue(job.state() == ScriptRun.State.FINISHED, "not finished: " + job.state());
		List<String> printed = BotScripts.log(bot, Output.Level.PRINT);
		helper.assertTrue(printed.equals(expected), "printed " + printed + ", expected " + expected);
	}

	/** The position {@code (x, y, z)} of the test area as a script prints it. */
	private static String printed(GameTestHelper helper, int x, int y, int z) {
		BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
		return "Pos(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
	}
}
