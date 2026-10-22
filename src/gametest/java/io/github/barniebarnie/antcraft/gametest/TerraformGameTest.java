package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.work.Farming;
import io.github.barniebarnie.antcraft.work.Field;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.Storage;
import io.github.barniebarnie.antcraft.work.Terraform;
import java.lang.reflect.Method;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Colonies levelling an area: miners dig what is above the height, builders fill the holes up to it. */
public class TerraformGameTest implements CustomTestMethodInvoker {
	private static final BlockPos CORE = new BlockPos(0, 1, 0);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
			}
		}
		method.invoke(this, helper);
	}

	private static Colony colony(GameTestHelper helper, BlockPos core, int radius) {
		helper.setBlock(core, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(core)).orElseThrow();
		colony.setRadius(radius);
		// No default stock targets, so only the test's own work shows up.
		colony.setStockTargets(List.of());
		return colony;
	}

	/** A colony with a mineshaft of no levels in the corner, already dug, so the miner has only the terraform to do. */
	private static Colony colonyWithMiner(GameTestHelper helper) {
		Colony colony = colony(helper, CORE, 6);
		Mineshaft shaft = new Mineshaft(helper.absolutePos(new BlockPos(0, 1, 6)), List.of(), Mineshaft.DEFAULT_BRANCHES_PER_SIDE,
			Mineshaft.DEFAULT_BRANCH_LENGTH, Mineshaft.DEFAULT_TORCH_SPACING, Mineshaft.DEFAULT_ROUNDS, false);
		colony.setMineshaft(shaft);
		colony.removeBuild(shaft.plan());
		return colony;
	}

	private static Container core(GameTestHelper helper, BlockPos core) {
		return (Container) helper.getBlockEntity(core, BlockEntity.class);
	}

	private static AntBot member(GameTestHelper helper, Colony colony, int x, int z) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, x, 1, z);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		return bot;
	}

	private static AntBot miner(GameTestHelper helper, Colony colony, int x, int z) {
		AntBot miner = member(helper, colony, x, z);
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		return miner;
	}

	/** Levels the box from {@code from} to {@code to} (relative) at relative height {@code y}. */
	private static void terraform(GameTestHelper helper, Colony colony, BlockPos from, BlockPos to, int y) {
		int height = helper.absolutePos(new BlockPos(0, y, 0)).getY();
		colony.addBuild(Terraform.plan(helper.getLevel()::getBlockState, helper.absolutePos(from), helper.absolutePos(to), height).orElseThrow());
	}

	/** Every column of the box from (x1, z1) to (x2, z2) is solid at {@code y} and clear above it up to {@code top}, but for saplings. */
	private static void assertLevel(GameTestHelper helper, int x1, int z1, int x2, int z2, int y, int top) {
		for (BlockPos pos : BlockPos.betweenClosed(x1, y, z1, x2, top, z2)) {
			BlockState state = helper.getBlockState(pos);
			if (pos.getY() == y) {
				helper.assertTrue(state.isFaceSturdy(helper.getLevel(), helper.absolutePos(pos), Direction.UP), "not solid at " + pos.toShortString() + ": " + state);
			} else {
				helper.assertTrue(state.isAir() || state.is(BlockTags.SAPLINGS), state + " left at " + pos.toShortString());
			}
		}
	}

	@GameTest(maxTicks = 220)
	public void aMinerAndABuilderLevelAHillAndAHole(GameTestHelper helper) {
		Colony colony = colonyWithMiner(helper);
		// Ground at y=1 over x and z 2..7, a hole in it, and a hill 2 blocks high on it.
		for (BlockPos pos : BlockPos.betweenClosed(2, 1, 2, 7, 1, 7)) {
			helper.setBlock(pos, Blocks.DIRT);
		}
		helper.setBlock(3, 1, 3, Blocks.AIR);
		for (BlockPos pos : BlockPos.betweenClosed(5, 2, 5, 6, 2, 6)) {
			helper.setBlock(pos, Blocks.DIRT);
		}
		helper.setBlock(5, 3, 5, Blocks.DIRT);
		core(helper, CORE).setItem(0, new ItemStack(Items.DIRT, 4));
		AntBot miner = miner(helper, colony, 1, 3);
		AntBot builder = member(helper, colony, 1, 5);
		terraform(helper, colony, new BlockPos(2, 0, 2), new BlockPos(7, 4, 7), 1);
		boolean[] builderDug = {false};
		helper.onEachTick(() -> builderDug[0] |= builder.getInventory().countItem(Items.DIRT) > 4);

		helper.succeedWhen(() -> {
			assertLevel(helper, 2, 2, 7, 7, 1, 4);
			helper.assertTrue(helper.getBlockState(new BlockPos(3, 1, 3)).is(Blocks.DIRT), "hole not filled with dirt");
			helper.assertTrue(colony.builds().isEmpty(), "terraform still listed");
			helper.assertTrue(colony.board().jobs().isEmpty(), "jobs left: " + colony.board().jobs());
			helper.assertTrue(!builderDug[0], "the builder dug, not the miner");
			helper.assertTrue(miner.isAlive(), "miner gone");
		});
	}

	/** A dig batch is open to every colony bot, so a colony with neither miners nor builders levels a hill. */
	@GameTest(maxTicks = 1100)
	public void botsWithoutTheMinerOrBuilderRoleDigATerraform(GameTestHelper helper) {
		Colony colony = colony(helper, CORE, 6);
		for (BlockPos pos : BlockPos.betweenClosed(2, 1, 2, 6, 2, 6)) {
			helper.setBlock(pos, Blocks.DIRT);
		}
		AntBot farmer = member(helper, colony, 1, 3);
		AntBot lumberjack = member(helper, colony, 1, 5);
		terraform(helper, colony, new BlockPos(2, 0, 2), new BlockPos(6, 3, 6), 0);

		helper.succeedWhen(() -> {
			assertLevel(helper, 2, 2, 6, 6, 0, 3);
			helper.assertTrue(colony.builds().isEmpty(), "terraform still listed");
			helper.assertTrue(colony.board().jobs().stream().noneMatch(job -> job.type() == JobType.CLEAR_AREA), "dig jobs left: " + colony.board().jobs());
		});
	}

	/** A 4-log oak with a small canopy, trunk at (x, 1..4, z). */
	private static void tree(GameTestHelper helper, int x, int z) {
		BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1).setValue(LeavesBlock.PERSISTENT, false);
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

	/** The trees are felled by tree jobs, which the miner may help with, not dug out by the terraform's dig jobs. */
	@GameTest(maxTicks = 1600)
	public void treesInTheAreaAreFelledThroughTreeJobsNotDugOut(GameTestHelper helper) {
		Colony colony = colonyWithMiner(helper);
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 7, 0, 7)) {
			helper.setBlock(pos, Blocks.DIRT);
		}
		tree(helper, 4, 4);
		List<AntBot> bots = List.of(miner(helper, colony, 1, 3), member(helper, colony, 1, 5));
		terraform(helper, colony, new BlockPos(2, 0, 2), new BlockPos(6, 6, 6), 0);
		int[] logsHeld = new int[bots.size()];
		helper.onEachTick(() -> {
			for (int i = 0; i < bots.size(); i++) {
				AntBot bot = bots.get(i);
				int logs = bot.getInventory().countItem(Items.OAK_LOG);
				if (logs > logsHeld[i] && colony.board().jobOf(bot.getUUID()).filter(job -> job.type() == JobType.CLEAR_AREA).isPresent()) {
					helper.fail("a bot dug out logs on a dig job");
				}
				logsHeld[i] = logs;
			}
		});

		helper.succeedWhen(() -> {
			assertLevel(helper, 2, 2, 6, 6, 0, 6);
			helper.assertTrue(colony.builds().isEmpty(), "terraform still listed");
			int logs = bots.stream().mapToInt(bot -> bot.getInventory().countItem(Items.OAK_LOG)).sum() + core(helper, CORE).countItem(Items.OAK_LOG);
			helper.assertTrue(logs == 4, "the colony has " + logs + " logs");
		});
	}

	@GameTest(maxTicks = 60)
	public void aColonyWithNoFlatSpotLevelsOneForItsStorage(GameTestHelper helper) {
		// Bedrock walls keep every spot but the rows z=4..6 out; a bump and a hole spoil those.
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				if (x == 0 || x == 7 || z == 0 || z == 7) {
					for (int y = 0; y < 4; y++) {
						helper.setBlock(x, y, z, Blocks.BEDROCK);
					}
				}
			}
		}
		helper.setBlock(3, 1, 5, Blocks.DIRT);
		helper.setBlock(4, 0, 5, Blocks.AIR);
		BlockPos corePos = new BlockPos(2, 1, 2);
		Colony colony = colony(helper, corePos, 5);
		Container core = core(helper, corePos);
		// Chests to place and dirt to fill with; logs in every other slot, for storage.
		core.setItem(0, new ItemStack(Items.CHEST, 2));
		core.setItem(1, new ItemStack(Items.DIRT, 4));
		for (int i = 2; i < core.getContainerSize(); i++) {
			core.setItem(i, new ItemStack(Items.OAK_LOG, 64));
		}
		member(helper, colony, 3, 3);
		member(helper, colony, 4, 3);

		helper.succeedWhen(() -> {
			helper.assertTrue(helper.getBlockState(new BlockPos(3, 1, 5)).isAir(), "bump not dug");
			helper.assertTrue(helper.getBlockState(new BlockPos(4, 0, 5)).is(Blocks.DIRT), "hole not filled");
			helper.assertTrue(colony.storage().isPresent(), "no storage area");
			boolean chest = colony.storage().get().chestSpots().stream().anyMatch(spot -> Storage.containerAt(helper.getLevel(), spot) != null);
			helper.assertTrue(chest, "no storage chest placed");
		});
	}

	/**
	 * The farm a colony starts with (see {@link Field#plan}) on uneven ground: a bump in the crop layer is dug, and a
	 * hole and rock in the soil layer get dirt, so the whole field is soil with room above it, but for the middle.
	 */
	@GameTest(maxTicks = 1500)
	public void aFieldSiteIsLevelledWithSoilOnTop(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(2, 1, 2, 6, 1, 6)) {
			helper.setBlock(pos, Blocks.DIRT);
		}
		helper.setBlock(3, 2, 3, Blocks.COBBLESTONE);
		helper.setBlock(5, 1, 5, Blocks.AIR);
		helper.setBlock(3, 1, 5, Blocks.STONE);
		Colony colony = colony(helper, CORE, 6);
		core(helper, CORE).setItem(0, new ItemStack(Items.DIRT, 4));
		Field field = new Field(helper.absolutePos(new BlockPos(4, 1, 4)));
		colony.setField(field);
		colony.addBuild("farm", field.plan(helper.getLevel()::getBlockState).orElseThrow());
		member(helper, colony, 1, 3);
		member(helper, colony, 1, 5);

		helper.succeedWhen(() -> {
			for (BlockPos soil : BlockPos.betweenClosed(2, 1, 2, 6, 1, 6)) {
				if (!soil.equals(new BlockPos(4, 1, 4))) {
					helper.assertTrue(Farming.isSoil(helper.getBlockState(soil)), "no soil at " + soil.toShortString() + ": " + helper.getBlockState(soil));
					helper.assertTrue(Farming.isOpen(helper.getBlockState(soil.above())), "crop layer not clear at " + soil.above().toShortString());
				}
			}
			helper.assertTrue(colony.builds().isEmpty(), "farm still listed");
		});
	}

	/** Any bot can clear water, but only with a bucket or a filler block, and the colony has neither. */
	@GameTest(maxTicks = 700)
	public void waterStaysBlockedWithoutABucketOrFillerToClearIt(GameTestHelper helper) {
		Colony colony = colonyWithMiner(helper);
		// Dirt and a water source in a stone pocket beside it, both above the height.
		helper.setBlock(2, 1, 2, Blocks.DIRT);
		helper.setBlock(3, 1, 2, Blocks.WATER);
		helper.setBlock(3, 1, 1, Blocks.STONE);
		helper.setBlock(3, 1, 3, Blocks.STONE);
		helper.setBlock(4, 1, 2, Blocks.STONE);
		miner(helper, colony, 1, 4);
		terraform(helper, colony, new BlockPos(2, 1, 2), new BlockPos(3, 1, 2), 0);
		boolean[] reported = {false};
		boolean[] freed = {false};
		helper.onEachTick(() -> {
			boolean blocked = !colony.blockedSteps().isEmpty();
			freed[0] |= reported[0] && !blocked;
			reported[0] |= blocked;
		});

		helper.succeedWhen(() -> {
			helper.assertTrue(helper.getTick() >= 600, "still watching");
			helper.assertTrue(reported[0], "the water was never reported blocked");
			helper.assertTrue(!freed[0], "the blocked water was freed again with nothing to clear it with");
		});
	}

	/** Dirt to dig walled in with bedrock all round is within reach of the miner, but out of its sight, so it is left and reported blocked. */
	@GameTest(maxTicks = 150)
	public void dirtWalledInWithBedrockIsNotDugThroughTheWall(GameTestHelper helper) {
		Colony colony = colonyWithMiner(helper);
		BlockPos dirt = new BlockPos(3, 1, 3);
		helper.setBlock(dirt, Blocks.DIRT);
		for (Direction dir : Direction.values()) {
			helper.setBlock(dirt.relative(dir), Blocks.BEDROCK);
		}
		miner(helper, colony, 1, 3);
		terraform(helper, colony, new BlockPos(2, 1, 2), new BlockPos(4, 2, 4), 0);
		helper.onEachTick(() -> {
			if (!helper.getBlockState(dirt).is(Blocks.DIRT)) {
				helper.fail("dug the walled-in dirt through bedrock");
			}
		});

		helper.succeedWhen(() -> helper.assertTrue(colony.blockedSteps().contains(helper.absolutePos(dirt)), "the walled-in dirt was never reported blocked"));
	}

	@GameTest(maxTicks = 800)
	public void lavaInTheAreaStaysBlocked(GameTestHelper helper) {
		BlockPos corePos = new BlockPos(0, 2, 0);
		// Ground at y=1 with a hole in it and a lava source sunk in it.
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 1, 7)) {
			helper.setBlock(pos, Blocks.DIRT);
		}
		helper.setBlock(2, 1, 2, Blocks.AIR);
		helper.setBlock(4, 1, 4, Blocks.LAVA);
		Colony colony = colony(helper, corePos, 6);
		core(helper, corePos).setItem(0, new ItemStack(Items.DIRT, 8));
		member(helper, colony, 1, 2, 3);
		terraform(helper, colony, new BlockPos(2, 1, 2), new BlockPos(6, 3, 6), 1);
		BlockPos lava = helper.absolutePos(new BlockPos(4, 1, 4));
		boolean[] reported = {false};
		boolean[] freed = {false};
		helper.onEachTick(() -> {
			boolean blocked = colony.blockedSteps().contains(lava);
			freed[0] |= reported[0] && !blocked;
			reported[0] |= blocked;
		});

		helper.succeedWhen(() -> {
			helper.assertTrue(helper.getTick() >= 700, "still watching");
			helper.assertTrue(helper.getBlockState(new BlockPos(2, 1, 2)).is(Blocks.DIRT), "the hole is not filled");
			helper.assertTrue(helper.getBlockState(new BlockPos(4, 1, 4)).is(Blocks.LAVA), "the lava was filled");
			helper.assertTrue(reported[0], "the lava was never reported blocked");
			helper.assertTrue(!freed[0], "the blocked lava was freed again");
			helper.assertTrue(colony.blockedSteps().equals(List.of(lava)), "blocked: " + colony.blockedSteps());
		});
	}

	/** A member standing at (x, y, z), such as on ground raised above the floor. */
	private static AntBot member(GameTestHelper helper, Colony colony, int x, int y, int z) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, x, y, z);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		return bot;
	}

	/** There is no fluid left in the box from (x1, y1, z1) to (x2, y2, z2). */
	private static void assertDry(GameTestHelper helper, int x1, int y1, int z1, int x2, int y2, int z2) {
		for (BlockPos pos : BlockPos.betweenClosed(x1, y1, z1, x2, y2, z2)) {
			helper.assertTrue(helper.getBlockState(pos).getFluidState().isEmpty(), "water left at " + pos.toShortString());
		}
	}

	@GameTest(maxTicks = 90)
	public void aBuilderFillsAPondWithSand(GameTestHelper helper) {
		// Ground at y=1..2 with a 3x3 pond in it, 2 deep, down to the stone floor.
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 2, 7)) {
			boolean pond = pos.getX() >= 3 && pos.getX() <= 5 && pos.getZ() >= 3 && pos.getZ() <= 5;
			helper.setBlock(pos, pond ? Blocks.WATER : Blocks.DIRT);
		}
		BlockPos corePos = new BlockPos(0, 3, 0);
		Colony colony = colony(helper, corePos, 6);
		core(helper, corePos).setItem(0, new ItemStack(Items.SAND, 24));
		member(helper, colony, 1, 3, 3);
		terraform(helper, colony, new BlockPos(2, 1, 2), new BlockPos(6, 4, 6), 2);

		helper.succeedWhen(() -> {
			assertDry(helper, 2, 1, 2, 6, 4, 6);
			assertLevel(helper, 2, 2, 6, 6, 2, 4);
			helper.assertTrue(helper.getBlockState(new BlockPos(4, 1, 4)).is(Blocks.SAND), "the pond is not filled with sand");
			helper.assertTrue(colony.builds().isEmpty(), "terraform still listed");
		});
	}

	/**
	 * A pond 6 wide and 3 deep in the middle of a 16x16 area: its middle columns are out of reach from the bank,
	 * and from the top of the columns filled beside them, so the builders fill them swimming.
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 1000)
	public void buildersFillAPondTooWideAndDeepToReachFromTheBank(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 15, 3, 15)) {
			boolean pond = pos.getY() > 0 && pos.getX() >= 5 && pos.getX() <= 10 && pos.getZ() >= 5 && pos.getZ() <= 10;
			helper.setBlock(pos, pos.getY() == 0 ? Blocks.STONE : pond ? Blocks.WATER : Blocks.DIRT);
		}
		BlockPos corePos = new BlockPos(0, 4, 0);
		Colony colony = colony(helper, corePos, 12);
		// A mineshaft of no levels, taken as dug, so the bots have only the terraform to do.
		Mineshaft shaft = new Mineshaft(helper.absolutePos(new BlockPos(0, 4, 15)), List.of(), Mineshaft.DEFAULT_BRANCHES_PER_SIDE,
			Mineshaft.DEFAULT_BRANCH_LENGTH, Mineshaft.DEFAULT_TORCH_SPACING, Mineshaft.DEFAULT_ROUNDS, false);
		colony.setMineshaft(shaft);
		colony.removeBuild(shaft.plan());
		core(helper, corePos).setItem(0, new ItemStack(Items.DIRT, 64));
		core(helper, corePos).setItem(1, new ItemStack(Items.DIRT, 64));
		List<AntBot> bots = List.of(member(helper, colony, 2, 4, 3), member(helper, colony, 3, 4, 2));
		terraform(helper, colony, new BlockPos(4, 1, 4), new BlockPos(11, 5, 11), 3);
		boolean[] swam = {false};
		helper.onEachTick(() -> swam[0] |= bots.stream().anyMatch(AntBot::isInWater));

		// They fill it from the water, rather than dig down into the bank to get close enough.
		helper.onEachTick(() -> {
			for (BlockPos pos : BlockPos.betweenClosed(3, 1, 3, 12, 3, 12)) {
				boolean bank = pos.getX() == 3 || pos.getX() == 12 || pos.getZ() == 3 || pos.getZ() == 12;
				if (bank && !helper.getBlockState(pos).is(Blocks.DIRT)) {
					helper.fail("dug into the bank at " + pos.toShortString());
				}
			}
		});

		helper.succeedWhen(() -> {
			assertDry(helper, 4, 1, 4, 11, 5, 11);
			assertLevel(helper, 4, 4, 11, 11, 3, 5);
			helper.assertTrue(colony.builds().isEmpty(), "terraform still listed");
			helper.assertTrue(swam[0], "no builder went into the water");
		});
	}

	@GameTest(maxTicks = 790)
	public void aRiverEdgeIsFilledAndWalledOff(GameTestHelper helper) {
		// A bank of ground at y=1..2 along x 0..3, and a river 2 deep at x 4..6 between stone banks.
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 3, 7)) {
			if (pos.getX() <= 3) {
				helper.setBlock(pos, pos.getY() <= 2 ? Blocks.DIRT : Blocks.AIR);
			} else if (pos.getX() == 7 || pos.getZ() == 0 || pos.getZ() == 7) {
				helper.setBlock(pos, Blocks.STONE);
			} else if (pos.getY() <= 2) {
				helper.setBlock(pos, Blocks.WATER);
			}
		}
		BlockPos corePos = new BlockPos(0, 3, 1);
		Colony colony = colony(helper, corePos, 6);
		Mineshaft shaft = new Mineshaft(helper.absolutePos(new BlockPos(0, 3, 6)), List.of(), Mineshaft.DEFAULT_BRANCHES_PER_SIDE,
			Mineshaft.DEFAULT_BRANCH_LENGTH, Mineshaft.DEFAULT_TORCH_SPACING, Mineshaft.DEFAULT_ROUNDS, false);
		colony.setMineshaft(shaft);
		colony.removeBuild(shaft.plan());
		core(helper, corePos).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
		// For the builder, to break the cobblestone it fills the water above the height with.
		core(helper, corePos).setItem(1, new ItemStack(Items.STONE_PICKAXE));
		AntBot miner = member(helper, colony, 1, 3, 3);
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		member(helper, colony, 2, 3, 4);
		// Level the bank and the near half of the river at y=1, below the river's surface.
		terraform(helper, colony, new BlockPos(1, 1, 1), new BlockPos(5, 4, 6), 1);
		long[] doneAt = {-1};

		helper.succeedWhen(() -> {
			assertDry(helper, 1, 1, 1, 5, 4, 6);
			assertLevel(helper, 1, 1, 5, 6, 1, 4);
			helper.assertTrue(colony.builds().isEmpty(), "terraform still listed");
			helper.assertTrue(colony.blockedSteps().isEmpty(), "blocked: " + colony.blockedSteps());
			for (int z = 1; z <= 6; z++) {
				helper.assertTrue(helper.getBlockState(new BlockPos(6, 1, z)).is(Blocks.WATER), "the river outside the area is gone at z=" + z);
				helper.assertTrue(helper.getBlockState(new BlockPos(6, 2, z)).isFaceSturdy(helper.getLevel(), helper.absolutePos(new BlockPos(6, 2, z)), Direction.UP),
					"the river is not walled off at z=" + z);
			}
			// It stays dry: no water flows back in.
			if (doneAt[0] < 0) {
				doneAt[0] = helper.getTick();
			}
			helper.assertTrue(helper.getTick() - doneAt[0] >= 60, "watching for water flowing back since " + doneAt[0]);
		});
	}

	/**
	 * A colony bot with nothing to do, standing in the spot a builder fills, steps out of the way rather than keep
	 * the builder waiting for good, as one left floating in the last water of a pond did.
	 */
	@GameTest(maxTicks = 300)
	public void anIdleBotStandingWhereABlockGoesStepsAside(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(2, 1, 2, 6, 1, 6)) {
			helper.setBlock(pos, Blocks.DIRT);
		}
		helper.setBlock(4, 1, 4, Blocks.AIR);
		Colony colony = colony(helper, CORE, 6);
		core(helper, CORE).setItem(0, new ItemStack(Items.DIRT, 4));
		AntBot builder = member(helper, colony, 1, 3);
		// Standing in the hole; it joins once the builder holds the one job, so it has nothing to do.
		AntBot idle = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		terraform(helper, colony, new BlockPos(2, 1, 2), new BlockPos(6, 3, 6), 1);
		helper.onEachTick(() -> {
			if (idle.getColonyId() == null && colony.board().jobOf(builder.getUUID()).isPresent()) {
				ColonyManager.get(helper.getLevel()).join(idle, colony);
			}
		});

		helper.succeedWhen(() -> {
			helper.assertTrue(idle.getColonyId() != null, "the idle bot has not joined");
			helper.assertTrue(helper.getBlockState(new BlockPos(4, 1, 4)).is(Blocks.DIRT), "the hole is not filled");
		});
	}
}
