package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.entity.goal.LumberjackGoal;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import java.lang.reflect.Method;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.util.ProblemReporter;

public class LumberjackGameTest implements CustomTestMethodInvoker {
	private static final BlockPos CHEST = new BlockPos(1, 1, 1);
	/** Solo lumberjacks search 24 blocks around home, so keep them out of each other's test areas. */
	private static final int PADDING = 32;

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.DIRT);
			}
		}
		helper.setBlock(CHEST, Blocks.CHEST);
		method.invoke(this, helper);
	}

	/** A 4-log oak with a small canopy, trunk at (x, 1..4, z). */
	private static void tree(GameTestHelper helper, int x, int z, boolean playerPlacedLeaves) {
		BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState()
			.setValue(LeavesBlock.DISTANCE, 1)
			.setValue(LeavesBlock.PERSISTENT, playerPlacedLeaves);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				helper.setBlock(x + dx, 4, z + dz, leaves);
			}
		}
		helper.setBlock(x, 5, z, leaves);
		for (int y = 1; y <= 4; y++) {
			helper.setBlock(x, y, z, Blocks.OAK_LOG);
		}
	}

	/** A 7-log oak, trunk at (x, 1..7, z), with a canopy at y 6..7 that the bot cannot reach from the ground. */
	private static void tallTree(GameTestHelper helper, int x, int z) {
		BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState()
			.setValue(LeavesBlock.DISTANCE, 1)
			.setValue(LeavesBlock.PERSISTENT, false);
		for (int y = 6; y <= 7; y++) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					helper.setBlock(x + dx, y, z + dz, leaves);
				}
			}
		}
		for (int y = 1; y <= 7; y++) {
			helper.setBlock(x, y, z, Blocks.OAK_LOG);
		}
	}

	/** No logs, leaves or scaffold blocks are left above the floor. */
	private static void assertAreaCleared(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 7, 7)) {
			if (pos.equals(CHEST)) {
				continue;
			}
			BlockState state = helper.getBlockState(pos);
			helper.assertTrue(!state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES) && !state.is(Blocks.DIRT),
				state.getBlock() + " left at " + pos.toShortString());
		}
	}

	private static AntBot lumberjack(GameTestHelper helper, int x, int z) {
		return lumberjack(helper, x, 1, z);
	}

	private static AntBot lumberjack(GameTestHelper helper, int x, int y, int z) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, x, y, z);
		bot.setHome(helper.absolutePos(CHEST));
		bot.setRole(BotRole.LUMBERJACK);
		return bot;
	}

	/**
	 * A dirt pillar at (x, 1..7, z) that the bot remembers placing as scaffolding, so it starts in cleanup.
	 * Its top blocks are 5 and 6 blocks above the ground, out of reach from it.
	 */
	private static void leftoverPillar(GameTestHelper helper, AntBot bot, int x, int z) {
		LumberjackGoal goal = goal(bot);
		for (int y = 1; y <= 7; y++) {
			helper.setBlock(x, y, z, Blocks.DIRT);
			goal.adoptScaffold(helper.absolutePos(new BlockPos(x, y, z)));
		}
	}

	private static LumberjackGoal goal(AntBot bot) {
		return bot.getGoalSelector().getAvailableGoals().stream()
			.map(WrappedGoal::getGoal)
			.filter(LumberjackGoal.class::isInstance)
			.map(LumberjackGoal.class::cast)
			.findFirst()
			.orElseThrow();
	}

	/** The bot only chops the tree after cleanup is done, so storing its logs shows cleanup finished. */
	private static void assertTreeStored(GameTestHelper helper) {
		Container chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
		helper.assertTrue(chest.countItem(Items.OAK_LOG) == 4, "chest has " + chest.countItem(Items.OAK_LOG) + " logs");
	}

	@GameTest(padding = PADDING, maxTicks = 1200)
	public void takesHighPillarDownWhileStandingOnIt(GameTestHelper helper) {
		tree(helper, 2, 6, false);
		AntBot bot = lumberjack(helper, 5, 8, 5);
		leftoverPillar(helper, bot, 5, 5);

		helper.succeedWhen(() -> {
			for (int y = 1; y <= 7; y++) {
				helper.assertBlockNotPresent(Blocks.DIRT, 5, y, 5);
			}
			assertTreeStored(helper);
		});
	}

	@GameTest(padding = PADDING, maxTicks = 1600)
	public void retriesHighScaffoldOnceTheRestIsDown(GameTestHelper helper) {
		tree(helper, 2, 6, false);
		AntBot bot = lumberjack(helper, 6, 2);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		leftoverPillar(helper, bot, 5, 5);

		helper.succeedWhen(() -> {
			// The top of the pillar is out of reach from the ground once the rest is gone; the bot
			// pillars up to it, and takes that pillar down too.
			assertNoDirtLeft(helper);
			assertTreeStored(helper);
		});
	}

	@GameTest(padding = PADDING, maxTicks = 1200)
	public void givesUpOnScaffoldItCannotReachEvenByPillaring(GameTestHelper helper) {
		tree(helper, 2, 6, false);
		AntBot bot = lumberjack(helper, 6, 2);
		helper.setBlock(5, 7, 5, Blocks.DIRT);
		goal(bot).adoptScaffold(helper.absolutePos(new BlockPos(5, 7, 5)));

		// With no scaffold blocks to pillar with, it gives up on the floating block and moves on.
		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.DIRT, 5, 7, 5);
			assertTreeStored(helper);
		});
	}

	@GameTest(padding = PADDING, maxTicks = 1200)
	public void scaffoldSurvivesSaveAndLoad(GameTestHelper helper) {
		tree(helper, 2, 6, false);
		AntBot bot = lumberjack(helper, 6, 2);
		for (int y = 1; y <= 3; y++) {
			helper.setBlock(5, y, 5, Blocks.DIRT);
			goal(bot).adoptScaffold(helper.absolutePos(new BlockPos(5, y, 5)));
		}
		AntBot copy = reload(helper, bot);
		bot.discard();

		helper.succeedWhen(() -> {
			assertNoDirtLeft(helper);
			assertTreeStored(helper);
			helper.assertTrue(copy.isAlive(), "reloaded bot is gone");
		});
	}

	/** Saves the bot and loads it into a new bot, as when its chunk unloads and loads again. */
	private static AntBot reload(GameTestHelper helper, AntBot bot) {
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
		bot.saveWithoutId(output);
		AntBot copy = helper.spawn(ModEntities.ANT_BOT, 6, 1, 6);
		copy.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), output.buildResult()));
		return copy;
	}

	private static void assertNoDirtLeft(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 7, 7)) {
			helper.assertTrue(!helper.getBlockState(pos).is(Blocks.DIRT), "dirt left at " + pos.toShortString());
		}
	}

	@GameTest(padding = PADDING, maxTicks = 3600)
	public void pillarsUpToWideCanopyAndLeavesNoScaffold(GameTestHelper helper) {
		BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState()
			.setValue(LeavesBlock.DISTANCE, 1)
			.setValue(LeavesBlock.PERSISTENT, false);
		for (int y = 6; y <= 7; y++) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					helper.setBlock(4 + dx, y, 4 + dz, leaves);
				}
			}
		}
		for (int y = 1; y <= 7; y++) {
			helper.setBlock(4, y, 4, Blocks.OAK_LOG);
		}
		AntBot bot = lumberjack(helper, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));

		helper.succeedWhen(() -> {
			assertAreaCleared(helper);
			Container chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
			helper.assertTrue(chest.countItem(Items.OAK_LOG) == 7, "chest has " + chest.countItem(Items.OAK_LOG) + " logs");
		});
	}

	@GameTest(padding = PADDING, maxTicks = 1200)
	public void chopsTreeAndStoresLogsInHomeChest(GameTestHelper helper) {
		tree(helper, 5, 5, false);
		lumberjack(helper, 2, 4);

		helper.succeedWhen(() -> {
			for (int y = 1; y <= 4; y++) {
				helper.assertBlockNotPresent(Blocks.OAK_LOG, 5, y, 5);
			}
			Container chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
			helper.assertTrue(chest.countItem(Items.OAK_LOG) == 4, "chest has " + chest.countItem(Items.OAK_LOG) + " logs");
		});
	}

	@GameTest(padding = PADDING, maxTicks = 2400)
	public void pillarsUpToTallTreeAndClearsLeavesAndPillars(GameTestHelper helper) {
		tallTree(helper, 5, 5);
		AntBot bot = lumberjack(helper, 2, 4);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));

		helper.succeedWhen(() -> {
			assertAreaCleared(helper);
			Container chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
			helper.assertTrue(chest.countItem(Items.OAK_LOG) == 7, "chest has " + chest.countItem(Items.OAK_LOG) + " logs");
		});
	}

	@GameTest(padding = PADDING, maxTicks = 2400)
	public void takesScaffoldFromHomeToReachTallTree(GameTestHelper helper) {
		tallTree(helper, 5, 5);
		helper.getBlockEntity(CHEST, ChestBlockEntity.class).setItem(0, new ItemStack(Items.DIRT, 16));
		lumberjack(helper, 2, 4);

		helper.succeedWhen(() -> {
			assertAreaCleared(helper);
			Container chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
			helper.assertTrue(chest.countItem(Items.OAK_LOG) == 7, "chest has " + chest.countItem(Items.OAK_LOG) + " logs");
		});
	}

	@GameTest(padding = PADDING, maxTicks = 200)
	public void leavesPlayerBuiltLogsAlone(GameTestHelper helper) {
		tree(helper, 5, 5, true);
		AntBot bot = lumberjack(helper, 2, 4);

		helper.runAfterDelay(160, () -> {
			for (int y = 1; y <= 4; y++) {
				helper.assertBlockPresent(Blocks.OAK_LOG, 5, y, 5);
			}
			helper.assertTrue(bot.getInventory().isEmpty(), "bot picked something up");
			helper.succeed();
		});
	}
}
