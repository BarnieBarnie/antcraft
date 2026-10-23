package io.github.barniebarnie.antcraft.gametest;

import static io.github.barniebarnie.antcraft.gametest.TestPositions.relative;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.goal.BuilderGoal;
import io.github.barniebarnie.antcraft.pathing.LevelWorldView;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.LevelTreeView;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.Mining;
import io.github.barniebarnie.antcraft.work.RegionPlan;
import io.github.barniebarnie.antcraft.work.Storage;
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
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.squid.Squid;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class BuilderGameTest implements CustomTestMethodInvoker {
	private static final BlockPos CORE = new BlockPos(1, 1, 1);
	private static final BlockState COBBLE = Blocks.COBBLESTONE.defaultBlockState();
	private static final BlockState STAIRS_EAST = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);

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

	private static AntBot member(GameTestHelper helper, Colony colony, int x, int z) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, x, 1, z);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		return bot;
	}

	/**
	 * Whether the only plan left, if any, is the mineshaft: an order for a bucket or flint and steel wants iron,
	 * so the colony starts a mine for it.
	 */
	private static boolean onlyTheMineshaftLeft(Colony colony) {
		return colony.builds().stream().allMatch(plan -> colony.mineshaft().filter(shaft -> shaft.plan().equals(plan)).isPresent());
	}

	private static BuildPlan.Step step(GameTestHelper helper, int x, int y, int z, BlockState state) {
		return new BuildPlan.Step(helper.absolutePos(new BlockPos(x, y, z)), state);
	}

	private static void assertState(GameTestHelper helper, int x, int y, int z, BlockState state) {
		BlockState actual = helper.getBlockState(new BlockPos(x, y, z));
		helper.assertTrue(actual.equals(state), "expected " + state + " at " + x + "," + y + "," + z + " but found " + actual);
	}

	@GameTest(maxTicks = 80)
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
		member(helper, colony, 1, 4);

		helper.succeedWhen(() -> {
			helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(4, 1, 4));
			assertState(helper, 5, 1, 5, COBBLE);
			assertState(helper, 5, 2, 5, COBBLE);
			assertState(helper, 3, 1, 5, STAIRS_EAST);
			helper.assertTrue(colony.builds().isEmpty(), "finished plan still listed");
			helper.assertTrue(core(helper).countItem(Items.COBBLESTONE) == 0, "cobblestone not taken from the core");
		});
	}

	/**
	 * A colony short of cobblestone for one build, with the bricks for another in the core, has the bot build the
	 * bricks before it starts the mineshaft; once only the build waiting for cobblestone is left, the mineshaft starts.
	 */
	// Measured: about 20 ticks.
	@GameTest(maxTicks = 60)
	public void aBuildWithItsBlocksInTheCoreGoesBeforeTheMineshaft(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.BRICKS, 3));
		colony.addBuild(new BuildPlan(List.of(step(helper, 5, 1, 5, Blocks.BRICKS.defaultBlockState()),
			step(helper, 5, 2, 5, Blocks.BRICKS.defaultBlockState()), step(helper, 5, 3, 5, Blocks.BRICKS.defaultBlockState()))));
		colony.addBuild(new BuildPlan(List.of(step(helper, 3, 1, 5, COBBLE))));
		// Short of cobblestone from the start, as a colony is whose build ordered it before.
		colony.addOrder(Items.COBBLESTONE, 1);
		member(helper, colony, 1, 4);

		helper.onEachTick(() -> {
			if (colony.mineshaft().isPresent() && colony.board().jobs().stream()
					.anyMatch(job -> job.type() == JobType.BUILD && job.isOpen() && job.item().equals(Optional.of(Items.BRICKS)))) {
				helper.fail("mineshaft started while the bricks build was open");
			}
		});
		helper.succeedWhen(() -> {
			assertState(helper, 5, 3, 5, Blocks.BRICKS.defaultBlockState());
			helper.assertTrue(colony.mineshaft().isPresent(), "no mineshaft once only the cobblestone build was left");
		});
	}

	/** A torch broken off a finished batch is put back while the batch after it still waits for its planks. */
	@GameTest(maxTicks = 230)
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
		member(helper, colony, 1, 4);

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

	@GameTest(maxTicks = 30)
	public void ordersMissingMaterialsAndBuildsOnceStocked(GameTestHelper helper) {
		Colony colony = colony(helper);
		colony.addBuild(new BuildPlan(List.of(step(helper, 4, 1, 4, STAIRS_EAST))));
		member(helper, colony, 1, 4);

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

	/**
	 * Stone to dig declares a pickaxe, which no bot carries and the core lacks: the bot takes the job anyway and
	 * orders one, and fetches it once it comes instead of digging by hand for good.
	 */
	@GameTest(maxTicks = 130)
	public void ordersAPickaxeForStoneNoBotHasOneFor(GameTestHelper helper) {
		Colony colony = colony(helper);
		helper.setBlock(5, 1, 5, Blocks.STONE);
		colony.addBuild(new BuildPlan(List.of(BuildPlan.Step.clear(helper.absolutePos(new BlockPos(5, 1, 5))))));
		AntBot builder = member(helper, colony, 6, 6);

		boolean[] stocked = {false};
		helper.onEachTick(() -> {
			if (!stocked[0] && colony.orders().containsKey(Items.WOODEN_PICKAXE)) {
				helper.assertTrue(helper.getBlockState(new BlockPos(5, 1, 5)).is(Blocks.STONE), "stone dug without a pickaxe");
				// What a crafter would bring.
				core(helper).setItem(1, new ItemStack(Items.WOODEN_PICKAXE));
				stocked[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(stocked[0], "no pickaxe ordered: " + colony.orders());
			helper.assertTrue(ToolKind.PICKAXE.bestCarried(builder) == Items.WOODEN_PICKAXE, "builder has no pickaxe");
			helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(5, 1, 5));
		});
	}

	/** A plan to dig four stone blocks, and a member with a wooden pickaxe that breaks after the second. */
	private static AntBot digsStoneWithAPickaxeAboutToBreak(GameTestHelper helper, Colony colony) {
		List<BuildPlan.Step> steps = new ArrayList<>();
		for (BlockPos pos : List.of(new BlockPos(4, 1, 4), new BlockPos(5, 1, 4), new BlockPos(4, 1, 5), new BlockPos(5, 1, 5))) {
			helper.setBlock(pos, Blocks.STONE);
			steps.add(BuildPlan.Step.clear(helper.absolutePos(pos)));
		}
		colony.addBuild(new BuildPlan(steps));
		AntBot builder = member(helper, colony, 2, 4);
		ItemStack pickaxe = new ItemStack(Items.WOODEN_PICKAXE);
		pickaxe.setDamageValue(pickaxe.getMaxDamage() - 2);
		builder.getInventory().addItem(pickaxe);
		return builder;
	}

	private static long stoneLeft(GameTestHelper helper) {
		return Stream.of(new BlockPos(4, 1, 4), new BlockPos(5, 1, 4), new BlockPos(4, 1, 5), new BlockPos(5, 1, 5))
			.filter(pos -> helper.getBlockState(pos).is(Blocks.STONE)).count();
	}

	/** A pickaxe that breaks mid-batch is replaced from the core at once, not on the next upgrade check, and the batch dug with it. */
	@GameTest(maxTicks = 210)
	public void fetchesAnotherPickaxeAtOnceWhenItsPickaxeBreaks(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
		AntBot builder = digsStoneWithAPickaxeAboutToBreak(helper, colony);

		long[] broke = {-1};
		long[] stone = {4};
		helper.onEachTick(() -> {
			long now = helper.getLevel().getGameTime();
			boolean has = ToolKind.PICKAXE.bestCarried(builder) != null;
			if (broke[0] < 0 && !has) {
				broke[0] = now;
				stone[0] = stoneLeft(helper);
			}
			if (broke[0] >= 0 && !has) {
				helper.assertTrue(now - broke[0] <= 50, "no new pickaxe fetched " + (now - broke[0]) + " ticks after the old one broke");
				helper.assertTrue(stoneLeft(helper) == stone[0], "stone dug by hand");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(broke[0] >= 0, "pickaxe never broke");
			helper.assertTrue(stoneLeft(helper) == 0, "stone left to dig");
			helper.assertTrue(core(helper).countItem(Items.WOODEN_PICKAXE) == 0, "pickaxe left in the core");
		});
	}

	/**
	 * With no other pickaxe in the core, a builder whose pickaxe breaks hands the batch back rather than dig by hand,
	 * and the colony orders one; made from the log in the core, it digs the rest with it.
	 */
	@GameTest(maxTicks = 580)
	public void handsTheBatchBackAndOrdersAPickaxeWhenItsPickaxeBreaks(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.OAK_LOG, 2));
		AntBot builder = digsStoneWithAPickaxeAboutToBreak(helper, colony);

		long[] broke = {-1};
		long[] stone = {4};
		boolean[] handedBack = {false};
		boolean[] ordered = {false};
		helper.onEachTick(() -> {
			boolean has = ToolKind.PICKAXE.bestCarried(builder) != null;
			if (broke[0] < 0 && !has) {
				broke[0] = helper.getLevel().getGameTime();
				stone[0] = stoneLeft(helper);
			}
			if (broke[0] >= 0 && !has) {
				helper.assertTrue(stoneLeft(helper) == stone[0], "stone dug by hand");
				handedBack[0] |= colony.board().jobOf(builder.getUUID()).isEmpty();
				ordered[0] |= colony.orders().containsKey(Items.WOODEN_PICKAXE);
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(broke[0] >= 0, "pickaxe never broke");
			helper.assertTrue(handedBack[0], "batch not handed back");
			helper.assertTrue(ordered[0], "no pickaxe ordered");
			helper.assertTrue(stoneLeft(helper) == 0, "stone left to dig");
		});
	}

	/** Runs a command as the server would for an operator standing at the colony core, and returns the messages it sent back. */
	private static List<Component> command(GameTestHelper helper, PermissionSet permissions, String command) {
		List<Component> messages = new ArrayList<>();
		CommandSource collect = new CommandSource() {
			@Override
			public void sendSystemMessage(Component message) {
				messages.add(message);
			}

			@Override
			public boolean acceptsSuccess() {
				return true;
			}

			@Override
			public boolean acceptsFailure() {
				return true;
			}

			@Override
			public boolean shouldInformAdmins() {
				return false;
			}
		};
		CommandSourceStack source = helper.getLevel().getServer().createCommandSourceStack()
			.withSource(collect)
			.withLevel(helper.getLevel())
			.withPosition(Vec3.atCenterOf(helper.absolutePos(CORE)))
			.withPermission(permissions);
		helper.getLevel().getServer().getCommands().performPrefixedCommand(source, command);
		return messages;
	}

	/** Whether one of {@code messages} is (or holds) the translation {@code key}. */
	private static boolean says(List<Component> messages, String key) {
		return messages.stream().flatMap(m -> Stream.concat(Stream.of(m), m.getSiblings().stream()))
			.anyMatch(m -> m.getContents() instanceof TranslatableContents t && t.getKey().equals(key));
	}

	private static String coords(GameTestHelper helper, int x, int y, int z) {
		BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	private static String copy(GameTestHelper helper) {
		return "antcraft colony build copy " + coords(helper, 5, 1, 1) + " " + coords(helper, 6, 2, 1) + " " + coords(helper, 5, 1, 5);
	}

	@GameTest(maxTicks = 90)
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
		member(helper, colony, 1, 4);

		helper.succeedWhen(() -> {
			assertState(helper, 5, 1, 5, COBBLE);
			assertState(helper, 6, 1, 5, COBBLE);
			assertState(helper, 5, 2, 5, STAIRS_EAST);
			helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(6, 2, 5));
			helper.assertTrue(colony.builds().isEmpty(), "finished copy still listed");
		});
	}

	/** The bundled cobble_step structure: two cobblestone with east-facing stairs on one, turned to run south. */
	@GameTest(maxTicks = 80)
	public void loadCommandAddsBundledStructureThatBuilderBuilds(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 2));
		core(helper).setItem(1, new ItemStack(Items.OAK_STAIRS, 1));
		// In the way of the structure, where it has air.
		helper.setBlock(5, 2, 5, Blocks.DIRT);

		command(helper, PermissionSet.ALL_PERMISSIONS, "antcraft colony build load antcraft:cobble_step " + coords(helper, 5, 1, 4) + " clockwise_90");
		helper.assertTrue(colony.builds().size() == 1, "structure not added: " + colony.builds());
		member(helper, colony, 1, 4);

		helper.succeedWhen(() -> {
			assertState(helper, 5, 1, 4, COBBLE);
			assertState(helper, 5, 1, 5, COBBLE);
			assertState(helper, 5, 2, 4, STAIRS_EAST.setValue(StairBlock.FACING, Direction.SOUTH));
			helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(5, 2, 5));
			helper.assertTrue(colony.builds().isEmpty(), "finished structure still listed");
		});
	}

	/** The bundled cobble_step structure mirrored front to back: the stairs sit on the east cobblestone and face west. */
	@GameTest(maxTicks = 90)
	public void loadCommandWithAMirrorBuildsTheStructureFlipped(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 2));
		core(helper).setItem(1, new ItemStack(Items.OAK_STAIRS, 1));
		// In the way of the structure, where the mirrored structure has air.
		helper.setBlock(5, 2, 4, Blocks.DIRT);

		command(helper, PermissionSet.ALL_PERMISSIONS, "antcraft colony build load antcraft:cobble_step " + coords(helper, 5, 1, 4) + " none front_back");
		helper.assertTrue(colony.builds().size() == 1, "structure not added: " + colony.builds());
		member(helper, colony, 1, 4);

		helper.succeedWhen(() -> {
			assertState(helper, 5, 1, 4, COBBLE);
			assertState(helper, 6, 1, 4, COBBLE);
			assertState(helper, 6, 2, 4, STAIRS_EAST.setValue(StairBlock.FACING, Direction.WEST));
			helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(5, 2, 4));
			helper.assertTrue(colony.builds().isEmpty(), "finished structure still listed");
		});
	}

	/** The test structure two_palettes: one block, cobblestone in palette 0 and oak planks in palette 1. */
	@GameTest(maxTicks = 40)
	public void loadCommandBuildsTheChosenPalette(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 1));
		core(helper).setItem(1, new ItemStack(Items.OAK_PLANKS, 1));

		List<Component> messages = command(helper, PermissionSet.ALL_PERMISSIONS,
			"antcraft colony build load antcraft-gametest:two_palettes " + coords(helper, 5, 1, 4) + " none none 1");
		helper.assertTrue(colony.builds().size() == 1, "structure not added: " + messages);
		member(helper, colony, 1, 4);

		helper.succeedWhen(() -> {
			assertState(helper, 5, 1, 4, Blocks.OAK_PLANKS.defaultBlockState());
			helper.assertTrue(colony.builds().isEmpty(), "finished structure still listed");
		});
	}

	@GameTest(maxTicks = 1)
	public void loadCommandWithAPaletteTheStructureLacksFails(GameTestHelper helper) {
		Colony colony = colony(helper);

		List<Component> messages = command(helper, PermissionSet.ALL_PERMISSIONS,
			"antcraft colony build load antcraft-gametest:two_palettes " + coords(helper, 5, 1, 4) + " none none 2");
		helper.assertTrue(says(messages, "commands.antcraft.colony.build.no_palette"), "no no_palette error: " + messages);
		helper.assertTrue(colony.builds().isEmpty(), "structure added: " + colony.builds());
		helper.succeed();
	}

	/** Any colony bot takes build jobs through the worker goal, not only builders. */
	@GameTest(maxTicks = 90)
	public void aColonyBotWithoutTheBuilderRoleBuildsACopy(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 2));
		core(helper).setItem(1, new ItemStack(Items.OAK_STAIRS, 1));
		helper.setBlock(5, 1, 1, Blocks.COBBLESTONE);
		helper.setBlock(6, 1, 1, Blocks.COBBLESTONE);
		helper.setBlock(new BlockPos(5, 2, 1), STAIRS_EAST);
		// In the way of the copy, where the original has air.
		helper.setBlock(6, 2, 5, Blocks.DIRT);

		command(helper, PermissionSet.ALL_PERMISSIONS, copy(helper));
		AntBot farmer = member(helper, colony, 1, 4);

		helper.succeedWhen(() -> {
			assertState(helper, 5, 1, 5, COBBLE);
			assertState(helper, 6, 1, 5, COBBLE);
			assertState(helper, 5, 2, 5, STAIRS_EAST);
			helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(6, 2, 5));
			helper.assertTrue(colony.builds().isEmpty(), "finished copy still listed");
		});
	}

	/** Three batches of one plan that need none of the others are three jobs, taken by three bots at once. */
	@GameTest(maxTicks = 70)
	public void threeBotsWorkOnThreeOpenBatchesOfOnePlanAtOnce(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 9));
		List<BuildPlan.Step> steps = new ArrayList<>();
		for (int x : new int[] {1, 4, 7}) {
			for (int y = 1; y <= 3; y++) {
				steps.add(step(helper, x, y, 6, COBBLE));
			}
		}
		colony.addBuild(new BuildPlan(steps, List.of(new BuildPlan.Batch(0, 3, List.of()), new BuildPlan.Batch(3, 6, List.of()),
			new BuildPlan.Batch(6, 9, List.of()))));
		List<AntBot> bots = List.of(member(helper, colony, 2, 2), member(helper, colony, 4, 2),
			member(helper, colony, 6, 2));

		boolean[] together = {false};
		helper.onEachTick(() -> together[0] |= bots.stream().allMatch(bot -> colony.board().jobOf(bot.getUUID()).isPresent()));
		helper.succeedWhen(() -> {
			for (int x : new int[] {1, 4, 7}) {
				for (int y = 1; y <= 3; y++) {
					assertState(helper, x, y, 6, COBBLE);
				}
			}
			helper.assertTrue(together[0], "the three bots never worked on the plan at the same time");
			helper.assertTrue(colony.builds().isEmpty(), "finished plan still listed");
		});
	}

	/** A door and a bed are one step each, placed whole from one item, as a player would. */
	@GameTest(maxTicks = 80)
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
		member(helper, colony, 1, 4);

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
	@GameTest(maxTicks = 90)
	public void twoBuildersBuildOneCopyTogether(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 16));
		for (int x = 0; x < 8; x++) {
			helper.setBlock(x, 1, 7, Blocks.COBBLESTONE);
			helper.setBlock(x, 2, 7, Blocks.COBBLESTONE);
		}
		colony.addBuild(RegionPlan.copy(helper.getLevel()::getBlockState,
			helper.absolutePos(new BlockPos(0, 1, 7)), helper.absolutePos(new BlockPos(7, 2, 7)), helper.absolutePos(new BlockPos(0, 1, 4))).orElseThrow());
		List<AntBot> builders = List.of(member(helper, colony, 2, 2), member(helper, colony, 5, 2));

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

	/**
	 * In a 16x16 area with room all round, a copied floor 8 by 8 and two layers high is four cells a layer. One bot
	 * builds it alone; then it is taken down and three bots build it again, together, in well under the time the one took.
	 */
	// Measured: run alone, one bot 1069 ticks and three 531 (passes at tick 1600); in full runs it passes at 1601 to 1715.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 3500)
	public void threeBotsBuildACopyFasterThanOneAlone(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 15, 0, 15)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		Colony colony = colony(helper);
		// Enough for both builds, so nothing is ordered.
		for (int slot = 0; slot < 4; slot++) {
			core(helper).setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
		}
		BlockPos from = helper.absolutePos(new BlockPos(4, 1, 4));
		BlockPos to = helper.absolutePos(new BlockPos(11, 2, 11));
		Runnable build = () -> colony.addBuild(RegionPlan.copy(pos -> COBBLE, from, to, from).orElseThrow());
		build.run();
		member(helper, colony, 2, 3);

		// The ticks the one bot took, then the three.
		long[] took = {-1, -1};
		long[] started = {helper.getTick()};
		helper.onEachTick(() -> {
			if (took[1] >= 0 || !colony.builds().isEmpty()) {
				return;
			}
			if (took[0] < 0) {
				took[0] = helper.getTick() - started[0];
				for (BlockPos pos : BlockPos.betweenClosed(4, 1, 4, 11, 2, 11)) {
					helper.setBlock(pos, Blocks.AIR);
				}
				build.run();
				member(helper, colony, 3, 2);
				member(helper, colony, 2, 2);
				started[0] = helper.getTick();
			} else {
				took[1] = helper.getTick() - started[0];
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(took[1] >= 0, "not built twice yet; the one bot took " + took[0]);
			helper.assertTrue(colony.blockedSteps().isEmpty(), "blocked steps: " + colony.blockedSteps());
			for (BlockPos pos : BlockPos.betweenClosed(4, 1, 4, 11, 2, 11)) {
				assertState(helper, pos.getX(), pos.getY(), pos.getZ(), COBBLE);
			}
			helper.assertTrue(took[1] * 3 < took[0] * 2, "three bots took " + took[1] + " ticks, one alone " + took[0]);
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

	@GameTest(maxTicks = 50)
	public void resumesHalfDonePlanAfterSaveAndLoad(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 10));
		// Built before: the builder must skip it.
		helper.setBlock(3, 1, 6, Blocks.COBBLESTONE);
		colony.addBuild(new BuildPlan(List.of(step(helper, 3, 1, 6, COBBLE), step(helper, 4, 1, 6, COBBLE),
			step(helper, 5, 1, 6, COBBLE), step(helper, 6, 1, 6, COBBLE))));
		AntBot builder = member(helper, colony, 1, 4);

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

	@GameTest(maxTicks = 30)
	public void otherBotsDoNotBreakPlacedBlocks(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.OAK_PLANKS, 1));
		BlockPos planks = new BlockPos(4, 1, 4);
		colony.addBuild(new BuildPlan(List.of(step(helper, 4, 1, 4, Blocks.OAK_PLANKS.defaultBlockState()))));
		member(helper, colony, 1, 4);

		LevelWorldView view = new LevelWorldView(helper.getLevel(), new SimpleContainer(1), ItemStack.EMPTY, Set.of());
		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.OAK_PLANKS, planks);
			helper.assertTrue(view.breakTicks(helper.absolutePos(planks)) < 0, "pathing may break the placed planks");
			helper.assertFalse(Mining.isMinable(helper.getLevel(), helper.absolutePos(planks)), "miners may break the placed planks");
		});
	}

	@GameTest(maxTicks = 30)
	public void replacedBlocksAreNoLongerProtected(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.OAK_PLANKS, 1));
		BlockPos planks = new BlockPos(4, 1, 4);
		colony.addBuild(new BuildPlan(List.of(step(helper, 4, 1, 4, Blocks.OAK_PLANKS.defaultBlockState()))));
		member(helper, colony, 1, 4);

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

	@GameTest(maxTicks = 1900)
	public void digsMineshaftAndWalksBackUp(GameTestHelper helper) {
		digsMineshaftAndWalksBackUp(helper, MinerGameTest.mine(helper));
	}

	/** A pocket of water around the stairs at y=2: the builder walls it off and fills what is in the way, so the stairs end up dry. */
	@GameTest(maxTicks = 1700)
	public void digsMineshaftThroughWaterAndLeavesItDryAndWalkable(GameTestHelper helper) {
		Mineshaft shaft = MinerGameTest.mine(helper);
		// Held in by the stone around it.
		for (BlockPos pos : BlockPos.betweenClosed(1, 2, 2, 5, 2, 5)) {
			helper.setBlock(pos, Blocks.WATER);
		}
		digsMineshaftAndWalksBackUp(helper, shaft);
	}

	/** A cave across the stairs at y=2: the builder bridges the floors and walls in the sides, so the stairs stay one continuous way down. */
	@GameTest(maxTicks = 1700)
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
	@GameTest(maxTicks = 2200)
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

	/**
	 * A cave across the stairs at y=2 in tuff, which yields no filler, a builder carrying a little
	 * sand, and sand and dirt in the core: the walls cannot use sand, so it never takes any from the core, and puts what it carries
	 * back once it fetches dirt for them.
	 */
	@GameTest(maxTicks = 1500)
	public void fetchesNoSandForAShaftsWalls(GameTestHelper helper) {
		Mineshaft shaft = MinerGameTest.mine(helper);
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 4, 7)) {
			helper.setBlock(pos, pos.getY() == 2 ? Blocks.AIR : Blocks.TUFF);
		}
		AntBot builder = digsMineshaftAndWalksBackUp(helper, shaft, List.of(new ItemStack(Items.SAND, 64), new ItemStack(Items.DIRT, 64)),
			List.of(new ItemStack(Items.SAND, 8)), () -> { });
		helper.onEachTick(() -> {
			int sand = carried(builder, Items.SAND);
			helper.assertTrue(sand <= 8, "builder picked up sand, carries " + sand);
			helper.assertTrue(sand == 0 || carried(builder, Items.DIRT) == 0, "builder kept " + sand + " sand while fetching dirt");
		});
	}

	private static int carried(AntBot bot, Item item) {
		return bot.getInventory().countItem(item) + (bot.getMainHandItem().is(item) ? bot.getMainHandItem().getCount() : 0);
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
	private static AntBot digsMineshaftAndWalksBackUp(GameTestHelper helper, Mineshaft shaft, List<ItemStack> fillers, List<ItemStack> carried, Runnable also) {
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
		GameTests.failOnGiveUp(helper, colony);
		AntBot builder = helper.spawn(ModEntities.ANT_BOT, 1, 5, 1);
		ColonyManager.get(helper.getLevel()).join(builder, colony);
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
		return builder;
	}

	/** A new colony has no torches or chests yet: the builder digs the shaft anyway and places them once they come. */
	@GameTest(maxTicks = 1400)
	public void digsMineshaftBeforeItsTorchesAndChestsAreMade(GameTestHelper helper) {
		Mineshaft shaft = MinerGameTest.mine(helper);
		helper.setBlock(MinerGameTest.MINE_CORE, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(MinerGameTest.MINE_CORE)).orElseThrow();
		colony.setStockTargets(List.of());
		colony.setMineshaft(shaft);
		GameTests.failOnGiveUp(helper, colony);
		AntBot builder = helper.spawn(ModEntities.ANT_BOT, 1, 5, 1);
		ColonyManager.get(helper.getLevel()).join(builder, colony);
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
	@GameTest(maxTicks = 830)
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
			helper.assertTrue(colony.builds().isEmpty(), "shaft not finished, next step " + shaft.plan().nextStep(helper.getLevel()::getBlockState)
				+ "; core " + Storage.stock(core) + builders.stream().map(builder -> "; bot at " + builder.position().subtract(helper.absoluteVec(Vec3.ZERO))
					+ " holds " + Storage.stock(builder.getInventory()) + " on " + colony.board().jobOf(builder.getUUID()).map(job -> relative(helper, job.target()))).toList());
			helper.assertTrue(shaft.plan().nextStep(helper.getLevel()::getBlockState).isEmpty(), "plan dropped before it was done");
			for (int level : shaft.levels()) {
				helper.assertTrue(helper.getLevel().getBlockState(shaft.landingChest(level)).is(Blocks.CHEST), "no chest at landing " + level);
			}
		});
	}

	/**
	 * Where two builders stalled for good (#160): one finished the stairs to the lower landing with nothing left to
	 * take home and stands idle in the corner of the landing, by the stairs up. The other has dug the lower hall and
	 * must fetch the landing's chest from the core. Bots do not push each other, so it gets past.
	 */
	@GameTest(maxTicks = 210)
	public void climbsTheShaftPastAnIdleBotOnTheLanding(GameTestHelper helper) {
		Mineshaft shaft = MinerGameTest.mine(helper);
		helper.setBlock(MinerGameTest.MINE_CORE, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(MinerGameTest.MINE_CORE)).orElseThrow();
		colony.setStockTargets(List.of());
		((Container) helper.getBlockEntity(MinerGameTest.MINE_CORE, BlockEntity.class)).setItem(0, new ItemStack(Items.CHEST, 1));
		BlockPos chest = shaft.landingChest(shaft.levels().getLast());
		for (BuildPlan.Step step : shaft.plan().steps()) {
			helper.getLevel().setBlockAndUpdate(step.pos(), step.pos().equals(chest) ? Blocks.AIR.defaultBlockState() : step.state());
		}
		colony.setMineshaft(shaft);
		// Not of the colony, so it stands still as an idle member does.
		helper.spawn(ModEntities.ANT_BOT, new Vec3(3.7, 1, 3.3));
		AntBot builder = helper.spawn(ModEntities.ANT_BOT, new Vec3(2.8, 1, 3.7));
		ColonyManager.get(helper.getLevel()).join(builder, colony);
		builder.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));

		helper.succeedWhen(() -> {
			helper.assertTrue(helper.getLevel().getBlockState(chest).is(Blocks.CHEST), "no chest at the lower landing; the builder is at "
				+ builder.position().subtract(helper.absoluteVec(Vec3.ZERO)));
			helper.assertTrue(colony.builds().isEmpty(), "shaft not finished");
		});
	}

	@GameTest(maxTicks = 70)
	public void finishesAroundAStepBehindBedrockAndReportsIt(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 1));
		helper.setBlock(4, 1, 4, Blocks.BEDROCK);
		helper.setBlock(5, 1, 3, Blocks.DIRT);
		colony.addBuild(new BuildPlan(List.of(
			BuildPlan.Step.clear(helper.absolutePos(new BlockPos(4, 1, 4))),
			BuildPlan.Step.clear(helper.absolutePos(new BlockPos(5, 1, 3))),
			step(helper, 5, 1, 5, COBBLE))));
		member(helper, colony, 1, 4);

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
	@GameTest(maxTicks = 180)
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
		member(helper, colony, 2, 4);

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
	 * A block to place in a hollow walled in with bedrock on every side, within reach of where the builder stands:
	 * like a player it cannot place what it cannot see, so it leaves the hollow empty and reports the step.
	 */
	@GameTest(maxTicks = 180)
	public void doesNotPlaceAStepBehindAWallAndReportsIt(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 2));
		BlockPos hollow = new BlockPos(4, 1, 4);
		for (Direction side : Direction.values()) {
			helper.setBlock(hollow.relative(side), Blocks.BEDROCK);
		}
		colony.addBuild(new BuildPlan(List.of(
			step(helper, 4, 1, 4, COBBLE),
			step(helper, 6, 1, 6, COBBLE))));
		member(helper, colony, 2, 4);

		helper.onEachTick(() -> {
			if (!helper.getBlockState(hollow).isAir()) {
				helper.fail("placed the step through the bedrock wall");
			}
		});
		helper.succeedWhen(() -> {
			assertState(helper, 6, 1, 6, COBBLE);
			helper.assertTrue(colony.blockedSteps().equals(List.of(helper.absolutePos(hollow))), "walled-in step not reported: " + colony.blockedSteps());
		});
	}

	/**
	 * A wall any solid block will do, in water walled in with bedrock on every side, within reach of where the builder
	 * stands: like a player it cannot wall off water it cannot see, so it leaves the water and reports the step.
	 */
	@GameTest(maxTicks = 180)
	public void doesNotWallOffWaterItCannotSeeAndReportsIt(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 2));
		BlockPos water = new BlockPos(4, 1, 4);
		for (Direction side : Direction.values()) {
			helper.setBlock(water.relative(side), Blocks.BEDROCK);
		}
		helper.setBlock(water, Blocks.WATER);
		colony.addBuild(new BuildPlan(List.of(
			BuildPlan.Step.solid(helper.absolutePos(water)),
			step(helper, 6, 1, 6, COBBLE))));
		member(helper, colony, 2, 4);

		helper.onEachTick(() -> {
			if (!helper.getBlockState(water).is(Blocks.WATER)) {
				helper.fail("walled off the water through the bedrock wall");
			}
		});
		helper.succeedWhen(() -> {
			assertState(helper, 6, 1, 6, COBBLE);
			helper.assertTrue(colony.blockedSteps().equals(List.of(helper.absolutePos(water))), "walled-in step not reported: " + colony.blockedSteps());
		});
	}

	/**
	 * Water to clear walled in with bedrock on every side, within reach of where the builder stands, and a bucket in
	 * the core: like a player it cannot scoop what it cannot see, so it leaves the water and reports the step.
	 */
	@GameTest(maxTicks = 180)
	public void doesNotScoopAStepsWaterBehindAWallAndReportsIt(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.BUCKET));
		core(helper).setItem(1, new ItemStack(Items.COBBLESTONE, 1));
		BlockPos water = new BlockPos(4, 1, 4);
		for (Direction side : Direction.values()) {
			helper.setBlock(water.relative(side), Blocks.BEDROCK);
		}
		helper.setBlock(water, Blocks.WATER);
		colony.addBuild(new BuildPlan(List.of(
			BuildPlan.Step.clear(helper.absolutePos(water)),
			step(helper, 6, 1, 6, COBBLE))));
		member(helper, colony, 2, 4);

		helper.onEachTick(() -> {
			if (!helper.getBlockState(water).is(Blocks.WATER)) {
				helper.fail("scooped the water through the bedrock wall");
			}
		});
		helper.succeedWhen(() -> {
			assertState(helper, 6, 1, 6, COBBLE);
			helper.assertTrue(colony.blockedSteps().equals(List.of(helper.absolutePos(water))), "walled-in step not reported: " + colony.blockedSteps());
		});
	}

	/**
	 * Water to clear walled in with bedrock on every side, within reach of where the builder stands, and no bucket but
	 * cobblestone in the core: like a player it cannot fill what it cannot see, so it leaves the water and reports the step.
	 */
	@GameTest(maxTicks = 180)
	public void doesNotFillAStepsWaterBehindAWallAndReportsIt(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 2));
		BlockPos water = new BlockPos(4, 1, 4);
		for (Direction side : Direction.values()) {
			helper.setBlock(water.relative(side), Blocks.BEDROCK);
		}
		helper.setBlock(water, Blocks.WATER);
		colony.addBuild(new BuildPlan(List.of(
			BuildPlan.Step.clear(helper.absolutePos(water)),
			step(helper, 6, 1, 6, COBBLE))));
		member(helper, colony, 2, 4);

		helper.onEachTick(() -> {
			if (!helper.getBlockState(water).is(Blocks.WATER)) {
				helper.fail("filled the water through the bedrock wall");
			}
		});
		helper.succeedWhen(() -> {
			assertState(helper, 6, 1, 6, COBBLE);
			helper.assertTrue(colony.blockedSteps().equals(List.of(helper.absolutePos(water))), "walled-in step not reported: " + colony.blockedSteps());
		});
	}

	/**
	 * A wall any solid block will do, in a hollow walled in with bedrock but for one stone side the plan digs out after
	 * it, as a mineshaft walls a cave: like a player it does not place the wall through the stone, but digs the stone
	 * first and walls the hollow once it sees it.
	 */
	@GameTest(maxTicks = 50)
	public void doesNotPlaceAWallThroughUndugStone(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 2));
		BlockPos hollow = new BlockPos(4, 1, 4);
		BlockPos stone = hollow.west();
		for (Direction side : Direction.values()) {
			helper.setBlock(hollow.relative(side), side == Direction.WEST ? Blocks.STONE : Blocks.BEDROCK);
		}
		colony.addBuild(new BuildPlan(List.of(
			BuildPlan.Step.solid(helper.absolutePos(hollow)),
			BuildPlan.Step.clear(helper.absolutePos(stone)))));
		member(helper, colony, 1, 4).getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));

		helper.onEachTick(() -> {
			if (!helper.getBlockState(hollow).isAir() && helper.getBlockState(stone).is(Blocks.STONE)) {
				helper.fail("placed the wall through the undug stone");
			}
		});
		helper.succeedWhen(() -> {
			assertState(helper, 4, 1, 4, COBBLE);
			helper.assertTrue(helper.getBlockState(stone).isAir(), "stone not dug");
			helper.assertTrue(colony.blockedSteps().isEmpty(), "blocked: " + colony.blockedSteps());
		});
	}

	/**
	 * A wall any solid block will do, in water walled in with bedrock but for one stone side the plan digs out, as a
	 * mineshaft walls water beside its stairs: like a player it does not wall off the water through the stone, but digs
	 * the stone and walls the water in the same tick it sees it, before the water flows into the dug cell.
	 */
	@GameTest(maxTicks = 100)
	public void doesNotWallOffWaterBehindUndugStone(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 2));
		BlockPos water = new BlockPos(4, 1, 4);
		BlockPos stone = water.west();
		for (Direction side : Direction.values()) {
			helper.setBlock(water.relative(side), side == Direction.WEST ? Blocks.STONE : Blocks.BEDROCK);
		}
		helper.setBlock(water, Blocks.WATER);
		colony.addBuild(new BuildPlan(List.of(
			BuildPlan.Step.solid(helper.absolutePos(water)),
			BuildPlan.Step.clear(helper.absolutePos(stone)))));
		member(helper, colony, 1, 4).getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));

		helper.onEachTick(() -> {
			if (!helper.getBlockState(water).is(Blocks.WATER) && helper.getBlockState(stone).is(Blocks.STONE)) {
				helper.fail("walled off the water through the undug stone");
			}
			if (!helper.getBlockState(stone).getFluidState().isEmpty()) {
				helper.fail("water ran into the dug cell");
			}
		});
		helper.succeedWhen(() -> {
			assertState(helper, 4, 1, 4, COBBLE);
			helper.assertTrue(helper.getBlockState(stone).isAir(), "stone not dug");
			helper.assertTrue(colony.blockedSteps().isEmpty(), "blocked: " + colony.blockedSteps());
		});
	}

	/** Bedrock from 3, 1, 2 to 6, 3, 6 and under the floor west of it, so only what a test carves out of it is open. */
	private static void bedrockBox(GameTestHelper helper) {
		for (int x = 0; x <= 6; x++) {
			for (int z = 0; z <= 7; z++) {
				for (int y = x <= 3 ? 0 : 1; y <= 3; y++) {
					if (x >= 3 && z >= 2 && z <= 6 || y == 0) {
						helper.setBlock(x, y, z, Blocks.BEDROCK);
					}
				}
			}
		}
	}

	/**
	 * Stone to dig at the end of a tunnel one block high, under glass the plan digs after it, and water north of the stone:
	 * from no spot does the builder see both the stone and, through it, the water, so it cannot dig the stone and wall the
	 * water in one tick. It digs the glass first, and then the stone from where it sees both.
	 */
	@GameTest(maxTicks = 190)
	public void digsANeighbourForAViewOfWaterBehindACell(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 1));
		bedrockBox(helper);
		BlockPos stone = new BlockPos(4, 1, 4);
		BlockPos water = stone.north();
		BlockPos glass = new BlockPos(3, 2, 4);
		helper.setBlock(3, 1, 4, Blocks.AIR);
		helper.setBlock(glass, Blocks.GLASS);
		helper.setBlock(stone, Blocks.STONE);
		helper.setBlock(water, Blocks.WATER);
		colony.addBuild(new BuildPlan(List.of(
			BuildPlan.Step.clear(helper.absolutePos(stone)),
			BuildPlan.Step.clear(helper.absolutePos(glass)))));
		member(helper, colony, 1, 4).getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));

		helper.onEachTick(() -> {
			if (!helper.getBlockState(stone).getFluidState().isEmpty()) {
				helper.fail("water ran into the dug cell");
			}
			if (!colony.blockedSteps().isEmpty()) {
				helper.fail("reported: " + colony.blockedSteps());
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(helper.getBlockState(glass).isAir(), "glass not dug");
			helper.assertTrue(helper.getBlockState(stone).isAir(), "stone not dug");
			helper.assertBlockPresent(Blocks.COBBLESTONE, water);
		});
	}

	/**
	 * Stone to dig with water on two sides, and one cobblestone in the core: the builder cannot wall both off as it digs,
	 * so it reports the step rather than fetch filler the core does not have, forever.
	 */
	@GameTest(maxTicks = 40)
	public void reportsADigWithTooLittleFillerToWallItsWater(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 1));
		bedrockBox(helper);
		BlockPos stone = new BlockPos(4, 1, 4);
		helper.setBlock(3, 1, 4, Blocks.AIR);
		helper.setBlock(3, 2, 4, Blocks.AIR);
		helper.setBlock(stone, Blocks.STONE);
		helper.setBlock(stone.north(), Blocks.WATER);
		helper.setBlock(stone.east(), Blocks.WATER);
		colony.addBuild(new BuildPlan(List.of(BuildPlan.Step.clear(helper.absolutePos(stone)))));
		member(helper, colony, 1, 4).getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.STONE, stone);
			helper.assertTrue(colony.blockedSteps().equals(List.of(helper.absolutePos(stone))), "dig short of filler not reported: " + colony.blockedSteps());
		});
	}

	/**
	 * Bedrock to clear with water on two sides and filler enough for both walls in the core: it is reported, and the
	 * water is not walled off with the colony's filler for a dig that cannot happen.
	 */
	@GameTest(maxTicks = 30)
	public void reportsBedrockToClearBesideWater(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 2));
		bedrockBox(helper);
		BlockPos bedrock = new BlockPos(4, 1, 4);
		helper.setBlock(3, 1, 4, Blocks.AIR);
		helper.setBlock(3, 2, 4, Blocks.AIR);
		helper.setBlock(bedrock.north(), Blocks.WATER);
		helper.setBlock(bedrock.east(), Blocks.WATER);
		colony.addBuild(new BuildPlan(List.of(BuildPlan.Step.clear(helper.absolutePos(bedrock)))));
		member(helper, colony, 1, 4).getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));

		helper.onEachTick(() -> {
			if (helper.getBlockState(bedrock.north()).getFluidState().isEmpty() || helper.getBlockState(bedrock.east()).getFluidState().isEmpty()) {
				helper.fail("walled off the water beside bedrock");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.BEDROCK, bedrock);
			helper.assertTrue(core(helper).countItem(Items.COBBLESTONE) == 2, "filler taken from the core");
			helper.assertTrue(colony.blockedSteps().equals(List.of(helper.absolutePos(bedrock))), "bedrock beside water not reported: " + colony.blockedSteps());
		});
	}

	/** As {@link #reportsADigWithTooLittleFillerToWallItsWater}, but once it is reported a second cobblestone goes in the core: the dig is retried and done. */
	@GameTest(maxTicks = 260)
	public void retriesADigReportedForTooLittleFillerOnceThereIsEnough(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 1));
		bedrockBox(helper);
		BlockPos stone = new BlockPos(4, 1, 4);
		helper.setBlock(3, 1, 4, Blocks.AIR);
		helper.setBlock(3, 2, 4, Blocks.AIR);
		helper.setBlock(stone, Blocks.STONE);
		helper.setBlock(stone.north(), Blocks.WATER);
		helper.setBlock(stone.east(), Blocks.WATER);
		colony.addBuild(new BuildPlan(List.of(BuildPlan.Step.clear(helper.absolutePos(stone)))));
		member(helper, colony, 1, 4).getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		boolean[] reported = {false};

		helper.onEachTick(() -> {
			if (!reported[0] && !colony.blockedSteps().isEmpty()) {
				reported[0] = true;
				core(helper).setItem(1, new ItemStack(Items.COBBLESTONE, 1));
			}
			if (!helper.getBlockState(stone).getFluidState().isEmpty()) {
				helper.fail("water ran into the dug cell");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(reported[0], "dig never reported");
			helper.assertTrue(helper.getBlockState(stone).isAir(), "stone not dug");
			helper.assertBlockPresent(Blocks.COBBLESTONE, stone.north());
			helper.assertBlockPresent(Blocks.COBBLESTONE, stone.east());
			helper.assertTrue(colony.blockedSteps().isEmpty(), "still reported: " + colony.blockedSteps());
		});
	}

	/** Stone to dig with water on two sides, and one dirt and one cobblestone in the core: it walls one off with each. */
	@GameTest(maxTicks = 60)
	public void wallsWaterOffWithMixedFiller(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 1));
		core(helper).setItem(1, new ItemStack(Items.DIRT, 1));
		bedrockBox(helper);
		BlockPos stone = new BlockPos(4, 1, 4);
		helper.setBlock(3, 1, 4, Blocks.AIR);
		helper.setBlock(3, 2, 4, Blocks.AIR);
		helper.setBlock(stone, Blocks.STONE);
		helper.setBlock(stone.north(), Blocks.WATER);
		helper.setBlock(stone.east(), Blocks.WATER);
		colony.addBuild(new BuildPlan(List.of(BuildPlan.Step.clear(helper.absolutePos(stone)))));
		member(helper, colony, 1, 4).getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));

		helper.onEachTick(() -> {
			if (!helper.getBlockState(stone).getFluidState().isEmpty()) {
				helper.fail("water ran into the dug cell");
			}
			if (!colony.blockedSteps().isEmpty()) {
				helper.fail("reported: " + colony.blockedSteps());
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(helper.getBlockState(stone).isAir(), "stone not dug");
			Set<Block> walls = Set.of(helper.getBlockState(stone.north()).getBlock(), helper.getBlockState(stone.east()).getBlock());
			helper.assertTrue(walls.equals(Set.of(Blocks.COBBLESTONE, Blocks.DIRT)), "walls: " + walls);
		});
	}

	/**
	 * A block to place high in the air, with no scaffold to pillar up with: the builder cannot get
	 * within reach of it, so it gives the step up and reports it, rather than trying forever.
	 */
	@GameTest(maxTicks = 170)
	public void givesUpOnAStepItCannotReachAndReportsIt(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 2));
		BuildPlan.Step high = step(helper, 4, 7, 4, COBBLE);
		colony.addBuild(new BuildPlan(List.of(high, step(helper, 6, 1, 6, COBBLE))));
		member(helper, colony, 2, 4);

		helper.succeedWhen(() -> {
			assertState(helper, 6, 1, 6, COBBLE);
			helper.assertTrue(colony.blockedSteps().equals(List.of(high.pos())), "unreachable step not reported: " + colony.blockedSteps());
			helper.assertTrue(colony.unreachableSteps().equals(List.of(high.pos())), "step not reported as unreachable: " + colony.unreachableSteps());
		});
	}

	/**
	 * One builder finds its batch blocked by bedrock while the other digs out dirt for a batch that
	 * does not need it; the batch after the dirt is posted and built all the same, while the one
	 * after the bedrock waits.
	 */
	@GameTest(maxTicks = 90)
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
		member(helper, colony, 1, 4);
		member(helper, colony, 1, 5);

		helper.succeedWhen(() -> {
			helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(5, 1, 3));
			assertState(helper, 5, 1, 5, COBBLE);
			helper.assertBlockPresent(Blocks.AIR, new BlockPos(6, 1, 6));
			helper.assertTrue(colony.blockedSteps().equals(List.of(helper.absolutePos(new BlockPos(4, 1, 4)))), "blocked step not reported: " + colony.blockedSteps());
			helper.assertTrue(colony.builds().isEmpty(), "plan left with only blocked batches still listed");
		});
	}

	/** A door whose upper half would go into bedrock cannot be placed, so it is reported like a step behind bedrock. */
	@GameTest(maxTicks = 40)
	public void reportsADoorWhoseUpperHalfIsBedrock(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.OAK_DOOR, 1));
		core(helper).setItem(1, new ItemStack(Items.COBBLESTONE, 1));
		helper.setBlock(4, 2, 4, Blocks.BEDROCK);
		BuildPlan.Step door = new BuildPlan.Step(helper.absolutePos(new BlockPos(4, 1, 4)), Blocks.OAK_DOOR.defaultBlockState());
		colony.addBuild(new BuildPlan(List.of(door, step(helper, 5, 1, 5, COBBLE))));
		member(helper, colony, 1, 4);

		helper.succeedWhen(() -> {
			assertState(helper, 5, 1, 5, COBBLE);
			helper.assertBlockPresent(Blocks.BEDROCK, new BlockPos(4, 2, 4));
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
			helper.assertTrue(colony.blockedSteps().equals(List.of(door.pos())), "blocked door not reported: " + colony.blockedSteps());
		});
	}

	@GameTest(maxTicks = 250)
	public void doesABlockedStepOnceTheBedrockIsReplacedByStone(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.STONE_PICKAXE));
		helper.setBlock(4, 1, 4, Blocks.BEDROCK);
		colony.addBuild(new BuildPlan(List.of(BuildPlan.Step.clear(helper.absolutePos(new BlockPos(4, 1, 4))))));
		member(helper, colony, 1, 4);

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

	@GameTest(maxTicks = 30)
	public void clearsTheBlockedListOnCommand(GameTestHelper helper) {
		Colony colony = colony(helper);
		helper.setBlock(4, 1, 4, Blocks.BEDROCK);
		colony.addBuild(new BuildPlan(List.of(BuildPlan.Step.clear(helper.absolutePos(new BlockPos(4, 1, 4))))));
		member(helper, colony, 1, 4);

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

	@GameTest(maxTicks = 30)
	public void scoopsAWaterSourceWithABucket(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.BUCKET));
		colony.addBuild(pool(helper, Blocks.WATER.defaultBlockState()));
		AntBot builder = member(helper, colony, 1, 4);

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.AIR, new BlockPos(4, 1, 4));
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
			helper.assertTrue(colony.blockedSteps().isEmpty(), "reported blocked: " + colony.blockedSteps());
			helper.assertTrue(builder.getInventory().countItem(Items.WATER_BUCKET) + core(helper).countItem(Items.WATER_BUCKET) == 1, "water not scooped up");
		});
	}

	@GameTest(maxTicks = 60)
	public void fillsAWaterSourceWithoutABucketAndClearsIt(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.DIRT));
		colony.addBuild(pool(helper, Blocks.WATER.defaultBlockState()));
		member(helper, colony, 1, 4);

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.AIR, new BlockPos(4, 1, 4));
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
			helper.assertTrue(colony.blockedSteps().isEmpty(), "reported blocked: " + colony.blockedSteps());
		});
	}

	@GameTest(maxTicks = 30)
	public void reportsLavaItCannotClearAsBlocked(GameTestHelper helper) {
		Colony colony = colony(helper);
		colony.addBuild(pool(helper, Blocks.LAVA.defaultBlockState()));
		member(helper, colony, 1, 4);

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

	@GameTest(maxTicks = 30)
	public void doesNotPlaceWaterWithoutABucketOrASourceAndReportsIt(GameTestHelper helper) {
		Colony colony = colony(helper);
		colony.addBuild(waterStep(helper));
		member(helper, colony, 1, 4);

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.AIR, new BlockPos(4, 1, 4));
			helper.assertTrue(colony.blockedSteps().equals(List.of(helper.absolutePos(new BlockPos(4, 1, 4)))), "step not reported: " + colony.blockedSteps());
		});
	}

	@GameTest(maxTicks = 30)
	public void placesWaterFromABucketInTheCoreAndKeepsTheBucket(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.WATER_BUCKET));
		colony.addBuild(waterStep(helper));
		AntBot builder = member(helper, colony, 1, 4);

		helper.succeedWhen(() -> {
			assertState(helper, 4, 1, 4, Blocks.WATER.defaultBlockState());
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
			helper.assertTrue(builder.getInventory().countItem(Items.WATER_BUCKET) + core(helper).countItem(Items.WATER_BUCKET) == 0, "water bucket not used");
			helper.assertTrue(builder.getInventory().countItem(Items.BUCKET) + core(helper).countItem(Items.BUCKET) == 1, "empty bucket not kept");
		});
	}

	@GameTest(maxTicks = 70)
	public void ordersABucketAndFillsItAtASourceToPlaceWater(GameTestHelper helper) {
		Colony colony = colony(helper);
		BlockPos source = new BlockPos(6, 1, 1);
		for (Direction side : Direction.Plane.HORIZONTAL) {
			helper.setBlock(source.relative(side), Blocks.STONE);
		}
		helper.setBlock(source, Blocks.WATER);
		colony.addBuild(waterStep(helper));
		member(helper, colony, 1, 4);

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
			helper.assertTrue(onlyTheMineshaftLeft(colony), "plan not finished");
		});
	}

	/** A plan to place a waterlogged stair at (4, 1, 4), walled in by stone so its water does not spread. */
	private static BuildPlan waterloggedStairStep(GameTestHelper helper) {
		for (Direction side : Direction.Plane.HORIZONTAL) {
			helper.setBlock(new BlockPos(4, 1, 4).relative(side), Blocks.STONE);
		}
		return new BuildPlan(List.of(step(helper, 4, 1, 4, STAIRS_EAST.setValue(BlockStateProperties.WATERLOGGED, true))));
	}

	@GameTest(maxTicks = 30)
	public void placesAWaterloggedStairDryWithoutAWaterBucketAndReportsIt(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.OAK_STAIRS));
		colony.addBuild(waterloggedStairStep(helper));
		member(helper, colony, 1, 4);

		helper.onEachTick(() -> helper.assertFalse(helper.getBlockState(new BlockPos(4, 1, 4)).getFluidState().is(Fluids.WATER),
			"stair waterlogged without a water bucket"));
		helper.succeedWhen(() -> {
			assertState(helper, 4, 1, 4, STAIRS_EAST);
			helper.assertTrue(colony.blockedSteps().equals(List.of(helper.absolutePos(new BlockPos(4, 1, 4)))), "step not reported: " + colony.blockedSteps());
		});
	}

	@GameTest(maxTicks = 40)
	public void fillsAWaterloggedStairFromABucketInTheCoreAndKeepsTheBucket(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.OAK_STAIRS));
		core(helper).setItem(1, new ItemStack(Items.WATER_BUCKET));
		colony.addBuild(waterloggedStairStep(helper));
		AntBot builder = member(helper, colony, 1, 4);

		helper.succeedWhen(() -> {
			assertState(helper, 4, 1, 4, STAIRS_EAST.setValue(BlockStateProperties.WATERLOGGED, true));
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
			helper.assertTrue(builder.getInventory().countItem(Items.WATER_BUCKET) + core(helper).countItem(Items.WATER_BUCKET) == 0, "water bucket not used");
			helper.assertTrue(builder.getInventory().countItem(Items.BUCKET) + core(helper).countItem(Items.BUCKET) == 1, "empty bucket not kept");
		});
	}

	@GameTest(maxTicks = 70)
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
		member(helper, colony, 1, 4);

		helper.succeedWhen(() -> {
			assertState(helper, 4, 1, 4, STAIRS_EAST.setValue(BlockStateProperties.WATERLOGGED, true));
			helper.assertBlockNotPresent(Blocks.WATER, source);
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
		});
	}

	/** The nearest source is walled in with stone, top included, within reach of the builder: like a player, it fills its bucket at the open one instead. */
	@GameTest(maxTicks = 70)
	public void doesNotScoopAWaterSourceBehindAWall(GameTestHelper helper) {
		Colony colony = colony(helper);
		BlockPos walled = new BlockPos(1, 1, 6);
		BlockPos open = new BlockPos(6, 1, 1);
		for (BlockPos source : List.of(walled, open)) {
			for (Direction side : Direction.Plane.HORIZONTAL) {
				helper.setBlock(source.relative(side), Blocks.STONE);
			}
			helper.setBlock(source, Blocks.WATER);
		}
		helper.setBlock(walled.above(), Blocks.STONE);
		core(helper).setItem(0, new ItemStack(Items.OAK_STAIRS));
		core(helper).setItem(1, new ItemStack(Items.BUCKET));
		colony.addBuild(waterloggedStairStep(helper));
		member(helper, colony, 1, 4);

		helper.onEachTick(() -> {
			if (!helper.getBlockState(walled).is(Blocks.WATER)) {
				helper.fail("scooped the walled-in source through the stone");
			}
		});
		helper.succeedWhen(() -> {
			assertState(helper, 4, 1, 4, STAIRS_EAST.setValue(BlockStateProperties.WATERLOGGED, true));
			helper.assertBlockNotPresent(Blocks.WATER, open);
		});
	}

	@GameTest(maxTicks = 30)
	public void scoopsTheWaterOutOfAStairPlannedDry(GameTestHelper helper) {
		Colony colony = colony(helper);
		// Only for its stone walls, which keep the water in.
		waterloggedStairStep(helper);
		helper.setBlock(new BlockPos(4, 1, 4), STAIRS_EAST.setValue(BlockStateProperties.WATERLOGGED, true));
		core(helper).setItem(0, new ItemStack(Items.BUCKET));
		colony.addBuild(new BuildPlan(List.of(step(helper, 4, 1, 4, STAIRS_EAST))));
		AntBot builder = member(helper, colony, 1, 4);

		// The stair stays: only its water goes, scooped up like a player would, not broken out.
		helper.onEachTick(() -> helper.assertBlockPresent(Blocks.OAK_STAIRS, new BlockPos(4, 1, 4)));
		helper.succeedWhen(() -> {
			assertState(helper, 4, 1, 4, STAIRS_EAST);
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
			helper.assertTrue(builder.getInventory().countItem(Items.WATER_BUCKET) + core(helper).countItem(Items.WATER_BUCKET) == 1, "water not scooped up");
		});
	}

	@GameTest(maxTicks = 30)
	public void reportsFireThatCannotStayAsBlockedAndKeepsItBlocked(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
		// Over air, with nothing flammable beside it, fire goes out at once.
		BuildPlan.Step fire = step(helper, 4, 2, 4, Blocks.FIRE.defaultBlockState());
		colony.addBuild(new BuildPlan(List.of(fire)));
		AntBot builder = member(helper, colony, 1, 4);

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

	@GameTest(maxTicks = 30)
	public void lightsFireOnNetherrackWithFlintAndSteelFromTheCore(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
		helper.setBlock(4, 0, 4, Blocks.NETHERRACK);
		colony.addBuild(new BuildPlan(List.of(step(helper, 4, 1, 4, Blocks.FIRE.defaultBlockState()))));
		AntBot builder = member(helper, colony, 1, 4);

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.FIRE, new BlockPos(4, 1, 4));
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
			ItemStack tool = flintAndSteel(helper, builder);
			helper.assertFalse(tool.isEmpty(), "flint and steel used up");
			helper.assertTrue(tool.getDamageValue() == 1, "flint and steel not worn: " + tool.getDamageValue());
		});
	}

	@GameTest(maxTicks = 30)
	public void reportsFireOnStoneAsBlockedRatherThanRelightingIt(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
		// Stone does not burn forever: fire on it goes out and would be lit again until the tool breaks.
		BuildPlan.Step fire = step(helper, 4, 1, 4, Blocks.FIRE.defaultBlockState());
		colony.addBuild(new BuildPlan(List.of(fire)));
		AntBot builder = member(helper, colony, 1, 4);

		helper.onEachTick(() -> helper.assertBlockNotPresent(Blocks.FIRE, new BlockPos(4, 1, 4)));
		helper.succeedWhen(() -> {
			helper.assertTrue(colony.blockedSteps().equals(List.of(fire.pos())), "step not reported: " + colony.blockedSteps());
			helper.assertTrue(flintAndSteel(helper, builder).getDamageValue() == 0, "flint and steel used");
		});
	}

	@GameTest(maxTicks = 30)
	public void ordersNoFlintAndSteelForBlockedFire(GameTestHelper helper) {
		Colony colony = colony(helper);
		BuildPlan.Step fire = step(helper, 4, 1, 4, Blocks.FIRE.defaultBlockState());
		colony.addBuild(new BuildPlan(List.of(fire)));
		member(helper, colony, 1, 4);

		helper.onEachTick(() -> helper.assertFalse(colony.ordersFor(Colony.OrderPurpose.BUILD).containsKey(Items.FLINT_AND_STEEL),
			"flint and steel ordered: " + colony.orders()));
		helper.succeedWhen(() -> helper.assertTrue(colony.blockedSteps().equals(List.of(fire.pos())), "step not reported: " + colony.blockedSteps()));
	}

	@GameTest(maxTicks = 30)
	public void leavesFireThatWouldSpreadToPlanksUnlit(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
		helper.setBlock(4, 0, 4, Blocks.NETHERRACK);
		// Two blocks away: fire spreads into the air beside it, and on to the planks.
		BuildPlan.Step planks = step(helper, 6, 1, 4, Blocks.OAK_PLANKS.defaultBlockState());
		helper.setBlock(6, 1, 4, Blocks.OAK_PLANKS);
		BuildPlan.Step fire = step(helper, 4, 1, 4, Blocks.FIRE.defaultBlockState());
		colony.addBuild(new BuildPlan(List.of(planks, fire)));
		AntBot builder = member(helper, colony, 1, 4);

		helper.onEachTick(() -> helper.assertBlockNotPresent(Blocks.FIRE, new BlockPos(4, 1, 4)));
		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(6, 1, 4));
			helper.assertTrue(colony.blockedSteps().equals(List.of(fire.pos())), "step not reported: " + colony.blockedSteps());
			helper.assertTrue(flintAndSteel(helper, builder).getDamageValue() == 0, "flint and steel used");
		});
	}

	@GameTest(maxTicks = 30)
	public void lightsSoulFireNextToPlanksSinceItNeverSpreads(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
		helper.setBlock(4, 0, 4, Blocks.SOUL_SAND);
		BuildPlan.Step planks = step(helper, 5, 1, 4, Blocks.OAK_PLANKS.defaultBlockState());
		helper.setBlock(5, 1, 4, Blocks.OAK_PLANKS);
		colony.addBuild(new BuildPlan(List.of(planks, step(helper, 4, 1, 4, Blocks.SOUL_FIRE.defaultBlockState()))));
		member(helper, colony, 1, 4);

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.SOUL_FIRE, new BlockPos(4, 1, 4));
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
		});
	}

	/** Fire spreads with no player near, as it would around one, in this test's own batch only (see its test environment). */
	@GameTest(maxTicks = 800, environment = "antcraft-gametest:fire_spread")
	public void lightsAFireplaceInsideAWoodenHouseThatDoesNotBurn(GameTestHelper helper) {
		helper.assertTrue(helper.getLevel().getGameRules().get(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER) == -1, "fire does not spread");
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
		// A stone hearth around (4, 1, 4) on netherrack, open to the west, with a chimney of air above the fire.
		for (BlockPos pos : BlockPos.betweenClosed(3, 1, 3, 5, 6, 5)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		helper.setBlock(4, 0, 4, Blocks.NETHERRACK);
		for (int y = 1; y <= 6; y++) {
			helper.setBlock(4, y, 4, Blocks.AIR);
		}
		helper.setBlock(3, 1, 4, Blocks.AIR);
		// Oak planks walls against it, two blocks from the fire.
		List<BlockPos> walls = new ArrayList<>();
		for (int y = 1; y <= 3; y++) {
			for (int i = 2; i <= 6; i++) {
				walls.add(new BlockPos(i, y, 2));
				walls.add(new BlockPos(i, y, 6));
				walls.add(new BlockPos(6, y, i));
			}
		}
		walls.forEach(pos -> helper.setBlock(pos, Blocks.OAK_PLANKS));
		colony.addBuild(new BuildPlan(List.of(step(helper, 4, 1, 4, Blocks.FIRE.defaultBlockState()))));
		member(helper, colony, 1, 4);

		long[] lit = {-1};
		helper.onEachTick(() -> {
			walls.forEach(pos -> helper.assertBlockPresent(Blocks.OAK_PLANKS, pos));
			if (lit[0] < 0 && helper.getBlockState(new BlockPos(4, 1, 4)).is(Blocks.FIRE)) {
				lit[0] = helper.getTick();
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(lit[0] >= 0, "fireplace not lit: blocked " + colony.blockedSteps());
			// Long enough for the fire to tick several times.
			helper.assertTrue(helper.getTick() - lit[0] >= 400, "fire not burning long enough yet");
			helper.assertBlockPresent(Blocks.FIRE, new BlockPos(4, 1, 4));
		});
	}

	@GameTest(maxTicks = 30)
	public void placesNoPlanksWhereLitFireSpreadsToThem(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.OAK_PLANKS, 1));
		helper.setBlock(4, 0, 4, Blocks.NETHERRACK);
		helper.setBlock(4, 1, 4, Blocks.FIRE);
		// Two blocks away: fire spreads into the air beside it, and on to the planks.
		BuildPlan.Step planks = step(helper, 6, 1, 4, Blocks.OAK_PLANKS.defaultBlockState());
		colony.addBuild(new BuildPlan(List.of(planks)));
		member(helper, colony, 1, 4);

		helper.onEachTick(() -> helper.assertBlockNotPresent(Blocks.OAK_PLANKS, new BlockPos(6, 1, 4)));
		helper.succeedWhen(() -> helper.assertTrue(colony.blockedSteps().equals(List.of(planks.pos())), "step not reported: " + colony.blockedSteps()));
	}

	@GameTest(maxTicks = 30)
	public void ordersFlintAndSteelForFireAndWaitsForIt(GameTestHelper helper) {
		Colony colony = colony(helper);
		helper.setBlock(4, 0, 4, Blocks.NETHERRACK);
		colony.addBuild(new BuildPlan(List.of(step(helper, 4, 1, 4, Blocks.FIRE.defaultBlockState()))));
		member(helper, colony, 1, 4);

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
			helper.assertTrue(onlyTheMineshaftLeft(colony), "plan not finished");
		});
	}

	@GameTest(maxTicks = 410)
	public void waitsForAMobToLeaveTheSpot(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 1));
		BlockPos spot = new BlockPos(4, 1, 4);
		colony.addBuild(new BuildPlan(List.of(step(helper, 4, 1, 4, COBBLE))));
		Pig pig = helper.spawn(EntityTypes.PIG, spot);
		pig.setNoAi(true);
		member(helper, colony, 1, 4);

		helper.onEachTick(() -> {
			if (pig.isAlive() && helper.getBlockState(spot).is(Blocks.COBBLESTONE)) {
				helper.fail("placed a block into the pig");
			}
			if (!colony.blockedSteps().isEmpty()) {
				helper.fail("step reported blocked while waiting: " + colony.blockedSteps());
			}
		});
		helper.runAfterDelay(200, pig::discard);
		helper.succeedWhen(() -> {
			helper.assertFalse(pig.isAlive(), "pig still there");
			assertState(helper, 4, 1, 4, COBBLE);
		});
	}

	/**
	 * A colony bot on a job of its own, standing in the spot of a step: it steps aside for a moment, so the builder
	 * places the step rather than giving it up as blocked.
	 */
	@GameTest(maxTicks = 60)
	public void aBusyBotStandingWhereABlockGoesStepsAside(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 1));
		BlockPos spot = new BlockPos(4, 1, 4);
		colony.addBuild(new BuildPlan(List.of(step(helper, 4, 1, 4, COBBLE))));
		AntBot busy = member(helper, colony, 4, 4);
		member(helper, colony, 1, 4);

		helper.onEachTick(() -> {
			// At work on something of its own the whole time, as a bot holding a job is.
			busy.setWorking(true);
			if (helper.getBlockState(spot).is(Blocks.COBBLESTONE) && busy.getBoundingBox().intersects(new AABB(helper.absolutePos(spot)))) {
				helper.fail("placed a block into the busy bot");
			}
			if (!colony.blockedSteps().isEmpty()) {
				helper.fail("step reported blocked: " + colony.blockedSteps());
			}
		});
		helper.succeedWhen(() -> {
			assertState(helper, 4, 1, 4, COBBLE);
		});
	}

	/**
	 * A bot chopping a log from the spot of a step, on a real job: it steps aside, the builder places the step, and
	 * the bot goes on to fell the log.
	 */
	@GameTest(maxTicks = 130)
	public void aBotChoppingWhereABlockGoesStepsAsideAndFellsTheLog(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 1));
		BlockPos spot = new BlockPos(4, 1, 4);
		BlockPos log = new BlockPos(5, 1, 4);
		helper.setBlock(log, Blocks.OAK_LOG);
		colony.addBuild(new BuildPlan(List.of(step(helper, 4, 1, 4, COBBLE))));
		AntBot chopper = member(helper, colony, 4, 4);
		long now = helper.getLevel().getGameTime();
		colony.board().post(JobType.CHOP_LOG, helper.absolutePos(log));
		helper.assertTrue(colony.board().claim(chopper.getUUID(), Set.of(JobType.CHOP_LOG), helper.absolutePos(spot), now).isPresent(), "log job not claimed");
		member(helper, colony, 1, 4);

		boolean[] placedWhileChopping = {false};
		helper.onEachTick(() -> {
			boolean placed = helper.getBlockState(spot).is(Blocks.COBBLESTONE);
			if (placed && chopper.getBoundingBox().intersects(new AABB(helper.absolutePos(spot)))) {
				helper.fail("placed a block into the chopper");
			}
			if (placed && !placedWhileChopping[0]) {
				helper.assertTrue(colony.board().jobOf(chopper.getUUID()).filter(job -> job.type() == JobType.CHOP_LOG).isPresent(),
					"the log job was over before the step was placed, so nothing stood in the way");
				placedWhileChopping[0] = true;
			}
			if (!colony.blockedSteps().isEmpty()) {
				helper.fail("step reported blocked: " + colony.blockedSteps());
			}
		});
		helper.succeedWhen(() -> {
			assertState(helper, 4, 1, 4, COBBLE);
			helper.assertBlockPresent(Blocks.AIR, log);
			helper.assertTrue(colony.board().jobs().stream().noneMatch(job -> job.type() == JobType.CHOP_LOG), "log job still on the board");
		});
	}

	/** A mob that never leaves the spot: the builder gives the step up after a while and reports it, rather than waiting forever. */
	@GameTest(maxTicks = 630)
	public void reportsAStepWhoseSpotStaysTaken(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 1));
		BlockPos spot = new BlockPos(4, 1, 4);
		colony.addBuild(new BuildPlan(List.of(step(helper, 4, 1, 4, COBBLE))));
		Pig pig = helper.spawn(EntityTypes.PIG, spot);
		pig.setNoAi(true);
		member(helper, colony, 1, 4);

		helper.succeedWhen(() -> {
			helper.assertTrue(pig.isAlive(), "pig gone");
			helper.assertBlockPresent(Blocks.AIR, spot);
			helper.assertTrue(colony.blockedSteps().equals(List.of(helper.absolutePos(spot))), "taken spot not reported: " + colony.blockedSteps());
		});
	}

	/** Water beside a block to dig, and a mob that never leaves the water: the wall that would hold the water back cannot go in, so the dig is reported. */
	@GameTest(maxTicks = 630)
	public void reportsAStepWhoseFluidWallStaysTaken(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.DIRT, 8));
		BlockPos dig = new BlockPos(4, 1, 4);
		BlockPos water = new BlockPos(4, 1, 5);
		helper.setBlock(dig, Blocks.DIRT);
		// Held in by the stone around it and the dirt to dig.
		for (BlockPos pos : List.of(new BlockPos(3, 1, 5), new BlockPos(5, 1, 5), new BlockPos(4, 1, 6))) {
			helper.setBlock(pos, Blocks.STONE);
		}
		helper.setBlock(water, Blocks.WATER);
		colony.addBuild(new BuildPlan(List.of(BuildPlan.Step.clear(helper.absolutePos(dig)))));
		// A squid, which does not drown there.
		Squid squid = helper.spawn(EntityTypes.SQUID, water);
		squid.setNoAi(true);
		member(helper, colony, 1, 4);

		helper.succeedWhen(() -> {
			helper.assertTrue(squid.isAlive(), "squid gone");
			helper.assertBlockPresent(Blocks.WATER, water);
			helper.assertBlockPresent(Blocks.DIRT, dig);
			helper.assertTrue(colony.blockedSteps().equals(List.of(helper.absolutePos(dig))), "dig behind a taken wall not reported: " + colony.blockedSteps());
		});
	}

	@GameTest(maxTicks = 20)
	public void withdrawsOrderWhenPlanIsCancelled(GameTestHelper helper) {
		Colony colony = colony(helper);
		BuildPlan plan = new BuildPlan(List.of(step(helper, 4, 1, 4, STAIRS_EAST)));
		colony.addBuild(plan);
		member(helper, colony, 1, 4);

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

	@GameTest(maxTicks = 30)
	public void withdrawsOrderWhenPlanFinishesWithItemsFromElsewhere(GameTestHelper helper) {
		Colony colony = colony(helper);
		colony.addBuild(new BuildPlan(List.of(step(helper, 4, 1, 4, STAIRS_EAST))));
		member(helper, colony, 1, 4);

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
	@GameTest(maxTicks = 110)
	public void pillarsUpToBuildASixHighColumnAndLeavesNoScaffold(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 6));
		core(helper).setItem(1, new ItemStack(Items.DIRT, 16));
		colony.addBuild(column(helper, 6, COBBLE));
		AntBot builder = member(helper, colony, 1, 4);

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

	/**
	 * Two build jobs on a ledge too high to reach or see from the ground: the builder pillars up once and does both
	 * from up there, rather than take the pillar down after the first and build it again.
	 */
	@GameTest(maxTicks = 370)
	public void buildsTwoJobsOnALedgeWithOnePillar(GameTestHelper helper) {
		Colony colony = colony(helper);
		// A 5x5 ledge, four high, with the steps in the middle: out of reach and sight from the ground around it.
		for (BlockPos pos : BlockPos.betweenClosed(3, 1, 3, 7, 4, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		colony.addBuild(new BuildPlan(List.of(step(helper, 5, 5, 4, COBBLE))));
		colony.addBuild(new BuildPlan(List.of(step(helper, 5, 5, 5, COBBLE))));
		AntBot builder = member(helper, colony, 1, 4);
		builder.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 2));
		builder.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		// Blocks placed in all and the most standing at once: equal while it never builds a pillar again.
		int[] placed = {0, 0, 0};
		helper.onEachTick(() -> {
			int now = strayDirt(helper);
			placed[0] += Math.max(0, now - placed[2]);
			placed[1] = Math.max(placed[1], now);
			placed[2] = now;
			if (placed[0] > placed[1]) {
				helper.fail("built the pillar again: placed " + placed[0] + " blocks, at most " + placed[1] + " at once");
			}
		});

		helper.succeedWhen(() -> {
			assertState(helper, 5, 5, 4, COBBLE);
			assertState(helper, 5, 5, 5, COBBLE);
			helper.assertTrue(colony.builds().isEmpty(), "plans not finished");
			helper.assertTrue(placed[1] > 0, "never pillared up");
			helper.assertTrue(strayDirt(helper) == 0, strayDirt(helper) + " dirt left standing");
			helper.assertTrue(builder.getScaffold().isEmpty(), "scaffold left: " + builder.getScaffold());
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
	@GameTest(maxTicks = 110)
	public void takesScaffoldWithTheMaterialsInOneTripToTheCore(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 6));
		core(helper).setItem(1, new ItemStack(Items.DIRT, 16));
		colony.addBuild(column(helper, 6, COBBLE));
		AntBot builder = member(helper, colony, 1, 4);

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
	@GameTest(maxTicks = 110)
	public void keepsItsScaffoldWhenFetchingForHighSteps(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 6));
		colony.addBuild(column(helper, 6, COBBLE));
		AntBot builder = member(helper, colony, 1, 4);
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
	@GameTest(maxTicks = 40)
	public void putsScaffoldBackWhenNoHighStepsAreLeft(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 2));
		colony.addBuild(column(helper, 2, COBBLE));
		AntBot builder = member(helper, colony, 1, 4);
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
	@GameTest(maxTicks = 120)
	public void interruptedBuilderResumesOnItsOwnScaffold(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 6));
		core(helper).setItem(1, new ItemStack(Items.DIRT, 16));
		colony.addBuild(column(helper, 6, COBBLE));
		AntBot builder = member(helper, colony, 1, 4);

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
	@GameTest(maxTicks = 150)
	public void reloadedBuilderResumesOnItsOwnScaffold(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 6));
		core(helper).setItem(1, new ItemStack(Items.DIRT, 16));
		colony.addBuild(column(helper, 6, COBBLE));
		AntBot[] builder = {member(helper, colony, 1, 4)};

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

	/** A bot with scaffold left up from a build it was called away from takes it down between jobs. */
	@GameTest(maxTicks = 70)
	public void takesScaffoldLeftFromABuildDownBetweenJobs(GameTestHelper helper) {
		Colony colony = colony(helper);
		AntBot bot = member(helper, colony, 4, 4);
		for (int y = 1; y <= 2; y++) {
			helper.setBlock(5, y, 5, Blocks.DIRT);
			bot.addScaffold(helper.absolutePos(new BlockPos(5, y, 5)));
		}

		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getScaffold().isEmpty(), "scaffold left: " + bot.getScaffold());
			helper.assertBlockNotPresent(Blocks.DIRT, 5, 1, 5);
			helper.assertBlockNotPresent(Blocks.DIRT, 5, 2, 5);
		});
	}

	/**
	 * A bot afloat in a pool one block deep under a rim two blocks up, which it cannot jump out of, with its scaffold
	 * over the water out of reach: it swims over and takes it down from the water rather than dig into the bank, and
	 * then climbs out on a block it places in the water, again without digging the bank.
	 */
	@GameTest(maxTicks = 220)
	public void takesScaffoldDownFromTheWaterAndClimbsOutWithoutDiggingTheBank(GameTestHelper helper) {
		Colony colony = colony(helper);
		// A stone bank at y=1..2, with a pool along x 1..6, z 4..5: water at y=1, air above.
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 2, 7)) {
			boolean pool = pos.getX() >= 1 && pos.getX() <= 6 && pos.getZ() >= 4 && pos.getZ() <= 5;
			if (!pos.equals(CORE)) {
				helper.setBlock(pos, !pool ? Blocks.STONE : pos.getY() == 1 ? Blocks.WATER : Blocks.AIR);
			}
		}
		helper.setBlock(6, 2, 5, Blocks.DIRT);
		AntBot bot = member(helper, colony, 1, 4);
		bot.addScaffold(helper.absolutePos(new BlockPos(6, 2, 5)));

		helper.onEachTick(() -> {
			for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 2, 7)) {
				boolean pool = pos.getX() >= 1 && pos.getX() <= 6 && pos.getZ() >= 4 && pos.getZ() <= 5;
				if (!pool && !pos.equals(CORE) && !helper.getBlockState(pos).is(Blocks.STONE)) {
					helper.fail("dug into the bank at " + pos.toShortString());
				}
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getScaffold().isEmpty(), "scaffold left: " + bot.getScaffold());
			helper.assertBlockNotPresent(Blocks.DIRT, 6, 2, 5);
			helper.assertTrue(bot.getY() >= helper.absolutePos(BlockPos.ZERO).getY() + 3 && !bot.isInWater(),
				"still in the pool at " + relative(helper, bot.blockPosition()).toShortString());
		});
	}

	/**
	 * The #243 pool: a stone bank at y=1..2 with a pool along x 1..6, z 4..5, water at y=1 under air. Fails at once
	 * when the bank is dug into.
	 */
	private static void poolUnderARim(GameTestHelper helper) {
		poolUnderARim(helper, 4);
	}

	/** A pool like the #243 one along z {@code z} and {@code z + 1}. */
	private static void poolUnderARim(GameTestHelper helper, int z) {
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 2, 7)) {
			boolean pool = pos.getX() >= 1 && pos.getX() <= 6 && pos.getZ() >= z && pos.getZ() <= z + 1;
			if (!pos.equals(CORE)) {
				helper.setBlock(pos, !pool ? Blocks.STONE : pos.getY() == 1 ? Blocks.WATER : Blocks.AIR);
			}
		}
		helper.onEachTick(() -> {
			for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 2, 7)) {
				boolean pool = pos.getX() >= 1 && pos.getX() <= 6 && pos.getZ() >= z && pos.getZ() <= z + 1;
				if (!pool && !pos.equals(CORE) && !helper.getBlockState(pos).is(Blocks.STONE)) {
					helper.fail("dug into the bank at " + pos.toShortString());
				}
			}
		});
	}

	/**
	 * An idle bot dropped into the #243 pool with no job and no scaffold up, carrying dirt: it climbs out on blocks it
	 * places in the water, without digging the bank, and takes those down from the bank.
	 */
	@GameTest(maxTicks = 80)
	public void idleBotClimbsOutOfAPoolWithoutDiggingTheBank(GameTestHelper helper) {
		Colony colony = colony(helper);
		poolUnderARim(helper);
		AntBot bot = member(helper, colony, 3, 4);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 4));

		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getY() >= helper.absolutePos(BlockPos.ZERO).getY() + 3 && bot.onGround() && !bot.isInWater(),
				"still in the pool at " + relative(helper, bot.blockPosition()).toShortString());
			helper.assertTrue(bot.getScaffold().isEmpty(), "scaffold left: " + bot.getScaffold());
		});
	}

	/**
	 * An idle bot carrying no blocks in a pool like the #243 one beside the core, which holds dirt: it takes dirt from the
	 * core from the water and climbs out on it, without digging the bank.
	 */
	@GameTest(maxTicks = 90)
	public void idleBotFetchesBlocksToClimbOutOfAPool(GameTestHelper helper) {
		Colony colony = colony(helper);
		poolUnderARim(helper, 2);
		core(helper).setItem(0, new ItemStack(Items.DIRT, 4));
		AntBot bot = member(helper, colony, 3, 3);
		bot.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));

		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getY() >= helper.absolutePos(BlockPos.ZERO).getY() + 3 && bot.onGround() && !bot.isInWater(),
				"still in the pool at " + relative(helper, bot.blockPosition()).toShortString());
			helper.assertTrue(bot.getScaffold().isEmpty(), "scaffold left: " + bot.getScaffold());
		});
	}

	/**
	 * An idle bot in the #243 pool with no blocks to climb out on, and none in the core, stays afloat rather than dig
	 * into the bank, though its pickaxe would make short work of it.
	 */
	@GameTest(maxTicks = 120)
	public void idleBotWithNoBlocksDoesNotDigOutOfAPool(GameTestHelper helper) {
		Colony colony = colony(helper);
		poolUnderARim(helper);
		AntBot bot = member(helper, colony, 3, 4);
		bot.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));

		helper.runAtTickTime(100, () -> {
			helper.assertTrue(bot.isInWater(), "left the pool without blocks at " + relative(helper, bot.blockPosition()).toShortString());
			helper.succeed();
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
	@GameTest(maxTicks = 110)
	public void neverMixesUpScaffoldAndPlanBlocksOfTheSameKind(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.DIRT, 22));
		colony.addBuild(column(helper, 6, Blocks.DIRT.defaultBlockState()));
		AntBot builder = member(helper, colony, 1, 4);

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

	/**
	 * A bot standing on a lone cobblestone scaffold block five blocks up, with nothing else under it, no blocks or
	 * pickaxe to dig down with, none in the core, and too little health to take the fall well, does not take that
	 * block down from under itself: the fall would cost it half its health or more. It leaves the block up.
	 */
	@GameTest(maxTicks = 300)
	public void doesNotTakeDownScaffoldItWouldFallFarFrom(GameTestHelper helper) {
		Colony colony = colony(helper);
		BlockPos floating = new BlockPos(4, 5, 4);
		helper.setBlock(floating, Blocks.COBBLESTONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 6, 4);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		bot.addScaffold(helper.absolutePos(floating));
		bot.setHealth(4);

		// Long enough to break the cobblestone by hand.
		helper.runAfterDelay(250, () -> {
			helper.assertBlockPresent(Blocks.COBBLESTONE, floating);
			helper.assertTrue(bot.getHealth() == 4, "fell and got hurt: " + bot.getHealth());
			helper.assertTrue(bot.getScaffold().isEmpty(), "still taking it down: " + bot.getScaffold());
			helper.succeed();
		});
	}

	/**
	 * A bot standing on a lone cobblestone scaffold block five blocks up, with no blocks or pickaxe to dig down with,
	 * and the dirt in the core walled in out of its reach, does not stay up there: it breaks the block by hand and
	 * takes the short fall, which costs it little of its health.
	 */
	@GameTest(maxTicks = 440)
	public void breaksAFloatingBlockByHandWithNoReachableCore(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.DIRT, 8));
		for (int x = 0; x <= 2; x++) {
			for (int y = 1; y <= 2; y++) {
				for (int z = 0; z <= 2; z++) {
					if (!new BlockPos(x, y, z).equals(CORE)) {
						helper.setBlock(x, y, z, Blocks.STONE);
					}
				}
			}
		}
		BlockPos floating = new BlockPos(4, 5, 4);
		helper.setBlock(floating, Blocks.COBBLESTONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 6, 4);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		bot.addScaffold(helper.absolutePos(floating));
		bot.setHealth(bot.getMaxHealth());

		helper.succeedWhen(() -> {
			helper.assertBlockNotPresent(Blocks.COBBLESTONE, floating);
			helper.assertTrue(bot.isAlive(), "died");
			helper.assertTrue(bot.getHealth() > bot.getMaxHealth() / 2, "hurt badly: " + bot.getHealth());
			helper.assertTrue(bot.getScaffold().isEmpty(), "scaffold still recorded: " + bot.getScaffold());
			helper.assertTrue(bot.onGround() && bot.getY() < helper.absolutePos(new BlockPos(0, 2, 0)).getY(), "not down on the floor");
			helper.assertTrue(core(helper).getItem(0).getCount() == 8, "took dirt from the walled-in core");
		});
	}

	/**
	 * A bot on top of a 5-high pillar of its own scaffold, too high to drop from (a 6-high one would put its head
	 * in the ceiling of the test area): it does not stay up there,
	 * but gets down, taking the pillar down under it, unhurt.
	 */
	@GameTest(maxTicks = 200)
	public void getsDownFromAHighPillarAndTakesItDown(GameTestHelper helper) {
		Colony colony = colony(helper);
		for (int y = 1; y <= 5; y++) {
			helper.setBlock(4, y, 4, Blocks.DIRT);
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 6, 4);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		for (int y = 1; y <= 5; y++) {
			bot.addScaffold(helper.absolutePos(new BlockPos(4, y, 4)));
		}
		bot.setHealth(bot.getMaxHealth());
		helper.onEachTick(() -> {
			if (bot.getScaffold().isEmpty() && strayDirt(helper) > 0) {
				helper.fail("forgot the pillar with " + strayDirt(helper) + " dirt standing, bot at " + TestPositions.relative(helper, bot.blockPosition()).toShortString());
			}
		});

		helper.succeedWhen(() -> {
			helper.assertTrue(strayDirt(helper) == 0, strayDirt(helper) + " dirt left standing");
			helper.assertTrue(bot.getScaffold().isEmpty(), "scaffold still recorded: " + bot.getScaffold());
			helper.assertTrue(bot.onGround() && bot.getY() < helper.absolutePos(new BlockPos(0, 2, 0)).getY(), "not down on the floor");
			helper.assertTrue(bot.getHealth() == bot.getMaxHealth(), "fell and got hurt: " + bot.getHealth());
		});
	}

	/**
	 * A bot on a lone scaffold block five blocks up, carrying no blocks: rather than stay up there or fall, it digs
	 * down, taking the block it stands on and placing it again one lower, and leaves no scaffold standing.
	 */
	@GameTest(maxTicks = 140)
	public void digsDownFromAFloatingScaffoldBlockWithNoBlocks(GameTestHelper helper) {
		Colony colony = colony(helper);
		BlockPos floating = new BlockPos(4, 5, 4);
		helper.setBlock(floating, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 6, 4);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		bot.addScaffold(helper.absolutePos(floating));
		bot.setHealth(bot.getMaxHealth());
		helper.onEachTick(() -> {
			if (bot.getScaffold().isEmpty() && strayDirt(helper) > 0) {
				helper.fail("left " + strayDirt(helper) + " dirt standing, bot at " + TestPositions.relative(helper, bot.blockPosition()).toShortString());
			}
		});

		helper.succeedWhen(() -> {
			helper.assertTrue(strayDirt(helper) == 0, strayDirt(helper) + " dirt left standing");
			helper.assertTrue(bot.getScaffold().isEmpty(), "scaffold still recorded: " + bot.getScaffold());
			helper.assertTrue(bot.onGround() && bot.getY() < helper.absolutePos(new BlockPos(0, 2, 0)).getY(), "not down on the floor");
			helper.assertTrue(bot.getHealth() == bot.getMaxHealth(), "fell and got hurt: " + bot.getHealth());
		});
	}

	/**
	 * A bot on a lone scaffold block five blocks up, carrying scaffold blocks: rather than stay up there, it towers
	 * down, placing a block under the one it stands on and taking that one down, and leaves no scaffold standing.
	 */
	@GameTest(maxTicks = 140)
	public void towersDownFromAFloatingScaffoldBlock(GameTestHelper helper) {
		Colony colony = colony(helper);
		BlockPos floating = new BlockPos(4, 5, 4);
		helper.setBlock(floating, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 6, 4);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		bot.addScaffold(helper.absolutePos(floating));
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 4));
		bot.setHealth(bot.getMaxHealth());
		helper.onEachTick(() -> {
			if (bot.getScaffold().isEmpty() && strayDirt(helper) > 0) {
				helper.fail("left " + strayDirt(helper) + " dirt standing, bot at " + TestPositions.relative(helper, bot.blockPosition()).toShortString());
			}
		});

		helper.succeedWhen(() -> {
			helper.assertTrue(strayDirt(helper) == 0, strayDirt(helper) + " dirt left standing");
			helper.assertTrue(bot.getScaffold().isEmpty(), "scaffold still recorded: " + bot.getScaffold());
			helper.assertTrue(bot.onGround() && bot.getY() < helper.absolutePos(new BlockPos(0, 2, 0)).getY(), "not down on the floor");
			helper.assertTrue(bot.getHealth() == bot.getMaxHealth(), "fell and got hurt: " + bot.getHealth());
		});
	}

	/**
	 * A bot on a lone cobblestone scaffold block four up, with no pickaxe to dig down with and no blocks to tower down
	 * with, and dirt in the core on a stone stand within reach: it fetches dirt from the core and towers down, rather
	 * than stay up there, and gets down unhurt.
	 */
	@GameTest(maxTicks = 460)
	public void fetchesBlocksToGetDownFromACobblestoneBlockWithNoPickaxe(GameTestHelper helper) {
		getsDownFromACobblestoneBlockWithDirtInTheCore(helper, ItemStack.EMPTY);
	}

	/**
	 * As {@link #fetchesBlocksToGetDownFromACobblestoneBlockWithNoPickaxe}, with a pickaxe: the cobblestone it would dig
	 * down with is no block it pillars with, so it fetches dirt rather than break the block and fall.
	 */
	@GameTest(maxTicks = 100)
	public void fetchesBlocksToGetDownFromACobblestoneBlockItDoesNotPillarWith(GameTestHelper helper) {
		getsDownFromACobblestoneBlockWithDirtInTheCore(helper, new ItemStack(Items.STONE_PICKAXE));
	}

	private static void getsDownFromACobblestoneBlockWithDirtInTheCore(GameTestHelper helper, ItemStack tool) {
		BlockPos core = new BlockPos(1, 3, 1);
		helper.setBlock(1, 1, 1, Blocks.STONE);
		helper.setBlock(1, 2, 1, Blocks.STONE);
		helper.setBlock(core, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(core)).orElseThrow();
		colony.setRadius(6);
		colony.setStockTargets(List.of());
		((Container) helper.getBlockEntity(core, BlockEntity.class)).setItem(0, new ItemStack(Items.DIRT, 8));
		BlockPos floating = new BlockPos(3, 4, 1);
		helper.setBlock(floating, Blocks.COBBLESTONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 5, 1);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		bot.addScaffold(helper.absolutePos(floating));
		bot.setItemInHand(InteractionHand.MAIN_HAND, tool);
		bot.setHealth(bot.getMaxHealth());
		helper.onEachTick(() -> {
			if (bot.getHealth() < bot.getMaxHealth()) {
				helper.fail("fell and got hurt: " + bot.getHealth());
			}
		});

		helper.succeedWhen(() -> {
			helper.assertBlockNotPresent(Blocks.COBBLESTONE, floating);
			helper.assertTrue(strayDirt(helper) == 0, strayDirt(helper) + " dirt left standing");
			helper.assertTrue(bot.getScaffold().isEmpty(), "scaffold still recorded: " + bot.getScaffold());
			helper.assertTrue(bot.onGround() && bot.getY() < helper.absolutePos(new BlockPos(0, 2, 0)).getY(), "not down on the floor");
		});
	}

	/**
	 * A bot on top of its pillar with a build job on the floor across the area: that job is not on its ledge, so it
	 * takes its pillar down before it claims the job, rather than leave the pillar standing until the job ends.
	 */
	@GameTest(maxTicks = 220)
	public void takesItsPillarDownBeforeLeavingForABuildOffItsLedge(GameTestHelper helper) {
		Colony colony = colony(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 6, 5, 6);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		for (int y = 1; y <= 4; y++) {
			helper.setBlock(6, y, 6, Blocks.DIRT);
			bot.addScaffold(helper.absolutePos(new BlockPos(6, y, 6)));
		}
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 1));
		colony.addBuild(new BuildPlan(List.of(step(helper, 1, 1, 6, COBBLE))));
		boolean[] claimed = {false};
		helper.onEachTick(() -> {
			if (colony.board().jobOf(bot.getUUID()).isPresent() && !claimed[0]) {
				claimed[0] = true;
				helper.assertTrue(strayDirt(helper) == 0, "left for the build with " + strayDirt(helper) + " of its pillar standing");
			}
		});

		helper.succeedWhen(() -> {
			helper.assertTrue(claimed[0], "never went for the build");
			assertState(helper, 1, 1, 6, COBBLE);
			helper.assertTrue(strayDirt(helper) == 0, strayDirt(helper) + " dirt left standing");
			helper.assertTrue(bot.getScaffold().isEmpty(), "scaffold left: " + bot.getScaffold());
		});
	}
}
