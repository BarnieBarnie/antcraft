package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.colony.StockTarget;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.goal.LumberjackGoal;
import io.github.barniebarnie.antcraft.entity.goal.WorkerGoal;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.work.Replanting;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.util.ProblemReporter;

public class LumberjackGameTest implements CustomTestMethodInvoker {
	/** The colony core, where bots take the logs. */
	private static final BlockPos CHEST = new BlockPos(1, 1, 1);
	/** Keeps the test areas apart, so no colony posts jobs for another test's trees. */
	private static final int PADDING = 32;

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.DIRT);
			}
		}
		// A colony core that wants only logs, so it posts its trees and orders no crafting.
		helper.setBlock(CHEST, ModBlocks.COLONY_CORE);
		Colony colony = colonyAtHome(helper);
		colony.setRadius(6);
		colony.setStockTargets(List.of(StockTarget.of(ItemTags.LOGS, 64)));
		method.invoke(this, helper);
	}

	private static Container home(GameTestHelper helper) {
		return (Container) helper.getBlockEntity(CHEST, BlockEntity.class);
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

	/**
	 * No logs or scaffold blocks are left above the floor. Leaves may be left: they decay by
	 * themselves once the logs are gone.
	 */
	private static void assertAreaCleared(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 7, 7)) {
			if (pos.equals(CHEST)) {
				continue;
			}
			BlockState state = helper.getBlockState(pos);
			helper.assertTrue(!state.is(BlockTags.LOGS) && !state.is(Blocks.DIRT),
				state.getBlock() + " left at " + pos.toShortString());
		}
	}

	private static AntBot lumberjack(GameTestHelper helper, int x, int z) {
		return lumberjack(helper, x, 1, z);
	}

	/** A member of the colony at {@link #CHEST}. */
	private static AntBot lumberjack(GameTestHelper helper, int x, int y, int z) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, x, y, z);
		ColonyManager.get(helper.getLevel()).join(bot, colonyAtHome(helper));
		return bot;
	}

	/**
	 * A dirt pillar at (x, 1..7, z) that the bot remembers placing as scaffolding, so it starts in cleanup.
	 * Its top blocks are 5 and 6 blocks above the ground, out of reach from it.
	 */
	private static void leftoverPillar(GameTestHelper helper, AntBot bot, int x, int z) {
		for (int y = 1; y <= 7; y++) {
			helper.setBlock(x, y, z, Blocks.DIRT);
			bot.addScaffold(helper.absolutePos(new BlockPos(x, y, z)));
		}
	}

	private static LumberjackGoal goal(AntBot bot) {
		return bot.getGoalSelector().getAvailableGoals().stream()
			.map(WrappedGoal::getGoal)
			.filter(WorkerGoal.class::isInstance)
			.map(goal -> (LumberjackGoal) ((WorkerGoal) goal).executor(JobType.CHOP_TREE))
			.findFirst()
			.orElseThrow();
	}

	/** The bot only takes the tree on after cleanup is done, so storing its logs shows cleanup finished. */
	private static void assertTreeStored(GameTestHelper helper) {
		Container chest = home(helper);
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
		bot.addScaffold(helper.absolutePos(new BlockPos(5, 7, 5)));

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
			bot.addScaffold(helper.absolutePos(new BlockPos(5, y, 5)));
		}
		AntBot copy = reload(helper, bot);
		bot.discard();

		helper.succeedWhen(() -> {
			assertNoDirtLeft(helper);
			assertTreeStored(helper);
			helper.assertTrue(copy.isAlive(), "reloaded bot is gone");
		});
	}

	/**
	 * A bot stands on top of a scaffold pillar left from a tree it was called away from. It takes the
	 * pillar down first, and only then takes another job: harvesting the colony's ripe wheat.
	 */
	@GameTest(padding = PADDING, maxTicks = 1600)
	public void takesScaffoldLeftUpDownBeforeTakingAnotherJob(GameTestHelper helper) {
		// No logs wanted, so harvesting is the only colony work.
		colonyAtHome(helper).setStockTargets(List.of());
		for (int x = 3; x <= 5; x++) {
			helper.setBlock(x, 0, 5, Blocks.FARMLAND);
			helper.setBlock(x, 1, 5, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
		}
		AntBot bot = lumberjack(helper, 6, 8, 2);
		leftoverPillar(helper, bot, 6, 2);

		Container core = home(helper);
		helper.onEachTick(() -> {
			if (core.countItem(Items.WHEAT) > 0 && helper.getBlockState(new BlockPos(6, 1, 2)).is(Blocks.DIRT)) {
				helper.fail("farmed before taking the scaffold down");
			}
		});
		helper.succeedWhen(() -> {
			assertNoDirtLeft(helper);
			helper.assertTrue(bot.getScaffold().isEmpty(), "bot still has scaffold " + bot.getScaffold());
			helper.assertTrue(core.countItem(Items.WHEAT) == 3, "core has " + core.countItem(Items.WHEAT) + " wheat");
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
			Container chest = home(helper);
			helper.assertTrue(chest.countItem(Items.OAK_LOG) == 7, "chest has " + chest.countItem(Items.OAK_LOG) + " logs");
		});
	}

	/**
	 * A 2x2 jungle trunk 8 logs tall, as the trunk of a giant jungle tree. Its top logs need a two
	 * block pillar, but the bot only takes one scaffold block at a time from home: it has to come
	 * back down for more mid-tree and go up again.
	 */
	@GameTest(padding = PADDING, maxTicks = 4800)
	public void restocksScaffoldMidTreeToFellATall2x2Trunk(GameTestHelper helper) {
		BlockState leaves = Blocks.JUNGLE_LEAVES.defaultBlockState()
			.setValue(LeavesBlock.DISTANCE, 1)
			.setValue(LeavesBlock.PERSISTENT, false);
		for (int dx = -1; dx <= 2; dx++) {
			for (int dz = -1; dz <= 2; dz++) {
				helper.setBlock(4 + dx, 9, 4 + dz, leaves);
			}
		}
		for (int y = 1; y <= 8; y++) {
			for (BlockPos column : List.of(new BlockPos(4, y, 4), new BlockPos(5, y, 4), new BlockPos(4, y, 5), new BlockPos(5, y, 5))) {
				helper.setBlock(column, Blocks.JUNGLE_LOG);
			}
		}
		Container chest = home(helper);
		chest.setItem(0, new ItemStack(Items.DIRT, 16));
		AntBot bot = lumberjack(helper, 1, 6);
		goal(bot).setScaffoldStock(1);

		helper.succeedWhen(() -> {
			for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 9, 7)) {
				helper.assertTrue(!helper.getBlockState(pos).is(BlockTags.LOGS), "log left at " + pos.toShortString());
				helper.assertTrue(!helper.getBlockState(pos).is(Blocks.DIRT), "dirt left at " + pos.toShortString());
			}
			helper.assertTrue(chest.countItem(Items.JUNGLE_LOG) == 32, "chest has " + chest.countItem(Items.JUNGLE_LOG) + " logs");
		});
	}

	/** An oak whose branches are joined to the trunk and to each other only at a corner. */
	@GameTest(padding = PADDING, maxTicks = 3600)
	public void fellsBranchingOakIncludingDiagonalBranches(GameTestHelper helper) {
		BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState()
			.setValue(LeavesBlock.DISTANCE, 1)
			.setValue(LeavesBlock.PERSISTENT, false);
		for (int y = 1; y <= 5; y++) {
			helper.setBlock(3, y, 3, Blocks.OAK_LOG);
		}
		List<BlockPos> branches = List.of(new BlockPos(4, 6, 4), new BlockPos(5, 7, 5), new BlockPos(2, 6, 2), new BlockPos(1, 7, 1));
		branches.forEach(pos -> helper.setBlock(pos, Blocks.OAK_LOG));
		for (BlockPos branch : List.of(new BlockPos(5, 7, 5), new BlockPos(1, 7, 1))) {
			helper.setBlock(branch.above(), leaves);
		}
		home(helper).setItem(0, new ItemStack(Items.DIRT, 16));
		lumberjack(helper, 1, 5);

		helper.succeedWhen(() -> {
			assertAreaCleared(helper);
			Container chest = home(helper);
			helper.assertTrue(chest.countItem(Items.OAK_LOG) == 9, "chest has " + chest.countItem(Items.OAK_LOG) + " logs");
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
			Container chest = home(helper);
			helper.assertTrue(chest.countItem(Items.OAK_LOG) == 4, "chest has " + chest.countItem(Items.OAK_LOG) + " logs");
		});
	}

	@GameTest(padding = PADDING, maxTicks = 2400)
	public void pillarsUpToTallTreeAndClearsPillars(GameTestHelper helper) {
		tallTree(helper, 5, 5);
		AntBot bot = lumberjack(helper, 2, 4);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));

		helper.succeedWhen(() -> {
			assertAreaCleared(helper);
			Container chest = home(helper);
			helper.assertTrue(chest.countItem(Items.OAK_LOG) == 7, "chest has " + chest.countItem(Items.OAK_LOG) + " logs");
		});
	}

	@GameTest(padding = PADDING, maxTicks = 2400)
	public void takesScaffoldFromHomeToReachTallTree(GameTestHelper helper) {
		tallTree(helper, 5, 5);
		home(helper).setItem(0, new ItemStack(Items.DIRT, 16));
		lumberjack(helper, 2, 4);

		helper.succeedWhen(() -> {
			assertAreaCleared(helper);
			Container chest = home(helper);
			helper.assertTrue(chest.countItem(Items.OAK_LOG) == 7, "chest has " + chest.countItem(Items.OAK_LOG) + " logs");
		});
	}

	@GameTest(padding = PADDING, maxTicks = 2400)
	public void takesOnlyDirtFromHomeNotCobblestone(GameTestHelper helper) {
		tallTree(helper, 5, 5);
		Container home = home(helper);
		// Cobblestone first, so a bot that takes any scaffold block takes it.
		home.setItem(0, new ItemStack(Items.COBBLESTONE, 16));
		home.setItem(1, new ItemStack(Items.DIRT, 16));
		AntBot bot = lumberjack(helper, 2, 4);

		helper.succeedWhen(() -> {
			assertAreaCleared(helper);
			helper.assertTrue(home.countItem(Items.OAK_LOG) == 7, "chest has " + home.countItem(Items.OAK_LOG) + " logs");
			helper.assertTrue(home.countItem(Items.COBBLESTONE) == 16, "chest has " + home.countItem(Items.COBBLESTONE) + " cobblestone");
			helper.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE) == 0, "bot took cobblestone");
		});
	}

	@GameTest(padding = PADDING, maxTicks = 2400)
	public void pillarsWithDirtNotCobblestoneItCarries(GameTestHelper helper) {
		tallTree(helper, 5, 5);
		AntBot bot = lumberjack(helper, 2, 4);
		// Cobblestone first, so a bot that places any scaffold block places it.
		bot.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 16));
		bot.getInventory().setItem(1, new ItemStack(Items.DIRT, 16));

		// Cleanup would get placed cobblestone back, so watch the world rather than the inventory.
		helper.onEachTick(() -> {
			for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 7, 7)) {
				if (helper.getBlockState(pos).is(Blocks.COBBLESTONE)) {
					helper.fail("bot placed cobblestone at " + pos.toShortString());
				}
			}
		});
		helper.succeedWhen(() -> {
			assertAreaCleared(helper);
			Container chest = home(helper);
			helper.assertTrue(chest.countItem(Items.OAK_LOG) == 7, "chest has " + chest.countItem(Items.OAK_LOG) + " logs");
			helper.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE) + home(helper).countItem(Items.COBBLESTONE) == 16, "bot lost cobblestone");
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

	@GameTest(padding = PADDING, maxTicks = 1200)
	public void leavesDecayingLeavesForNatureToRemove(GameTestHelper helper) {
		tree(helper, 5, 5, false);
		lumberjack(helper, 2, 4);
		long[] trunkGoneAt = {-1};
		int[] leavesLeft = {-1};
		// Leaves the bot does not need out of the way are not broken. Natural decay is slow
		// (a few per cent per leaf in this window), so nearly all of them are still there.
		helper.onEachTick(() -> {
			if (trunkGoneAt[0] < 0 && countBlocks(helper, BlockTags.LOGS) == 0) {
				trunkGoneAt[0] = helper.getTick();
			}
			if (trunkGoneAt[0] >= 0 && leavesLeft[0] < 0 && helper.getTick() >= trunkGoneAt[0] + 100) {
				leavesLeft[0] = countBlocks(helper, BlockTags.LEAVES);
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(leavesLeft[0] >= 0, "not yet checked the leaves");
			helper.assertTrue(leavesLeft[0] >= 6, "only " + leavesLeft[0] + " of 9 leaves left");
			Container chest = home(helper);
			helper.assertTrue(chest.countItem(Items.OAK_LOG) == 4, "chest has " + chest.countItem(Items.OAK_LOG) + " logs");
		});
	}

	private static int countBlocks(GameTestHelper helper, TagKey<Block> tag) {
		int count = 0;
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 7, 7)) {
			if (helper.getBlockState(pos).is(tag)) {
				count++;
			}
		}
		return count;
	}

	private static List<BlockPos> trunk(GameTestHelper helper, int x, int z) {
		List<BlockPos> logs = new ArrayList<>();
		for (int y = 1; y <= 4; y++) {
			logs.add(helper.absolutePos(new BlockPos(x, y, z)));
		}
		return logs;
	}

	/**
	 * A colony lumberjack resuming a chop job whose bottom log is already gone replants at the job's
	 * original base on the ground, not at the lowest log still standing.
	 */
	@GameTest(padding = PADDING, maxTicks = 1600)
	public void colonyLumberjackReplantsResumedTreeAtItsOriginalBase(GameTestHelper helper) {
		Colony colony = colonyAtHome(helper);
		List<BlockPos> logs = trunk(helper, 5, 5);
		for (int y = 2; y <= 4; y++) {
			helper.setBlock(5, y, 5, Blocks.OAK_LOG);
		}
		colony.board().post(JobType.CHOP_TREE, logs.getFirst(), logs);
		AntBot bot = colonyLumberjack(helper, colony, 2, 2);
		bot.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));

		helper.succeedWhen(() -> {
			helper.assertTrue(countBlocks(helper, BlockTags.LOGS) == 0, "logs left");
			helper.assertBlockPresent(Blocks.OAK_SAPLING, 5, 1, 5);
		});
	}

	/**
	 * A 4-log oak with a single leaf on top, out of the way, trunk at (x, 1..4, z). Once the logs are
	 * gone the leaf is removed, so no sapling can drop by chance: the test decides when one is available.
	 */
	private static void bareTree(GameTestHelper helper, int x, int z) {
		bareTree(helper, x, z, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
	}

	/** Like {@link #bareTree(GameTestHelper, int, int)}, of another kind of tree. */
	private static void bareTree(GameTestHelper helper, int x, int z, Block log, Block leaf) {
		for (int y = 1; y <= 4; y++) {
			helper.setBlock(x, y, z, log);
		}
		helper.setBlock(x, 5, z, leaf.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1).setValue(LeavesBlock.PERSISTENT, false));
		helper.onEachTick(() -> {
			if (countBlocks(helper, BlockTags.LOGS) == 0 && helper.getBlockState(new BlockPos(x, 5, z)).is(BlockTags.LEAVES)) {
				helper.setBlock(x, 5, z, Blocks.AIR);
			}
		});
	}

	private static Colony colonyAtHome(GameTestHelper helper) {
		return ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(CHEST)).orElseThrow();
	}

	private static AntBot colonyLumberjack(GameTestHelper helper, Colony colony, int x, int z) {
		return lumberjack(helper, x, z);
	}

	@GameTest(padding = PADDING, maxTicks = 1600)
	public void nearerLumberjackReplantsTreeFelledWithoutSapling(GameTestHelper helper) {
		Colony colony = colonyAtHome(helper);
		bareTree(helper, 5, 5);
		AntBot feller = colonyLumberjack(helper, colony, 2, 2);
		BlockPos base = helper.absolutePos(new BlockPos(5, 1, 5));
		AntBot[] planter = {null};
		helper.onEachTick(() -> {
			// The feller had no sapling and posted a replant job; it is now sent off, out of the way, as on another errand.
			if (planter[0] == null && colony.board().has(JobType.REPLANT, base)) {
				feller.setWalkTarget(helper.absolutePos(new BlockPos(0, 1, 7)));
				planter[0] = colonyLumberjack(helper, colony, 6, 3);
				// Once the feller is off: with nothing left to store it would otherwise take it before it leaves.
				helper.runAfterDelay(20, () -> helper.spawnItem(Items.OAK_SAPLING, new BlockPos(6, 1, 6)));
			}
			if (colony.board().jobOf(feller.getUUID()).filter(job -> job.type() == JobType.REPLANT).isPresent()) {
				helper.fail("the feller claimed the replant job instead of the nearer lumberjack");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(planter[0] != null, "no replant job posted yet");
			helper.assertBlockPresent(Blocks.OAK_SAPLING, 5, 1, 5);
			helper.assertTrue(!colony.board().has(JobType.REPLANT, base), "replant job still on the board");
			Container core = home(helper);
			helper.assertTrue(core.countItem(Items.OAK_LOG) == 4, "core has " + core.countItem(Items.OAK_LOG) + " logs");
		});
	}

	private static int carried(AntBot bot, net.minecraft.world.item.Item item) {
		return bot.getInventory().countItem(item) + (bot.getMainHandItem().is(item) ? bot.getMainHandItem().getCount() : 0);
	}

	/**
	 * A colony tree whose lower trunk was chopped and whose leaves have decayed since: what is left
	 * no longer looks like a tree, but its chop job remembers the logs, so a lumberjack fells the rest.
	 */
	@GameTest(padding = PADDING, maxTicks = 1600)
	public void fellsTheLeaflessRestOfAColonyTree(GameTestHelper helper) {
		Colony colony = colonyAtHome(helper);
		List<BlockPos> logs = new ArrayList<>();
		for (int y = 1; y <= 4; y++) {
			logs.add(helper.absolutePos(new BlockPos(5, y, 5)));
		}
		for (int y = 2; y <= 4; y++) {
			helper.setBlock(5, y, 5, Blocks.OAK_LOG);
		}
		colony.board().post(JobType.CHOP_TREE, logs.getFirst(), logs);
		colonyLumberjack(helper, colony, 2, 2);

		helper.succeedWhen(() -> {
			helper.assertTrue(countBlocks(helper, BlockTags.LOGS) == 0, "logs left");
			helper.assertTrue(colony.board().jobs().stream().noneMatch(job -> job.type() == JobType.CHOP_TREE), "chop job left: " + colony.board().jobs());
		});
	}

	/**
	 * A colony tree whose top logs are out of reach, with no scaffold anywhere: the lumberjack gives the
	 * job back to retry later. Once the core holds scaffold, a later visit fells the rest.
	 */
	@GameTest(padding = PADDING, maxTicks = 2400)
	public void comesBackForLogsItCouldNotReach(GameTestHelper helper) {
		Colony colony = colonyAtHome(helper);
		tallTree(helper, 5, 5);
		colonyLumberjack(helper, colony, 2, 4);
		boolean[] stocked = {false};
		helper.onEachTick(() -> {
			if (!stocked[0] && colony.board().jobs().stream().anyMatch(job -> job.type() == JobType.CHOP_LOG && job.tries() == 1)) {
				stocked[0] = true;
				(home(helper)).setItem(0, new ItemStack(Items.DIRT, 16));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(stocked[0], "the tree was not given back to retry");
			helper.assertTrue(countBlocks(helper, BlockTags.LOGS) == 0, "logs left");
			helper.assertTrue(colony.board().jobs().stream().noneMatch(job -> job.type() == JobType.CHOP_TREE), "chop job left: " + colony.board().jobs());
		});
	}

	/** Leafless logs no tree job covers, like a player's log fence, are left alone. */
	@GameTest(padding = PADDING, maxTicks = 400)
	public void leavesLeaflessLogsOfNoKnownTreeAlone(GameTestHelper helper) {
		Colony colony = colonyAtHome(helper);
		for (int y = 1; y <= 3; y++) {
			helper.setBlock(5, y, 5, Blocks.OAK_LOG);
		}
		AntBot bot = colonyLumberjack(helper, colony, 2, 2);

		helper.runAfterDelay(300, () -> {
			helper.assertTrue(countBlocks(helper, BlockTags.LOGS) == 3, "logs were chopped");
			helper.assertTrue(colony.board().jobs().isEmpty(), "jobs posted: " + colony.board().jobs());
			helper.assertTrue(carried(bot, Items.OAK_LOG) == 0, "bot has logs");
			helper.succeed();
		});
	}

	@GameTest(padding = PADDING, maxTicks = 400)
	public void swapsToBetterAxeInColonyCore(GameTestHelper helper) {
		Colony colony = colonyAtHome(helper);
		Container core = home(helper);
		core.setItem(0, new ItemStack(Items.STONE_AXE));
		AntBot bot = colonyLumberjack(helper, colony, 5, 5);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_AXE));

		helper.succeedWhen(() -> {
			helper.assertTrue(carried(bot, Items.STONE_AXE) == 1, "lumberjack has no stone axe");
			helper.assertTrue(carried(bot, Items.WOODEN_AXE) == 0, "lumberjack kept its wooden axe");
			helper.assertTrue(core.countItem(Items.WOODEN_AXE) == 1, "wooden axe not back in the core");
		});
	}

	@GameTest(padding = PADDING, maxTicks = 400)
	public void ordersBetterAxeTheColonyCanMake(GameTestHelper helper) {
		Colony colony = colonyAtHome(helper);
		Container core = home(helper);
		core.setItem(0, new ItemStack(Items.COBBLESTONE, 3));
		core.setItem(1, new ItemStack(Items.STICK, 2));
		AntBot bot = colonyLumberjack(helper, colony, 5, 5);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_AXE));

		helper.succeedWhen(() -> helper.assertTrue(colony.orders().getOrDefault(Items.STONE_AXE, 0) == 1, "orders: " + colony.orders()));
	}

	@GameTest(padding = PADDING, maxTicks = 1600)
	public void replantsWithSaplingFromColonyCore(GameTestHelper helper) {
		Colony colony = colonyAtHome(helper);
		Container core = home(helper);
		core.setItem(0, new ItemStack(Items.OAK_SAPLING));
		bareTree(helper, 5, 5);
		colonyLumberjack(helper, colony, 2, 2);

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.OAK_SAPLING, 5, 1, 5);
			helper.assertTrue(core.countItem(Items.OAK_SAPLING) == 0, "sapling still in the core");
			helper.assertTrue(colony.board().jobs().isEmpty(), "jobs left on the board: " + colony.board().jobs());
		});
	}

	@GameTest(padding = PADDING, maxTicks = 200)
	public void dropsReplantJobsForReplantedOrUnplantableBases(GameTestHelper helper) {
		Colony colony = colonyAtHome(helper);
		BlockPos replanted = new BlockPos(5, 1, 5);
		helper.setBlock(replanted, Blocks.OAK_SAPLING);
		BlockPos paved = new BlockPos(3, 1, 3);
		helper.setBlock(paved.below(), Blocks.STONE);
		BlockPos open = new BlockPos(6, 1, 2);
		for (BlockPos pos : List.of(replanted, paved, open)) {
			colony.board().post(JobType.REPLANT, helper.absolutePos(pos));
		}

		helper.succeedWhen(() -> {
			helper.assertTrue(!colony.board().has(JobType.REPLANT, helper.absolutePos(replanted)), "job kept for a replanted base");
			helper.assertTrue(!colony.board().has(JobType.REPLANT, helper.absolutePos(paved)), "job kept for a base on stone");
			helper.assertTrue(colony.board().has(JobType.REPLANT, helper.absolutePos(open)), "job dropped for a plantable base");
		});
	}

	@GameTest(padding = PADDING, maxTicks = 1200)
	public void doesNotPillarUpToDropsItCannotWalkTo(GameTestHelper helper) {
		tree(helper, 5, 5, false);
		// A drop stuck up high, as on a canopy that is left to decay. Pillaring up to it would
		// leave scaffolding behind, since collecting is done after cleanup.
		for (int y = 1; y <= 4; y++) {
			helper.setBlock(2, y, 6, Blocks.STONE);
		}
		helper.spawnItem(Items.APPLE, new BlockPos(2, 5, 6));
		AntBot bot = lumberjack(helper, 2, 3);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));

		helper.succeedWhen(() -> {
			assertTreeStored(helper);
			assertNoDirtLeft(helper);
			helper.assertTrue(bot.getInventory().countItem(Items.DIRT) + home(helper).countItem(Items.DIRT) == 16, "bot placed dirt it did not take down");
		});
	}

	@GameTest(padding = PADDING, maxTicks = 1200)
	public void breaksLeavesInTheWayToReachTheLogs(GameTestHelper helper) {
		tree(helper, 6, 6, false);
		// The bot starts shut in by leaves, out of reach of the tree.
		BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
		for (int y = 1; y <= 2; y++) {
			helper.setBlock(1, y, 4, leaves);
			helper.setBlock(3, y, 4, leaves);
			helper.setBlock(2, y, 3, leaves);
			helper.setBlock(2, y, 5, leaves);
		}
		helper.setBlock(2, 3, 4, leaves);
		lumberjack(helper, 2, 4);

		helper.succeedWhen(() -> {
			for (int y = 1; y <= 4; y++) {
				helper.assertBlockNotPresent(Blocks.OAK_LOG, 6, y, 6);
			}
			Container chest = home(helper);
			helper.assertTrue(chest.countItem(Items.OAK_LOG) == 4, "chest has " + chest.countItem(Items.OAK_LOG) + " logs");
		});
	}

	/**
	 * The bottom log of a tree is walled in with bedrock on every side, within reach from the ground:
	 * like a player it cannot break the log through the wall, so it fells it only once the log above
	 * is gone and it can see it from above.
	 */
	@GameTest(padding = PADDING, maxTicks = 1200)
	public void fellsALogBehindAWallOnlyOnceItSeesIt(GameTestHelper helper) {
		tree(helper, 5, 5, false);
		for (BlockPos wall : List.of(new BlockPos(4, 1, 5), new BlockPos(6, 1, 5), new BlockPos(5, 1, 4), new BlockPos(5, 1, 6))) {
			helper.setBlock(wall, Blocks.BEDROCK);
		}
		lumberjack(helper, 2, 5);

		helper.onEachTick(() -> {
			if (!helper.getBlockState(new BlockPos(5, 1, 5)).is(Blocks.OAK_LOG) && helper.getBlockState(new BlockPos(5, 2, 5)).is(Blocks.OAK_LOG)) {
				helper.fail("chopped the walled-in log through the bedrock");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(countBlocks(helper, BlockTags.LOGS) == 0, "logs left");
			assertTreeStored(helper);
		});
	}

	/** A scaffold block walled in with bedrock on every side: cleanup cannot see it, so it leaves it rather than breaking through. */
	@GameTest(padding = PADDING, maxTicks = 1200)
	public void cleanupLeavesScaffoldItCannotSee(GameTestHelper helper) {
		tree(helper, 2, 6, false);
		AntBot bot = lumberjack(helper, 6, 2);
		BlockPos hidden = new BlockPos(5, 1, 5);
		helper.setBlock(hidden, Blocks.DIRT);
		for (BlockPos wall : List.of(hidden.west(), hidden.east(), hidden.north(), hidden.south(), hidden.above())) {
			helper.setBlock(wall, Blocks.BEDROCK);
		}
		bot.addScaffold(helper.absolutePos(hidden));

		helper.onEachTick(() -> {
			if (!helper.getBlockState(hidden).is(Blocks.DIRT)) {
				helper.fail("took the walled-in scaffold down through the bedrock");
			}
		});
		helper.succeedWhen(() -> assertTreeStored(helper));
	}

	/** Two colony lumberjacks fell a tall tree together, a log each at a time, and the rest of it is never posted as another tree. */
	@GameTest(padding = PADDING, maxTicks = 2400)
	public void colonyLumberjacksFellATreeTogetherWithoutPostingItAgain(GameTestHelper helper) {
		Colony colony = colonyAtHome(helper);
		tallTree(helper, 5, 5);
		AntBot a = colonyLumberjack(helper, colony, 2, 4);
		AntBot b = colonyLumberjack(helper, colony, 4, 2);
		a.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		b.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		Set<UUID> choppers = new HashSet<>();
		helper.onEachTick(() -> {
			List<Job> trees = colony.board().jobs().stream().filter(job -> job.type() == JobType.CHOP_TREE).toList();
			if (trees.size() > 1) {
				helper.fail("tree posted again while being felled: " + trees);
			}
			colony.board().jobs().stream().filter(job -> job.type() == JobType.CHOP_LOG).forEach(job -> job.claimedBy().ifPresent(choppers::add));
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(countBlocks(helper, BlockTags.LOGS) == 0, "logs left");
			helper.assertTrue(choppers.size() == 2, "logs chopped by " + choppers.size() + " bots");
		});
	}

	/**
	 * The lumberjack stands inside a ring of dirt another bot placed as scaffolding, with the tree
	 * outside. Digging through would be quicker, but it pillars over the ring instead.
	 */
	@GameTest(padding = PADDING, maxTicks = 1600)
	public void leavesScaffoldOfOtherBotsAlone(GameTestHelper helper) {
		tree(helper, 6, 1, false);
		// The owner is walled in with barriers, out of reach of its scaffold, so it cannot take it down itself.
		AntBot owner = helper.spawn(ModEntities.ANT_BOT, 7, 1, 7);
		for (BlockPos wall : List.of(new BlockPos(6, 1, 7), new BlockPos(7, 1, 6), new BlockPos(8, 1, 7), new BlockPos(7, 1, 8))) {
			helper.setBlock(wall, Blocks.BARRIER);
			helper.setBlock(wall.above(), Blocks.BARRIER);
		}
		helper.setBlock(7, 3, 7, Blocks.BARRIER);
		List<BlockPos> ring = new ArrayList<>();
		for (int x = 0; x <= 2; x++) {
			for (int z = 4; z <= 6; z++) {
				if (x == 1 && z == 5) {
					continue;
				}
				for (int y = 1; y <= 2; y++) {
					ring.add(new BlockPos(x, y, z));
					helper.setBlock(x, y, z, Blocks.DIRT);
				}
			}
		}
		AntBot bot = lumberjack(helper, 1, 5);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		helper.onEachTick(() -> {
			// The owner gives up on scaffold it cannot reach; keep the ring its scaffold.
			ring.forEach(pos -> owner.getScaffold().add(helper.absolutePos(pos)));
			for (BlockPos pos : ring) {
				if (!helper.getBlockState(pos).is(Blocks.DIRT)) {
					helper.fail("the other bot's scaffold at " + pos.toShortString() + " was broken");
				}
			}
		});
		helper.succeedWhen(() -> {
			for (int y = 1; y <= 4; y++) {
				helper.assertBlockNotPresent(Blocks.OAK_LOG, 6, y, 1);
			}
		});
	}

	/** A short dark oak: a 2x2 trunk at (4..5, 1..3, 4..5) with a leaf layer on top. */
	private static void darkOak(GameTestHelper helper) {
		BlockState leaves = Blocks.DARK_OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1).setValue(LeavesBlock.PERSISTENT, false);
		for (int x = 3; x <= 6; x++) {
			for (int z = 3; z <= 6; z++) {
				helper.setBlock(x, 4, z, leaves);
			}
		}
		for (int y = 1; y <= 3; y++) {
			for (int x = 4; x <= 5; x++) {
				for (int z = 4; z <= 5; z++) {
					helper.setBlock(x, y, z, Blocks.DARK_OAK_LOG);
				}
			}
		}
	}

	/** At most how long three bots take to fell a {@link #darkOak} together; one bot alone takes longer. */
	private static final int SWARM_TICKS = 500;

	@GameTest(padding = PADDING, maxTicks = 800)
	public void threeBotsFellOneTreeTogether(GameTestHelper helper) {
		Colony colony = colonyAtHome(helper);
		darkOak(helper);
		List<AntBot> bots = List.of(colonyLumberjack(helper, colony, 1, 6), colonyLumberjack(helper, colony, 6, 1), colonyLumberjack(helper, colony, 7, 7));
		Set<UUID> choppers = new HashSet<>();
		long[] felledAt = {-1};
		helper.onEachTick(() -> {
			bots.forEach(bot -> colony.board().jobOf(bot.getUUID())
				.filter(job -> job.type() == JobType.CHOP_LOG).ifPresent(job -> choppers.add(bot.getUUID())));
			if (felledAt[0] < 0 && countBlocks(helper, BlockTags.LOGS) == 0) {
				felledAt[0] = helper.getTick();
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(felledAt[0] >= 0, "logs left");
			helper.assertTrue(choppers.size() == 3, "logs chopped by " + choppers.size() + " bots");
			helper.assertTrue(felledAt[0] <= SWARM_TICKS, "three bots took " + felledAt[0] + " ticks");
		});
	}

	@GameTest(padding = PADDING, maxTicks = 1600)
	public void oneBotAloneFellsTheTreeSlowerThanThree(GameTestHelper helper) {
		Colony colony = colonyAtHome(helper);
		darkOak(helper);
		colonyLumberjack(helper, colony, 1, 6);
		long[] felledAt = {-1};
		helper.onEachTick(() -> {
			if (felledAt[0] < 0 && countBlocks(helper, BlockTags.LOGS) == 0) {
				felledAt[0] = helper.getTick();
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(felledAt[0] >= 0, "logs left");
			helper.assertTrue(felledAt[0] > SWARM_TICKS, "one bot alone took only " + felledAt[0] + " ticks");
		});
	}

	private static void assertDarkOakSaplingSquare(GameTestHelper helper) {
		helper.assertTrue(countBlocks(helper, BlockTags.LOGS) == 0, "logs left");
		for (int x = 4; x <= 5; x++) {
			for (int z = 4; z <= 5; z++) {
				helper.assertBlockPresent(Blocks.DARK_OAK_SAPLING, x, 1, z);
			}
		}
	}

	/** A dark oak only grows back from four saplings in a square, so a felled 2x2 trunk gets four of its own kind. */
	@GameTest(padding = PADDING, maxTicks = 1600)
	public void replantsFelled2x2DarkOakWithFourSaplings(GameTestHelper helper) {
		darkOak(helper);
		AntBot bot = lumberjack(helper, 1, 6);
		// An oak sapling first, which would not grow a dark oak back.
		bot.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
		bot.getInventory().addItem(new ItemStack(Items.DARK_OAK_SAPLING, 4));

		helper.succeedWhen(() -> assertDarkOakSaplingSquare(helper));
	}

	/** A colony lumberjack without saplings posts a replant job for the whole square, then plants it from the core. */
	@GameTest(padding = PADDING, maxTicks = 1600)
	public void colonyReplantsFelled2x2DarkOakFromTheCore(GameTestHelper helper) {
		Colony colony = colonyAtHome(helper);
		Container core = home(helper);
		core.setItem(0, new ItemStack(Items.OAK_SAPLING, 4));
		core.setItem(1, new ItemStack(Items.DARK_OAK_SAPLING, 4));
		darkOak(helper);
		colonyLumberjack(helper, colony, 1, 6);

		helper.succeedWhen(() -> {
			assertDarkOakSaplingSquare(helper);
			helper.assertTrue(colony.board().jobs().stream().noneMatch(job -> job.type() == JobType.REPLANT), "replant job left: " + colony.board().jobs());
		});
	}

	/** A felled birch is replanted with a birch sapling, not the oak sapling the lumberjack carries first, so a birch forest stays birch. */
	@GameTest(padding = PADDING, maxTicks = 1200)
	public void replantsFelledBirchWithBirchSapling(GameTestHelper helper) {
		bareTree(helper, 5, 5, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES);
		AntBot bot = lumberjack(helper, 2, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
		bot.getInventory().addItem(new ItemStack(Items.BIRCH_SAPLING));

		helper.succeedWhen(() -> {
			helper.assertTrue(countBlocks(helper, BlockTags.LOGS) == 0, "logs left");
			helper.assertBlockPresent(Blocks.BIRCH_SAPLING, 5, 1, 5);
		});
	}

	/** A colony lumberjack without saplings posts a birch replant job for a felled birch, and takes the birch sapling from the core, not the oak one. */
	@GameTest(padding = PADDING, maxTicks = 1600)
	public void colonyReplantsFelledBirchWithBirchSaplingFromTheCore(GameTestHelper helper) {
		Colony colony = colonyAtHome(helper);
		Container core = home(helper);
		core.setItem(0, new ItemStack(Items.OAK_SAPLING));
		core.setItem(1, new ItemStack(Items.BIRCH_SAPLING));
		bareTree(helper, 5, 5, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES);
		colonyLumberjack(helper, colony, 2, 2);
		Set<Optional<net.minecraft.world.item.Item>> kinds = new HashSet<>();
		helper.onEachTick(() -> colony.board().jobs().stream()
			.filter(job -> job.type() == JobType.REPLANT)
			.forEach(job -> kinds.add(job.item())));

		helper.succeedWhen(() -> {
			helper.assertTrue(kinds.equals(Set.of(Optional.of(Items.BIRCH_SAPLING))), "replant jobs needed " + kinds);
			helper.assertBlockPresent(Blocks.BIRCH_SAPLING, 5, 1, 5);
			helper.assertTrue(core.countItem(Items.OAK_SAPLING) == 1, "oak sapling taken from the core");
		});
	}

	/** The four spots of a 2x2 trunk at (4..5, 1, 4..5), the first of them built over with cobblestone. */
	private static List<BlockPos> partlyBuiltOverSquare(GameTestHelper helper) {
		helper.setBlock(4, 1, 4, Blocks.COBBLESTONE);
		return List.of(helper.absolutePos(new BlockPos(4, 1, 4)), helper.absolutePos(new BlockPos(5, 1, 4)),
			helper.absolutePos(new BlockPos(4, 1, 5)), helper.absolutePos(new BlockPos(5, 1, 5)));
	}

	private static void assertOneSpruceSaplingBesideTheCobblestone(GameTestHelper helper) {
		helper.assertBlockPresent(Blocks.COBBLESTONE, 4, 1, 4);
		long saplings = BlockPos.betweenClosedStream(4, 1, 4, 5, 1, 5).filter(pos -> helper.getBlockState(pos).is(Blocks.SPRUCE_SAPLING)).count();
		helper.assertTrue(saplings == 1, saplings + " spruce saplings planted");
	}

	/** A colony replant job for a partly built-over 2x2 spruce site is taken with one spruce sapling in the core. */
	@GameTest(padding = PADDING, maxTicks = 600)
	public void colonyReplantsAPartlyBuiltOver2x2SiteAs1x1FromTheCore(GameTestHelper helper) {
		Colony colony = colonyAtHome(helper);
		Container core = home(helper);
		core.setItem(0, new ItemStack(Items.SPRUCE_SAPLING));
		List<BlockPos> spots = partlyBuiltOverSquare(helper);
		colony.board().post(JobType.REPLANT, spots.getFirst(), spots, Optional.of(Items.SPRUCE_SAPLING), helper.getLevel().getGameTime());
		colonyLumberjack(helper, colony, 2, 2);

		helper.succeedWhen(() -> {
			assertOneSpruceSaplingBesideTheCobblestone(helper);
			helper.assertTrue(colony.board().jobs().stream().noneMatch(job -> job.type() == JobType.REPLANT), "replant job left: " + colony.board().jobs());
		});
	}

	/** A colony lumberjack knocked into a pool 2 deep, with no scaffold anywhere, swims out and fells its tree. */
	@GameTest(padding = PADDING, maxTicks = 1200)
	public void swimsOutOfAPoolAndFellsItsTree(GameTestHelper helper) {
		Colony colony = colonyAtHome(helper);
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, -1, z, Blocks.DIRT);
				helper.setBlock(x, -2, z, Blocks.DIRT);
			}
		}
		for (int x = 2; x <= 4; x++) {
			for (int z = 3; z <= 5; z++) {
				helper.setBlock(x, -1, z, Blocks.WATER);
				helper.setBlock(x, 0, z, Blocks.WATER);
			}
		}
		tree(helper, 6, 6, false);
		AntBot bot = lumberjack(helper, 3, 0, 4);
		ColonyManager.get(helper.getLevel()).join(bot, colony);

		helper.succeedWhen(() -> {
			helper.assertTrue(countBlocks(helper, BlockTags.LOGS) == 0, "logs left; bot at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
			helper.assertFalse(bot.isInWater(), "still in the water");
		});
	}

	@GameTest(padding = PADDING, maxTicks = 1200)
	public void colonyBotOfAnotherRoleFellsATreeFromItsJob(GameTestHelper helper) {
		Colony colony = colonyAtHome(helper);
		bareTree(helper, 5, 5);
		AntBot bot = colonyLumberjack(helper, colony, 2, 2);

		helper.succeedWhen(() -> {
			helper.assertTrue(countBlocks(helper, BlockTags.LOGS) == 0, "logs left");
			helper.assertTrue(colony.board().jobs().stream().noneMatch(job -> job.type() == JobType.CHOP_TREE), "chop job left: " + colony.board().jobs());
			// Done with the job, it brings the logs home rather than keep them from the colony.
			Container core = home(helper);
			helper.assertTrue(core.countItem(Items.OAK_LOG) == 4, "core has " + core.countItem(Items.OAK_LOG) + " logs");
			helper.assertTrue(carried(bot, Items.OAK_LOG) == 0, "guard kept " + carried(bot, Items.OAK_LOG) + " logs");
		});
	}

	/**
	 * Logs a bot carries count as colony stock, which crafters cannot use, so with trees still to fell and an
	 * order for planks it brings the first tree's logs to the core rather than keep them until the last.
	 */
	@GameTest(padding = PADDING, maxTicks = 2400)
	public void bringsLogsToTheCoreForAnOrderWhileTreesAreLeft(GameTestHelper helper) {
		Colony colony = colonyAtHome(helper);
		bareTree(helper, 5, 5);
		bareTree(helper, 5, 2);
		bareTree(helper, 2, 6);
		colony.addOrder(Items.OAK_PLANKS, 4);
		colonyLumberjack(helper, colony, 2, 2);
		boolean[] storedWithTreesLeft = {false};
		helper.onEachTick(() -> {
			Container core = home(helper);
			if (core.countItem(Items.OAK_LOG) + core.countItem(Items.OAK_PLANKS) > 0 && countBlocks(helper, BlockTags.LOGS) > 0) {
				storedWithTreesLeft[0] = true;
			}
		});

		helper.succeedWhen(() -> helper.assertTrue(storedWithTreesLeft[0],
			"core has " + home(helper).countItem(Items.OAK_LOG) + " logs, " + countBlocks(helper, BlockTags.LOGS) + " logs standing"));
	}

	@GameTest(padding = PADDING, maxTicks = 1200)
	public void twoColonyBotsEachTakeTheTreeNearerToThem(GameTestHelper helper) {
		Colony colony = colonyAtHome(helper);
		bareTree(helper, 2, 5);
		bareTree(helper, 5, 2);
		AntBot west = colonyLumberjack(helper, colony, 1, 6);
		AntBot east = colonyLumberjack(helper, colony, 6, 1);
		BlockPos westTree = helper.absolutePos(new BlockPos(2, 1, 5));
		BlockPos eastTree = helper.absolutePos(new BlockPos(5, 1, 2));
		Set<BlockPos> felled = new HashSet<>();
		helper.onEachTick(() -> {
			for (AntBot bot : List.of(west, east)) {
				BlockPos nearer = bot == west ? westTree : eastTree;
				colony.board().jobOf(bot.getUUID()).filter(job -> job.type() == JobType.CHOP_LOG).ifPresent(job -> {
					BlockPos trunk = job.target().atY(nearer.getY());
					if (!trunk.equals(nearer)) {
						helper.fail("a bot took a log of the tree further from it, at " + job.target().subtract(helper.absolutePos(BlockPos.ZERO)));
					}
					felled.add(trunk);
				});
			}
		});

		helper.succeedWhen(() -> {
			helper.assertTrue(countBlocks(helper, BlockTags.LOGS) == 0, "logs left");
			helper.assertTrue(felled.equals(Set.of(westTree, eastTree)), "trees claimed: " + felled);
		});
	}

	@GameTest(padding = PADDING, maxTicks = 800)
	public void replantJobIsTakenByABotThatIsNotALumberjack(GameTestHelper helper) {
		Colony colony = colonyAtHome(helper);
		BlockPos base = helper.absolutePos(new BlockPos(5, 1, 5));
		colony.board().post(JobType.REPLANT, base, List.of(base), Optional.of(Items.OAK_SAPLING), helper.getLevel().getGameTime());
		AntBot bot = colonyLumberjack(helper, colony, 2, 2);
		bot.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.OAK_SAPLING, 5, 1, 5);
			helper.assertTrue(!colony.board().has(JobType.REPLANT, base), "replant job still on the board");
		});
	}
}
