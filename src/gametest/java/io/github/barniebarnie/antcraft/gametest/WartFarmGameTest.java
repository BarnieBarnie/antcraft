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
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code jobs/wart_farm.py} (#834; docs/cookbook/wart-farm.md): a nether wart farm on lib.cellfarm, its cells a row of
 * three nether wart on soul sand. Each test's farm has its corner at (1, 1, 3), so cell 1 is the floor from (1, 0, 3) to
 * (3, 0, 3), and cell 2 the same at x 5 to 7; the depot's first chest is at (1, 1, 1). Planted wart can age on a random
 * tick while a test runs, so the tests that harvest ripen all three of a cell themselves.
 */
public class WartFarmGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** A supply chest of soulSand soul sand and three wart, the bot by it, and jobs.wart_farm run with size cells. */
	private static AntBot farm(GameTestHelper helper, int soulSand, String size) {
		helper.setBlock(6, 1, 6, Blocks.CHEST);
		Container chest = helper.getBlockEntity(new BlockPos(6, 1, 6), ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.NETHER_WART, 3));
		chest.setItem(1, new ItemStack(Items.SOUL_SAND, soulSand));
		chest.setItem(2, new ItemStack(Items.STONE_PICKAXE));
		chest.setItem(3, new ItemStack(Items.CHEST, 3)); // the cobblestone dug goes in a chest of its own
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		BlockPos at = helper.absolutePos(new BlockPos(1, 1, 3));
		BotScripts.run(bot, """
			import jobs.wart_farm
			jobs.wart_farm.run(Pos(%d, %d, %d), %s, 8)
			""".formatted(at.getX(), at.getY(), at.getZ(), size));
		BotScripts.failOnError(helper, bot);
		return bot;
	}

	/** The wart in the depot's chests, along its row east of (1, 1, 1). */
	private static int stored(GameTestHelper helper) {
		int n = 0;
		for (int x = 1; x < 5; x++) {
			if (helper.getBlockState(new BlockPos(x, 1, 1)).is(Blocks.CHEST)) {
				n += helper.getBlockEntity(new BlockPos(x, 1, 1), ChestBlockEntity.class).countItem(Items.NETHER_WART);
			}
		}
		return n;
	}

	private static boolean said(AntBot bot, String what) {
		return BotScripts.log(bot, Output.Level.PRINT).stream().anyMatch(l -> l.contains(what));
	}

	/** Wart planted at (x, 1, 3) on soul sand, below age at most. */
	private static void assertPlanted(GameTestHelper helper, int x, int most) {
		helper.assertBlockPresent(Blocks.SOUL_SAND, x, 0, 3);
		BlockState s = helper.getBlockState(new BlockPos(x, 1, 3));
		helper.assertTrue(s.is(Blocks.NETHER_WART) && s.getValue(NetherWartBlock.AGE) <= most,
			"no wart of age " + most + " or less at " + x + " 1 3: " + s);
	}

	/** Ripens the wart of the cell from x: age 3. */
	private static void ripen(GameTestHelper helper, int x) {
		for (int dx = 0; dx < 3; dx++) {
			helper.setBlock(x + dx, 1, 3, Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, 3));
		}
	}

	/** Ripens cell 1 once the bot said it built it. */
	private static boolean[] ripenWhenBuilt(GameTestHelper helper, AntBot bot) {
		boolean[] ripe = new boolean[1];
		helper.onEachTick(() -> {
			if (!ripe[0] && said(bot, "built cell 1")) {
				ripen(helper, 1);
				ripe[0] = true;
			}
		});
		return ripe;
	}

	/** On a stone floor: the cell's three spots get soul sand with wart planted on it. */
	// Measured: passes at tick 69 to 70 (red: No module named 'jobs.wart_farm').
	@GameTest(maxTicks = 160)
	public void aCellIsBuiltOnSoulSandAndPlanted(GameTestHelper helper) {
		AntBot bot = farm(helper, 3, "1");
		helper.succeedWhen(() -> {
			for (int x = 1; x <= 3; x++) {
				assertPlanted(helper, x, 3);
			}
			helper.assertTrue(said(bot, "built cell 1"), "said " + BotScripts.log(bot, Output.Level.PRINT));
		});
	}

	/** Ripe wart in the built cell is harvested, replanted young and the rest (each drops 2 to 4) stored in the depot. */
	// Measured: passes at tick 185 to 235.
	@GameTest(maxTicks = 500)
	public void ripeWartIsHarvestedReplantedAndStored(GameTestHelper helper) {
		AntBot bot = farm(helper, 3, "1");
		boolean[] ripe = ripenWhenBuilt(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(ripe[0], "cell 1 not built; said " + BotScripts.log(bot, Output.Level.PRINT));
			for (int x = 1; x <= 3; x++) {
				// Replanted at age 0; a random tick can age it a stage, never to ripe in a test's time.
				assertPlanted(helper, x, 2);
			}
			int stored = stored(helper);
			// 3 wart drop at least 6; 3 of them are replanted.
			helper.assertTrue(stored >= 3, "wart in the depot " + stored + "; said " + BotScripts.log(bot, Output.Level.PRINT));
		});
	}

	/**
	 * With wart for one cell only and soul sand for two, the farm builds cell 1, then lacks wart for cell 2; ripe wart of
	 * cell 1 is harvested and stored, and cell 2 is built from the stored wart and the chest's soul sand.
	 */
	// Measured: passes at tick 267 to 317.
	@GameTest(maxTicks = 700)
	public void aSecondCellIsBuiltFromStoredWartAndSoulSand(GameTestHelper helper) {
		AntBot bot = farm(helper, 6, "2");
		ripenWhenBuilt(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(said(bot, "built cell 2"), "said " + BotScripts.log(bot, Output.Level.PRINT));
			for (int x : new int[] {1, 2, 3, 5, 6, 7}) {
				assertPlanted(helper, x, 2);
			}
		});
	}
}
