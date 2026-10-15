package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.entity.goal.LumberjackGoal;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
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

	@GameTest(padding = PADDING, maxTicks = 1600)
	public void takesScaffoldDownAfterPlayerChangesRole(GameTestHelper helper) {
		scaffoldThenNewRole(helper, AntBot::setRole);
	}

	@GameTest(padding = PADDING, maxTicks = 1600)
	public void takesScaffoldDownAfterColonyChangesRole(GameTestHelper helper) {
		scaffoldThenNewRole(helper, AntBot::assignRole);
	}

	/**
	 * A colony lumberjack stands on top of its scaffold pillar when it is made a farmer. It takes
	 * the pillar down first, and then harvests the colony's ripe wheat.
	 */
	private static void scaffoldThenNewRole(GameTestHelper helper, BiConsumer<AntBot, BotRole> changeRole) {
		BlockPos corePos = new BlockPos(1, 1, 6);
		helper.setBlock(corePos, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(corePos)).orElseThrow();
		colony.setRadius(6);
		// No default stock targets, so harvesting is the only colony work.
		colony.setStockTargets(List.of());
		for (int x = 3; x <= 5; x++) {
			helper.setBlock(x, 0, 5, Blocks.FARMLAND);
			helper.setBlock(x, 1, 5, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
		}
		AntBot bot = lumberjack(helper, 6, 8, 2);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		leftoverPillar(helper, bot, 6, 2);
		changeRole.accept(bot, BotRole.FARMER);

		Container core = (Container) helper.getBlockEntity(corePos, BlockEntity.class);
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
	public void pillarsUpToTallTreeAndClearsPillars(GameTestHelper helper) {
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

	@GameTest(padding = PADDING, maxTicks = 2400)
	public void takesOnlyDirtFromHomeNotCobblestone(GameTestHelper helper) {
		tallTree(helper, 5, 5);
		Container home = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
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
			Container chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
			helper.assertTrue(chest.countItem(Items.OAK_LOG) == 7, "chest has " + chest.countItem(Items.OAK_LOG) + " logs");
			helper.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE) == 16, "bot lost cobblestone");
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
			Container chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
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

	@GameTest(padding = PADDING, maxTicks = 1200)
	public void comesBackForSaplingsDroppedByDecayingLeaves(GameTestHelper helper) {
		tree(helper, 5, 5, false);
		lumberjack(helper, 2, 4);
		boolean[] decayed = {false};
		// Once the bot has stored the logs and moved on, the leaves decay and drop a sapling.
		helper.onEachTick(() -> {
			Container chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
			if (!decayed[0] && chest.countItem(Items.OAK_LOG) == 4) {
				decayed[0] = true;
				for (BlockPos pos : BlockPos.betweenClosed(4, 4, 4, 6, 5, 6)) {
					if (helper.getBlockState(pos).is(BlockTags.LEAVES)) {
						helper.setBlock(pos, Blocks.AIR);
					}
				}
				helper.spawnItem(Items.OAK_SAPLING, new BlockPos(6, 4, 6));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(decayed[0], "logs not stored yet");
			helper.assertBlockPresent(Blocks.OAK_SAPLING, 5, 1, 5);
		});
	}

	/**
	 * A solo lumberjack fells a tree without a sapling to replant, then its chunk unloads and loads
	 * again. When the leaves drop a sapling later, the loaded bot still comes back to plant it.
	 */
	@GameTest(padding = PADDING, maxTicks = 1600)
	public void remembersTreeToReplantAcrossReload(GameTestHelper helper) {
		bareTree(helper, 5, 5);
		AntBot bot = lumberjack(helper, 2, 4);
		AntBot[] copy = {null};
		helper.onEachTick(() -> {
			Container chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
			if (copy[0] == null && chest.countItem(Items.OAK_LOG) == 4) {
				copy[0] = reload(helper, bot);
				bot.discard();
				helper.spawnItem(Items.OAK_SAPLING, new BlockPos(6, 1, 6));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(copy[0] != null, "logs not stored yet");
			helper.assertTrue(copy[0].isAlive(), "reloaded bot is gone");
			helper.assertBlockPresent(Blocks.OAK_SAPLING, 5, 1, 5);
		});
	}

	/** A remembered tree is forgotten once it was felled too long ago, counted in game time across a reload. */
	@GameTest(padding = PADDING, maxTicks = 400)
	public void forgetsTreeFelledTooLongAgoAcrossReload(GameTestHelper helper) {
		AntBot bot = lumberjack(helper, 2, 4);
		long now = helper.getLevel().getGameTime();
		// Felled long enough ago that it is forgotten 50 ticks from now.
		bot.getRevisits().put(helper.absolutePos(new BlockPos(5, 1, 5)), now - LumberjackGoal.REVISIT_TICKS + 50);
		AntBot copy = reload(helper, bot);
		bot.discard();
		helper.runAfterDelay(100, () -> helper.spawnItem(Items.OAK_SAPLING, new BlockPos(6, 1, 6)));
		helper.runAfterDelay(300, () -> {
			helper.assertTrue(copy.isAlive(), "reloaded bot is gone");
			helper.assertBlockNotPresent(Blocks.OAK_SAPLING, 5, 1, 5);
			helper.succeed();
		});
	}

	/**
	 * A 4-log oak with a single leaf on top, out of the way, trunk at (x, 1..4, z). Once the logs are
	 * gone the leaf is removed, so no sapling can drop by chance: the test decides when one is available.
	 */
	private static void bareTree(GameTestHelper helper, int x, int z) {
		for (int y = 1; y <= 4; y++) {
			helper.setBlock(x, y, z, Blocks.OAK_LOG);
		}
		helper.setBlock(x, 5, z, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1).setValue(LeavesBlock.PERSISTENT, false));
		helper.onEachTick(() -> {
			if (countBlocks(helper, BlockTags.LOGS) == 0 && helper.getBlockState(new BlockPos(x, 5, z)).is(BlockTags.LEAVES)) {
				helper.setBlock(x, 5, z, Blocks.AIR);
			}
		});
	}

	/** Turns the home chest into a colony core, with no stock targets so the colony orders no crafting. */
	private static Colony colonyAtHome(GameTestHelper helper) {
		helper.setBlock(CHEST, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(CHEST)).orElseThrow();
		colony.setRadius(6);
		colony.setStockTargets(List.of());
		return colony;
	}

	private static AntBot colonyLumberjack(GameTestHelper helper, Colony colony, int x, int z) {
		AntBot bot = lumberjack(helper, x, z);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		return bot;
	}

	@GameTest(padding = PADDING, maxTicks = 1600)
	public void nearerLumberjackReplantsTreeFelledWithoutSapling(GameTestHelper helper) {
		Colony colony = colonyAtHome(helper);
		bareTree(helper, 5, 5);
		AntBot feller = colonyLumberjack(helper, colony, 2, 2);
		BlockPos base = helper.absolutePos(new BlockPos(5, 1, 5));
		AntBot[] planter = {null};
		helper.onEachTick(() -> {
			// The feller had no sapling and posted a replant job; it is now taking its logs home.
			if (planter[0] == null && colony.board().has(JobType.REPLANT, base) && feller.isWorking()) {
				planter[0] = colonyLumberjack(helper, colony, 6, 3);
				helper.spawnItem(Items.OAK_SAPLING, new BlockPos(6, 1, 6));
			}
			if (colony.board().jobOf(feller.getUUID()).filter(job -> job.type() == JobType.REPLANT).isPresent()) {
				helper.fail("the feller claimed the replant job instead of the nearer lumberjack");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(planter[0] != null, "no replant job posted yet");
			helper.assertBlockPresent(Blocks.OAK_SAPLING, 5, 1, 5);
			helper.assertTrue(!colony.board().has(JobType.REPLANT, base), "replant job still on the board");
			Container core = (Container) helper.getBlockEntity(CHEST, BlockEntity.class);
			helper.assertTrue(core.countItem(Items.OAK_LOG) == 4, "core has " + core.countItem(Items.OAK_LOG) + " logs");
		});
	}

	private static int carried(AntBot bot, net.minecraft.world.item.Item item) {
		return bot.getInventory().countItem(item) + (bot.getMainHandItem().is(item) ? bot.getMainHandItem().getCount() : 0);
	}

	@GameTest(padding = PADDING, maxTicks = 400)
	public void swapsToBetterAxeInColonyCore(GameTestHelper helper) {
		Colony colony = colonyAtHome(helper);
		Container core = (Container) helper.getBlockEntity(CHEST, BlockEntity.class);
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
		Container core = (Container) helper.getBlockEntity(CHEST, BlockEntity.class);
		core.setItem(0, new ItemStack(Items.COBBLESTONE, 3));
		core.setItem(1, new ItemStack(Items.STICK, 2));
		AntBot bot = colonyLumberjack(helper, colony, 5, 5);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_AXE));

		helper.succeedWhen(() -> helper.assertTrue(colony.orders().getOrDefault(Items.STONE_AXE, 0) == 1, "orders: " + colony.orders()));
	}

	@GameTest(padding = PADDING, maxTicks = 400)
	public void soloLumberjackSwapsToBetterAxeInHomeChest(GameTestHelper helper) {
		Container chest = (Container) helper.getBlockEntity(CHEST, BlockEntity.class);
		chest.setItem(0, new ItemStack(Items.STONE_AXE));
		AntBot bot = lumberjack(helper, 5, 5);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_AXE));

		helper.succeedWhen(() -> {
			helper.assertTrue(carried(bot, Items.STONE_AXE) == 1, "lumberjack has no stone axe");
			helper.assertTrue(chest.countItem(Items.WOODEN_AXE) == 1, "wooden axe not back in the chest");
		});
	}

	@GameTest(padding = PADDING, maxTicks = 1600)
	public void replantsWithSaplingFromColonyCore(GameTestHelper helper) {
		Colony colony = colonyAtHome(helper);
		Container core = (Container) helper.getBlockEntity(CHEST, BlockEntity.class);
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
			helper.assertTrue(bot.getInventory().countItem(Items.DIRT) == 16, "bot has " + bot.getInventory().countItem(Items.DIRT) + " dirt");
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
			Container chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
			helper.assertTrue(chest.countItem(Items.OAK_LOG) == 4, "chest has " + chest.countItem(Items.OAK_LOG) + " logs");
		});
	}
}
