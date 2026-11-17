package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * {@code jobs/cactus_farm.py} (#830; docs/cookbook/cactus-farm.md): a cactus farm on lib.cellfarm, its cells 2 by 2 floor
 * blocks with cactus on sand at two opposite corners, a checkerboard. Each test's farm has its corner at (1, 1, 3), so cell
 * 1 is the floor from (1, 0, 3) to (2, 0, 4), its cacti over (1, 0, 3) and (2, 0, 4), and cell 2 the same at x 4 to 5; the
 * depot's first chest is at (1, 1, 1). Every test fails at once where a cactus hurts the bot.
 */
public class CactusFarmGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** A supply chest of what one cell takes, the bot by it, and jobs.cactus_farm run with size cells. */
	private static AntBot farm(GameTestHelper helper, int cactus, String size) {
		helper.setBlock(6, 1, 6, Blocks.CHEST);
		Container chest = helper.getBlockEntity(new BlockPos(6, 1, 6), ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.CACTUS, cactus));
		chest.setItem(1, new ItemStack(Items.SAND, 4));
		chest.setItem(2, new ItemStack(Items.STONE_PICKAXE));
		chest.setItem(3, new ItemStack(Items.CHEST, 3)); // the cobblestone over goes in a chest of its own
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		BlockPos at = helper.absolutePos(new BlockPos(1, 1, 3));
		BotScripts.run(bot, """
			import jobs.cactus_farm
			jobs.cactus_farm.run(Pos(%d, %d, %d), %s, 8)
			""".formatted(at.getX(), at.getY(), at.getZ(), size));
		BotScripts.failOnError(helper, bot);
		helper.onEachTick(() -> {
			DamageSource hurt = bot.getLastDamageSource();
			if (hurt != null && hurt.is(DamageTypes.CACTUS)) {
				helper.fail("a cactus hurt the bot at " + bot.blockPosition());
			}
		});
		return bot;
	}

	/** The cactus in the depot's chests, along its row east of (1, 1, 1). */
	private static int stored(GameTestHelper helper) {
		int n = 0;
		for (int x = 1; x < 5; x++) {
			if (helper.getBlockState(new BlockPos(x, 1, 1)).is(Blocks.CHEST)) {
				n += helper.getBlockEntity(new BlockPos(x, 1, 1), ChestBlockEntity.class).countItem(Items.CACTUS);
			}
		}
		return n;
	}

	private static boolean said(AntBot bot, String what) {
		return BotScripts.log(bot, Output.Level.PRINT).stream().anyMatch(l -> l.contains(what));
	}

	/** Cactus on sand at the cell's two spots, and no block beside either, at its own height. */
	private static void cell(GameTestHelper helper, int x, int z) {
		for (BlockPos spot : new BlockPos[] {new BlockPos(x, 1, z), new BlockPos(x + 1, 1, z + 1)}) {
			helper.assertBlockPresent(Blocks.SAND, spot.below());
			helper.assertBlockPresent(Blocks.CACTUS, spot);
			for (Direction dir : Direction.Plane.HORIZONTAL) {
				helper.assertBlockPresent(Blocks.AIR, spot.relative(dir));
			}
		}
	}

	/** On a stone floor: sand is put at the cell's two spots, cactus on it, with nothing beside either. */
	// Measured: passes at tick 56 (red: No module named 'jobs.cactus_farm').
	@GameTest(maxTicks = 120)
	public void aCellIsBuiltWithCactiOnSandNoneBesideABlock(GameTestHelper helper) {
		AntBot bot = farm(helper, 2, "1");
		helper.succeedWhen(() -> {
			cell(helper, 1, 3);
			helper.assertTrue(said(bot, "built cell 1"), "said " + BotScripts.log(bot, Output.Level.PRINT));
		});
	}

	/** A cactus three high in the built cell is cut to its bottom block, the two cut stored in the depot, the bot unhurt. */
	// Measured: passes at tick 191.
	@GameTest(maxTicks = 400)
	public void aCactusThreeHighIsCutToOneAndStored(GameTestHelper helper) {
		AntBot bot = farm(helper, 2, "1");
		boolean[] grown = new boolean[1];
		helper.onEachTick(() -> {
			if (!grown[0] && said(bot, "built cell 1")) {
				helper.setBlock(1, 2, 3, Blocks.CACTUS);
				helper.setBlock(1, 3, 3, Blocks.CACTUS);
				grown[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(grown[0], "cell 1 not built; said " + BotScripts.log(bot, Output.Level.PRINT));
			helper.assertBlockPresent(Blocks.CACTUS, 1, 1, 3);
			helper.assertBlockNotPresent(Blocks.CACTUS, 1, 2, 3);
			int stored = stored(helper);
			helper.assertTrue(stored == 2, "cactus in the depot " + stored + "; said " + BotScripts.log(bot, Output.Level.PRINT));
		});
	}

	/**
	 * With cactus for one cell only, the farm builds cell 1, then lacks cactus for cell 2; cactus grown in cell 1 is
	 * harvested and stored, and cell 2 is planted from the depot's cactus, the other 2 left there.
	 */
	// Measured: passes at tick 258.
	@GameTest(maxTicks = 520)
	public void aSecondCellIsPlantedFromStoredCactus(GameTestHelper helper) {
		AntBot bot = farm(helper, 2, "2");
		boolean[] grown = new boolean[1];
		helper.onEachTick(() -> {
			if (!grown[0] && said(bot, "built cell 1")) {
				for (BlockPos spot : new BlockPos[] {new BlockPos(1, 2, 3), new BlockPos(2, 2, 4)}) {
					helper.setBlock(spot, Blocks.CACTUS);
					helper.setBlock(spot.above(), Blocks.CACTUS);
				}
				grown[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(said(bot, "built cell 2"), "said " + BotScripts.log(bot, Output.Level.PRINT));
			cell(helper, 4, 3);
			cell(helper, 1, 3);
			int stored = stored(helper);
			helper.assertTrue(stored == 2, "cactus in the depot " + stored + " of the 4 cut, 2 planted");
		});
	}
}
