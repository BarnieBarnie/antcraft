package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
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

/**
 * {@code jobs/storekeeper.py} (#1020): hauls what gatherers put in their source chests into the colony store, a
 * lib.depot "storage" from (1, 1, 1) east, each item into the chest of its category.
 */
public class StorekeeperGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/**
	 * A source chest at (5, 1, 5) with oak logs and cobblestone, given as sources: the storekeeper takes both and puts
	 * the logs in the store's logs chest and the cobblestone in its stone chest, each chest one kind.
	 */
	// Measured: passes at tick 6 (red: No module named 'jobs.storekeeper').
	@GameTest(maxTicks = 20)
	public void aSourceChestIsSortedIntoTheStore(GameTestHelper helper) {
		Container source = chestAt(helper, 5, 1, 5);
		source.setItem(0, new ItemStack(Items.OAK_LOG, 10));
		source.setItem(1, new ItemStack(Items.COBBLESTONE, 12));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.CHEST, 2));
		BotScripts.run(bot, "import jobs.storekeeper\njobs.storekeeper.run(%s, \"east\", [%s])\n"
			.formatted(printed(helper, 1, 1, 1), printed(helper, 5, 1, 5)));
		BotScripts.failOnWarning(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(source.isEmpty(), "the source chest still holds items");
			holdsOnly(helper, 1, 1, 1, Items.OAK_LOG, Items.COBBLESTONE);
			holdsOnly(helper, 2, 1, 1, Items.OAK_LOG, Items.COBBLESTONE);
			int logs = chest(helper, 1, 1, 1).countItem(Items.OAK_LOG) + chest(helper, 2, 1, 1).countItem(Items.OAK_LOG);
			int stone = chest(helper, 1, 1, 1).countItem(Items.COBBLESTONE) + chest(helper, 2, 1, 1).countItem(Items.COBBLESTONE);
			helper.assertTrue(logs == 10 && stone == 12, "stored " + logs + " logs and " + stone + " cobblestone");
		});
	}

	/**
	 * Bot A in a colony stores logs and oak saplings in a wood farm's depot (jobs.wood_farm.CATEGORIES) from (1, 1, 5),
	 * which publishes it as a source (#1018). The storekeeper, given no sources, finds it in colony_memory and hauls the
	 * logs to its store at (1, 1, 1); the saplings stay in the farm's chest, for its replanting.
	 */
	// Measured: passes at tick 11 (red: No module named 'jobs.storekeeper').
	@GameTest(maxTicks = 30)
	public void aPublishedDepotIsHauledButItsSaplingsStay(GameTestHelper helper) {
		helper.setBlock(6, 1, 7, ModBlocks.COLONY_CORE);
		ColonyManager colonies = ColonyManager.get(helper.getLevel());
		Colony colony = colonies.byCore(helper.absolutePos(new BlockPos(6, 1, 7))).orElseThrow();
		AntBot farmer = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		AntBot keeper = helper.spawn(ModEntities.ANT_BOT, 4, 1, 3);
		colonies.join(farmer, colony);
		colonies.join(keeper, colony);
		farmer.getInventory().addItem(new ItemStack(Items.OAK_LOG, 9));
		farmer.getInventory().addItem(new ItemStack(Items.OAK_SAPLING, 5));
		farmer.getInventory().addItem(new ItemStack(Items.CHEST, 2));
		keeper.getInventory().addItem(new ItemStack(Items.CHEST, 1));
		BotScripts.run(farmer, """
			import lib.depot
			import jobs.wood_farm
			lib.depot.depot("oak_farm", %s, "east", jobs.wood_farm.CATEGORIES)
			lib.depot.store("oak_farm")
			colony_memory["stored"] = True
			""".formatted(printed(helper, 1, 1, 5)));
		BotScripts.run(keeper, """
			while not colony_memory.get("stored"):
			    wait(1)
			import jobs.storekeeper
			jobs.storekeeper.run(%s)
			""".formatted(printed(helper, 1, 1, 1)));
		BotScripts.failOnWarning(helper, farmer, keeper);
		helper.succeedWhen(() -> {
			Container logs = chest(helper, 1, 1, 5);
			Container saplings = chest(helper, 2, 1, 5);
			helper.assertTrue(logs.isEmpty(), "the farm's logs chest still holds " + logs.countItem(Items.OAK_LOG) + " logs");
			helper.assertTrue(saplings.countItem(Items.OAK_SAPLING) == 5, "the farm's saplings: " + saplings.countItem(Items.OAK_SAPLING));
			helper.assertTrue(chest(helper, 1, 1, 1).countItem(Items.OAK_LOG) == 9, "logs in the store: " + chest(helper, 1, 1, 1).countItem(Items.OAK_LOG));
			helper.assertBlockNotPresent(Blocks.CHEST, 2, 1, 1);
		});
	}

	/**
	 * A source chest full of cobblestone, 27 stacks, more than the bot's 27 slots hold beside its chest: the storekeeper
	 * takes what fits, stores it and comes back for the rest, so the store's stone chest ends up with all of it and
	 * nothing is dropped.
	 */
	// Measured: passes at tick 6, the chests within reach (red: No module named 'jobs.storekeeper').
	@GameTest(maxTicks = 20)
	public void aFullInventoryTakesSeveralTrips(GameTestHelper helper) {
		Container source = chestAt(helper, 5, 1, 5);
		for (int slot = 0; slot < source.getContainerSize(); slot++) {
			source.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.CHEST, 1));
		BotScripts.run(bot, "import jobs.storekeeper\njobs.storekeeper.run(%s, \"east\", [%s])\n"
			.formatted(printed(helper, 1, 1, 1), printed(helper, 5, 1, 5)));
		BotScripts.failOnWarning(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(source.isEmpty(), "the source chest still holds " + source.countItem(Items.COBBLESTONE));
			int stored = chest(helper, 1, 1, 1).countItem(Items.COBBLESTONE);
			helper.assertTrue(stored == 27 * 64, "cobblestone in the store: " + stored);
			helper.assertItemEntityNotPresent(Items.COBBLESTONE);
		});
	}

	/**
	 * Source and store far apart in a wide area: the store from (1, 1, 1) east, the source chest of logs and cobblestone
	 * at (21, 1, 21), the bot by the store. It walks to the source, back to the store, and sorts it all in.
	 */
	// Measured: passes at tick 336, walking about 28 blocks each way.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 700)
	public void aFarSourceIsWalkedToAndBack(GameTestHelper helper) {
		Container source = chestAt(helper, 21, 1, 21);
		source.setItem(0, new ItemStack(Items.OAK_LOG, 10));
		source.setItem(1, new ItemStack(Items.COBBLESTONE, 12));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.CHEST, 2));
		BotScripts.run(bot, "import jobs.storekeeper\njobs.storekeeper.run(%s, \"east\", [%s])\n"
			.formatted(printed(helper, 1, 1, 1), printed(helper, 21, 1, 21)));
		BotScripts.failOnWarning(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(source.isEmpty(), "the source chest still holds items");
			int logs = chest(helper, 1, 1, 1).countItem(Items.OAK_LOG) + chest(helper, 2, 1, 1).countItem(Items.OAK_LOG);
			int stone = chest(helper, 1, 1, 1).countItem(Items.COBBLESTONE) + chest(helper, 2, 1, 1).countItem(Items.COBBLESTONE);
			helper.assertTrue(logs == 10 && stone == 12, "stored " + logs + " logs and " + stone + " cobblestone");
		});
	}

	/**
	 * The store cannot grow: its one chest at (1, 1, 1) holds 26 stacks of cobblestone, the bot no chest and no wood is
	 * near to craft one. The bot hauls the source's 27 stacks, fills the store's last stack and cannot store the rest:
	 * it warns once. A stack put in the source then stays there for a round of waiting and more; given a chest, the bot
	 * places it at (2, 1, 1), stores its load and hauls that stack too, nothing dropped.
	 */
	// Measured: passes at tick 218, the chest given 150 ticks after the warning (a storekeeper that resets warned each
	// round fails it at tick 117, warned twice).
	@GameTest(maxTicks = 450)
	public void aStoreThatCannotGrowWarnsOnceAndGoesOnWhenGivenAChest(GameTestHelper helper) {
		Container store = chestAt(helper, 1, 1, 1);
		for (int slot = 0; slot < 26; slot++) {
			store.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
		}
		Container source = chestAt(helper, 5, 1, 5);
		for (int slot = 0; slot < source.getContainerSize(); slot++) {
			source.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		BotScripts.run(bot, "import jobs.storekeeper\njobs.storekeeper.run(%s, \"east\", [%s])\n"
			.formatted(printed(helper, 1, 1, 1), printed(helper, 5, 1, 5)));
		BotScripts.failOnError(helper, bot);
		int[] warnedAt = {-1}; // the tick of the warning, -1 before it, -2 once the chest is given
		helper.onEachTick(() -> {
			List<String> warnings = BotScripts.warnings(bot);
			if (warnings.size() > 1) {
				helper.fail("warned more than once: " + warnings);
			}
			if (warnings.isEmpty() || warnedAt[0] == -2) {
				return;
			}
			int tick = (int) helper.getTick();
			if (warnedAt[0] == -1) {
				warnedAt[0] = tick;
				source.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
			}
			// More than a round of WAIT (100 ticks) after the warning: still nothing hauled, then the chest is given.
			if (tick < warnedAt[0] + 150) {
				helper.assertTrue(source.countItem(Items.COBBLESTONE) == 64,
					"hauled while the store cannot grow: the source holds " + source.countItem(Items.COBBLESTONE));
				helper.assertBlockNotPresent(Blocks.CHEST, 2, 1, 1);
			} else {
				bot.getInventory().addItem(new ItemStack(Items.CHEST, 1));
				warnedAt[0] = -2;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(warnedAt[0] == -2, "no chest given yet");
			helper.assertTrue(source.isEmpty(), "the source chest still holds " + source.countItem(Items.COBBLESTONE));
			int stored = store.countItem(Items.COBBLESTONE) + chest(helper, 2, 1, 1).countItem(Items.COBBLESTONE);
			helper.assertTrue(stored == 54 * 64, "cobblestone in the store: " + stored);
			helper.assertItemEntityNotPresent(Items.COBBLESTONE);
		});
	}

	/**
	 * keep by depot name and by chest position: bot A in a colony stores oak logs in depot farm_a from (1, 1, 5) and
	 * cobblestone in depot farm_b from (1, 1, 3), both published as sources. The storekeeper, keep {"farm_a": 4 oak
	 * logs, farm_b's chest: 5 cobblestone}, hauls the rest to its store from (1, 1, 1) and leaves exactly those.
	 */
	// Measured: passes at tick 19, green at once.
	@GameTest(maxTicks = 40)
	public void aKeepByDepotNameAndByPositionLeavesExactlyWhatItNames(GameTestHelper helper) {
		helper.setBlock(6, 1, 7, ModBlocks.COLONY_CORE);
		ColonyManager colonies = ColonyManager.get(helper.getLevel());
		Colony colony = colonies.byCore(helper.absolutePos(new BlockPos(6, 1, 7))).orElseThrow();
		AntBot farmer = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		AntBot keeper = helper.spawn(ModEntities.ANT_BOT, 4, 1, 2);
		colonies.join(farmer, colony);
		colonies.join(keeper, colony);
		farmer.getInventory().addItem(new ItemStack(Items.OAK_LOG, 20));
		farmer.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 30));
		farmer.getInventory().addItem(new ItemStack(Items.CHEST, 2));
		keeper.getInventory().addItem(new ItemStack(Items.CHEST, 2));
		BotScripts.run(farmer, """
			import lib.depot
			lib.depot.depot("farm_a", %s, "east", {"logs": ["#minecraft:logs"]})
			lib.depot.store("farm_a", ["minecraft:cobblestone"])
			lib.depot.depot("farm_b", %s, "east", {"stone": ["minecraft:cobblestone"]})
			lib.depot.store("farm_b")
			colony_memory["stored"] = True
			""".formatted(printed(helper, 1, 1, 5), printed(helper, 1, 1, 3)));
		BotScripts.run(keeper, """
			while not colony_memory.get("stored"):
			    wait(1)
			import jobs.storekeeper
			jobs.storekeeper.run(%s, keep={"farm_a": [("minecraft:oak_log", 4)], %s: [("minecraft:cobblestone", 5)]})
			""".formatted(printed(helper, 1, 1, 1), printed(helper, 1, 1, 3)));
		BotScripts.failOnWarning(helper, farmer, keeper);
		helper.succeedWhen(() -> {
			Container logs = chest(helper, 1, 1, 5);
			Container stone = chest(helper, 1, 1, 3);
			helper.assertTrue(logs.countItem(Items.OAK_LOG) == 4, "farm_a keeps " + logs.countItem(Items.OAK_LOG) + " logs");
			helper.assertTrue(stone.countItem(Items.COBBLESTONE) == 5, "farm_b keeps " + stone.countItem(Items.COBBLESTONE) + " cobblestone");
			int storedLogs = chest(helper, 1, 1, 1).countItem(Items.OAK_LOG) + chest(helper, 2, 1, 1).countItem(Items.OAK_LOG);
			int storedStone = chest(helper, 1, 1, 1).countItem(Items.COBBLESTONE) + chest(helper, 2, 1, 1).countItem(Items.COBBLESTONE);
			helper.assertTrue(storedLogs == 16 && storedStone == 25, "stored " + storedLogs + " logs and " + storedStone + " cobblestone");
		});
	}

	/**
	 * CATEGORIES come from item tags (#1025): red wool (#minecraft:wool) and a gold ingot (#c:ingots) each go into a
	 * chest of their own category, not the "other" chest that takes the paper, so the three end up in three chests.
	 */
	// Measured: passes at tick 7 (red: chest (1, 1, 1) holds 2 of the kinds, the wool with the paper in "other").
	@GameTest(maxTicks = 20)
	public void woolAndIngotsAreSortedByTheirTags(GameTestHelper helper) {
		Container source = chestAt(helper, 5, 1, 5);
		source.setItem(0, new ItemStack(Items.WOOL.red(), 3));
		source.setItem(1, new ItemStack(Items.GOLD_INGOT, 2));
		source.setItem(2, new ItemStack(Items.PAPER, 4));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.CHEST, 3));
		BotScripts.run(bot, "import jobs.storekeeper\njobs.storekeeper.run(%s, \"east\", [%s])\n"
			.formatted(printed(helper, 1, 1, 1), printed(helper, 5, 1, 5)));
		BotScripts.failOnWarning(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(source.isEmpty(), "the source chest still holds items");
			for (int x = 1; x <= 3; x++) {
				holdsOnly(helper, x, 1, 1, Items.WOOL.red(), Items.GOLD_INGOT, Items.PAPER);
			}
		});
	}

	/** The chest at (x, y, z) holds no item but those given. */
	private static void holdsOnly(GameTestHelper helper, int x, int y, int z, Item... kinds) {
		Container c = chest(helper, x, y, z);
		int kindsHeld = 0;
		for (Item kind : kinds) {
			kindsHeld += c.countItem(kind) > 0 ? 1 : 0;
		}
		helper.assertTrue(kindsHeld == 1, "chest (" + x + ", " + y + ", " + z + ") holds " + kindsHeld + " of the kinds");
	}

	private static Container chestAt(GameTestHelper helper, int x, int y, int z) {
		helper.setBlock(x, y, z, Blocks.CHEST);
		return chest(helper, x, y, z);
	}

	private static Container chest(GameTestHelper helper, int x, int y, int z) {
		helper.assertBlockPresent(Blocks.CHEST, x, y, z);
		return (Container) helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(x, y, z)));
	}

	/** The position {@code (x, y, z)} of the test area as a script prints it. */
	private static String printed(GameTestHelper helper, int x, int y, int z) {
		BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
		return "Pos(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
	}
}
