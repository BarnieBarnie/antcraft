package io.github.barniebarnie.antcraft.gametest;

import static io.github.barniebarnie.antcraft.gametest.TestPositions.relative;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.entity.goal.BuilderGoal;
import io.github.barniebarnie.antcraft.pathing.LevelWorldView;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.LevelTreeView;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.Mining;
import io.github.barniebarnie.antcraft.work.RegionPlan;
import io.github.barniebarnie.antcraft.work.ToolKind;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
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
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.Fluids;
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

	/** A torch broken off a finished batch is put back while the batch after it still waits for its planks. */
	@GameTest(maxTicks = 600)
	public void replacesABrokenTorchOfAFinishedBatchWhileLaterBatchesAreOpen(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 1));
		core(helper).setItem(1, new ItemStack(Items.TORCH, 2));
		BlockPos torch = new BlockPos(5, 2, 5);
		BuildPlan plan = new BuildPlan(List.of(
			step(helper, 5, 1, 5, COBBLE),
			step(helper, 5, 2, 5, Blocks.TORCH.defaultBlockState()),
			step(helper, 3, 1, 5, Blocks.OAK_PLANKS.defaultBlockState())),
			List.of(new BuildPlan.Batch(0, 2, List.of()), new BuildPlan.Batch(2, 3, List.of(0))));
		colony.addBuild(plan);
		member(helper, colony, BotRole.BUILDER, 1, 4);

		boolean[] broken = {false};
		helper.onEachTick(() -> {
			boolean firstOpen = colony.board().jobs().stream().anyMatch(job -> colony.build(job.id()).equals(Optional.of(plan.batch(0))));
			if (!broken[0] && helper.getBlockState(torch).is(Blocks.TORCH) && !firstOpen) {
				helper.setBlock(torch, Blocks.AIR);
				broken[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(broken[0], "first batch not finished");
			helper.assertBlockPresent(Blocks.TORCH, torch);
			helper.assertTrue(colony.builds().equals(List.of(plan)), "plan dropped while its planks are still to place");
			helper.assertTrue(colony.board().jobs().stream().anyMatch(job -> colony.build(job.id()).equals(Optional.of(plan.batch(1)))),
				"later batch not open");
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

	/** A door and a bed are one step each, placed whole from one item, as a player would. */
	@GameTest(maxTicks = 600)
	public void buildsACopiedDoorAndBedWhole(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.OAK_DOOR, 1));
		core(helper).setItem(1, new ItemStack(Items.BED.red(), 1));
		BlockState doorLower = Blocks.OAK_DOOR.defaultBlockState()
			.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
			.setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.RIGHT);
		BlockState doorUpper = doorLower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
		BlockState bedFoot = Blocks.BED.red().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
		BlockState bedHead = bedFoot.setValue(BlockStateProperties.BED_PART, BedPart.HEAD);
		helper.setBlock(new BlockPos(5, 1, 1), doorLower);
		helper.setBlock(new BlockPos(5, 2, 1), doorUpper);
		helper.setBlock(new BlockPos(6, 1, 2), bedFoot);
		helper.setBlock(new BlockPos(6, 1, 1), bedHead);
		// Where the copied door's upper half goes, which the plan has no step for.
		helper.setBlock(4, 2, 4, Blocks.DIRT);
		colony.addBuild(RegionPlan.copy(helper.getLevel()::getBlockState,
			helper.absolutePos(new BlockPos(5, 1, 1)), helper.absolutePos(new BlockPos(6, 2, 2)), helper.absolutePos(new BlockPos(4, 1, 4))).orElseThrow());
		member(helper, colony, BotRole.BUILDER, 1, 4);

		helper.succeedWhen(() -> {
			assertState(helper, 4, 1, 4, doorLower);
			assertState(helper, 4, 2, 4, doorUpper);
			assertState(helper, 5, 1, 5, bedFoot);
			assertState(helper, 5, 1, 4, bedHead);
			helper.assertTrue(colony.builds().isEmpty(), "finished copy still listed");
			helper.assertTrue(core(helper).countItem(Items.OAK_DOOR) == 0 && core(helper).countItem(Items.BED.red()) == 0, "items not taken from the core");
		});
	}

	/** A copied wall eight wide is two batches a layer, so two builders build it at the same time. */
	@GameTest(maxTicks = 1200)
	public void twoBuildersBuildOneCopyTogether(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 16));
		for (int x = 0; x < 8; x++) {
			helper.setBlock(x, 1, 7, Blocks.COBBLESTONE);
			helper.setBlock(x, 2, 7, Blocks.COBBLESTONE);
		}
		colony.addBuild(RegionPlan.copy(helper.getLevel()::getBlockState,
			helper.absolutePos(new BlockPos(0, 1, 7)), helper.absolutePos(new BlockPos(7, 2, 7)), helper.absolutePos(new BlockPos(0, 1, 4))).orElseThrow());
		List<AntBot> builders = List.of(member(helper, colony, BotRole.BUILDER, 2, 2), member(helper, colony, BotRole.BUILDER, 5, 2));

		boolean[] together = {false};
		helper.onEachTick(() -> {
			if (builders.stream().allMatch(builder -> colony.board().jobOf(builder.getUUID()).isPresent())) {
				together[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			for (int x = 0; x < 8; x++) {
				assertState(helper, x, 1, 4, COBBLE);
				assertState(helper, x, 2, 4, COBBLE);
			}
			helper.assertTrue(together[0], "the builders never built at the same time");
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
				helper.assertTrue(loaded.isProtected(helper.getLevel()::getBlockState, helper.absolutePos(new BlockPos(4, 1, 6))), "placed block not saved as protected");
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

	@GameTest(maxTicks = 600)
	public void replacedBlocksAreNoLongerProtected(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.OAK_PLANKS, 1));
		BlockPos planks = new BlockPos(4, 1, 4);
		colony.addBuild(new BuildPlan(List.of(step(helper, 4, 1, 4, Blocks.OAK_PLANKS.defaultBlockState()))));
		member(helper, colony, BotRole.BUILDER, 1, 4);

		BlockPos pos = helper.absolutePos(planks);
		LevelWorldView view = new LevelWorldView(helper.getLevel(), new SimpleContainer(1), ItemStack.EMPTY, Set.of());
		boolean[] replaced = {false};
		helper.onEachTick(() -> {
			if (!replaced[0] && colony.builds().isEmpty() && helper.getBlockState(planks).is(Blocks.OAK_PLANKS)) {
				helper.assertTrue(ColonyManager.isProtected(helper.getLevel(), pos), "placed planks not protected");
				// A player breaks the planks and puts a log there.
				helper.setBlock(planks, Blocks.OAK_LOG);
				replaced[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(replaced[0], "planks not placed");
			helper.assertFalse(ColonyManager.isProtected(helper.getLevel(), pos), "player's log still protected");
			helper.assertTrue(new LevelTreeView(helper.getLevel()).isLog(pos), "lumberjacks skip the player's log");
			helper.assertTrue(view.breakTicks(pos) >= 0, "pathing may not break the player's log");
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
		digsMineshaftAndWalksBackUp(helper, MinerGameTest.mine(helper));
	}

	/** A pocket of water around the stairs at y=2: the builder walls it off and fills what is in the way, so the stairs end up dry. */
	@GameTest(maxTicks = 2400)
	public void digsMineshaftThroughWaterAndLeavesItDryAndWalkable(GameTestHelper helper) {
		Mineshaft shaft = MinerGameTest.mine(helper);
		// Held in by the stone around it.
		for (BlockPos pos : BlockPos.betweenClosed(1, 2, 2, 5, 2, 5)) {
			helper.setBlock(pos, Blocks.WATER);
		}
		digsMineshaftAndWalksBackUp(helper, shaft);
	}

	/** A cave across the stairs at y=2: the builder bridges the floors and walls in the sides, so the stairs stay one continuous way down. */
	@GameTest(maxTicks = 2400)
	public void bridgesAndWallsACaveTheMineshaftCrosses(GameTestHelper helper) {
		Mineshaft shaft = MinerGameTest.mine(helper);
		for (BlockPos pos : BlockPos.betweenClosed(0, 2, 0, 7, 2, 7)) {
			helper.setBlock(pos, Blocks.AIR);
		}
		int caveY = helper.absolutePos(new BlockPos(0, 2, 0)).getY();
		digsMineshaftAndWalksBackUp(helper, shaft, () -> {
			Set<BlockPos> dug = new HashSet<>();
			Set<BlockPos> floors = new HashSet<>();
			Set<BlockPos> open = new HashSet<>();
			for (BlockPos stair : shaft.stairs()) {
				IntStream.range(0, 3).forEach(up -> dug.add(stair.above(up)));
				floors.add(stair.below());
			}
			for (int level : shaft.levels()) {
				List<BlockPos> hall = shaft.hall(level);
				hall.forEach(cell -> dug.addAll(List.of(cell, cell.above())));
				hall.forEach(cell -> floors.add(cell.below()));
				open.add(shaft.landingChest(level));
				open.add(hall.getLast().offset(hall.getLast().subtract(hall.get(hall.size() - 2))));
				shaft.branches(level).forEach(branch -> open.addAll(branch.cells()));
			}
			for (BlockPos floor : floors) {
				BlockState state = helper.getLevel().getBlockState(floor);
				helper.assertTrue(state.isFaceSturdy(helper.getLevel(), floor, Direction.UP),
					"no floor at " + floor.subtract(helper.absolutePos(BlockPos.ZERO)) + ": " + state);
			}
			// The cave is walled off beside every stair and hall, so nothing walks in from it.
			for (BlockPos cell : dug) {
				for (Direction side : Direction.Plane.HORIZONTAL) {
					BlockPos wall = cell.relative(side);
					if (wall.getY() == caveY && !dug.contains(wall) && !open.contains(wall)) {
						helper.assertFalse(helper.getLevel().getBlockState(wall).isAir(), "cave open at " + wall.subtract(helper.absolutePos(BlockPos.ZERO)));
					}
				}
			}
		});
	}

	/**
	 * A cave across the stairs at y=2 in deepslate, a builder carrying sand, and sand and dirt as the
	 * core's fillers: the walls are dirt or the cobbled deepslate it digs, never sand, which would
	 * fall into the shaft once the cell below is dug.
	 */
	@GameTest(maxTicks = 3600)
	public void wallsAShaftThroughACaveWithFillersThatDoNotFall(GameTestHelper helper) {
		Mineshaft shaft = MinerGameTest.mine(helper);
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 4, 7)) {
			helper.setBlock(pos, pos.getY() == 2 ? Blocks.AIR : Blocks.DEEPSLATE);
		}
		helper.onEachTick(() -> {
			for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 7, 7, 7)) {
				helper.assertFalse(helper.getBlockState(pos).is(Blocks.SAND), "sand placed at " + pos.immutable());
			}
		});
		digsMineshaftAndWalksBackUp(helper, shaft, List.of(new ItemStack(Items.SAND, 64), new ItemStack(Items.DIRT, 64)),
			List.of(new ItemStack(Items.SAND, 64)), () -> { });
	}

	private static void digsMineshaftAndWalksBackUp(GameTestHelper helper, Mineshaft shaft) {
		digsMineshaftAndWalksBackUp(helper, shaft, () -> { });
	}

	private static void digsMineshaftAndWalksBackUp(GameTestHelper helper, Mineshaft shaft, Runnable also) {
		digsMineshaftAndWalksBackUp(helper, shaft, List.of(new ItemStack(Items.COBBLESTONE, 64)), List.of(), also);
	}

	/**
	 * Has a builder carrying {@code carried} dig {@code shaft}, with torches, chests and
	 * {@code fillers} in the core, and then walk back up the stairs as they are. Every stair and
	 * hall ends up dug, dry, lit and walled in, and {@code also} holds.
	 */
	private static void digsMineshaftAndWalksBackUp(GameTestHelper helper, Mineshaft shaft, List<ItemStack> fillers, List<ItemStack> carried, Runnable also) {
		helper.setBlock(MinerGameTest.MINE_CORE, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(MinerGameTest.MINE_CORE)).orElseThrow();
		colony.setStockTargets(List.of());
		Container core = (Container) helper.getBlockEntity(MinerGameTest.MINE_CORE, BlockEntity.class);
		core.setItem(0, new ItemStack(Items.TORCH, 8));
		core.setItem(1, new ItemStack(Items.CHEST, 2));
		for (int i = 0; i < fillers.size(); i++) {
			core.setItem(2 + i, fillers.get(i).copy());
		}
		colony.setMineshaft(shaft);
		AntBot builder = helper.spawn(ModEntities.ANT_BOT, 1, 5, 1);
		ColonyManager.get(helper.getLevel()).join(builder, colony);
		builder.setRole(BotRole.BUILDER);
		builder.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		carried.forEach(stack -> builder.getInventory().addItem(stack.copy()));

		BlockPos home = new BlockPos(1, 5, 1);
		Set<?>[] dug = {null};
		helper.onEachTick(() -> {
			if (dug[0] == null && colony.builds().isEmpty()) {
				helper.assertTrue(shaft.plan().nextStep(helper.getLevel()::getBlockState).isEmpty(), "plan dropped before it was done");
				helper.assertTrue(relative(helper, builder.blockPosition()).getY() < 3, "builder not down the shaft");
				dug[0] = solid(helper);
				builder.setWalkTarget(helper.absolutePos(home));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(dug[0] != null, "shaft not dug, next step " + shaft.plan().nextStep(helper.getLevel()::getBlockState));
			helper.assertTrue(builder.blockPosition().distManhattan(helper.absolutePos(home)) <= 2, "builder at " + relative(helper, builder.blockPosition()));
			// It walked up the stairs as they are, without digging or building its own way.
			helper.assertTrue(solid(helper).equals(dug[0]), "blocks changed on the way up");
			MinerGameTest.assertLit(helper, MinerGameTest.dugPath(shaft, false));
			for (int level : shaft.levels()) {
				helper.assertTrue(helper.getLevel().getBlockState(shaft.landingChest(level)).is(Blocks.CHEST), "no chest at landing " + level);
			}
			for (BuildPlan.Step step : shaft.plan().steps()) {
				BlockState state = helper.getLevel().getBlockState(step.pos());
				helper.assertTrue(step.isDone(state) && state.getFluidState().isEmpty(),
					"not done at " + step.pos().subtract(helper.absolutePos(BlockPos.ZERO)) + ": " + state);
			}
			also.run();
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
				helper.assertTrue(step.isDone(state) || state.isAir(), "not dug at " + relative(helper, step.pos()) + ": " + state);
			}
			helper.assertFalse(colony.builds().isEmpty(), "plan dropped while torches and chests are still to place");
			helper.assertTrue(colony.orders().containsKey(Items.TORCH) && colony.orders().containsKey(Items.CHEST), "not ordered: " + colony.orders());
		});
	}

	/** Once the stairs reach the first landing, one builder digs its hall while the other digs on down. */
	@GameTest(maxTicks = 2400)
	public void twoBuildersDigOneShaftTogether(GameTestHelper helper) {
		Mineshaft shaft = MinerGameTest.mine(helper);
		helper.setBlock(MinerGameTest.MINE_CORE, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(MinerGameTest.MINE_CORE)).orElseThrow();
		colony.setStockTargets(List.of());
		Container core = (Container) helper.getBlockEntity(MinerGameTest.MINE_CORE, BlockEntity.class);
		core.setItem(0, new ItemStack(Items.TORCH, 8));
		core.setItem(1, new ItemStack(Items.CHEST, 2));
		colony.setMineshaft(shaft);
		List<AntBot> builders = new ArrayList<>();
		for (BlockPos at : List.of(new BlockPos(1, 5, 1), new BlockPos(1, 5, 5))) {
			AntBot builder = helper.spawn(ModEntities.ANT_BOT, at.getX(), at.getY(), at.getZ());
			ColonyManager.get(helper.getLevel()).join(builder, colony);
			builder.setRole(BotRole.BUILDER);
			builder.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
			builders.add(builder);
		}

		boolean[] together = {false};
		helper.onEachTick(() -> {
			if (builders.stream().allMatch(builder -> colony.board().jobOf(builder.getUUID()).isPresent())) {
				together[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(together[0], "the builders never dug at the same time");
			helper.assertTrue(colony.builds().isEmpty(), "shaft not finished, next step " + shaft.plan().nextStep(helper.getLevel()::getBlockState));
			helper.assertTrue(shaft.plan().nextStep(helper.getLevel()::getBlockState).isEmpty(), "plan dropped before it was done");
			for (int level : shaft.levels()) {
				helper.assertTrue(helper.getLevel().getBlockState(shaft.landingChest(level)).is(Blocks.CHEST), "no chest at landing " + level);
			}
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

	/**
	 * Dirt to clear walled in with bedrock on every side, within reach of where the builder stands:
	 * like a player it cannot break what it cannot see, so it leaves the dirt and reports the step.
	 */
	@GameTest(maxTicks = 600)
	public void doesNotClearABlockBehindAWallAndReportsIt(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 1));
		BlockPos dirt = new BlockPos(4, 1, 4);
		helper.setBlock(dirt, Blocks.DIRT);
		for (Direction side : Direction.values()) {
			helper.setBlock(dirt.relative(side), Blocks.BEDROCK);
		}
		colony.addBuild(new BuildPlan(List.of(
			BuildPlan.Step.clear(helper.absolutePos(dirt)),
			step(helper, 6, 1, 6, COBBLE))));
		member(helper, colony, BotRole.BUILDER, 2, 4);

		helper.onEachTick(() -> {
			if (!helper.getBlockState(dirt).is(Blocks.DIRT)) {
				helper.fail("cleared the dirt through the bedrock wall");
			}
		});
		helper.succeedWhen(() -> {
			assertState(helper, 6, 1, 6, COBBLE);
			helper.assertTrue(colony.blockedSteps().equals(List.of(helper.absolutePos(dirt))), "walled-in step not reported: " + colony.blockedSteps());
		});
	}

	/**
	 * A block to place high in the air, with no scaffold to pillar up with: the builder cannot get
	 * within reach of it, so it gives the step up and reports it, rather than trying forever.
	 */
	@GameTest(maxTicks = 600)
	public void givesUpOnAStepItCannotReachAndReportsIt(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 2));
		BuildPlan.Step high = step(helper, 4, 7, 4, COBBLE);
		colony.addBuild(new BuildPlan(List.of(high, step(helper, 6, 1, 6, COBBLE))));
		member(helper, colony, BotRole.BUILDER, 2, 4);

		helper.succeedWhen(() -> {
			assertState(helper, 6, 1, 6, COBBLE);
			helper.assertTrue(colony.blockedSteps().equals(List.of(high.pos())), "unreachable step not reported: " + colony.blockedSteps());
		});
	}

	/**
	 * One builder finds its batch blocked by bedrock while the other digs out dirt for a batch that
	 * does not need it; the batch after the dirt is posted and built all the same, while the one
	 * after the bedrock waits.
	 */
	@GameTest(maxTicks = 600)
	public void aBlockedBatchLeavesTheBatchesThatDoNotNeedItToBeBuilt(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 2));
		helper.setBlock(4, 1, 4, Blocks.BEDROCK);
		helper.setBlock(5, 1, 3, Blocks.DIRT);
		colony.addBuild(new BuildPlan(List.of(
			BuildPlan.Step.clear(helper.absolutePos(new BlockPos(4, 1, 4))),
			step(helper, 6, 1, 6, COBBLE),
			BuildPlan.Step.clear(helper.absolutePos(new BlockPos(5, 1, 3))),
			step(helper, 5, 1, 5, COBBLE)),
			List.of(new BuildPlan.Batch(0, 1, List.of()), new BuildPlan.Batch(1, 2, List.of(0)),
				new BuildPlan.Batch(2, 3, List.of()), new BuildPlan.Batch(3, 4, List.of(2)))));
		member(helper, colony, BotRole.BUILDER, 1, 4);
		member(helper, colony, BotRole.BUILDER, 1, 5);

		helper.succeedWhen(() -> {
			helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(5, 1, 3));
			assertState(helper, 5, 1, 5, COBBLE);
			helper.assertBlockPresent(Blocks.AIR, new BlockPos(6, 1, 6));
			helper.assertTrue(colony.blockedSteps().equals(List.of(helper.absolutePos(new BlockPos(4, 1, 4)))), "blocked step not reported: " + colony.blockedSteps());
			helper.assertTrue(colony.builds().isEmpty(), "plan left with only blocked batches still listed");
		});
	}

	/** A door whose upper half would go into bedrock cannot be placed, so it is reported like a step behind bedrock. */
	@GameTest(maxTicks = 300)
	public void reportsADoorWhoseUpperHalfIsBedrock(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.OAK_DOOR, 1));
		core(helper).setItem(1, new ItemStack(Items.COBBLESTONE, 1));
		helper.setBlock(4, 2, 4, Blocks.BEDROCK);
		BuildPlan.Step door = new BuildPlan.Step(helper.absolutePos(new BlockPos(4, 1, 4)), Blocks.OAK_DOOR.defaultBlockState());
		colony.addBuild(new BuildPlan(List.of(door, step(helper, 5, 1, 5, COBBLE))));
		member(helper, colony, BotRole.BUILDER, 1, 4);

		helper.succeedWhen(() -> {
			assertState(helper, 5, 1, 5, COBBLE);
			helper.assertBlockPresent(Blocks.BEDROCK, new BlockPos(4, 2, 4));
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
			helper.assertTrue(colony.blockedSteps().equals(List.of(door.pos())), "blocked door not reported: " + colony.blockedSteps());
		});
	}

	@GameTest(maxTicks = 800)
	public void doesABlockedStepOnceTheBedrockIsReplacedByStone(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.STONE_PICKAXE));
		helper.setBlock(4, 1, 4, Blocks.BEDROCK);
		colony.addBuild(new BuildPlan(List.of(BuildPlan.Step.clear(helper.absolutePos(new BlockPos(4, 1, 4))))));
		member(helper, colony, BotRole.BUILDER, 1, 4);

		boolean[] replaced = {false};
		helper.onEachTick(() -> {
			if (!replaced[0] && !colony.blockedSteps().isEmpty()) {
				helper.setBlock(4, 1, 4, Blocks.STONE);
				replaced[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(replaced[0], "bedrock never reported");
			helper.assertBlockPresent(Blocks.AIR, new BlockPos(4, 1, 4));
			helper.assertTrue(colony.blockedSteps().isEmpty(), "still reported blocked: " + colony.blockedSteps());
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
		});
	}

	@GameTest(maxTicks = 300)
	public void clearsTheBlockedListOnCommand(GameTestHelper helper) {
		Colony colony = colony(helper);
		helper.setBlock(4, 1, 4, Blocks.BEDROCK);
		colony.addBuild(new BuildPlan(List.of(BuildPlan.Step.clear(helper.absolutePos(new BlockPos(4, 1, 4))))));
		member(helper, colony, BotRole.BUILDER, 1, 4);

		boolean[] cleared = {false};
		helper.onEachTick(() -> {
			if (!cleared[0] && !colony.blockedSteps().isEmpty()) {
				command(helper, PermissionSet.NO_PERMISSIONS, "antcraft colony blocked clear");
				helper.assertFalse(colony.blockedSteps().isEmpty(), "cleared without permission");
				command(helper, PermissionSet.ALL_PERMISSIONS, "antcraft colony blocked clear");
				cleared[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(cleared[0], "bedrock never reported");
			helper.assertTrue(colony.blockedSteps().isEmpty(), "still reported blocked: " + colony.blockedSteps());
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

	/** A plan to place a water source at (4, 1, 4), walled in by stone so it does not spread. */
	private static BuildPlan waterStep(GameTestHelper helper) {
		for (Direction side : Direction.Plane.HORIZONTAL) {
			helper.setBlock(new BlockPos(4, 1, 4).relative(side), Blocks.STONE);
		}
		return new BuildPlan(List.of(step(helper, 4, 1, 4, Blocks.WATER.defaultBlockState())));
	}

	@GameTest(maxTicks = 300)
	public void doesNotPlaceWaterWithoutABucketOrASourceAndReportsIt(GameTestHelper helper) {
		Colony colony = colony(helper);
		colony.addBuild(waterStep(helper));
		member(helper, colony, BotRole.BUILDER, 1, 4);

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.AIR, new BlockPos(4, 1, 4));
			helper.assertTrue(colony.blockedSteps().equals(List.of(helper.absolutePos(new BlockPos(4, 1, 4)))), "step not reported: " + colony.blockedSteps());
		});
	}

	@GameTest(maxTicks = 300)
	public void placesWaterFromABucketInTheCoreAndKeepsTheBucket(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.WATER_BUCKET));
		colony.addBuild(waterStep(helper));
		AntBot builder = member(helper, colony, BotRole.BUILDER, 1, 4);

		helper.succeedWhen(() -> {
			assertState(helper, 4, 1, 4, Blocks.WATER.defaultBlockState());
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
			helper.assertTrue(builder.getInventory().countItem(Items.WATER_BUCKET) + core(helper).countItem(Items.WATER_BUCKET) == 0, "water bucket not used");
			helper.assertTrue(builder.getInventory().countItem(Items.BUCKET) + core(helper).countItem(Items.BUCKET) == 1, "empty bucket not kept");
		});
	}

	@GameTest(maxTicks = 600)
	public void ordersABucketAndFillsItAtASourceToPlaceWater(GameTestHelper helper) {
		Colony colony = colony(helper);
		BlockPos source = new BlockPos(6, 1, 1);
		for (Direction side : Direction.Plane.HORIZONTAL) {
			helper.setBlock(source.relative(side), Blocks.STONE);
		}
		helper.setBlock(source, Blocks.WATER);
		colony.addBuild(waterStep(helper));
		member(helper, colony, BotRole.BUILDER, 1, 4);

		boolean[] stocked = {false};
		helper.onEachTick(() -> {
			// A water bucket cannot be made, so only the empty bucket is ordered.
			helper.assertFalse(colony.orders().containsKey(Items.WATER_BUCKET), "uncraftable water bucket ordered");
			if (!stocked[0] && colony.orders().containsKey(Items.BUCKET)) {
				core(helper).setItem(0, new ItemStack(Items.BUCKET));
				stocked[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(stocked[0], "no bucket ordered: " + colony.orders());
			assertState(helper, 4, 1, 4, Blocks.WATER.defaultBlockState());
			helper.assertBlockNotPresent(Blocks.WATER, source);
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
		});
	}

	/** A plan to place a waterlogged stair at (4, 1, 4), walled in by stone so its water does not spread. */
	private static BuildPlan waterloggedStairStep(GameTestHelper helper) {
		for (Direction side : Direction.Plane.HORIZONTAL) {
			helper.setBlock(new BlockPos(4, 1, 4).relative(side), Blocks.STONE);
		}
		return new BuildPlan(List.of(step(helper, 4, 1, 4, STAIRS_EAST.setValue(BlockStateProperties.WATERLOGGED, true))));
	}

	@GameTest(maxTicks = 300)
	public void placesAWaterloggedStairDryWithoutAWaterBucketAndReportsIt(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.OAK_STAIRS));
		colony.addBuild(waterloggedStairStep(helper));
		member(helper, colony, BotRole.BUILDER, 1, 4);

		helper.onEachTick(() -> helper.assertFalse(helper.getBlockState(new BlockPos(4, 1, 4)).getFluidState().is(Fluids.WATER),
			"stair waterlogged without a water bucket"));
		helper.succeedWhen(() -> {
			assertState(helper, 4, 1, 4, STAIRS_EAST);
			helper.assertTrue(colony.blockedSteps().equals(List.of(helper.absolutePos(new BlockPos(4, 1, 4)))), "step not reported: " + colony.blockedSteps());
		});
	}

	@GameTest(maxTicks = 300)
	public void fillsAWaterloggedStairFromABucketInTheCoreAndKeepsTheBucket(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.OAK_STAIRS));
		core(helper).setItem(1, new ItemStack(Items.WATER_BUCKET));
		colony.addBuild(waterloggedStairStep(helper));
		AntBot builder = member(helper, colony, BotRole.BUILDER, 1, 4);

		helper.succeedWhen(() -> {
			assertState(helper, 4, 1, 4, STAIRS_EAST.setValue(BlockStateProperties.WATERLOGGED, true));
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
			helper.assertTrue(builder.getInventory().countItem(Items.WATER_BUCKET) + core(helper).countItem(Items.WATER_BUCKET) == 0, "water bucket not used");
			helper.assertTrue(builder.getInventory().countItem(Items.BUCKET) + core(helper).countItem(Items.BUCKET) == 1, "empty bucket not kept");
		});
	}

	@GameTest(maxTicks = 400)
	public void fillsAnEmptyBucketAtASourceToWaterlogAStair(GameTestHelper helper) {
		Colony colony = colony(helper);
		BlockPos source = new BlockPos(6, 1, 1);
		for (Direction side : Direction.Plane.HORIZONTAL) {
			helper.setBlock(source.relative(side), Blocks.STONE);
		}
		helper.setBlock(source, Blocks.WATER);
		core(helper).setItem(0, new ItemStack(Items.OAK_STAIRS));
		core(helper).setItem(1, new ItemStack(Items.BUCKET));
		colony.addBuild(waterloggedStairStep(helper));
		member(helper, colony, BotRole.BUILDER, 1, 4);

		helper.succeedWhen(() -> {
			assertState(helper, 4, 1, 4, STAIRS_EAST.setValue(BlockStateProperties.WATERLOGGED, true));
			helper.assertBlockNotPresent(Blocks.WATER, source);
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
		});
	}

	@GameTest(maxTicks = 300)
	public void scoopsTheWaterOutOfAStairPlannedDry(GameTestHelper helper) {
		Colony colony = colony(helper);
		// Only for its stone walls, which keep the water in.
		waterloggedStairStep(helper);
		helper.setBlock(new BlockPos(4, 1, 4), STAIRS_EAST.setValue(BlockStateProperties.WATERLOGGED, true));
		core(helper).setItem(0, new ItemStack(Items.BUCKET));
		colony.addBuild(new BuildPlan(List.of(step(helper, 4, 1, 4, STAIRS_EAST))));
		AntBot builder = member(helper, colony, BotRole.BUILDER, 1, 4);

		// The stair stays: only its water goes, scooped up like a player would, not broken out.
		helper.onEachTick(() -> helper.assertBlockPresent(Blocks.OAK_STAIRS, new BlockPos(4, 1, 4)));
		helper.succeedWhen(() -> {
			assertState(helper, 4, 1, 4, STAIRS_EAST);
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
			helper.assertTrue(builder.getInventory().countItem(Items.WATER_BUCKET) + core(helper).countItem(Items.WATER_BUCKET) == 1, "water not scooped up");
		});
	}

	@GameTest(maxTicks = 300)
	public void reportsFireThatCannotStayAsBlockedAndKeepsItBlocked(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
		// Over air, with nothing flammable beside it, fire goes out at once.
		BuildPlan.Step fire = step(helper, 4, 2, 4, Blocks.FIRE.defaultBlockState());
		colony.addBuild(new BuildPlan(List.of(fire)));
		AntBot builder = member(helper, colony, BotRole.BUILDER, 1, 4);

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.AIR, new BlockPos(4, 2, 4));
			helper.assertTrue(colony.blockedSteps().equals(List.of(fire.pos())), "step not reported: " + colony.blockedSteps());
			// The recheck leaves it reported rather than freeing it every survey.
			helper.assertFalse(BuilderGoal.workable(helper.getLevel(), colony).test(new BuildPlan(List.of(fire)), fire), "fire counted as workable");
			helper.assertTrue(flintAndSteel(helper, builder).getDamageValue() == 0, "flint and steel used");
		});
	}

	/** The one flint and steel the builder or the core holds. */
	private static ItemStack flintAndSteel(GameTestHelper helper, AntBot builder) {
		for (Container container : List.<Container>of(builder.getInventory(), core(helper))) {
			for (int i = 0; i < container.getContainerSize(); i++) {
				if (container.getItem(i).is(Items.FLINT_AND_STEEL)) {
					return container.getItem(i);
				}
			}
		}
		return ItemStack.EMPTY;
	}

	@GameTest(maxTicks = 300)
	public void lightsFireOnNetherrackWithFlintAndSteelFromTheCore(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
		helper.setBlock(4, 0, 4, Blocks.NETHERRACK);
		colony.addBuild(new BuildPlan(List.of(step(helper, 4, 1, 4, Blocks.FIRE.defaultBlockState()))));
		AntBot builder = member(helper, colony, BotRole.BUILDER, 1, 4);

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.FIRE, new BlockPos(4, 1, 4));
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
			ItemStack tool = flintAndSteel(helper, builder);
			helper.assertFalse(tool.isEmpty(), "flint and steel used up");
			helper.assertTrue(tool.getDamageValue() == 1, "flint and steel not worn: " + tool.getDamageValue());
		});
	}

	@GameTest(maxTicks = 300)
	public void reportsFireOnStoneAsBlockedRatherThanRelightingIt(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
		// Stone does not burn forever: fire on it goes out and would be lit again until the tool breaks.
		BuildPlan.Step fire = step(helper, 4, 1, 4, Blocks.FIRE.defaultBlockState());
		colony.addBuild(new BuildPlan(List.of(fire)));
		AntBot builder = member(helper, colony, BotRole.BUILDER, 1, 4);

		helper.onEachTick(() -> helper.assertBlockNotPresent(Blocks.FIRE, new BlockPos(4, 1, 4)));
		helper.succeedWhen(() -> {
			helper.assertTrue(colony.blockedSteps().equals(List.of(fire.pos())), "step not reported: " + colony.blockedSteps());
			helper.assertTrue(flintAndSteel(helper, builder).getDamageValue() == 0, "flint and steel used");
		});
	}

	@GameTest(maxTicks = 300)
	public void ordersNoFlintAndSteelForBlockedFire(GameTestHelper helper) {
		Colony colony = colony(helper);
		BuildPlan.Step fire = step(helper, 4, 1, 4, Blocks.FIRE.defaultBlockState());
		colony.addBuild(new BuildPlan(List.of(fire)));
		member(helper, colony, BotRole.BUILDER, 1, 4);

		helper.onEachTick(() -> helper.assertFalse(colony.ordersFor(Colony.OrderPurpose.BUILD).containsKey(Items.FLINT_AND_STEEL),
			"flint and steel ordered: " + colony.orders()));
		helper.succeedWhen(() -> helper.assertTrue(colony.blockedSteps().equals(List.of(fire.pos())), "step not reported: " + colony.blockedSteps()));
	}

	@GameTest(maxTicks = 300)
	public void leavesFireThatWouldSpreadToPlanksUnlit(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
		helper.setBlock(4, 0, 4, Blocks.NETHERRACK);
		// Two blocks away: fire spreads into the air beside it, and on to the planks.
		BuildPlan.Step planks = step(helper, 6, 1, 4, Blocks.OAK_PLANKS.defaultBlockState());
		helper.setBlock(6, 1, 4, Blocks.OAK_PLANKS);
		BuildPlan.Step fire = step(helper, 4, 1, 4, Blocks.FIRE.defaultBlockState());
		colony.addBuild(new BuildPlan(List.of(planks, fire)));
		AntBot builder = member(helper, colony, BotRole.BUILDER, 1, 4);

		helper.onEachTick(() -> helper.assertBlockNotPresent(Blocks.FIRE, new BlockPos(4, 1, 4)));
		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(6, 1, 4));
			helper.assertTrue(colony.blockedSteps().equals(List.of(fire.pos())), "step not reported: " + colony.blockedSteps());
			helper.assertTrue(flintAndSteel(helper, builder).getDamageValue() == 0, "flint and steel used");
		});
	}

	@GameTest(maxTicks = 600)
	public void ordersFlintAndSteelForFireAndWaitsForIt(GameTestHelper helper) {
		Colony colony = colony(helper);
		helper.setBlock(4, 0, 4, Blocks.NETHERRACK);
		colony.addBuild(new BuildPlan(List.of(step(helper, 4, 1, 4, Blocks.FIRE.defaultBlockState()))));
		member(helper, colony, BotRole.BUILDER, 1, 4);

		boolean[] stocked = {false};
		helper.onEachTick(() -> {
			helper.assertTrue(colony.blockedSteps().isEmpty(), "waiting fire reported as blocked: " + colony.blockedSteps());
			if (!stocked[0] && colony.ordersFor(Colony.OrderPurpose.BUILD).getOrDefault(Items.FLINT_AND_STEEL, 0) == 1) {
				core(helper).setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
				stocked[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(stocked[0], "no flint and steel ordered: " + colony.orders());
			helper.assertBlockPresent(Blocks.FIRE, new BlockPos(4, 1, 4));
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
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

	/** A plan for a column at (5, 1..height, 5) of {@code state}. */
	private static BuildPlan column(GameTestHelper helper, int height, BlockState state) {
		return new BuildPlan(IntStream.rangeClosed(1, height).mapToObj(y -> step(helper, 5, y, 5, state)).toList());
	}

	/** How many dirt blocks stand in the area above the floor, leaving out the column at (5, y, 5). */
	private static int strayDirt(GameTestHelper helper) {
		int dirt = 0;
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 7, 7)) {
			if (!(pos.getX() == 5 && pos.getZ() == 5) && helper.getBlockState(pos).is(Blocks.DIRT)) {
				dirt++;
			}
		}
		return dirt;
	}

	/**
	 * The top of a 6-high column is out of reach from the ground: the builder pillars up with dirt
	 * from the core, and takes the pillar down once the column stands, getting the dirt back.
	 */
	@GameTest(maxTicks = 1200)
	public void pillarsUpToBuildASixHighColumnAndLeavesNoScaffold(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 6));
		core(helper).setItem(1, new ItemStack(Items.DIRT, 16));
		colony.addBuild(column(helper, 6, COBBLE));
		AntBot builder = member(helper, colony, BotRole.BUILDER, 1, 4);

		boolean[] pillared = {false};
		helper.onEachTick(() -> pillared[0] |= !builder.getScaffold().isEmpty());
		helper.succeedWhen(() -> {
			for (int y = 1; y <= 6; y++) {
				assertState(helper, 5, y, 5, COBBLE);
			}
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
			helper.assertTrue(pillared[0], "never placed scaffold");
			helper.assertTrue(builder.getScaffold().isEmpty(), "scaffold left: " + builder.getScaffold());
			helper.assertTrue(strayDirt(helper) == 0, "dirt scaffold left standing");
			int dirt = core(helper).countItem(Items.DIRT) + builder.getInventory().countItem(Items.DIRT);
			helper.assertTrue(dirt == 16, "scaffold dirt not got back: " + dirt);
		});
	}

	/** How many items the container holds in all. */
	private static int itemCount(Container container) {
		int count = 0;
		for (int i = 0; i < container.getContainerSize(); i++) {
			count += container.getItem(i).getCount();
		}
		return count;
	}

	/**
	 * A builder fetching the column's cobblestone also takes the scaffold dirt it will pillar with,
	 * so it goes to the core once, rather than once for the cobblestone and again for the dirt.
	 */
	@GameTest(maxTicks = 1200)
	public void takesScaffoldWithTheMaterialsInOneTripToTheCore(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 6));
		core(helper).setItem(1, new ItemStack(Items.DIRT, 16));
		colony.addBuild(column(helper, 6, COBBLE));
		AntBot builder = member(helper, colony, BotRole.BUILDER, 1, 4);

		// A visit is a run of ticks in which the builder takes items from the core.
		int[] last = {itemCount(core(helper))};
		boolean[] taking = {false};
		int[] visits = {0};
		helper.onEachTick(() -> {
			int now = itemCount(core(helper));
			boolean took = now < last[0];
			if (took && !taking[0]) {
				visits[0]++;
			}
			taking[0] = took;
			last[0] = now;
		});
		helper.succeedWhen(() -> {
			for (int y = 1; y <= 6; y++) {
				assertState(helper, 5, y, 5, COBBLE);
			}
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
			helper.assertTrue(builder.getScaffold().isEmpty(), "scaffold left: " + builder.getScaffold());
			helper.assertTrue(visits[0] == 1, "went to the core " + visits[0] + " times");
		});
	}

	/** A builder that already carries scaffold keeps it when it fetches the cobblestone for a column it must pillar up to. */
	@GameTest(maxTicks = 1200)
	public void keepsItsScaffoldWhenFetchingForHighSteps(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 6));
		colony.addBuild(column(helper, 6, COBBLE));
		AntBot builder = member(helper, colony, BotRole.BUILDER, 1, 4);
		builder.getInventory().addItem(new ItemStack(Items.DIRT, 16));

		helper.onEachTick(() -> {
			if (!helper.getBlockState(new BlockPos(5, 6, 5)).is(Blocks.COBBLESTONE) && core(helper).countItem(Items.DIRT) > 0) {
				helper.fail("scaffold dirt put back in the core before the column stands");
			}
		});
		helper.succeedWhen(() -> {
			for (int y = 1; y <= 6; y++) {
				assertState(helper, 5, y, 5, COBBLE);
			}
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
			helper.assertTrue(builder.getScaffold().isEmpty(), "scaffold left: " + builder.getScaffold());
		});
	}

	/** With no step high enough to pillar up to, the scaffold a builder carries goes back to the core when it fetches materials. */
	@GameTest(maxTicks = 600)
	public void putsScaffoldBackWhenNoHighStepsAreLeft(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 2));
		colony.addBuild(column(helper, 2, COBBLE));
		AntBot builder = member(helper, colony, BotRole.BUILDER, 1, 4);
		builder.getInventory().addItem(new ItemStack(Items.DIRT, 16));

		helper.succeedWhen(() -> {
			assertState(helper, 5, 1, 5, COBBLE);
			assertState(helper, 5, 2, 5, COBBLE);
			helper.assertTrue(core(helper).countItem(Items.DIRT) == 16, "scaffold dirt not put back: " + core(helper).countItem(Items.DIRT));
		});
	}

	/**
	 * A builder sent to a walk target mid-pillar goes back to its column on its own scaffold: no
	 * other goal takes the pillar down in between, and it takes it down itself once the column stands.
	 */
	@GameTest(maxTicks = 1200)
	public void interruptedBuilderResumesOnItsOwnScaffold(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 6));
		core(helper).setItem(1, new ItemStack(Items.DIRT, 16));
		colony.addBuild(column(helper, 6, COBBLE));
		AntBot builder = member(helper, colony, BotRole.BUILDER, 1, 4);

		Set<BlockPos> pillar = new HashSet<>();
		helper.onEachTick(() -> {
			if (pillar.isEmpty() && builder.getScaffold().size() >= 1) {
				pillar.addAll(builder.getScaffold());
				// Interrupted: walk to where it stands, which clears the target again right away.
				builder.setWalkTarget(builder.blockPosition());
			}
			assertScaffoldKeptUntilColumnStands(helper, pillar);
		});
		helper.succeedWhen(() -> assertColumnBuiltWithoutScaffoldLeft(helper, colony, builder, pillar));
	}

	/** A builder saved and loaded mid-pillar goes on from its scaffold, and takes it down itself once the column stands. */
	@GameTest(maxTicks = 1200)
	public void reloadedBuilderResumesOnItsOwnScaffold(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 6));
		core(helper).setItem(1, new ItemStack(Items.DIRT, 16));
		colony.addBuild(column(helper, 6, COBBLE));
		AntBot[] builder = {member(helper, colony, BotRole.BUILDER, 1, 4)};

		Set<BlockPos> pillar = new HashSet<>();
		helper.onEachTick(() -> {
			if (pillar.isEmpty() && builder[0].getScaffold().size() >= 1) {
				pillar.addAll(builder[0].getScaffold());
				builder[0] = reload(helper, builder[0]);
				helper.assertTrue(builder[0].getScaffold().equals(pillar), "scaffold not loaded: " + builder[0].getScaffold());
			}
			assertScaffoldKeptUntilColumnStands(helper, pillar);
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(builder[0].isAlive(), "reloaded bot is gone");
			assertColumnBuiltWithoutScaffoldLeft(helper, colony, builder[0], pillar);
		});
	}

	/** A builder made a farmer with its scaffold still up takes that scaffold down, as a former lumberjack does. */
	@GameTest(maxTicks = 600)
	public void takesBuilderScaffoldDownAfterRoleChange(GameTestHelper helper) {
		Colony colony = colony(helper);
		AntBot bot = member(helper, colony, BotRole.BUILDER, 4, 4);
		for (int y = 1; y <= 2; y++) {
			helper.setBlock(5, y, 5, Blocks.DIRT);
			bot.addScaffold(helper.absolutePos(new BlockPos(5, y, 5)), BotRole.BUILDER);
		}
		bot.setRole(BotRole.FARMER);

		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getScaffold().isEmpty(), "scaffold left: " + bot.getScaffold());
			helper.assertBlockNotPresent(Blocks.DIRT, 5, 1, 5);
			helper.assertBlockNotPresent(Blocks.DIRT, 5, 2, 5);
		});
	}

	/** Fails once a block of {@code pillar} is gone while the column's top block is not in place. */
	private static void assertScaffoldKeptUntilColumnStands(GameTestHelper helper, Set<BlockPos> pillar) {
		if (helper.getBlockState(new BlockPos(5, 6, 5)).is(Blocks.COBBLESTONE)) {
			return;
		}
		for (BlockPos pos : pillar) {
			if (!helper.getLevel().getBlockState(pos).is(Blocks.DIRT)) {
				helper.fail("scaffold at " + relative(helper, pos).toShortString() + " taken down before the column stands");
			}
		}
	}

	private static void assertColumnBuiltWithoutScaffoldLeft(GameTestHelper helper, Colony colony, AntBot builder, Set<BlockPos> pillar) {
		helper.assertFalse(pillar.isEmpty(), "never placed scaffold");
		for (int y = 1; y <= 6; y++) {
			assertState(helper, 5, y, 5, COBBLE);
		}
		helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
		helper.assertTrue(builder.getScaffold().isEmpty(), "scaffold left: " + builder.getScaffold());
		helper.assertTrue(strayDirt(helper) == 0, "dirt scaffold left standing");
	}

	/**
	 * A dirt column, built from the same dirt the builder pillars with. Its scaffold never stands
	 * where the column goes, so it never stands in for a column block, and taking the scaffold down
	 * never takes a column block that is in place.
	 */
	@GameTest(maxTicks = 1200)
	public void neverMixesUpScaffoldAndPlanBlocksOfTheSameKind(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.DIRT, 22));
		colony.addBuild(column(helper, 6, Blocks.DIRT.defaultBlockState()));
		AntBot builder = member(helper, colony, BotRole.BUILDER, 1, 4);

		boolean[] placed = new boolean[7];
		boolean[] pillared = {false};
		helper.onEachTick(() -> {
			pillared[0] |= !builder.getScaffold().isEmpty();
			for (int y = 1; y <= 6; y++) {
				BlockPos pos = helper.absolutePos(new BlockPos(5, y, 5));
				if (builder.getScaffold().contains(pos)) {
					helper.fail("scaffold placed where the column goes, at y=" + y);
				}
				boolean there = helper.getBlockState(new BlockPos(5, y, 5)).is(Blocks.DIRT);
				if (placed[y] && !there) {
					helper.fail("column block at y=" + y + " taken down");
				}
				placed[y] |= there;
			}
		});
		helper.succeedWhen(() -> {
			for (int y = 1; y <= 6; y++) {
				helper.assertTrue(ColonyManager.isProtected(helper.getLevel(), helper.absolutePos(new BlockPos(5, y, 5))), "column block at y=" + y + " not placed by the builder");
			}
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
			helper.assertTrue(pillared[0], "never placed scaffold");
			helper.assertTrue(builder.getScaffold().isEmpty(), "scaffold left: " + builder.getScaffold());
			helper.assertTrue(strayDirt(helper) == 0, "dirt scaffold left standing");
		});
	}
}
