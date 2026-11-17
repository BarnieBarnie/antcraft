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
 * {@code jobs/kelp_farm.py} (#835; docs/cookbook/kelp-farm.md): a kelp farm in a pool of water. Each test's ground is
 * stone up to y 2, the bot walking at y 3; the pool is dug into it, two deep, water at y 1 and 2 over the floor x 2 to 4,
 * z 3 to 4, the six blocks kelp stands on. The depot's first chest is at (2, 3, 1), north of the pool.
 */
public class KelpFarmGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** The pool, a supply chest of six kelp and a chest for the depot, the bot by it, and jobs.kelp_farm run over the pool. */
	private static AntBot farm(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				boolean pool = x >= 2 && x <= 4 && z >= 3 && z <= 4;
				for (int y = 1; y <= 2; y++) {
					helper.setBlock(x, y, z, pool ? Blocks.WATER : Blocks.STONE);
				}
			}
		}
		helper.setBlock(6, 3, 6, Blocks.CHEST);
		Container chest = helper.getBlockEntity(new BlockPos(6, 3, 6), ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.KELP, 6));
		chest.setItem(1, new ItemStack(Items.CHEST));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 3, 6);
		BlockPos low = helper.absolutePos(new BlockPos(2, 0, 3));
		BlockPos high = helper.absolutePos(new BlockPos(4, 0, 4));
		BotScripts.run(bot, """
			import jobs.kelp_farm
			jobs.kelp_farm.run(Pos(%d, %d, %d), Pos(%d, %d, %d), 8)
			""".formatted(low.getX(), low.getY(), low.getZ(), high.getX(), high.getY(), high.getZ()));
		BotScripts.failOnError(helper, bot);
		return bot;
	}

	private static boolean planted(GameTestHelper helper) {
		for (int x = 2; x <= 4; x++) {
			for (int z = 3; z <= 4; z++) {
				if (!helper.getBlockState(new BlockPos(x, 1, z)).is(Blocks.KELP)
					&& !helper.getBlockState(new BlockPos(x, 1, z)).is(Blocks.KELP_PLANT)) {
					return false;
				}
			}
		}
		return true;
	}

	private static String said(AntBot bot) {
		return "said " + BotScripts.log(bot, Output.Level.PRINT);
	}

	/** Kelp from the supply chest is planted on each of the six floor blocks, under water. */
	// Measured: passes at tick 30 to 31 (red: No module named 'jobs.kelp_farm').
	@GameTest(maxTicks = 70)
	public void kelpIsPlantedOnThePoolsFloor(GameTestHelper helper) {
		AntBot bot = farm(helper);
		helper.succeedWhen(() -> helper.assertTrue(planted(helper), "not all planted; " + said(bot)));
	}

	/** Kelp grown two high on two spots is cut at its second block, so its bottom block stays and grows again; the cut kelp is stored in the depot. */
	// Measured: passes at tick 152.
	@GameTest(maxTicks = 320)
	public void grownKelpIsCutAboveItsBottomBlockAndStored(GameTestHelper helper) {
		AntBot bot = farm(helper);
		boolean[] grown = new boolean[1];
		helper.onEachTick(() -> {
			if (!grown[0] && planted(helper)) {
				for (int x : new int[] {2, 4}) {
					helper.setBlock(x, 1, 3, Blocks.KELP_PLANT);
					helper.setBlock(x, 2, 3, Blocks.KELP);
				}
				grown[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(grown[0], "not planted; " + said(bot));
			for (int x : new int[] {2, 4}) {
				helper.assertTrue(helper.getBlockState(new BlockPos(x, 1, 3)).is(Blocks.KELP), "bottom block at " + x + " gone");
				helper.assertBlockPresent(Blocks.WATER, x, 2, 3);
			}
			helper.assertBlockPresent(Blocks.CHEST, 2, 3, 1);
			int stored = helper.getBlockEntity(new BlockPos(2, 3, 1), ChestBlockEntity.class).countItem(Items.KELP);
			helper.assertTrue(stored >= 2, "kelp in the depot " + stored + " of the 2 cut; " + said(bot));
		});
	}
}
