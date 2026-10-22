package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyActions;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.ColonyStatus;
import io.github.barniebarnie.antcraft.colony.DefaultStock;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.colony.Project;
import io.github.barniebarnie.antcraft.colony.StockTarget;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.network.ColonyNetworking;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.registry.ModItems;
import io.github.barniebarnie.antcraft.work.Storage;
import io.github.barniebarnie.antcraft.work.ToolKind;
import java.lang.reflect.Method;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.nbt.CompoundTag;
import java.util.Set;
import java.util.EnumSet;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.ProblemReporter;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class ColonyGameTest implements CustomTestMethodInvoker {
	private static final BlockPos CORE = new BlockPos(1, 1, 1);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.DIRT);
			}
		}
		method.invoke(this, helper);
	}

	private static Colony placeCore(GameTestHelper helper) {
		helper.setBlock(CORE, ModBlocks.COLONY_CORE);
		Colony colony = colonyAt(helper).orElseThrow(() -> new AssertionError("no colony founded"));
		// Keep the colony to this test area so it does not post jobs for other tests' trees.
		colony.setRadius(6);
		// No default stock targets, so only the test's own orders give the colony work.
		colony.setStockTargets(List.of());
		return colony;
	}

	private static Optional<Colony> colonyAt(GameTestHelper helper) {
		return ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(CORE));
	}

	private static void tree(GameTestHelper helper, int x, int z) {
		BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1);
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

	private static AntBot member(GameTestHelper helper, Colony colony, int x, int z) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, x, 1, z);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		return bot;
	}

	/**
	 * A colony a player founds in a 16x16 area with three bots, an empty core and three trees starts its starter
	 * projects, tools first, and gets its starter tool kit crafted from wood it fells. Without the tools project it
	 * takes over 900 ticks, the default stock ordering each tool only once its wood is in.
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 800)
	public void aNewColonyCraftsItsStarterToolsFromWoodItFells(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 15, 0, 15)) {
			helper.setBlock(pos, Blocks.DIRT);
		}
		tree(helper, 2, 2);
		tree(helper, 13, 2);
		tree(helper, 2, 13);
		BlockPos core = new BlockPos(8, 1, 8);
		helper.setBlock(core, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(core)).orElseThrow();
		colony.setRadius(7);
		// As a player's placing does.
		ModBlocks.COLONY_CORE.setPlacedBy(helper.getLevel(), helper.absolutePos(core), helper.getBlockState(core), null, new ItemStack(ModItems.COLONY_CORE));
		helper.assertTrue(!colony.projects().isEmpty() && colony.projects().getFirst().name().equals("tools"),
			"tools not the first project: " + colony.projects().stream().map(Project::name).toList());
		member(helper, colony, 7, 6);
		member(helper, colony, 9, 6);
		member(helper, colony, 6, 9);

		helper.succeedWhen(() -> {
			Map<net.minecraft.world.item.Item, Integer> held = Storage.held(helper.getLevel(), colony);
			for (StockTarget tool : DefaultStock.TOOLS) {
				int have = held.entrySet().stream().filter(e -> tool.matches(e.getKey())).mapToInt(Map.Entry::getValue).sum();
				helper.assertTrue(have >= tool.count(), "short of " + tool.name() + "; holds " + held);
			}
		});
	}

	/** Bots saved while colonies still gave them roles load as ordinary workers: the role is ignored. */
	@GameTest(maxTicks = 550)
	public void aBotSavedWithARoleLoadsWithoutItAndTakesAnyJob(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		colony.setStockTargets(List.of(StockTarget.of(ItemTags.LOGS, 64)));
		tree(helper, 5, 5);
		AntBot bot = member(helper, colony, 2, 2);
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
		bot.saveWithoutId(output);
		CompoundTag saved = output.buildResult();
		// A guard, set by a player: such a bot used to patrol and never fell trees.
		saved.putString("Role", "guard");
		saved.putBoolean("RoleSetByPlayer", true);
		saved.putString("ScaffoldRole", "builder");
		bot.discard();
		AntBot copy = helper.spawn(ModEntities.ANT_BOT, 2, 1, 2);
		copy.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));

		helper.succeedWhen(() -> {
			helper.assertValueEqual(copy.getColonyId(), colony.id(), "colony");
			Container core = (Container) helper.getBlockEntity(CORE, net.minecraft.world.level.block.entity.BlockEntity.class);
			helper.assertTrue(core.countItem(Items.OAK_LOG) == 4, "core has " + core.countItem(Items.OAK_LOG) + " logs");
		});
	}

	/** Clients pick the bot's skin by the kind of job it took last, so that has to be in the synched data. */
	@GameTest(maxTicks = 30)
	public void theTypeOfTheJobTakenIsSynched(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		colony.setStockTargets(List.of(StockTarget.of(ItemTags.LOGS, 64)));
		tree(helper, 5, 5);
		AntBot bot = member(helper, colony, 2, 2);
		helper.assertValueEqual(bot.getEntityData().get(AntBot.DATA_JOB), "", "synched job before any");

		helper.succeedWhen(() -> helper.assertValueEqual(bot.getEntityData().get(AntBot.DATA_JOB), JobType.CHOP_LOG.getSerializedName(), "synched job"));
	}

	/** A colony is a set of identical workers: three bots take a tree, a crop and a build at once. */
	@GameTest(maxTicks = 150)
	public void threeBotsWorkATreeACropAndABuildAtOnce(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		colony.setStockTargets(List.of(StockTarget.of(ItemTags.LOGS, 64)));
		// Each a walk away from the bots, so no job is done the moment it is taken.
		tree(helper, 5, 2);
		helper.setBlock(6, 0, 6, Blocks.FARMLAND);
		helper.setBlock(6, 1, 6, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, CropBlock.MAX_AGE));
		Container core = (Container) helper.getBlockEntity(CORE, net.minecraft.world.level.block.entity.BlockEntity.class);
		core.setItem(0, new ItemStack(Items.COBBLESTONE, 3));
		colony.addBuild(new BuildPlan(List.of(
			new BuildPlan.Step(helper.absolutePos(new BlockPos(1, 1, 6)), Blocks.COBBLESTONE.defaultBlockState()),
			new BuildPlan.Step(helper.absolutePos(new BlockPos(1, 2, 6)), Blocks.COBBLESTONE.defaultBlockState()),
			new BuildPlan.Step(helper.absolutePos(new BlockPos(1, 3, 6)), Blocks.COBBLESTONE.defaultBlockState()))));
		List<AntBot> bots = List.of(member(helper, colony, 2, 2), member(helper, colony, 3, 2), member(helper, colony, 2, 3));

		Set<JobType> together = EnumSet.noneOf(JobType.class);
		helper.onEachTick(() -> {
			Set<JobType> now = EnumSet.noneOf(JobType.class);
			bots.forEach(bot -> colony.board().jobOf(bot.getUUID()).ifPresent(job -> now.add(job.type())));
			if (now.size() > together.size()) {
				together.clear();
				together.addAll(now);
			}
		});
		helper.succeedWhen(() -> helper.assertValueEqual(together, EnumSet.of(JobType.CHOP_LOG, JobType.HARVEST_CROP, JobType.BUILD),
			"jobs worked at once"));
	}

	/**
	 * A project with nothing for it in the core: the colony orders what it waits for, bots fell a tree for the logs,
	 * craft the planks and the crafting table at the core, and build it.
	 */
	@GameTest(maxTicks = 430)
	public void aProjectWithNoMaterialsInTheCoreIsFinishedByBotsWhoGatherAndCraftWhatItNeeds(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		tree(helper, 5, 5);
		BlockPos planks = new BlockPos(1, 1, 6);
		BlockPos table = new BlockPos(2, 1, 6);
		Project hut = colony.addBuild("hut", new BuildPlan(List.of(
			new BuildPlan.Step(helper.absolutePos(planks), Blocks.OAK_PLANKS.defaultBlockState()),
			new BuildPlan.Step(helper.absolutePos(table), Blocks.CRAFTING_TABLE.defaultBlockState()))));
		colony.setPriority(hut.id(), 1);
		member(helper, colony, 2, 2);
		member(helper, colony, 3, 2);

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.OAK_PLANKS, planks);
			helper.assertBlockPresent(Blocks.CRAFTING_TABLE, table);
			helper.assertTrue(colony.projects().isEmpty(), "finished project still listed: " + colony.projects());
		});
	}

	/** Bots without a colony only follow orders and fight: no tree felling around them. */
	@GameTest(maxTicks = 350)
	public void aBotWithoutAColonyDoesNoWorkButWalksToAGotoTarget(GameTestHelper helper) {
		tree(helper, 5, 5);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 2);
		BlockPos target = helper.absolutePos(new BlockPos(6, 1, 1));
		boolean[] sent = {false};
		helper.runAfterDelay(150, () -> {
			bot.setWalkTarget(target);
			sent[0] = true;
		});

		helper.onEachTick(() -> {
			for (int y = 1; y <= 4; y++) {
				helper.assertBlockPresent(Blocks.OAK_LOG, 5, y, 5);
			}
			helper.assertTrue(bot.getInventory().isEmpty(), "bot picked something up");
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(sent[0], "not sent anywhere yet");
			helper.assertTrue(bot.getWalkTarget() == null, "walk target not cleared yet");
			helper.assertTrue(bot.blockPosition().distManhattan(target) <= 2, "bot not at target");
		});
	}

	@GameTest(maxTicks = 200)
	public void aColonyBotFightsAZombieThatComesNearItInsideTheRadius(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		AntBot bot = member(helper, colony, 2, 2);
		// Armed, the outcome does not depend on luck; a husk does not burn in daylight, so only the bot can kill it.
		bot.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		Husk husk = helper.spawn(EntityTypes.HUSK, 6, 1, 6);

		helper.succeedWhen(() -> {
			helper.assertTrue(!husk.isAlive(), "husk still alive");
			helper.assertTrue(husk.getLastHurtByMob() == bot, "husk was not killed by the bot");
		});
	}

	@GameTest
	public void placingCoreFoundsColony(GameTestHelper helper) {
		placeCore(helper);
		helper.succeed();
	}

	@GameTest
	public void newColonyStartsWithDefaultStockTargets(GameTestHelper helper) {
		helper.setBlock(CORE, ModBlocks.COLONY_CORE);
		Colony colony = colonyAt(helper).orElseThrow(() -> new AssertionError("no colony founded"));
		helper.assertValueEqual(colony.stockTargets(), DefaultStock.TARGETS, "stock targets");
		helper.succeed();
	}

	@GameTest(maxTicks = 200)
	public void aStockTargetMetByAPickaxeABotCarriesOrdersNoOther(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		colony.setStockTargets(List.of(StockTarget.of(Items.STONE_PICKAXE, 1)));
		// Enough to make another, so the colony would order one if it were short.
		Container core = (Container) helper.getBlockEntity(CORE, net.minecraft.world.level.block.entity.BlockEntity.class);
		core.setItem(0, new ItemStack(Items.COBBLESTONE, 3));
		core.setItem(1, new ItemStack(Items.STICK, 2));
		AntBot bot = member(helper, colony, 4, 4);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_PICKAXE));
		// Past a survey of the colony's stock.
		helper.runAfterDelay(120, () -> {
			helper.assertFalse(colony.orders().containsKey(Items.STONE_PICKAXE), "ordered a pickaxe a bot carries: " + colony.orders());
			helper.succeed();
		});
	}

	@GameTest
	public void aSparePickaxeIsOneNoMemberWorksWith(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		AntBot iron = member(helper, colony, 4, 4);
		iron.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
		AntBot carrier = member(helper, colony, 5, 5);
		carrier.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_PICKAXE));
		carrier.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		// Each member works with its best pickaxe, so only the wooden one is spare.
		helper.assertValueEqual(Storage.spare(helper.getLevel(), colony, ToolKind.PICKAXE::bestCarried),
			Map.of(Items.WOODEN_PICKAXE, 1), "spare");
		helper.succeed();
	}

	@GameTest
	public void breakingCoreDissolvesColony(GameTestHelper helper) {
		placeCore(helper);
		helper.setBlock(CORE, Blocks.AIR);

		helper.assertTrue(colonyAt(helper).isEmpty(), "colony still exists");
		helper.succeed();
	}

	@GameTest
	public void botActivatedNearCoreJoinsColony(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.BOT_CORE));
		BlockPos floor = helper.absolutePos(new BlockPos(4, 0, 4));
		helper.useBlock(new BlockPos(4, 0, 4), player, new BlockHitResult(Vec3.atCenterOf(floor), Direction.UP, floor, false));

		List<AntBot> bots = helper.getLevel().getEntities(ModEntities.ANT_BOT, bot -> bot.blockPosition().closerThan(floor, 3)).stream().map(AntBot.class::cast).toList();
		helper.assertTrue(bots.size() == 1, "expected one bot, found " + bots.size());
		AntBot bot = bots.getFirst();
		helper.assertValueEqual(bot.getColonyId(), colony.id(), "colony id");
		helper.assertValueEqual(bot.getHome(), helper.absolutePos(CORE), "home");
		helper.assertTrue(colony.members().contains(bot.getUUID()), "bot not in member list");
		helper.succeed();
	}

	@GameTest(maxTicks = 530)
	public void lumberjacksSplitTreesAndStoreLogsInCore(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		colony.setStockTargets(List.of(StockTarget.of(ItemTags.LOGS, 64)));
		tree(helper, 2, 5);
		tree(helper, 5, 2);
		AntBot alice = member(helper, colony, 1, 3);
		AntBot bob = member(helper, colony, 3, 1);

		helper.runAfterDelay(40, () -> {
			Optional<UUID> aliceJob = colony.board().jobOf(alice.getUUID()).map(Job::id);
			Optional<UUID> bobJob = colony.board().jobOf(bob.getUUID()).map(Job::id);
			helper.assertTrue(aliceJob.isPresent() && bobJob.isPresent(), "both bots should have a job");
			helper.assertTrue(!aliceJob.equals(bobJob), "bots claimed the same tree");
		});
		helper.succeedWhen(() -> {
			Container core = (Container) helper.getBlockEntity(CORE, net.minecraft.world.level.block.entity.BlockEntity.class);
			helper.assertTrue(core.countItem(Items.OAK_LOG) == 8, "core has " + core.countItem(Items.OAK_LOG) + " logs");
		});
	}

	@GameTest(maxTicks = 50)
	public void jobIsFreedWhenItsBotDies(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		colony.setStockTargets(List.of(StockTarget.of(ItemTags.LOGS, 64)));
		tree(helper, 5, 5);
		AntBot bot = member(helper, colony, 2, 2);

		helper.runAfterDelay(20, () -> {
			helper.assertTrue(colony.board().jobOf(bot.getUUID()).isPresent(), "bot has no job");
			bot.kill(helper.getLevel());
		});
		helper.runAfterDelay(25, () -> {
			helper.assertTrue(colony.board().jobs().stream().allMatch(Job::isOpen), "job still claimed");
			helper.assertTrue(!colony.members().contains(bot.getUUID()), "dead bot still a member");
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 200)
	public void colonyWithItsWoodStockMetLeavesTreesStanding(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		colony.setStockTargets(List.of(StockTarget.of(ItemTags.LOGS, 4)));
		Container core = (Container) helper.getBlockEntity(CORE, net.minecraft.world.level.block.entity.BlockEntity.class);
		core.setItem(0, new ItemStack(Items.OAK_LOG, 4));
		tree(helper, 5, 5);
		member(helper, colony, 2, 2);

		helper.runAfterDelay(150, () -> {
			helper.assertTrue(colony.board().jobs().stream().noneMatch(job -> job.type() == JobType.CHOP_TREE), "tree job posted");
			for (int y = 1; y <= 4; y++) {
				helper.assertBlockPresent(Blocks.OAK_LOG, 5, y, 5);
			}
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 550)
	public void unassignedMemberTakesUpWorkTheColonyNeeds(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		colony.setStockTargets(List.of(StockTarget.of(ItemTags.LOGS, 64)));
		tree(helper, 5, 5);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 2);
		ColonyManager.get(helper.getLevel()).join(bot, colony);

		helper.succeedWhen(() -> {
			Container core = (Container) helper.getBlockEntity(CORE, net.minecraft.world.level.block.entity.BlockEntity.class);
			helper.assertTrue(core.countItem(Items.OAK_LOG) == 4, "core has " + core.countItem(Items.OAK_LOG) + " logs");
		});
	}

	@GameTest
	public void statusRequestAnswersForTheNearestColony(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		colony.addOrder(Items.OAK_PLANKS, 4);

		ColonyNetworking.StatusReply near = ColonyNetworking.reply(helper.getLevel(), new ColonyNetworking.Requester(helper.absolutePos(new BlockPos(4, 1, 4)), false, false, true));
		helper.assertValueEqual(near.status().map(ColonyStatus::core), Optional.of(colony.core()), "core of the colony answered for");
		helper.assertValueEqual(near.status().get().orders().getFirst().item(), Items.OAK_PLANKS, "ordered item");

		// Far above every test area, so out of range of all colonies.
		ColonyNetworking.StatusReply far = ColonyNetworking.reply(helper.getLevel(), new ColonyNetworking.Requester(helper.absolutePos(new BlockPos(4, 1000, 4)), true, false, true));
		helper.assertTrue(far.status().isEmpty(), "answered for a colony far away");
		helper.succeed();
	}

	@GameTest
	public void screenActionsChangeTheColony(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		BlockPos near = helper.absolutePos(new BlockPos(4, 1, 4));

		helper.assertTrue(edit(helper, near, false, colony, ColonyActions.Edit.ORDER, "minecraft:oak_planks", 4), "order refused");
		helper.assertValueEqual(colony.orders(), Map.of(Items.OAK_PLANKS, 4), "orders");
		helper.assertTrue(edit(helper, near, false, colony, ColonyActions.Edit.SET_STOCK, "minecraft:torch", 8), "stock target refused");
		helper.assertValueEqual(colony.stockTargets(), List.of(StockTarget.of(Items.TORCH, 8)), "stock targets");

		// Far above every test area, so out of range of the colony.
		BlockPos far = helper.absolutePos(new BlockPos(4, 1000, 4));
		helper.assertFalse(edit(helper, far, false, colony, ColonyActions.Edit.ORDER, "minecraft:stick", 4), "order from out of range");
		helper.assertValueEqual(colony.orders(), Map.of(Items.OAK_PLANKS, 4), "orders after refused order");
		helper.succeed();
	}

	@GameTest
	public void screenActionsOfNonOperatorsAreIgnoredWhenOnlyOperatorsMayAct(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		BlockPos near = helper.absolutePos(new BlockPos(4, 1, 4));
		ColonyNetworking.EditRequest order = new ColonyNetworking.EditRequest(colony.core(), ColonyActions.Edit.ORDER, "minecraft:oak_planks", 4);

		helper.assertFalse(ColonyNetworking.handle(helper.getLevel(), new ColonyNetworking.Requester(near, false, true, true), order), "non-operator's order accepted");
		helper.assertValueEqual(colony.orders(), Map.of(), "orders");
		helper.assertTrue(ColonyNetworking.handle(helper.getLevel(), new ColonyNetworking.Requester(near, true, true, true), order), "operator's order refused");
		helper.assertValueEqual(colony.orders(), Map.of(Items.OAK_PLANKS, 4), "orders");
		helper.succeed();
	}

	@GameTest
	public void statusRequestsOfNonOperatorsGetNoDataWhenOnlyOperatorsMayAct(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		colony.addOrder(Items.OAK_PLANKS, 4);
		BlockPos near = helper.absolutePos(new BlockPos(4, 1, 4));

		helper.assertTrue(ColonyNetworking.reply(helper.getLevel(), new ColonyNetworking.Requester(near, false, true, true)).status().isEmpty(), "non-operator got the colony's status");
		helper.assertValueEqual(ColonyNetworking.reply(helper.getLevel(), new ColonyNetworking.Requester(near, true, true, true)).status().map(ColonyStatus::core),
			Optional.of(colony.core()), "core of the colony answered for an operator");
		helper.succeed();
	}

	@GameTest
	public void colonyCommandsStayOperatorOnlyWhenTheScreenIsOpenToAll(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		CommandSourceStack player = helper.getLevel().getServer().createCommandSourceStack()
			.withLevel(helper.getLevel())
			.withPosition(Vec3.atCenterOf(helper.absolutePos(new BlockPos(4, 1, 4))))
			.withPermission(PermissionSet.NO_PERMISSIONS)
			.withSuppressedOutput();
		helper.assertFalse(helper.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild("antcraft").canUse(player),
			"non-operator may use /antcraft, including colony info");
		helper.getLevel().getServer().getCommands().performPrefixedCommand(player, "antcraft colony order minecraft:stick 4");
		helper.assertValueEqual(colony.orders(), Map.of(), "orders after a non-operator's command");
		helper.succeed();
	}

	@GameTest
	public void screenEditsApplyWhenBotsAreNotAllowed(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		BlockPos near = helper.absolutePos(new BlockPos(4, 1, 4));
		ColonyNetworking.EditRequest order = new ColonyNetworking.EditRequest(colony.core(), ColonyActions.Edit.ORDER, "minecraft:oak_planks", 4);
		ColonyNetworking.EditRequest radius = new ColonyNetworking.EditRequest(colony.core(), ColonyActions.Edit.RADIUS, "", 48);

		helper.assertTrue(ColonyNetworking.handle(helper.getLevel(), new ColonyNetworking.Requester(near, false, false, false), order), "order refused with bots not allowed");
		helper.assertValueEqual(colony.orders(), Map.of(Items.OAK_PLANKS, 4), "orders");
		helper.assertTrue(ColonyNetworking.handle(helper.getLevel(), new ColonyNetworking.Requester(near, false, false, false), radius), "radius refused with bots not allowed");
		helper.assertValueEqual(colony.radius(), 48, "radius");
		helper.succeed();
	}

	private static boolean edit(GameTestHelper helper, BlockPos from, boolean requireOp, Colony colony, ColonyActions.Edit kind, String target, int count) {
		return ColonyNetworking.handle(helper.getLevel(), new ColonyNetworking.Requester(from, false, requireOp, true), new ColonyNetworking.EditRequest(colony.core(), kind, target, count));
	}
}
