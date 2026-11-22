package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import java.lang.reflect.Method;
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
