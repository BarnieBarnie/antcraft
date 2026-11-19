package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code jobs/bamboo_farm.py} (#831; docs/cookbook/bamboo-farm.md): a bamboo farm on lib.cellfarm, its cells a row of
 * three bamboo on dirt. Each test's farm has its corner at (1, 1, 3), so cell 1 is the floor from (1, 0, 3) to
 * (3, 0, 3), and cell 2 the same at x 5 to 7; the depot's first chest is at (1, 1, 1). A planted bamboo sapling can grow
 * on a random tick while a test runs, so the tests that harvest grow all three stalks of a cell themselves.
 */
public class BambooFarmGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** A supply chest of what one cell takes, the bot by it, and jobs.bamboo_farm run with size cells. */
	private static AntBot farm(GameTestHelper helper, String size) {
		helper.setBlock(6, 1, 6, Blocks.CHEST);
		Container chest = helper.getBlockEntity(new BlockPos(6, 1, 6), ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.BAMBOO, 3));
		chest.setItem(1, new ItemStack(Items.DIRT, 6));
		chest.setItem(2, new ItemStack(Items.STONE_PICKAXE));
		chest.setItem(3, new ItemStack(Items.CHEST, 3)); // the cobblestone and dirt over go in a chest of their own
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		BlockPos at = helper.absolutePos(new BlockPos(1, 1, 3));
		BotScripts.run(bot, """
			import jobs.bamboo_farm
			jobs.bamboo_farm.run(Pos(%d, %d, %d), %s, 8)
			""".formatted(at.getX(), at.getY(), at.getZ(), size));
		BotScripts.failOnWarning(helper, bot);
		return bot;
	}

	/** The bamboo in the depot's chests, along its row east of (1, 1, 1). */
	private static int stored(GameTestHelper helper) {
		int n = 0;
		for (int x = 1; x < 5; x++) {
			if (helper.getBlockState(new BlockPos(x, 1, 1)).is(Blocks.CHEST)) {
				n += helper.getBlockEntity(new BlockPos(x, 1, 1), ChestBlockEntity.class).countItem(Items.BAMBOO);
			}
		}
		return n;
	}

	private static boolean said(AntBot bot, String what) {
		return BotScripts.log(bot, Output.Level.PRINT).stream().anyMatch(l -> l.contains(what));
	}

	/** Bamboo planted at (x, 1, 3) on dirt: a sapling, or the stalk one grew into. */
	private static void assertPlanted(GameTestHelper helper, int x) {
		helper.assertBlockPresent(Blocks.DIRT, x, 0, 3);
		BlockState s = helper.getBlockState(new BlockPos(x, 1, 3));
		helper.assertTrue(s.is(Blocks.BAMBOO_SAPLING) || s.is(Blocks.BAMBOO), "no bamboo at " + x + " 1 3: " + s);
	}

	/** Grows a stalk five high on each spot of the cell from x. */
	private static void grow(GameTestHelper helper, int x) {
		for (int dx = 0; dx < 3; dx++) {
			for (int y = 1; y <= 5; y++) {
				helper.setBlock(x + dx, y, 3, Blocks.BAMBOO);
			}
		}
	}

	/** On a stone floor: the cell's three spots get dirt with bamboo planted on it. */
	// Measured: passes at tick 71 to 73, also under load (red: No module named 'jobs.bamboo_farm').
	@GameTest(maxTicks = 160)
	public void aCellIsPlantedFromBamboo(GameTestHelper helper) {
		AntBot bot = farm(helper, "1");
		helper.succeedWhen(() -> {
			for (int x = 1; x <= 3; x++) {
				assertPlanted(helper, x);
			}
			helper.assertTrue(said(bot, "built cell 1"), "said " + BotScripts.log(bot, Output.Level.PRINT));
		});
	}

	/** Stalks five high in the built cell are cut to their bottom block, the 12 cut picked up and stored in the depot. */
	// Measured: passes at tick 326 to 377 alone, up to 398 in three full runs (#850).
	@GameTest(maxTicks = 780)
	public void tallBambooIsCutToOneAndStored(GameTestHelper helper) {
		AntBot bot = farm(helper, "1");
		boolean[] grown = new boolean[1];
		helper.onEachTick(() -> {
			if (!grown[0] && said(bot, "built cell 1")) {
				grow(helper, 1);
				grown[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(grown[0], "cell 1 not built; said " + BotScripts.log(bot, Output.Level.PRINT));
			for (int x = 1; x <= 3; x++) {
				helper.assertBlockPresent(Blocks.BAMBOO, x, 1, 3);
				helper.assertBlockNotPresent(Blocks.BAMBOO, x, 2, 3);
			}
			int stored = stored(helper);
			// A stalk can grow a block on a random tick before it is cut, so at least the 12 set.
			helper.assertTrue(stored >= 12, "bamboo in the depot " + stored + "; said " + BotScripts.log(bot, Output.Level.PRINT));
		});
	}

	/**
	 * With bamboo for one cell only, the farm builds cell 1, then lacks bamboo for cell 2; bamboo grown in cell 1 is
	 * harvested and stored, and cell 2 is planted from the depot's bamboo, 3 of the 12 cut.
	 */
	// Measured: passes at tick 375 to 505, up to 456 under load (#839).
	@GameTest(maxTicks = 1000)
	public void aSecondCellIsPlantedFromStoredBamboo(GameTestHelper helper) {
		AntBot bot = farm(helper, "2");
		boolean[] grown = new boolean[1];
		helper.onEachTick(() -> {
			if (!grown[0] && said(bot, "built cell 1")) {
				grow(helper, 1);
				grown[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(said(bot, "built cell 2"), "said " + BotScripts.log(bot, Output.Level.PRINT));
			for (int x : new int[] {1, 2, 3, 5, 6, 7}) {
				assertPlanted(helper, x);
			}
			int stored = stored(helper);
			// A stalk that grows, or a sapling of cell 2 that grows, on a random tick can be cut and stored too.
			helper.assertTrue(stored >= 9, "bamboo in the depot " + stored + " of the 12 cut, 3 planted");
		});
	}
}
