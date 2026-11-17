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
 * {@code jobs/stem_farm.py} (#828; docs/cookbook/stem-farm.md): a pumpkin farm on lib.cellfarm. The area's floor gets a
 * layer of dirt at y 1; the farm's corner is (1, 2, 3), so cell 1's floor is x 1 to 3, z 3 to 5 at y 1 and cell 2's x 5
 * to 7: stems on farmland at its corners, dirt fruit spots between them, water in the middle.
 */
public class StemFarmGameTest implements CustomTestMethodInvoker {
	/** Cell 1's stems, over its floor's corners. */
	private static final int[][] STEMS = {{1, 3}, {3, 3}, {1, 5}, {3, 5}};
	/** Cell 1's fruit spots, the floor blocks between its stems. */
	private static final int[][] SPOTS = {{2, 3}, {1, 4}, {3, 4}, {2, 5}};

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** Dirt at y 1, a supply chest at (6, 2, 7) holding items, the bot by it running the pumpkin farm of cells. */
	private static AntBot farm(GameTestHelper helper, int cells, ItemStack... items) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 1, z, Blocks.DIRT);
			}
		}
		helper.setBlock(6, 2, 7, Blocks.CHEST);
		Container chest = helper.getBlockEntity(new BlockPos(6, 2, 7), ChestBlockEntity.class);
		for (int i = 0; i < items.length; i++) {
			chest.setItem(i, items[i]);
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 2, 7);
		BlockPos at = helper.absolutePos(new BlockPos(1, 2, 3));
		BotScripts.run(bot, """
			import jobs.stem_farm
			jobs.stem_farm.run("pumpkin", Pos(%d, %d, %d), %d, 8)
			""".formatted(at.getX(), at.getY(), at.getZ(), cells));
		BotScripts.failOnError(helper, bot);
		return bot;
	}

	/** Stems planted on farmland at the cell's corners, each beside free dirt fruit spots, water in the middle. */
	// Measured: passes at tick 62 (red: No module named 'jobs.stem_farm').
	@GameTest(maxTicks = 160)
	public void aCellIsBuiltWithStemsBesideFreeFruitSpots(GameTestHelper helper) {
		AntBot bot = farm(helper, 1, new ItemStack(Items.PUMPKIN_SEEDS, 4), new ItemStack(Items.WATER_BUCKET),
			new ItemStack(Items.WOODEN_HOE), new ItemStack(Items.CHEST, 3));
		helper.succeedWhen(() -> {
			for (int[] s : STEMS) {
				helper.assertBlockPresent(Blocks.PUMPKIN_STEM, s[0], 2, s[1]);
				helper.assertBlockPresent(Blocks.FARMLAND, s[0], 1, s[1]);
			}
			for (int[] s : SPOTS) {
				helper.assertBlockPresent(Blocks.DIRT, s[0], 1, s[1]);
				helper.assertBlockPresent(Blocks.AIR, s[0], 2, s[1]);
			}
			helper.assertBlockPresent(Blocks.WATER, 2, 1, 4);
			helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).contains("built cell 1"),
				"said " + BotScripts.log(bot, Output.Level.PRINT));
		});
	}

	/**
	 * Two pumpkins put beside cell 1's stems are mined and stored in the depot (its row of chests from (1, 2, 1) east); the stems
	 * stay; one pumpkin is crafted into the 4 seeds cell 2 takes, which the bot carries none of, so cell 2 is built.
	 */
	// Measured: passes at tick 341 (red: No module named 'jobs.stem_farm').
	@GameTest(maxTicks = 700)
	public void aGrownFruitIsHarvestedStoredAndSeedsTheNextCell(GameTestHelper helper) {
		AntBot bot = farm(helper, 2, new ItemStack(Items.PUMPKIN_SEEDS, 4), new ItemStack(Items.WATER_BUCKET, 1),
			new ItemStack(Items.WATER_BUCKET, 1), new ItemStack(Items.WOODEN_HOE), new ItemStack(Items.CHEST, 3));
		boolean[] grown = new boolean[1];
		helper.onEachTick(() -> {
			if (!grown[0] && BotScripts.log(bot, Output.Level.PRINT).contains("built cell 1")) {
				helper.setBlock(2, 2, 3, Blocks.PUMPKIN);
				helper.setBlock(1, 2, 4, Blocks.PUMPKIN);
				grown[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(grown[0], "cell 1 not built; said " + BotScripts.log(bot, Output.Level.PRINT));
			helper.assertBlockPresent(Blocks.AIR, 2, 2, 3);
			helper.assertBlockPresent(Blocks.AIR, 1, 2, 4);
			for (int[] s : STEMS) {
				helper.assertBlockPresent(Blocks.PUMPKIN_STEM, s[0], 2, s[1]);
				helper.assertBlockPresent(Blocks.PUMPKIN_STEM, s[0] + 4, 2, s[1]);
			}
			int stored = 0;
			for (int x = 0; x < 8; x++) {
				if (helper.getBlockState(new BlockPos(x, 2, 1)).is(Blocks.CHEST)) {
					stored += helper.getBlockEntity(new BlockPos(x, 2, 1), ChestBlockEntity.class).countItem(Items.PUMPKIN);
				}
			}
			helper.assertTrue(stored == 1, stored + " pumpkins in the depot, not 1; said " + BotScripts.log(bot, Output.Level.PRINT));
		});
	}
}
