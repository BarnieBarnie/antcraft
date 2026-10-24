package io.github.barniebarnie.antcraft.gametest;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.datafixers.util.Either;
import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.colony.BlueprintPlacement;
import io.github.barniebarnie.antcraft.colony.PlacedBlueprint;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import io.github.barniebarnie.antcraft.colony.BotActivation;
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
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.Quarry;
import io.github.barniebarnie.antcraft.work.Sites;
import io.github.barniebarnie.antcraft.work.Storage;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
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
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import java.util.ArrayList;
import java.util.function.Predicate;
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
	/** The player behind the screen requests of these tests. */
	private static final UUID PLAYER = UUID.randomUUID();

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

	/**
	 * A colony short of cobblestone whose mineshaft has no branch job yet opens a quarry a little away from the core: two
	 * bots dig it and bring the cobblestone home, and the quarry closes once the stock target is met, its pit not dug out.
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 620)
	public void botsDigTheQuarryForCobblestoneAndStopOnceTheColonyHasEnough(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 15, 4, 15)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		BlockPos core = new BlockPos(2, 5, 2);
		helper.setBlock(core, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(core)).orElseThrow();
		colony.setStockTargets(List.of(StockTarget.of(Items.COBBLESTONE, 20)));
		// A shaft not yet dug to its first level, so it has no branch job; its digging is left out, so all cobblestone is the quarry's.
		int level = helper.absolutePos(new BlockPos(0, 1, 0)).getY();
		colony.cancelProject(colony.setMineshaft(new Mineshaft(helper.absolutePos(new BlockPos(10, 5, 10)), List.of(level), 1, 1,
			Mineshaft.DEFAULT_TORCH_SPACING, 1)).id());
		for (BlockPos at : List.of(new BlockPos(2, 5, 6), new BlockPos(6, 5, 2))) {
			AntBot bot = helper.spawn(ModEntities.ANT_BOT, at.getX(), at.getY(), at.getZ());
			ColonyManager.get(helper.getLevel()).join(bot, colony);
			bot.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		}
		Container store = (Container) helper.getBlockEntity(core, net.minecraft.world.level.block.entity.BlockEntity.class);
		boolean[] opened = {false};
		helper.onEachTick(() -> opened[0] |= colony.projects().stream().anyMatch(project -> project.name().equals(Project.QUARRY)));

		helper.succeedWhen(() -> {
			helper.assertTrue(opened[0], "no quarry opened");
			Quarry quarry = colony.quarry().orElseThrow();
			helper.assertTrue(!Sites.sharesColumns(quarry.area(), BoundingBox.fromCorners(helper.absolutePos(core).offset(-1, 0, -1),
				helper.absolutePos(core).offset(1, 0, 1))), "quarry beside the core");
			helper.assertTrue(store.countItem(Items.COBBLESTONE) >= 20, "core holds " + store.countItem(Items.COBBLESTONE) + " cobblestone");
			helper.assertTrue(colony.projects().stream().noneMatch(project -> project.name().equals(Project.QUARRY)), "quarry still open");
			helper.assertTrue(quarry.plan(helper.getLevel()::getBlockState).isPresent(), "quarry dug out");
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
	public void placingACoreMakesThePlacerItsOwner(GameTestHelper helper) {
		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.COLONY_CORE));
		BlockPos floor = helper.absolutePos(CORE.below());
		helper.useBlock(CORE.below(), player, new BlockHitResult(Vec3.atCenterOf(floor), Direction.UP, floor, false));

		Colony colony = colonyAt(helper).orElseThrow(() -> new AssertionError("no colony founded"));
		helper.assertValueEqual(colony.owner(), Optional.of(player.getUUID()), "owner");
		helper.assertValueEqual(ColonyStatus.of(helper.getLevel(), colony).access().owner().isPresent(), true, "status names an owner");
		helper.succeed();
	}

	/**
	 * A new colony with no field and no food does not say its food target needs a farm: bread takes wheat, but the
	 * target takes any colony food, such as carrots bots gather. A target only bread meets does.
	 */
	@GameTest
	public void aNewColonysFoodTargetDoesNotNeedAFarm(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		StockTarget food = DefaultStock.TARGETS.stream().filter(target -> target.match().left().equals(Optional.of(DefaultStock.COLONY_FOOD)))
			.findFirst().orElseThrow();
		colony.setStockTargets(List.of(food, StockTarget.of(Items.BREAD, 16)));
		helper.assertValueEqual(ColonyStatus.of(helper.getLevel(), colony).stock(), List.of(
			new ColonyStatus.Stock("#antcraft:colony_food", 0, 16, false), new ColonyStatus.Stock("minecraft:bread", 0, 16, true)), "stock");
		helper.succeed();
	}

	/** Checks whom a Bot Core's bot may join rather than spawning one, which would join the ownerless colonies of tests nearby. */
	@GameTest
	public void botCoreBotsJoinOnlyColoniesTheirPlayerMayActOn(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		colony.setOwner(UUID.randomUUID());
		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		BlockPos at = helper.absolutePos(new BlockPos(4, 1, 4));
		helper.assertFalse(BotActivation.mayJoin(player, at).test(colony), "joins a colony its player may not act on");
		colony.trust(player.getUUID());
		helper.assertTrue(BotActivation.mayJoin(player, at).test(colony), "does not join a colony its player is trusted with");
		helper.assertFalse(BotActivation.mayJoin(null, at).test(colony), "a bot of no player joins a colony");
		helper.succeed();
	}

	@GameTest
	public void theFirstPlayerToUseTheScreenOnAnOwnerlessColonyClaimsIt(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		BlockPos near = helper.absolutePos(new BlockPos(4, 1, 4));
		UUID stranger = UUID.randomUUID();

		helper.assertTrue(ColonyNetworking.reply(helper.getLevel(), new ColonyNetworking.Requester(PLAYER, near, false, false, true)).status().isPresent(),
			"first player got no status");
		helper.assertValueEqual(colony.owner(), Optional.of(PLAYER), "owner after the first status request");
		// Not a status request: that would claim the ownerless colonies of tests nearby for the other player.
		helper.assertFalse(ColonyActions.mayView(colony, stranger, near, false, false), "another player may view the claimed colony");
		ColonyNetworking.EditRequest order = new ColonyNetworking.EditRequest(colony.core(), ColonyActions.Edit.ORDER, "minecraft:stick", 4);
		helper.assertFalse(ColonyNetworking.handle(helper.getLevel(), new ColonyNetworking.Requester(stranger, near, false, false, true), order),
			"another player's order accepted");
		helper.assertValueEqual(colony.orders(), Map.of(), "orders");
		helper.assertValueEqual(colony.owner(), Optional.of(PLAYER), "owner after another player's requests");
		helper.succeed();
	}

	@GameTest
	public void trustCommandsLetAPlayerChangeTheColonyAndStopIt(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		colony.setOwner(UUID.randomUUID());
		CommandSourceStack op = helper.getLevel().getServer().createCommandSourceStack()
			.withLevel(helper.getLevel())
			.withPosition(Vec3.atCenterOf(helper.absolutePos(new BlockPos(4, 1, 4))))
			.withSuppressedOutput();
		helper.getLevel().getServer().getCommands().performPrefixedCommand(op, "antcraft colony trust Alice");
		helper.assertValueEqual(colony.trusted().size(), 1, "trusted players after trust");
		UUID alice = colony.trusted().iterator().next();
		helper.assertTrue(ColonyActions.mayAct(colony, alice, helper.absolutePos(new BlockPos(4, 1, 4)), false, false), "trusted player may not act");
		helper.getLevel().getServer().getCommands().performPrefixedCommand(op, "antcraft colony untrust Alice");
		helper.assertValueEqual(colony.trusted(), Set.of(), "trusted players after untrust");
		helper.succeed();
	}

	/**
	 * An owner who is not an operator trusts a player with their colony and takes it back with the colony commands; the
	 * trusted player can place a blueprint in between, but may not change whom the colony trusts.
	 */
	@GameTest(maxTicks = 5)
	public void aNonOperatorOwnerTrustsAndUntrustsAPlayer(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		ServerPlayer owner = helper.makeMockServerPlayerInLevel();
		owner.setPos(Vec3.atCenterOf(helper.absolutePos(new BlockPos(4, 1, 4))));
		colony.setOwner(owner.getUUID());
		helper.assertFalse(ColonyActions.isOp(owner), "the owner is an operator");
		CommandSourceStack asOwner = owner.createCommandSourceStack().withSuppressedOutput();
		BlockPos near = helper.absolutePos(new BlockPos(4, 1, 4));
		ColonyNetworking.PlaceBlueprint place = new ColonyNetworking.PlaceBlueprint(colony.core(), AntCraft.id("cobble_step"), near, Rotation.NONE, Mirror.NONE);

		helper.getLevel().getServer().getCommands().performPrefixedCommand(asOwner, "antcraft colony trust Alice");
		helper.assertValueEqual(colony.trusted().size(), 1, "trusted players after the owner's trust");
		UUID alice = colony.trusted().iterator().next();
		Either<BlueprintPlacement.Refusal, PlacedBlueprint> placed =
			ColonyNetworking.handle(helper.getLevel(), new ColonyNetworking.Requester(alice, near, false, false, true), place);
		helper.assertTrue(placed.right().isPresent(), "the trusted player's placement refused: " + placed.left());

		ServerPlayer friend = helper.makeMockServerPlayerInLevel();
		friend.setPos(Vec3.atCenterOf(near));
		colony.trust(friend.getUUID());
		helper.getLevel().getServer().getCommands().performPrefixedCommand(friend.createCommandSourceStack().withSuppressedOutput(), "antcraft colony untrust Alice");
		helper.assertTrue(colony.trusted().contains(alice), "a trusted player untrusted another");
		colony.untrust(friend.getUUID());

		helper.getLevel().getServer().getCommands().performPrefixedCommand(asOwner, "antcraft colony untrust Alice");
		helper.assertValueEqual(colony.trusted(), Set.of(), "trusted players after the owner's untrust");
		Either<BlueprintPlacement.Refusal, PlacedBlueprint> refused =
			ColonyNetworking.handle(helper.getLevel(), new ColonyNetworking.Requester(alice, near, false, false, true), place);
		helper.assertValueEqual(refused.left(), Optional.of(BlueprintPlacement.Refusal.NOT_ALLOWED), "the untrusted player's placement");
		helper.succeed();
	}

	/**
	 * An owner standing by another player's colony trusts a player with their own colony, a little further away: the
	 * trust command acts on the nearest colony the owner may trust, not the nearest colony.
	 */
	@GameTest(maxTicks = 5)
	public void anOwnersTrustCommandActsOnTheirOwnColonyWithAnotherNearer(GameTestHelper helper) {
		Colony own = placeCore(helper);
		BlockPos otherCore = new BlockPos(6, 1, 6);
		helper.setBlock(otherCore, ModBlocks.COLONY_CORE);
		Colony other = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(otherCore))
			.orElseThrow(() -> new AssertionError("no other colony founded"));
		other.setRadius(6);
		other.setStockTargets(List.of());
		other.setOwner(UUID.randomUUID());
		ServerPlayer owner = helper.makeMockServerPlayerInLevel();
		owner.setPos(Vec3.atCenterOf(helper.absolutePos(new BlockPos(6, 1, 5))));
		own.setOwner(owner.getUUID());
		CommandSourceStack asOwner = owner.createCommandSourceStack().withSuppressedOutput();

		helper.getLevel().getServer().getCommands().performPrefixedCommand(asOwner, "antcraft colony trust Alice");
		helper.assertValueEqual(own.trusted().size(), 1, "trusted players of the owner's colony after trust");
		helper.assertValueEqual(other.trusted(), Set.of(), "trusted players of the other player's colony");
		helper.getLevel().getServer().getCommands().performPrefixedCommand(asOwner, "antcraft colony untrust Alice");
		helper.assertValueEqual(own.trusted(), Set.of(), "trusted players of the owner's colony after untrust");
		helper.succeed();
	}

	/**
	 * An owner with two colonies close together trusts a player with the one they name by its core, not the nearer one,
	 * and takes it back the same way; naming another player's colony is refused.
	 */
	@GameTest(maxTicks = 5)
	public void anOwnerWithTwoColoniesTrustsAPlayerWithTheOneTheyName(GameTestHelper helper) {
		Colony near = placeCore(helper);
		Colony named = otherColony(helper, new BlockPos(6, 1, 6));
		Colony foreign = otherColony(helper, new BlockPos(6, 1, 1));
		foreign.setOwner(UUID.randomUUID());
		ServerPlayer owner = helper.makeMockServerPlayerInLevel();
		owner.setPos(Vec3.atCenterOf(helper.absolutePos(new BlockPos(2, 1, 2))));
		near.setOwner(owner.getUUID());
		named.setOwner(owner.getUUID());
		CommandSourceStack asOwner = owner.createCommandSourceStack().withSuppressedOutput();
		String namedCore = coords(named.core());

		helper.getLevel().getServer().getCommands().performPrefixedCommand(asOwner, "antcraft colony trust Alice " + namedCore);
		helper.assertValueEqual(named.trusted().size(), 1, "trusted players of the named colony after trust");
		helper.assertValueEqual(near.trusted(), Set.of(), "trusted players of the nearer colony");
		helper.getLevel().getServer().getCommands().performPrefixedCommand(asOwner, "antcraft colony untrust Alice " + namedCore);
		helper.assertValueEqual(named.trusted(), Set.of(), "trusted players of the named colony after untrust");

		helper.getLevel().getServer().getCommands().performPrefixedCommand(asOwner, "antcraft colony trust Alice " + coords(foreign.core()));
		helper.assertValueEqual(foreign.trusted(), Set.of(), "trusted players of another player's colony named by the owner");
		helper.assertValueEqual(near.trusted(), Set.of(), "trusted players of the nearer colony after naming another player's");
		helper.succeed();
	}

	/**
	 * An owner who is not an operator lists the players their colony trusts, the nearest or the one they name; listing
	 * another player's colony, even one that trusts them, is refused and names nobody.
	 */
	@GameTest(maxTicks = 5)
	public void aNonOperatorOwnerListsTheirColonysTrustedPlayers(GameTestHelper helper) {
		Colony own = placeCore(helper);
		Colony foreign = otherColony(helper, new BlockPos(6, 1, 6));
		foreign.setOwner(UUID.randomUUID());
		ServerPlayer owner = helper.makeMockServerPlayerInLevel();
		owner.setPos(Vec3.atCenterOf(helper.absolutePos(new BlockPos(2, 1, 2))));
		own.setOwner(owner.getUUID());
		helper.assertFalse(ColonyActions.isOp(owner), "the owner is an operator");
		helper.getLevel().getServer().getCommands().performPrefixedCommand(owner.createCommandSourceStack().withSuppressedOutput(), "antcraft colony trust Alice");
		foreign.trust(owner.getUUID());
		helper.assertValueEqual(own.trusted().size(), 1, "trusted players after the owner's trust");
		// The test server has not seen Alice, so it may name her by her id.
		Predicate<List<String>> namesAlice = lines -> lines.contains("Alice") || lines.contains(own.trusted().iterator().next().toString());

		List<String> listed = new ArrayList<>();
		CommandSourceStack asOwner = owner.createCommandSourceStack().withSource(recorder(listed));
		helper.getLevel().getServer().getCommands().performPrefixedCommand(asOwner, "antcraft colony trust list");
		helper.assertTrue(listed.contains("commands.antcraft.colony.owner") && namesAlice.test(listed),
			"the owner's list does not name Alice: " + listed);

		listed.clear();
		helper.getLevel().getServer().getCommands().performPrefixedCommand(asOwner, "antcraft colony trust list " + coords(own.core()));
		helper.assertTrue(namesAlice.test(listed), "the named colony's list does not name Alice: " + listed);

		listed.clear();
		helper.getLevel().getServer().getCommands().performPrefixedCommand(asOwner, "antcraft colony trust list " + coords(foreign.core()));
		helper.assertTrue(listed.contains("commands.antcraft.colony.trust.not_allowed"), "another player's colony was not refused: " + listed);
		helper.assertFalse(listed.contains("commands.antcraft.colony.owner"), "another player's colony was listed: " + listed);
		helper.succeed();
	}

	private static Colony otherColony(GameTestHelper helper, BlockPos core) {
		helper.setBlock(core, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(core))
			.orElseThrow(() -> new AssertionError("no colony founded at " + core));
		colony.setRadius(6);
		colony.setStockTargets(List.of());
		return colony;
	}

	private static String coords(BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	/** A command source that adds the translation keys and plain text of each message, arguments included, to {@code into}. */
	private static CommandSource recorder(List<String> into) {
		return new CommandSource() {
			@Override
			public void sendSystemMessage(Component message) {
				collect(message, into);
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
	}

	private static void collect(Component message, List<String> into) {
		if (message.getContents() instanceof TranslatableContents translatable) {
			into.add(translatable.getKey());
			for (Object arg : translatable.getArgs()) {
				if (arg instanceof Component component) {
					collect(component, into);
				} else {
					into.add(String.valueOf(arg));
				}
			}
		} else if (message.getContents() instanceof PlainTextContents plain) {
			into.add(plain.text());
		}
		message.getSiblings().forEach(sibling -> collect(sibling, into));
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

		ColonyNetworking.StatusReply near = ColonyNetworking.reply(helper.getLevel(), new ColonyNetworking.Requester(PLAYER, helper.absolutePos(new BlockPos(4, 1, 4)), false, false, true));
		helper.assertValueEqual(near.status().map(ColonyStatus::core), Optional.of(colony.core()), "core of the colony answered for");
		helper.assertValueEqual(near.status().get().orders().getFirst().item(), Items.OAK_PLANKS, "ordered item");

		// Far above every test area, so out of range of all colonies.
		ColonyNetworking.StatusReply far = ColonyNetworking.reply(helper.getLevel(), new ColonyNetworking.Requester(PLAYER, helper.absolutePos(new BlockPos(4, 1000, 4)), true, false, true));
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

		// The first edit claimed the colony, so its owner may change it from far away; another player nearby may not.
		BlockPos far = helper.absolutePos(new BlockPos(4, 1000, 4));
		helper.assertValueEqual(colony.owner(), Optional.of(PLAYER), "owner");
		helper.assertTrue(edit(helper, far, false, colony, ColonyActions.Edit.ORDER, "minecraft:stick", 4), "owner's order from out of range");
		ColonyNetworking.EditRequest order = new ColonyNetworking.EditRequest(colony.core(), ColonyActions.Edit.ORDER, "minecraft:torch", 4);
		helper.assertFalse(ColonyNetworking.handle(helper.getLevel(), new ColonyNetworking.Requester(UUID.randomUUID(), near, false, false, true), order),
			"another player's order");
		helper.assertValueEqual(colony.orders(), Map.of(Items.OAK_PLANKS, 4, Items.STICK, 4), "orders after refused order");
		helper.succeed();
	}

	@GameTest
	public void screenActionsOfNonOperatorsAreIgnoredWhenOnlyOperatorsMayAct(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		BlockPos near = helper.absolutePos(new BlockPos(4, 1, 4));
		ColonyNetworking.EditRequest order = new ColonyNetworking.EditRequest(colony.core(), ColonyActions.Edit.ORDER, "minecraft:oak_planks", 4);

		helper.assertFalse(ColonyNetworking.handle(helper.getLevel(), new ColonyNetworking.Requester(PLAYER, near, false, true, true), order), "non-operator's order accepted");
		helper.assertValueEqual(colony.orders(), Map.of(), "orders");
		helper.assertTrue(ColonyNetworking.handle(helper.getLevel(), new ColonyNetworking.Requester(PLAYER, near, true, true, true), order), "operator's order refused");
		helper.assertValueEqual(colony.orders(), Map.of(Items.OAK_PLANKS, 4), "orders");
		helper.succeed();
	}

	@GameTest
	public void statusRequestsOfNonOperatorsGetNoDataWhenOnlyOperatorsMayAct(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		colony.addOrder(Items.OAK_PLANKS, 4);
		BlockPos near = helper.absolutePos(new BlockPos(4, 1, 4));

		helper.assertTrue(ColonyNetworking.reply(helper.getLevel(), new ColonyNetworking.Requester(PLAYER, near, false, true, true)).status().isEmpty(), "non-operator got the colony's status");
		helper.assertValueEqual(ColonyNetworking.reply(helper.getLevel(), new ColonyNetworking.Requester(PLAYER, near, true, true, true)).status().map(ColonyStatus::core),
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
		// A player may use /antcraft colony trust and untrust, to trust players with their own colony, but nothing else.
		ServerPlayer serverPlayer = helper.makeMockServerPlayerInLevel();
		colony.setOwner(serverPlayer.getUUID());
		CommandSourceStack asPlayer = serverPlayer.createCommandSourceStack().withSuppressedOutput();
		CommandNode<CommandSourceStack> antcraft = helper.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild("antcraft");
		helper.assertFalse(antcraft.getChild("status").canUse(asPlayer), "non-operator player may use /antcraft status");
		helper.assertFalse(antcraft.getChild("colony").getChild("info").canUse(asPlayer), "non-operator player may use colony info");
		helper.assertFalse(antcraft.getChild("colony").getChild("order").canUse(asPlayer), "non-operator player may use colony order");
		helper.assertFalse(antcraft.getChild("colony").getChild("build").getChild("restore").canUse(asPlayer), "non-operator player may use colony build restore");
		helper.assertTrue(antcraft.getChild("colony").getChild("trust").canUse(asPlayer), "owner may not use colony trust");
		helper.assertTrue(antcraft.getChild("colony").getChild("trust").getChild("list").canUse(asPlayer), "owner may not use colony trust list");
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

		helper.assertTrue(ColonyNetworking.handle(helper.getLevel(), new ColonyNetworking.Requester(PLAYER, near, false, false, false), order), "order refused with bots not allowed");
		helper.assertValueEqual(colony.orders(), Map.of(Items.OAK_PLANKS, 4), "orders");
		helper.assertTrue(ColonyNetworking.handle(helper.getLevel(), new ColonyNetworking.Requester(PLAYER, near, false, false, false), radius), "radius refused with bots not allowed");
		helper.assertValueEqual(colony.radius(), 48, "radius");
		helper.succeed();
	}

	private static boolean edit(GameTestHelper helper, BlockPos from, boolean requireOp, Colony colony, ColonyActions.Edit kind, String target, int count) {
		return ColonyNetworking.handle(helper.getLevel(), new ColonyNetworking.Requester(PLAYER, from, false, requireOp, true), new ColonyNetworking.EditRequest(colony.core(), kind, target, count));
	}
}
