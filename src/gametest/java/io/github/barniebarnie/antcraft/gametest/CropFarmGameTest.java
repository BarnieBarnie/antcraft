package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * The job {@code jobs/crop_farm.py} (#827; docs/cookbook/crop-farm.md): a crop farm at (1, 1, 4) in the 24x8x24
 * {@code wide} structure, its 9x9 cells on a dirt floor (cell 1 from (1, 0, 4) to (9, 0, 12), its water at (5, 0, 8),
 * cell 2 from (11, 0, 4), its water at (15, 0, 8)), its depot a row of chests from (1, 1, 2) east and a chest of
 * supplies at (22, 1, 20).
 */
public class CropFarmGameTest implements CustomTestMethodInvoker {
	private static final BlockPos AT = new BlockPos(1, 1, 4);
	private static final BlockPos DEPOT = new BlockPos(1, 1, 2);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** Wheat seeds, an iron hoe and a water bucket: the bot builds cell 1, levelled, tilled and planted round its water. */
	// Measured: passes at tick 831.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 1700)
	public void theFirstCellIsBuiltAndPlantedRoundItsWater(GameTestHelper helper) {
		AntBot bot = farm(helper, Items.WHEAT_SEEDS, 80, 0);
		run(helper, bot, "wheat", 1);
		helper.succeedWhen(() -> assertCell(helper, 1, Blocks.WHEAT));
	}

	/**
	 * Carrots, their own seed: 80 for cell 1 in the supplies and a cell's worth more in the depot; the bot builds cell 2
	 * with them.
	 */
	// Measured: passes at tick 1851.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 3700)
	public void withACellsWorthOfSeedInTheDepotASecondCellIsBuilt(GameTestHelper helper) {
		AntBot bot = farm(helper, Items.CARROT, 80, 80);
		run(helper, bot, "carrot", 2);
		helper.succeedWhen(() -> {
			helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).contains("built cell 2"),
				"cell 2 not built; said " + BotScripts.log(bot, Output.Level.PRINT));
			assertCell(helper, 1, Blocks.CARROTS);
			assertCell(helper, 11, Blocks.CARROTS);
		});
	}

	/** As above, with 40 carrots in the depot: cell 2 is not built, and the bot says what it lacks. */
	// Measured: passes at tick 1143 to 1155.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 2300)
	public void withoutACellsWorthOfSeedNoSecondCellIsBuilt(GameTestHelper helper) {
		AntBot bot = farm(helper, Items.CARROT, 80, 40);
		run(helper, bot, "carrot", 2);
		helper.succeedWhen(() -> {
			helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).stream().anyMatch(s -> s.startsWith("next cell:")),
				"no next cell line; said " + BotScripts.log(bot, Output.Level.PRINT));
			assertCell(helper, 1, Blocks.CARROTS);
			for (int x = 11; x <= 19; x++) {
				for (int z = 4; z <= 12; z++) {
					helper.assertBlockPresent(Blocks.DIRT, x, 0, z);
					helper.assertBlockPresent(Blocks.AIR, x, 1, z);
				}
			}
		});
	}

	/** Once cell 1 is built the test ripens its wheat: the bot harvests and replants it and stores the 80 wheat. */
	// Measured: passes at tick 1488.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 3000)
	public void aRipeCellIsHarvestedReplantedAndStored(GameTestHelper helper) {
		AntBot bot = farm(helper, Items.WHEAT_SEEDS, 80, 0);
		run(helper, bot, "wheat", 1);
		boolean[] ripe = new boolean[1];
		helper.onEachTick(() -> {
			if (!ripe[0] && BotScripts.log(bot, Output.Level.PRINT).contains("built cell 1")) {
				forEachSpot(1, (x, z) -> helper.setBlock(x, 1, z, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7)));
				ripe[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(ripe[0], "cell 1 not built; said " + BotScripts.log(bot, Output.Level.PRINT));
			helper.assertValueEqual(depot(helper, Items.WHEAT), 80, "wheat in the depot");
			forEachSpot(1, (x, z) -> {
				helper.assertBlockPresent(Blocks.WHEAT, x, 1, z);
				helper.assertTrue(helper.getBlockState(new BlockPos(x, 1, z)).getValue(CropBlock.AGE) < 7, "not replanted at " + x + " " + z);
			});
		});
	}

	/**
	 * A dirt floor under both cells, a chest of supplies with seeds of seed, an iron hoe, two water buckets and two chests,
	 * and, where depot is more than 0, a chest at the depot's first slot holding depot more seeds; the bot by the supplies.
	 */
	private static AntBot farm(GameTestHelper helper, Item seed, int seeds, int depot) {
		for (int x = 1; x <= 19; x++) {
			for (int z = 4; z <= 12; z++) {
				helper.setBlock(x, 0, z, Blocks.DIRT);
			}
		}
		helper.setBlock(22, 1, 20, Blocks.CHEST);
		Container chest = helper.getBlockEntity(new BlockPos(22, 1, 20), ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(seed, 64));
		chest.setItem(1, new ItemStack(seed, seeds - 64));
		chest.setItem(2, new ItemStack(Items.IRON_HOE));
		chest.setItem(3, new ItemStack(Items.WATER_BUCKET));
		chest.setItem(4, new ItemStack(Items.WATER_BUCKET));
		chest.setItem(5, new ItemStack(Items.CHEST, 2));
		if (depot > 0) {
			helper.setBlock(DEPOT, Blocks.CHEST);
			Container chest2 = helper.getBlockEntity(DEPOT, ChestBlockEntity.class);
			chest2.setItem(0, new ItemStack(seed, Math.min(depot, 64)));
			if (depot > 64) {
				chest2.setItem(1, new ItemStack(seed, depot - 64));
			}
		}
		return helper.spawn(ModEntities.ANT_BOT, 20, 1, 18);
	}

	/** Runs jobs.crop_farm of crop at AT, up to cells cells, and fails the test on a script error. */
	private static void run(GameTestHelper helper, AntBot bot, String crop, int cells) {
		BlockPos at = helper.absolutePos(AT);
		List<String> said = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run jobs.crop_farm " + crop + " "
			+ at.getX() + " " + at.getY() + " " + at.getZ() + " " + cells);
		helper.assertTrue(bot.getScript() != null, "not run: " + said);
		BotScripts.failOnError(helper, bot);
	}

	/** The cell from x0: water in its middle, every other spot farmland under crop. */
	private static void assertCell(GameTestHelper helper, int x0, Block crop) {
		helper.assertBlockPresent(Blocks.WATER, x0 + 4, 0, 8);
		forEachSpot(x0, (x, z) -> {
			helper.assertBlockPresent(Blocks.FARMLAND, x, 0, z);
			helper.assertBlockPresent(crop, x, 1, z);
		});
	}

	/** Calls spot with each x, z of the cell from x0 (z 4 to 12) but its middle. */
	private static void forEachSpot(int x0, Spot spot) {
		for (int x = x0; x <= x0 + 8; x++) {
			for (int z = 4; z <= 12; z++) {
				if (x != x0 + 4 || z != 8) {
					spot.at(x, z);
				}
			}
		}
	}

	private interface Spot {
		void at(int x, int z);
	}

	/** How many of item the chests of the depot row, from DEPOT east, hold. */
	private static int depot(GameTestHelper helper, Item item) {
		int n = 0;
		for (int x = DEPOT.getX(); x < 20; x++) {
			BlockPos p = new BlockPos(x, DEPOT.getY(), DEPOT.getZ());
			if (helper.getBlockState(p).is(Blocks.CHEST)) {
				n += helper.getBlockEntity(p, ChestBlockEntity.class).countItem(item);
			}
		}
		return n;
	}
}
