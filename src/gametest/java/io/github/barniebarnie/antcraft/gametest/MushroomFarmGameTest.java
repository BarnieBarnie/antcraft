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

/**
 * {@code jobs/mushroom_farm.py} (#836; docs/cookbook/mushroom-farm.md): a mushroom farm on lib.cellfarm, its cells a row
 * of three podzol, a brown mushroom on the west one and a red one on the east one, which spread onto the middle one. Each
 * test's farm has its corner at (1, 1, 3), so cell 1 is the floor from (1, 0, 3) to (3, 0, 3); the depot's first chest is
 * at (1, 1, 1). A mushroom spreads on some 1 in 34000 ticks, so the test that harvests puts the spread one itself.
 */
public class MushroomFarmGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** A supply chest of a cell's podzol and mushrooms, the bot by it, and jobs.mushroom_farm run with one cell. */
	private static AntBot farm(GameTestHelper helper) {
		helper.setBlock(6, 1, 6, Blocks.CHEST);
		Container chest = helper.getBlockEntity(new BlockPos(6, 1, 6), ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.PODZOL, 3));
		chest.setItem(1, new ItemStack(Items.BROWN_MUSHROOM, 1));
		chest.setItem(2, new ItemStack(Items.RED_MUSHROOM, 1));
		chest.setItem(3, new ItemStack(Items.STONE_PICKAXE));
		chest.setItem(4, new ItemStack(Items.CHEST, 3)); // the cobblestone dug goes in a chest of its own
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		BlockPos at = helper.absolutePos(new BlockPos(1, 1, 3));
		BotScripts.run(bot, """
			import jobs.mushroom_farm
			jobs.mushroom_farm.run(Pos(%d, %d, %d), 1, 8)
			""".formatted(at.getX(), at.getY(), at.getZ()));
		BotScripts.failOnError(helper, bot);
		return bot;
	}

	private static boolean said(AntBot bot, String what) {
		return BotScripts.log(bot, Output.Level.PRINT).stream().anyMatch(l -> l.contains(what));
	}

	/** The cell's podzol, with a brown mushroom on its west spot and a red one on its east spot. */
	private static void assertKept(GameTestHelper helper) {
		for (int x = 1; x <= 3; x++) {
			helper.assertBlockPresent(Blocks.PODZOL, x, 0, 3);
		}
		helper.assertBlockPresent(Blocks.BROWN_MUSHROOM, 1, 1, 3);
		helper.assertBlockPresent(Blocks.RED_MUSHROOM, 3, 1, 3);
	}

	/** On a lit stone floor: the cell's spots get podzol, which a mushroom grows on in any light, and are planted. */
	// Measured: passes at tick 69 (red: No module named 'jobs.mushroom_farm').
	@GameTest(maxTicks = 140)
	public void aCellIsBuiltOnPodzolAndPlanted(GameTestHelper helper) {
		AntBot bot = farm(helper);
		helper.succeedWhen(() -> {
			assertKept(helper);
			helper.assertTrue(said(bot, "built cell 1"), "said " + BotScripts.log(bot, Output.Level.PRINT));
		});
	}

	/** A mushroom spread onto the cell's middle spot is harvested and stored; the two it spread from are left. */
	// Measured: passes at tick 181.
	@GameTest(maxTicks = 360)
	public void spreadMushroomsAreHarvestedAFewLeft(GameTestHelper helper) {
		AntBot bot = farm(helper);
		boolean[] spread = new boolean[1];
		helper.onEachTick(() -> {
			if (!spread[0] && said(bot, "built cell 1")) {
				helper.setBlock(2, 1, 3, Blocks.BROWN_MUSHROOM);
				spread[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(spread[0], "cell 1 not built; said " + BotScripts.log(bot, Output.Level.PRINT));
			assertKept(helper);
			helper.assertBlockPresent(Blocks.AIR, 2, 1, 3);
			int stored = 0;
			for (int x = 1; x < 5; x++) {
				if (helper.getBlockState(new BlockPos(x, 1, 1)).is(Blocks.CHEST)) {
					stored += helper.getBlockEntity(new BlockPos(x, 1, 1), ChestBlockEntity.class).countItem(Items.BROWN_MUSHROOM);
				}
			}
			helper.assertTrue(stored >= 1, "no mushroom in the depot; said " + BotScripts.log(bot, Output.Level.PRINT));
		});
	}
}
