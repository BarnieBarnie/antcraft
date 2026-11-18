package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
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

/**
 * {@code jobs/cane_farm.py} (#829; docs/cookbook/cane-farm.md): a sugarcane farm on lib.cellfarm, its cells a water
 * block in the floor with cane on dirt either side. Each test's farm has its corner at (1, 1, 3), so cell 1 is the floor
 * from (1, 0, 3) to (3, 0, 3), water at (2, 0, 3), and cell 2 the same at x 5 to 7; the depot's first chest is at (1, 1, 1).
 */
public class CaneFarmGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** A supply chest of what one cell takes, the bot by it, and jobs.cane_farm run with size cells. */
	private static AntBot farm(GameTestHelper helper, int cane, String size) {
		helper.setBlock(6, 1, 6, Blocks.CHEST);
		Container chest = helper.getBlockEntity(new BlockPos(6, 1, 6), ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.SUGAR_CANE, cane));
		chest.setItem(1, new ItemStack(Items.DIRT, 4));
		chest.setItem(2, new ItemStack(Items.WATER_BUCKET));
		chest.setItem(3, new ItemStack(Items.WATER_BUCKET));
		chest.setItem(4, new ItemStack(Items.STONE_PICKAXE));
		chest.setItem(5, new ItemStack(Items.CHEST, 3)); // the cobblestone and dirt over go in a chest of their own
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		BlockPos at = helper.absolutePos(new BlockPos(1, 1, 3));
		BotScripts.run(bot, """
			import jobs.cane_farm
			jobs.cane_farm.run(Pos(%d, %d, %d), %s, 8)
			""".formatted(at.getX(), at.getY(), at.getZ(), size));
		BotScripts.failOnError(helper, bot);
		return bot;
	}

	/** The cane in the depot's chests, along its row east of (1, 1, 1). */
	private static int stored(GameTestHelper helper) {
		int n = 0;
		for (int x = 1; x < 5; x++) {
			if (helper.getBlockState(new BlockPos(x, 1, 1)).is(Blocks.CHEST)) {
				n += helper.getBlockEntity(new BlockPos(x, 1, 1), ChestBlockEntity.class).countItem(Items.SUGAR_CANE);
			}
		}
		return n;
	}

	private static boolean said(AntBot bot, String what) {
		return BotScripts.log(bot, Output.Level.PRINT).stream().anyMatch(l -> l.contains(what));
	}

	/** On a stone floor: the cell's middle is dug out and holds water, dirt either side with cane on it. */
	// Measured: passes at tick 70 to 71 (red: No module named 'jobs.cane_farm').
	@GameTest(maxTicks = 160)
	public void aCellIsBuiltWithWaterAndCaneBesideIt(GameTestHelper helper) {
		AntBot bot = farm(helper, 2, "1");
		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.WATER, 2, 0, 3);
			helper.assertBlockPresent(Blocks.DIRT, 1, 0, 3);
			helper.assertBlockPresent(Blocks.DIRT, 3, 0, 3);
			helper.assertBlockPresent(Blocks.SUGAR_CANE, 1, 1, 3);
			helper.assertBlockPresent(Blocks.SUGAR_CANE, 3, 1, 3);
			helper.assertTrue(said(bot, "built cell 1"), "said " + BotScripts.log(bot, Output.Level.PRINT));
		});
	}

	/** Sand under a side is soil too (#844): cane is planted on it, no dirt put in its place. */
	// Measured: passes at tick 37 (red: dirt put in place of the sand at (1, 0, 3)).
	@GameTest(maxTicks = 80)
	public void caneIsPlantedOnSand(GameTestHelper helper) {
		helper.setBlock(1, 0, 3, Blocks.SAND);
		helper.setBlock(3, 0, 3, Blocks.RED_SAND);
		AntBot bot = farm(helper, 2, "1");
		helper.succeedWhen(() -> {
			helper.assertTrue(said(bot, "built cell 1"), "said " + BotScripts.log(bot, Output.Level.PRINT));
			helper.assertBlockPresent(Blocks.SAND, 1, 0, 3);
			helper.assertBlockPresent(Blocks.RED_SAND, 3, 0, 3);
			helper.assertBlockPresent(Blocks.SUGAR_CANE, 1, 1, 3);
			helper.assertBlockPresent(Blocks.SUGAR_CANE, 3, 1, 3);
		});
	}

	/**
	 * A cell whose water is gone, its cane popped off with it, gets its water back from the bucket the bot kept and its
	 * cane planted again (#844).
	 */
	// Measured: passes at tick 189 (red: still air at (2, 0, 3) on tick 402).
	@GameTest(maxTicks = 380)
	public void aCellsLostWaterIsPouredAgainAndReplanted(GameTestHelper helper) {
		AntBot bot = farm(helper, 4, "1");
		boolean[] drained = new boolean[1];
		helper.onEachTick(() -> {
			if (!drained[0] && said(bot, "built cell 1")) {
				helper.setBlock(2, 0, 3, Blocks.AIR);
				drained[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(drained[0], "cell 1 not built; said " + BotScripts.log(bot, Output.Level.PRINT));
			helper.assertBlockPresent(Blocks.WATER, 2, 0, 3);
			helper.assertBlockPresent(Blocks.SUGAR_CANE, 1, 1, 3);
			helper.assertBlockPresent(Blocks.SUGAR_CANE, 3, 1, 3);
		});
	}

	/** Cane three high on one side of the built cell is cut to its bottom block, the two cut stored in the depot. */
	// Measured: passes at tick 196 to 295.
	@GameTest(maxTicks = 600)
	public void caneThreeHighIsCutToOneAndStored(GameTestHelper helper) {
		AntBot bot = farm(helper, 2, "1");
		boolean[] grown = new boolean[1];
		helper.onEachTick(() -> {
			if (!grown[0] && said(bot, "built cell 1")) {
				helper.setBlock(1, 2, 3, Blocks.SUGAR_CANE);
				helper.setBlock(1, 3, 3, Blocks.SUGAR_CANE);
				grown[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(grown[0], "cell 1 not built; said " + BotScripts.log(bot, Output.Level.PRINT));
			helper.assertBlockPresent(Blocks.SUGAR_CANE, 1, 1, 3);
			helper.assertBlockNotPresent(Blocks.SUGAR_CANE, 1, 2, 3);
			int stored = stored(helper);
			helper.assertTrue(stored == 2, "cane in the depot " + stored + "; said " + BotScripts.log(bot, Output.Level.PRINT));
		});
	}

	/**
	 * The farm's kept blocks of a cane cell take in the block over its water (#912): a walk of the harvest left the bot on
	 * scaffold there, a block above the water, where the cell's kept floor (lib.cellfarm.floor) did not reach. With the
	 * cell's kept blocks kept, as the farm keeps them, a walk to stand over the water puts no scaffold over it.
	 */
	// Measured: passes at tick 58, the goto unreachable (red: cobblestone over the water on tick 40, the bot on it).
	@GameTest(maxTicks = 120)
	public void noScaffoldGoesOverACellsWater(GameTestHelper helper) {
		helper.setBlock(1, 0, 3, Blocks.DIRT);
		helper.setBlock(2, 0, 3, Blocks.WATER);
		helper.setBlock(3, 0, 3, Blocks.DIRT);
		helper.setBlock(1, 1, 3, Blocks.SUGAR_CANE);
		helper.setBlock(3, 1, 3, Blocks.SUGAR_CANE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 16));
		BlockPos low = helper.absolutePos(new BlockPos(1, 0, 3));
		BlockPos high = helper.absolutePos(new BlockPos(3, 0, 3));
		BlockPos over = helper.absolutePos(new BlockPos(2, 2, 3));
		BotScripts.run(bot, """
			import jobs.cane_farm
			keep_blocks(jobs.cane_farm.spec()["kept"](Pos(%d, %d, %d), Pos(%d, %d, %d)))
			r = goto(Pos(%d, %d, %d))
			print("walked", r.ok, r.reason)
			""".formatted(low.getX(), low.getY(), low.getZ(), high.getX(), high.getY(), high.getZ(), over.getX(), over.getY(), over.getZ()));
		BotScripts.failOnError(helper, bot);
		BlockPos above = new BlockPos(2, 1, 3);
		helper.onEachTick(() -> {
			if (!helper.getBlockState(above).isAir()) {
				helper.fail(helper.getBlockState(above) + " over the water; the bot at " + TestPositions.relative(helper, bot.position()));
			}
		});
		helper.succeedWhen(() -> helper.assertTrue(said(bot, "walked"), "said " + BotScripts.log(bot, Output.Level.PRINT)));
	}

	/**
	 * With cane for one cell only, the farm builds cell 1, then lacks cane for cell 2; cane grown in cell 1 is harvested
	 * and stored, and cell 2 is planted from the depot's cane, the other 2 left there.
	 */
	// It fails at once where cell 1's cane or water goes after it is built, or the farm says a cell was not built, with the
	// bot's call, line and position (#853). Measured: passes at tick 250 to 347 (red: cobblestone in place of the cane
	// at (3, 1, 3) on tick 183, the bot running pick_up('minecraft:sugar_cane'): its walk headed for a cut cane still
	// falling, so the path pillared at the cane under it).
	// Full runs: passes at up to tick 337 (#861).
	@GameTest(maxTicks = 680)
	public void aSecondCellIsPlantedFromStoredCane(GameTestHelper helper) {
		AntBot bot = farm(helper, 2, "2");
		boolean[] grown = new boolean[1];
		helper.onEachTick(() -> {
			if (!grown[0] && said(bot, "built cell 1")) {
				for (int x : new int[] {1, 3}) {
					helper.setBlock(x, 2, 3, Blocks.SUGAR_CANE);
					helper.setBlock(x, 3, 3, Blocks.SUGAR_CANE);
				}
				grown[0] = true;
			}
			boolean broken = grown[0] && !(helper.getBlockState(new BlockPos(1, 1, 3)).is(Blocks.SUGAR_CANE)
				&& helper.getBlockState(new BlockPos(3, 1, 3)).is(Blocks.SUGAR_CANE) && helper.getBlockState(new BlockPos(2, 0, 3)).is(Blocks.WATER));
			if (broken || said(bot, "cell not built")) {
				ScriptRun run = bot.getScript();
				helper.fail((broken ? "cell 1 broken: " + helper.getBlockState(new BlockPos(1, 1, 3)) + ", " + helper.getBlockState(new BlockPos(2, 0, 3))
					+ ", " + helper.getBlockState(new BlockPos(3, 1, 3)) : "a cell not built") + "; the bot at " + TestPositions.relative(helper, bot.position())
					+ (run == null ? "" : ", running " + run.call() + " on line " + run.line()) + "; said " + BotScripts.log(bot, Output.Level.PRINT));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(said(bot, "built cell 2"), "said " + BotScripts.log(bot, Output.Level.PRINT));
			helper.assertBlockPresent(Blocks.WATER, 6, 0, 3);
			helper.assertBlockPresent(Blocks.SUGAR_CANE, 5, 1, 3);
			helper.assertBlockPresent(Blocks.SUGAR_CANE, 7, 1, 3);
			helper.assertBlockPresent(Blocks.SUGAR_CANE, 1, 1, 3);
			helper.assertBlockPresent(Blocks.SUGAR_CANE, 3, 1, 3);
			int stored = stored(helper);
			helper.assertTrue(stored == 2, "cane in the depot " + stored + " of the 4 cut, 2 planted");
		});
	}
}
