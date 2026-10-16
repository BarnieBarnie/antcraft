package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.pathing.LevelWorldView;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.Mining;
import io.github.barniebarnie.antcraft.work.ToolKind;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

public class BuilderGameTest implements CustomTestMethodInvoker {
	private static final BlockPos CORE = new BlockPos(1, 1, 1);
	private static final BlockState COBBLE = Blocks.COBBLESTONE.defaultBlockState();
	private static final BlockState STAIRS_EAST = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
			}
		}
		method.invoke(this, helper);
	}

	private static Colony colony(GameTestHelper helper) {
		helper.setBlock(CORE, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(CORE)).orElseThrow();
		colony.setRadius(6);
		// No default stock targets, so only the builder's orders show up.
		colony.setStockTargets(List.of());
		return colony;
	}

	private static Container core(GameTestHelper helper) {
		return (Container) helper.getBlockEntity(CORE, BlockEntity.class);
	}

	private static AntBot member(GameTestHelper helper, Colony colony, BotRole role, int x, int z) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, x, 1, z);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		bot.setRole(role);
		return bot;
	}

	private static BuildPlan.Step step(GameTestHelper helper, int x, int y, int z, BlockState state) {
		return new BuildPlan.Step(helper.absolutePos(new BlockPos(x, y, z)), state);
	}

	private static void assertState(GameTestHelper helper, int x, int y, int z, BlockState state) {
		BlockState actual = helper.getBlockState(new BlockPos(x, y, z));
		helper.assertTrue(actual.equals(state), "expected " + state + " at " + x + "," + y + "," + z + " but found " + actual);
	}

	@GameTest(maxTicks = 600)
	public void buildsPlanWithMaterialsFromCore(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 2));
		core(helper).setItem(1, new ItemStack(Items.OAK_STAIRS, 1));
		helper.setBlock(4, 1, 4, Blocks.DIRT);
		colony.addBuild(new BuildPlan(List.of(
			BuildPlan.Step.clear(helper.absolutePos(new BlockPos(4, 1, 4))),
			step(helper, 5, 1, 5, COBBLE),
			step(helper, 5, 2, 5, COBBLE),
			step(helper, 3, 1, 5, STAIRS_EAST))));
		member(helper, colony, BotRole.BUILDER, 1, 4);

		helper.succeedWhen(() -> {
			helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(4, 1, 4));
			assertState(helper, 5, 1, 5, COBBLE);
			assertState(helper, 5, 2, 5, COBBLE);
			assertState(helper, 3, 1, 5, STAIRS_EAST);
			helper.assertTrue(colony.builds().isEmpty(), "finished plan still listed");
			helper.assertTrue(core(helper).countItem(Items.COBBLESTONE) == 0, "cobblestone not taken from the core");
		});
	}

	@GameTest(maxTicks = 600)
	public void ordersMissingMaterialsAndBuildsOnceStocked(GameTestHelper helper) {
		Colony colony = colony(helper);
		colony.addBuild(new BuildPlan(List.of(step(helper, 4, 1, 4, STAIRS_EAST))));
		member(helper, colony, BotRole.BUILDER, 1, 4);

		boolean[] stocked = {false};
		helper.onEachTick(() -> {
			if (!stocked[0] && colony.orders().containsKey(Items.OAK_STAIRS)) {
				// What a crafter would bring.
				core(helper).setItem(0, new ItemStack(Items.OAK_STAIRS, 1));
				stocked[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(stocked[0], "no order for stairs: " + colony.orders());
			assertState(helper, 4, 1, 4, STAIRS_EAST);
		});
	}

	/** The miner takes the core's only pickaxe; the builder orders its own instead of digging by hand for good. */
	@GameTest(maxTicks = 600)
	public void ordersAPickaxeWhenTheMinerTookTheLastOne(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.STONE_PICKAXE));
		helper.setBlock(5, 1, 5, Blocks.STONE);
		colony.addBuild(new BuildPlan(List.of(BuildPlan.Step.clear(helper.absolutePos(new BlockPos(5, 1, 5))))));
		// The miner stands at the core, so it gets there first.
		AntBot miner = member(helper, colony, BotRole.MINER, 2, 1);
		AntBot builder = member(helper, colony, BotRole.BUILDER, 6, 6);

		boolean[] stocked = {false};
		helper.onEachTick(() -> {
			if (!stocked[0] && colony.orders().containsKey(Items.WOODEN_PICKAXE)) {
				// What a crafter would bring.
				core(helper).setItem(1, new ItemStack(Items.WOODEN_PICKAXE));
				stocked[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(ToolKind.PICKAXE.bestCarried(miner) == Items.STONE_PICKAXE, "miner did not take the core's pickaxe");
			helper.assertTrue(stocked[0], "no pickaxe ordered: " + colony.orders());
			helper.assertTrue(ToolKind.PICKAXE.bestCarried(builder) == Items.WOODEN_PICKAXE, "builder has no pickaxe");
			helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(5, 1, 5));
		});
	}

	/** Runs a command as the server would for an operator standing at the colony core. */
	private static void command(GameTestHelper helper, PermissionSet permissions, String command) {
		CommandSourceStack source = helper.getLevel().getServer().createCommandSourceStack()
			.withLevel(helper.getLevel())
			.withPosition(Vec3.atCenterOf(helper.absolutePos(CORE)))
			.withPermission(permissions)
			.withSuppressedOutput();
		helper.getLevel().getServer().getCommands().performPrefixedCommand(source, command);
	}

	private static String coords(GameTestHelper helper, int x, int y, int z) {
		BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	private static String copy(GameTestHelper helper) {
		return "antcraft colony build copy " + coords(helper, 5, 1, 1) + " " + coords(helper, 6, 2, 1) + " " + coords(helper, 5, 1, 5);
	}

	@GameTest(maxTicks = 600)
	public void copyCommandAddsPlanThatBuilderBuilds(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 2));
		core(helper).setItem(1, new ItemStack(Items.OAK_STAIRS, 1));
		helper.setBlock(5, 1, 1, Blocks.COBBLESTONE);
		helper.setBlock(6, 1, 1, Blocks.COBBLESTONE);
		helper.setBlock(new BlockPos(5, 2, 1), STAIRS_EAST);
		// In the way of the copy, where the original has air.
		helper.setBlock(6, 2, 5, Blocks.DIRT);

		command(helper, PermissionSet.ALL_PERMISSIONS, copy(helper));
		helper.assertTrue(colony.builds().size() == 1, "copy not added: " + colony.builds());
		member(helper, colony, BotRole.BUILDER, 1, 4);

		helper.succeedWhen(() -> {
			assertState(helper, 5, 1, 5, COBBLE);
			assertState(helper, 6, 1, 5, COBBLE);
			assertState(helper, 5, 2, 5, STAIRS_EAST);
			helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(6, 2, 5));
			helper.assertTrue(colony.builds().isEmpty(), "finished copy still listed");
		});
	}

	@GameTest(maxTicks = 20)
	public void buildCommandsAreOperatorOnly(GameTestHelper helper) {
		Colony colony = colony(helper);
		command(helper, PermissionSet.NO_PERMISSIONS, copy(helper));
		helper.assertTrue(colony.builds().isEmpty(), "copied without permission");
		helper.succeed();
	}

	@GameTest(maxTicks = 20)
	public void cancelRemovesCopyButNotTheMineshaft(GameTestHelper helper) {
		Mineshaft mine = MinerGameTest.mine(helper);
		Colony colony = colony(helper);
		colony.setMineshaft(mine);
		BuildPlan shaft = colony.mineshaft().orElseThrow().plan();
		command(helper, PermissionSet.ALL_PERMISSIONS, copy(helper));
		helper.assertTrue(colony.builds().size() == 2, "copy not added: " + colony.builds());

		command(helper, PermissionSet.ALL_PERMISSIONS, "antcraft colony build cancel 1");
		helper.assertTrue(colony.builds().size() == 2, "mineshaft cancelled: " + colony.builds());
		command(helper, PermissionSet.ALL_PERMISSIONS, "antcraft colony build cancel 2");
		helper.assertTrue(colony.builds().equals(List.of(shaft)), "copy not cancelled: " + colony.builds());
		helper.succeed();
	}

	/** Saves the bot and loads it into a new bot, as when its chunk unloads and loads again. */
	private static AntBot reload(GameTestHelper helper, AntBot bot) {
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
		bot.saveWithoutId(output);
		bot.discard();
		AntBot copy = helper.spawn(ModEntities.ANT_BOT, 1, 1, 5);
		copy.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), output.buildResult()));
		return copy;
	}

	private static long placed(GameTestHelper helper) {
		return Stream.of(3, 4, 5, 6).filter(x -> helper.getBlockState(new BlockPos(x, 1, 6)).is(Blocks.COBBLESTONE)).count();
	}

	@GameTest(maxTicks = 800)
	public void resumesHalfDonePlanAfterSaveAndLoad(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 10));
		// Built before: the builder must skip it.
		helper.setBlock(3, 1, 6, Blocks.COBBLESTONE);
		colony.addBuild(new BuildPlan(List.of(step(helper, 3, 1, 6, COBBLE), step(helper, 4, 1, 6, COBBLE),
			step(helper, 5, 1, 6, COBBLE), step(helper, 6, 1, 6, COBBLE))));
		AntBot builder = member(helper, colony, BotRole.BUILDER, 1, 4);

		AntBot[] copy = {null};
		helper.onEachTick(() -> {
			if (copy[0] == null && placed(helper) == 2) {
				copy[0] = reload(helper, builder);
				Colony loaded = Colony.CODEC.parse(NbtOps.INSTANCE, Colony.CODEC.encodeStart(NbtOps.INSTANCE, colony).getOrThrow()).getOrThrow();
				helper.assertTrue(loaded.builds().equals(colony.builds()), "plans not saved: " + loaded.builds());
				helper.assertTrue(loaded.isProtected(helper.absolutePos(new BlockPos(4, 1, 6))), "placed block not saved as protected");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(copy[0] != null && copy[0].isAlive(), "bot not reloaded");
			helper.assertTrue(placed(helper) == 4, "plan not finished");
			// Three placed by the builder; the block that was already there took nothing.
			int left = core(helper).countItem(Items.COBBLESTONE) + copy[0].getInventory().countItem(Items.COBBLESTONE);
			helper.assertTrue(left == 7, "cobblestone left: " + left);
		});
	}

	@GameTest(maxTicks = 600)
	public void otherBotsDoNotBreakPlacedBlocks(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.OAK_PLANKS, 1));
		BlockPos planks = new BlockPos(4, 1, 4);
		colony.addBuild(new BuildPlan(List.of(step(helper, 4, 1, 4, Blocks.OAK_PLANKS.defaultBlockState()))));
		member(helper, colony, BotRole.BUILDER, 1, 4);

		LevelWorldView view = new LevelWorldView(helper.getLevel(), new SimpleContainer(1), ItemStack.EMPTY, Set.of());
		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.OAK_PLANKS, planks);
			helper.assertTrue(view.breakTicks(helper.absolutePos(planks)) < 0, "pathing may break the placed planks");
			helper.assertFalse(Mining.isMinable(helper.getLevel(), helper.absolutePos(planks)), "miners may break the placed planks");
		});
	}

	/** Every block in the area that is not air. */
	private static Set<BlockPos> solid(GameTestHelper helper) {
		Set<BlockPos> solid = new HashSet<>();
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 7, 7, 7)) {
			if (!helper.getBlockState(pos).isAir()) {
				solid.add(pos.immutable());
			}
		}
		return solid;
	}

	@GameTest(maxTicks = 2400)
	public void digsMineshaftAndWalksBackUp(GameTestHelper helper) {
		Mineshaft shaft = MinerGameTest.mine(helper);
		helper.setBlock(MinerGameTest.MINE_CORE, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(MinerGameTest.MINE_CORE)).orElseThrow();
		colony.setStockTargets(List.of());
		Container core = (Container) helper.getBlockEntity(MinerGameTest.MINE_CORE, BlockEntity.class);
		core.setItem(0, new ItemStack(Items.TORCH, 8));
		core.setItem(1, new ItemStack(Items.CHEST, 2));
		colony.setMineshaft(shaft);
		AntBot builder = helper.spawn(ModEntities.ANT_BOT, 1, 5, 1);
		ColonyManager.get(helper.getLevel()).join(builder, colony);
		builder.setRole(BotRole.BUILDER);
		builder.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));

		BlockPos home = new BlockPos(1, 5, 1);
		Set<?>[] dug = {null};
		helper.onEachTick(() -> {
			if (dug[0] == null && colony.builds().isEmpty()) {
				helper.assertTrue(shaft.plan().nextStep(helper.getLevel()::getBlockState).isEmpty(), "plan dropped before it was done");
				helper.assertTrue(helper.relativePos(builder.blockPosition()).getY() < 3, "builder not down the shaft");
				dug[0] = solid(helper);
				builder.setWalkTarget(helper.absolutePos(home));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(dug[0] != null, "shaft not dug, next step " + shaft.plan().nextStep(helper.getLevel()::getBlockState));
			helper.assertTrue(builder.blockPosition().distManhattan(helper.absolutePos(home)) <= 2, "builder at " + helper.relativePos(builder.blockPosition()));
			// It walked up the stairs as they are, without digging or building its own way.
			helper.assertTrue(solid(helper).equals(dug[0]), "blocks changed on the way up");
			MinerGameTest.assertLit(helper, MinerGameTest.dugPath(shaft, false));
			for (int level : shaft.levels()) {
				helper.assertTrue(helper.getLevel().getBlockState(shaft.landingChest(level)).is(Blocks.CHEST), "no chest at landing " + level);
			}
		});
	}

	/** A new colony has no torches or chests yet: the builder digs the shaft anyway and places them once they come. */
	@GameTest(maxTicks = 2400)
	public void digsMineshaftBeforeItsTorchesAndChestsAreMade(GameTestHelper helper) {
		Mineshaft shaft = MinerGameTest.mine(helper);
		helper.setBlock(MinerGameTest.MINE_CORE, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(MinerGameTest.MINE_CORE)).orElseThrow();
		colony.setStockTargets(List.of());
		colony.setMineshaft(shaft);
		AntBot builder = helper.spawn(ModEntities.ANT_BOT, 1, 5, 1);
		ColonyManager.get(helper.getLevel()).join(builder, colony);
		builder.setRole(BotRole.BUILDER);
		builder.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));

		helper.succeedWhen(() -> {
			for (BuildPlan.Step step : shaft.plan().steps()) {
				BlockState state = helper.getLevel().getBlockState(step.pos());
				helper.assertTrue(step.isDone(state) || state.isAir(), "not dug at " + helper.relativePos(step.pos()) + ": " + state);
			}
			helper.assertFalse(colony.builds().isEmpty(), "plan dropped while torches and chests are still to place");
			helper.assertTrue(colony.orders().containsKey(Items.TORCH) && colony.orders().containsKey(Items.CHEST), "not ordered: " + colony.orders());
		});
	}

	@GameTest(maxTicks = 600)
	public void finishesAroundAStepBehindBedrockAndReportsIt(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 1));
		helper.setBlock(4, 1, 4, Blocks.BEDROCK);
		helper.setBlock(5, 1, 3, Blocks.DIRT);
		colony.addBuild(new BuildPlan(List.of(
			BuildPlan.Step.clear(helper.absolutePos(new BlockPos(4, 1, 4))),
			BuildPlan.Step.clear(helper.absolutePos(new BlockPos(5, 1, 3))),
			step(helper, 5, 1, 5, COBBLE))));
		member(helper, colony, BotRole.BUILDER, 1, 4);

		helper.succeedWhen(() -> {
			helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(5, 1, 3));
			assertState(helper, 5, 1, 5, COBBLE);
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
			helper.assertTrue(colony.blockedSteps().equals(List.of(helper.absolutePos(new BlockPos(4, 1, 4)))), "blocked step not reported: " + colony.blockedSteps());
		});
	}

	/** A fluid source at (4, 1, 4), walled in by stone so it does not spread. */
	private static BuildPlan pool(GameTestHelper helper, BlockState fluid) {
		for (Direction side : Direction.Plane.HORIZONTAL) {
			helper.setBlock(new BlockPos(4, 1, 4).relative(side), Blocks.STONE);
		}
		helper.setBlock(4, 1, 4, fluid);
		return new BuildPlan(List.of(BuildPlan.Step.clear(helper.absolutePos(new BlockPos(4, 1, 4)))));
	}

	@GameTest(maxTicks = 300)
	public void scoopsAWaterSourceWithABucket(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.BUCKET));
		colony.addBuild(pool(helper, Blocks.WATER.defaultBlockState()));
		AntBot builder = member(helper, colony, BotRole.BUILDER, 1, 4);

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.AIR, new BlockPos(4, 1, 4));
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
			helper.assertTrue(colony.blockedSteps().isEmpty(), "reported blocked: " + colony.blockedSteps());
			helper.assertTrue(builder.getInventory().countItem(Items.WATER_BUCKET) + core(helper).countItem(Items.WATER_BUCKET) == 1, "water not scooped up");
		});
	}

	@GameTest(maxTicks = 300)
	public void fillsAWaterSourceWithoutABucketAndClearsIt(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.DIRT));
		colony.addBuild(pool(helper, Blocks.WATER.defaultBlockState()));
		member(helper, colony, BotRole.BUILDER, 1, 4);

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.AIR, new BlockPos(4, 1, 4));
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
			helper.assertTrue(colony.blockedSteps().isEmpty(), "reported blocked: " + colony.blockedSteps());
		});
	}

	@GameTest(maxTicks = 300)
	public void reportsLavaItCannotClearAsBlocked(GameTestHelper helper) {
		Colony colony = colony(helper);
		colony.addBuild(pool(helper, Blocks.LAVA.defaultBlockState()));
		member(helper, colony, BotRole.BUILDER, 1, 4);

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.LAVA, new BlockPos(4, 1, 4));
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
			helper.assertTrue(colony.blockedSteps().equals(List.of(helper.absolutePos(new BlockPos(4, 1, 4)))), "blocked step not reported: " + colony.blockedSteps());
		});
	}

	@GameTest(maxTicks = 600)
	public void waitsForAMobToLeaveTheSpot(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 1));
		BlockPos spot = new BlockPos(4, 1, 4);
		colony.addBuild(new BuildPlan(List.of(step(helper, 4, 1, 4, COBBLE))));
		Pig pig = helper.spawn(EntityTypes.PIG, spot);
		pig.setNoAi(true);
		member(helper, colony, BotRole.BUILDER, 1, 4);

		helper.onEachTick(() -> {
			if (pig.isAlive() && helper.getBlockState(spot).is(Blocks.COBBLESTONE)) {
				helper.fail("placed a block into the pig");
			}
		});
		helper.runAfterDelay(200, pig::discard);
		helper.succeedWhen(() -> {
			helper.assertFalse(pig.isAlive(), "pig still there");
			assertState(helper, 4, 1, 4, COBBLE);
		});
	}

	@GameTest(maxTicks = 400)
	public void withdrawsOrderWhenPlanIsCancelled(GameTestHelper helper) {
		Colony colony = colony(helper);
		BuildPlan plan = new BuildPlan(List.of(step(helper, 4, 1, 4, STAIRS_EAST)));
		colony.addBuild(plan);
		member(helper, colony, BotRole.BUILDER, 1, 4);

		boolean[] cancelled = {false};
		helper.onEachTick(() -> {
			if (!cancelled[0] && colony.orders().containsKey(Items.OAK_STAIRS)) {
				colony.removeBuild(plan);
				cancelled[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(cancelled[0], "no order for stairs");
			helper.assertTrue(colony.orders().isEmpty(), "order not withdrawn: " + colony.orders());
		});
	}

	@GameTest(maxTicks = 600)
	public void withdrawsOrderWhenPlanFinishesWithItemsFromElsewhere(GameTestHelper helper) {
		Colony colony = colony(helper);
		colony.addBuild(new BuildPlan(List.of(step(helper, 4, 1, 4, STAIRS_EAST))));
		member(helper, colony, BotRole.BUILDER, 1, 4);

		boolean[] stocked = {false};
		helper.onEachTick(() -> {
			if (!stocked[0] && colony.orders().containsKey(Items.OAK_STAIRS)) {
				// Brought by a player, not crafted, so the order is never fulfilled.
				core(helper).setItem(0, new ItemStack(Items.OAK_STAIRS, 1));
				stocked[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			assertState(helper, 4, 1, 4, STAIRS_EAST);
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
			helper.assertTrue(colony.orders().isEmpty(), "order not withdrawn: " + colony.orders());
		});
	}
}
