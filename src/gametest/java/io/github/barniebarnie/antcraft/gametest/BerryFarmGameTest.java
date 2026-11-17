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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code jobs/berry_farm.py} (#832; docs/cookbook/berry-farm.md): a sweet berry farm on lib.cellfarm, its cells a row of
 * three bushes on dirt. Each test's farm has its corner at (1, 1, 3), so cell 1 is the floor from (1, 0, 3) to (3, 0, 3),
 * and cell 2 the same at x 5 to 7; the depot's first chest is at (1, 1, 1). A bush can age on a random tick while a test
 * runs, so the tests that harvest ripen all three of a cell themselves. Every test fails at once where a bush hurts the
 * bot.
 */
public class BerryFarmGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** A supply chest of three berries and dirt dirt, the bot by it, and jobs.berry_farm run with size cells. */
	private static AntBot farm(GameTestHelper helper, int dirt, String size) {
		helper.setBlock(6, 1, 6, Blocks.CHEST);
		Container chest = helper.getBlockEntity(new BlockPos(6, 1, 6), ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.SWEET_BERRIES, 3));
		chest.setItem(1, new ItemStack(Items.DIRT, dirt));
		chest.setItem(2, new ItemStack(Items.STONE_PICKAXE));
		chest.setItem(3, new ItemStack(Items.CHEST, 3)); // the cobblestone dug goes in a chest of its own
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		BlockPos at = helper.absolutePos(new BlockPos(1, 1, 3));
		ScriptRun run = BotScripts.run(bot, """
			import jobs.berry_farm
			jobs.berry_farm.run(Pos(%d, %d, %d), %s, 8)
			""".formatted(at.getX(), at.getY(), at.getZ(), size));
		BotScripts.failOnError(helper, bot);
		helper.onEachTick(() -> {
			DamageSource hurt = bot.getLastDamageSource();
			if (hurt != null && hurt.is(DamageTypes.SWEET_BERRY_BUSH)) {
				// Where the bot stood, not just its block, and the call it ran: which step took it into the bush (#845).
				helper.fail("a sweet berry bush hurt the bot at " + TestPositions.relative(helper, bot.position()) + ", running " + run.action()
					+ " on line " + run.line());
			}
		});
		return bot;
	}

	/** The berries in the depot's chests, along its row east of (1, 1, 1). */
	private static int stored(GameTestHelper helper) {
		int n = 0;
		for (int x = 1; x < 5; x++) {
			if (helper.getBlockState(new BlockPos(x, 1, 1)).is(Blocks.CHEST)) {
				n += helper.getBlockEntity(new BlockPos(x, 1, 1), ChestBlockEntity.class).countItem(Items.SWEET_BERRIES);
			}
		}
		return n;
	}

	private static boolean said(AntBot bot, String what) {
		return BotScripts.log(bot, Output.Level.PRINT).stream().anyMatch(l -> l.contains(what));
	}

	/** A bush at (x, 1, 3) on dirt, of age at most. */
	private static void assertPlanted(GameTestHelper helper, int x, int most) {
		helper.assertBlockPresent(Blocks.DIRT, x, 0, 3);
		BlockState s = helper.getBlockState(new BlockPos(x, 1, 3));
		helper.assertTrue(s.is(Blocks.SWEET_BERRY_BUSH) && s.getValue(SweetBerryBushBlock.AGE) <= most,
			"no bush of age " + most + " or less at " + x + " 1 3: " + s);
	}

	/** Ripens cell 1, its bushes age 3, once the bot said it built it. */
	private static boolean[] ripenWhenBuilt(GameTestHelper helper, AntBot bot) {
		boolean[] ripe = new boolean[1];
		helper.onEachTick(() -> {
			if (!ripe[0] && said(bot, "built cell 1")) {
				for (int x = 1; x <= 3; x++) {
					helper.setBlock(x, 1, 3, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3));
				}
				ripe[0] = true;
			}
		});
		return ripe;
	}

	/** On a stone floor: the cell's three spots get dirt with a bush planted on it. */
	// Measured: passes at tick 71 to 72 (red: No module named 'jobs.berry_farm').
	@GameTest(maxTicks = 160)
	public void aCellIsPlantedWithBushes(GameTestHelper helper) {
		AntBot bot = farm(helper, 3, "1");
		helper.succeedWhen(() -> {
			for (int x = 1; x <= 3; x++) {
				assertPlanted(helper, x, 3);
			}
			helper.assertTrue(said(bot, "built cell 1"), "said " + BotScripts.log(bot, Output.Level.PRINT));
		});
	}

	/**
	 * Ripe bushes in the built cell are picked, not broken: each stays, back at age 1, and the berries (2 or 3 a bush at
	 * age 3) are stored in the depot.
	 */
	// Measured: passes at tick 200 to 254 (a first run, before pick_up reached into a bush for its berries, failed on tick 242: a bush hurt the bot).
	@GameTest(maxTicks = 500)
	public void ripeBushesArePickedAndStay(GameTestHelper helper) {
		AntBot bot = farm(helper, 3, "1");
		boolean[] ripe = ripenWhenBuilt(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(ripe[0], "cell 1 not built; said " + BotScripts.log(bot, Output.Level.PRINT));
			for (int x = 1; x <= 3; x++) {
				// Picked back to age 1; a random tick can age it a stage, which the next round picks again.
				assertPlanted(helper, x, 2);
			}
			int stored = stored(helper);
			helper.assertTrue(stored >= 6, "berries in the depot " + stored + "; said " + BotScripts.log(bot, Output.Level.PRINT));
		});
	}

	/**
	 * With berries for one cell only and dirt for two, the farm builds cell 1, then lacks berries for cell 2; the ripe
	 * bushes of cell 1 are picked and stored, and cell 2 is planted from the stored berries.
	 */
	// Measured: passes at tick 277 to 324.
	@GameTest(maxTicks = 650)
	public void aSecondCellIsPlantedFromStoredBerries(GameTestHelper helper) {
		AntBot bot = farm(helper, 6, "2");
		ripenWhenBuilt(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(said(bot, "built cell 2"), "said " + BotScripts.log(bot, Output.Level.PRINT));
			for (int x : new int[] {1, 2, 3, 5, 6, 7}) {
				assertPlanted(helper, x, 3);
			}
		});
	}
}
