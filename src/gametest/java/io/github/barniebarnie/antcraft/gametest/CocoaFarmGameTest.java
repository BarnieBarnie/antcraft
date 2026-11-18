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
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code jobs/cocoa_farm.py} (#833; docs/cookbook/cocoa-farm.md): a cocoa farm on lib.cellfarm, its cells a column of two
 * jungle logs with a pod on its west and east side at each height. Each test's farm has its corner at (1, 1, 3), so cell
 * 1 is the floor from (1, 0, 3) to (3, 0, 3), logs at (2, 1, 3) and (2, 2, 3), pods at x 1 and 3, and cell 2 the same at
 * x 5 to 7; the depot's first chest is at (1, 1, 1).
 */
public class CocoaFarmGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** A supply chest of logs jungle logs, 4 cocoa beans and 2 chests, the bot by it, and jobs.cocoa_farm run with size cells. */
	private static AntBot farm(GameTestHelper helper, int logs, int size) {
		return farm(helper, logs, size, 0);
	}

	/** Like {@link #farm(GameTestHelper, int, int)}, with the farm, supply chest and bot dz blocks further south. */
	private static AntBot farm(GameTestHelper helper, int logs, int size, int dz) {
		return farm(helper, logs, size, dz, true);
	}

	/** Like {@link #farm(GameTestHelper, int, int, int)}; failing at once on a lost log (failOnLostLog) only where watch. */
	private static AntBot farm(GameTestHelper helper, int logs, int size, int dz, boolean watch) {
		return farm(helper, logs, size, dz, watch, "");
	}

	/** Like {@link #farm(GameTestHelper, int, int, int, boolean)}, the script running prelude after its imports. */
	private static AntBot farm(GameTestHelper helper, int logs, int size, int dz, boolean watch, String prelude) {
		helper.setBlock(6, 1, 6 + dz, Blocks.CHEST);
		Container chest = helper.getBlockEntity(new BlockPos(6, 1, 6 + dz), ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.JUNGLE_LOG, logs));
		chest.setItem(1, new ItemStack(Items.COCOA_BEANS, 4));
		chest.setItem(2, new ItemStack(Items.CHEST, 2));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6 + dz);
		BlockPos at = helper.absolutePos(new BlockPos(1, 1, 3 + dz));
		ScriptRun run = BotScripts.run(bot, """
			import jobs.cocoa_farm
			import lib.cellfarm
			%s
			jobs.cocoa_farm.run(Pos(%d, %d, %d), %d, 8)
			""".formatted(prelude, at.getX(), at.getY(), at.getZ(), size));
		BotScripts.failOnError(helper, bot);
		if (watch) {
			failOnLostLog(helper, bot, run, dz);
		}
		return bot;
	}

	/**
	 * Fails the test at once where a built cell's jungle log goes missing, with where the bot stood and the call it ran:
	 * the farm never breaks its own logs (#849).
	 */
	private static void failOnLostLog(GameTestHelper helper, AntBot bot, ScriptRun run, int dz) {
		boolean[] built = new boolean[2];
		helper.onEachTick(() -> {
			for (int cell = 0; cell < 2; cell++) {
				for (int y = 1; y <= 2; y++) {
					BlockPos log = new BlockPos(2 + 4 * cell, y, 3 + dz);
					if (helper.getBlockState(log).is(Blocks.JUNGLE_LOG)) {
						built[cell] |= y == 2;
					} else if (built[cell]) {
						helper.fail("the jungle log at " + log + " is gone: " + helper.getBlockState(log) + "; the bot at "
							+ TestPositions.relative(helper, bot.position()) + ", running " + run.action() + " on line " + run.line());
					}
				}
			}
		});
	}

	/** A pod of age age on the log east (on x 1 or 5) or west (on x 3 or 7) of (x, y, 3). */
	private static BlockState pod(int x, int age) {
		return Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.FACING, x % 4 == 1 ? Direction.EAST : Direction.WEST)
			.setValue(CocoaBlock.AGE, age);
	}

	/** Asserts the cell from (x0, 0, 3) holds its two logs and a pod on each side at each height, facing its log. */
	private static void assertCell(GameTestHelper helper, int x0) {
		for (int y = 1; y <= 2; y++) {
			helper.assertBlockPresent(Blocks.JUNGLE_LOG, x0 + 1, y, 3);
			for (int x : new int[] {x0, x0 + 2}) {
				BlockState state = helper.getBlockState(new BlockPos(x, y, 3));
				helper.assertTrue(state.is(Blocks.COCOA) && state.getValue(CocoaBlock.FACING) == pod(x, 0).getValue(CocoaBlock.FACING),
					"no pod facing its log at " + x + " " + y + " 3: " + state);
			}
		}
	}

	/** The cocoa beans in the depot's chests, along its row east of (1, 1, 1). */
	private static int stored(GameTestHelper helper) {
		return stored(helper, 0);
	}

	private static int stored(GameTestHelper helper, int dz) {
		int n = 0;
		for (int x = 1; x < 5; x++) {
			if (helper.getBlockState(new BlockPos(x, 1, 1 + dz)).is(Blocks.CHEST)) {
				n += helper.getBlockEntity(new BlockPos(x, 1, 1 + dz), ChestBlockEntity.class).countItem(Items.COCOA_BEANS);
			}
		}
		return n;
	}

	private static boolean said(AntBot bot, String what) {
		return BotScripts.log(bot, Output.Level.PRINT).stream().anyMatch(l -> l.contains(what));
	}

	/** On the stone floor: two jungle logs stand in the cell's middle, a pod on each of their west and east sides. */
	// Measured: passes at tick 25 to 27, also under load (red: No module named 'jobs.cocoa_farm').
	@GameTest(maxTicks = 60)
	public void aCellIsBuiltWithPodsOnTheLogsSides(GameTestHelper helper) {
		AntBot bot = farm(helper, 2, 1);
		helper.succeedWhen(() -> {
			assertCell(helper, 1);
			helper.assertTrue(said(bot, "built cell 1"), "said " + BotScripts.log(bot, Output.Level.PRINT));
		});
	}

	/**
	 * Once cell 1 is built, its pod at (1, 1, 3) is ripened and the one at (3, 1, 3) grown to age 1 (and kept there, so a
	 * random tick cannot ripen it): the ripe pod is broken and a bean placed back, the 2 beans over stored, the unripe one
	 * left.
	 */
	// Measured: passes at tick 147 to 149 alone, up to 189 in three full runs (#850).
	@GameTest(maxTicks = 360)
	public void ripePodsAreHarvestedAndReplantedUnripeOnesLeft(GameTestHelper helper) {
		AntBot bot = farm(helper, 2, 1);
		boolean[] grown = new boolean[1];
		helper.onEachTick(() -> {
			if (!grown[0] && said(bot, "built cell 1")) {
				helper.setBlock(1, 1, 3, pod(1, 2));
				helper.setBlock(3, 1, 3, pod(3, 1));
				grown[0] = true;
			} else if (grown[0] && helper.getBlockState(new BlockPos(3, 1, 3)).equals(pod(3, 2))) {
				helper.setBlock(3, 1, 3, pod(3, 1));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(grown[0], "cell 1 not built; said " + BotScripts.log(bot, Output.Level.PRINT));
			assertCell(helper, 1);
			helper.assertTrue(helper.getBlockState(new BlockPos(1, 1, 3)).getValue(CocoaBlock.AGE) < 2, "the ripe pod not harvested");
			helper.assertTrue(helper.getBlockState(new BlockPos(3, 1, 3)).equals(pod(3, 1)), "the unripe pod not left");
			int stored = stored(helper);
			helper.assertTrue(stored == 2, "beans in the depot " + stored + "; said " + BotScripts.log(bot, Output.Level.PRINT));
		});
	}

	/**
	 * With beans for one cell only, the farm builds cell 1 and lacks beans for cell 2; cell 1's pods ripened are
	 * harvested, the beans stored, and cell 2 is built from the depot's beans and the supply's other 2 logs.
	 */
	// Measured: passes at tick 182 to 184 alone, up to 230 in three full runs and 251 seen elsewhere (#849, #850).
	@GameTest(maxTicks = 480)
	public void aSecondCellIsBuiltFromStoredBeansAndLogs(GameTestHelper helper) {
		AntBot bot = farm(helper, 4, 2);
		boolean[] grown = new boolean[1];
		helper.onEachTick(() -> {
			if (!grown[0] && said(bot, "built cell 1")) {
				for (int y = 1; y <= 2; y++) {
					helper.setBlock(1, y, 3, pod(1, 2));
					helper.setBlock(3, y, 3, pod(3, 2));
				}
				grown[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(said(bot, "built cell 2"), "said " + BotScripts.log(bot, Output.Level.PRINT));
			assertCell(helper, 1);
			assertCell(helper, 5);
		});
	}

	/**
	 * The ripe pod harvest with the farm's corner on the last row of its chunk (z 15 within it), the bot starting south of
	 * it in the next chunk, so lib.cellfarm.corner walks it back into the corner's chunk to wait (#849). Before #845 it was
	 * sent diagonal into cell 0 capped at chunk-local 15, the cell's lower log, and the walk broke both logs (red with that
	 * corner: the log at (2, 1, z) gone on tick 105, the bot running the corner's goto). The other tests' farms have their
	 * corner there only where the test area lies so, hence 1 in 6 runs failing at #847.
	 */
	// Measured: passes at tick 194 to 195.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 360)
	public void aHarvestWithTheCornerOnItsChunksLastRowLeavesTheLogs(GameTestHelper helper) {
		int dz = Math.floorMod(15 - helper.absolutePos(new BlockPos(0, 0, 3)).getZ(), 16);
		AntBot bot = farm(helper, 2, 1, dz);
		boolean[] grown = new boolean[1];
		helper.onEachTick(() -> {
			if (!grown[0] && said(bot, "built cell 1")) {
				helper.setBlock(1, 1, 3 + dz, pod(1, 2));
				grown[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(grown[0], "cell 1 not built; said " + BotScripts.log(bot, Output.Level.PRINT));
			BlockState replanted = helper.getBlockState(new BlockPos(1, 1, 3 + dz));
			helper.assertTrue(replanted.is(Blocks.COCOA) && replanted.getValue(CocoaBlock.AGE) < 2, "the ripe pod not replanted: " + replanted);
			helper.assertTrue(stored(helper, dz) >= 2, "beans in the depot " + stored(helper, dz));
		});
	}

	/**
	 * The corner walk of {@link #aHarvestWithTheCornerOnItsChunksLastRowLeavesTheLogs} sent onto cell 0's lower log, as
	 * before #845: the farm keeps its cells' logs from its walks' breaks (keep_blocks, #856), so the walk cannot end there
	 * and says so, and both logs stay (failOnLostLog).
	 */
	// Measured: passes at tick 46 (red, no kept: the log at (2, 1, 3) gone on tick 105, the bot running the corner's goto).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 100)
	public void aCornerWalkSentThroughACellLeavesItsLogs(GameTestHelper helper) {
		int dz = Math.floorMod(15 - helper.absolutePos(new BlockPos(0, 0, 3)).getZ(), 16);
		AntBot bot = farm(helper, 2, 1, dz, true, "lib.cellfarm.corner_spot = lambda low, high: low + (1, 1, 0)");
		helper.succeedWhen(() -> {
			helper.assertTrue(said(bot, "corner: unreachable"), "said " + BotScripts.log(bot, Output.Level.PRINT));
			helper.assertBlockPresent(Blocks.JUNGLE_LOG, 2, 1, 3 + dz);
			helper.assertBlockPresent(Blocks.JUNGLE_LOG, 2, 2, 3 + dz);
		});
	}

	/**
	 * A built cell's top log that is gone, its pods popped off with it, is placed again from the spare log in the depot,
	 * and its pods with the beans the test adds to the supply chest (#844).
	 */
	// Measured: passes at tick 131 (red: still air at (2, 2, 3) on tick 402).
	@GameTest(maxTicks = 270)
	public void aLostLogIsReplacedAndItsPodsReplanted(GameTestHelper helper) {
		AntBot bot = farm(helper, 3, 1, 0, false);
		boolean[] lost = new boolean[1];
		helper.onEachTick(() -> {
			if (!lost[0] && said(bot, "built cell 1")) {
				helper.setBlock(2, 2, 3, Blocks.AIR);
				helper.getBlockEntity(new BlockPos(6, 1, 6), ChestBlockEntity.class).setItem(5, new ItemStack(Items.COCOA_BEANS, 4));
				lost[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(lost[0], "cell 1 not built; said " + BotScripts.log(bot, Output.Level.PRINT));
			assertCell(helper, 1);
		});
	}
}
