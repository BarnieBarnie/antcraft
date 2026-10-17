package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyActions;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.ColonyStatus;
import io.github.barniebarnie.antcraft.colony.DefaultStock;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.StockTarget;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.network.ColonyNetworking;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.registry.ModItems;
import java.lang.reflect.Method;
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
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
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
		bot.setRole(BotRole.LUMBERJACK);
		return bot;
	}

	@GameTest(maxTicks = 1000)
	public void idleColonyLumberjackBecomesFarmer(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		helper.setBlock(4, 0, 4, Blocks.FARMLAND);
		helper.setBlock(4, 1, 4, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, CropBlock.MAX_AGE));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 2);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		// No trees here, so the lumberjack the colony picked has nothing to do.
		bot.assignRole(BotRole.LUMBERJACK);

		helper.succeedWhen(() -> helper.assertValueEqual(bot.getRole(), BotRole.FARMER, "role"));
	}

	@GameTest
	public void whoSetTheRoleSurvivesSaveAndLoad(GameTestHelper helper) {
		AntBot assigned = helper.spawn(ModEntities.ANT_BOT, 2, 1, 2);
		assigned.assignRole(BotRole.MINER);
		AntBot chosen = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		chosen.setRole(BotRole.FARMER);

		AntBot assignedCopy = reload(helper, assigned);
		AntBot chosenCopy = reload(helper, chosen);
		helper.assertValueEqual(assignedCopy.getRole(), BotRole.MINER, "assigned role");
		helper.assertFalse(assignedCopy.isRoleSetByPlayer(), "colony-assigned role came back as set by a player");
		helper.assertValueEqual(chosenCopy.getRole(), BotRole.FARMER, "chosen role");
		helper.assertTrue(chosenCopy.isRoleSetByPlayer(), "player-set role came back as colony-assigned");
		helper.succeed();
	}

	/** Clients pick the bot's skin by role, so the role has to be in the synched data. */
	@GameTest
	public void roleIsSyncedAndSurvivesSaveAndLoad(GameTestHelper helper) {
		AntBot assigned = helper.spawn(ModEntities.ANT_BOT, 2, 1, 2);
		assigned.assignRole(BotRole.MINER);
		AntBot chosen = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		chosen.setRole(BotRole.GUARD);
		helper.assertValueEqual(assigned.getEntityData().get(AntBot.DATA_ROLE), BotRole.MINER.id(), "synched assigned role");
		helper.assertValueEqual(chosen.getEntityData().get(AntBot.DATA_ROLE), BotRole.GUARD.id(), "synched chosen role");

		AntBot copy = reload(helper, chosen);
		helper.assertValueEqual(copy.getEntityData().get(AntBot.DATA_ROLE), BotRole.GUARD.id(), "synched role after load");
		helper.succeed();
	}

	private static AntBot reload(GameTestHelper helper, AntBot bot) {
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
		bot.saveWithoutId(output);
		AntBot copy = helper.spawn(ModEntities.ANT_BOT, 6, 1, 6);
		copy.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), output.buildResult()));
		return copy;
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

	@GameTest(maxTicks = 1600)
	public void lumberjacksSplitTreesAndStoreLogsInCore(GameTestHelper helper) {
		Colony colony = placeCore(helper);
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

	@GameTest(maxTicks = 200)
	public void jobIsFreedWhenItsBotDies(GameTestHelper helper) {
		Colony colony = placeCore(helper);
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

	@GameTest(maxTicks = 1200)
	public void unassignedMemberTakesUpWorkTheColonyNeeds(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		tree(helper, 5, 5);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 2);
		ColonyManager.get(helper.getLevel()).join(bot, colony);

		helper.succeedWhen(() -> {
			helper.assertValueEqual(bot.getRole(), BotRole.LUMBERJACK, "role");
			Container core = (Container) helper.getBlockEntity(CORE, net.minecraft.world.level.block.entity.BlockEntity.class);
			helper.assertTrue(core.countItem(Items.OAK_LOG) == 4, "core has " + core.countItem(Items.OAK_LOG) + " logs");
		});
	}

	@GameTest
	public void statusRequestAnswersForTheNearestColony(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		colony.addOrder(Items.OAK_PLANKS, 4);

		ColonyNetworking.StatusReply near = ColonyNetworking.reply(helper.getLevel(), helper.absolutePos(new BlockPos(4, 1, 4)), false, false);
		helper.assertValueEqual(near.status().map(ColonyStatus::core), Optional.of(colony.core()), "core of the colony answered for");
		helper.assertValueEqual(near.status().get().orders().getFirst().item(), Items.OAK_PLANKS, "ordered item");

		// Far above every test area, so out of range of all colonies.
		ColonyNetworking.StatusReply far = ColonyNetworking.reply(helper.getLevel(), helper.absolutePos(new BlockPos(4, 1000, 4)), true, false);
		helper.assertTrue(far.status().isEmpty(), "answered for a colony far away");
		helper.succeed();
	}

	@GameTest
	public void screenActionsChangeTheColony(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		AntBot bot = member(helper, colony, 2, 2);
		AntBot stranger = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		BlockPos near = helper.absolutePos(new BlockPos(4, 1, 4));

		helper.assertTrue(edit(helper, near, false, colony, ColonyActions.Edit.ORDER, "minecraft:oak_planks", 4), "order refused");
		helper.assertValueEqual(colony.orders(), Map.of(Items.OAK_PLANKS, 4), "orders");
		helper.assertTrue(edit(helper, near, false, colony, ColonyActions.Edit.SET_STOCK, "minecraft:torch", 8), "stock target refused");
		helper.assertValueEqual(colony.stockTargets(), List.of(StockTarget.of(Items.TORCH, 8)), "stock targets");

		helper.assertTrue(role(helper, near, false, colony, bot, Optional.of(BotRole.MINER)), "role change refused");
		helper.assertValueEqual(bot.getRole(), BotRole.MINER, "role");
		helper.assertTrue(bot.isRoleSetByPlayer(), "role not set by player");
		helper.assertTrue(role(helper, near, false, colony, bot, Optional.empty()), "giving back refused");
		helper.assertFalse(bot.isRoleSetByPlayer(), "bot not given back to the colony");

		helper.assertFalse(role(helper, near, false, colony, stranger, Optional.of(BotRole.MINER)), "role change for a bot not in the colony");
		helper.assertValueEqual(stranger.getRole(), BotRole.NONE, "stranger's role");
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

		helper.assertFalse(ColonyNetworking.handle(helper.getLevel(), near, false, true, true, order), "non-operator's order accepted");
		helper.assertValueEqual(colony.orders(), Map.of(), "orders");
		helper.assertTrue(ColonyNetworking.handle(helper.getLevel(), near, true, true, true, order), "operator's order refused");
		helper.assertValueEqual(colony.orders(), Map.of(Items.OAK_PLANKS, 4), "orders");
		helper.succeed();
	}

	@GameTest
	public void statusRequestsOfNonOperatorsGetNoDataWhenOnlyOperatorsMayAct(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		colony.addOrder(Items.OAK_PLANKS, 4);
		BlockPos near = helper.absolutePos(new BlockPos(4, 1, 4));

		helper.assertTrue(ColonyNetworking.reply(helper.getLevel(), near, false, true).status().isEmpty(), "non-operator got the colony's status");
		helper.assertValueEqual(ColonyNetworking.reply(helper.getLevel(), near, true, true).status().map(ColonyStatus::core),
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
	public void screenRoleChangesAreIgnoredWhenBotsAreNotAllowed(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		AntBot bot = member(helper, colony, 2, 2);
		BlockPos near = helper.absolutePos(new BlockPos(4, 1, 4));
		ColonyNetworking.RoleRequest request = new ColonyNetworking.RoleRequest(colony.core(), bot.getUUID(), Optional.of(BotRole.MINER));

		helper.assertFalse(ColonyNetworking.handle(helper.getLevel(), near, true, false, false, request), "role change accepted with bots not allowed");
		helper.assertValueEqual(bot.getRole(), BotRole.LUMBERJACK, "role");
		helper.succeed();
	}

	@GameTest
	public void screenEditsApplyWhenBotsAreNotAllowed(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		BlockPos near = helper.absolutePos(new BlockPos(4, 1, 4));
		ColonyNetworking.EditRequest order = new ColonyNetworking.EditRequest(colony.core(), ColonyActions.Edit.ORDER, "minecraft:oak_planks", 4);
		ColonyNetworking.EditRequest radius = new ColonyNetworking.EditRequest(colony.core(), ColonyActions.Edit.RADIUS, "", 48);

		helper.assertTrue(ColonyNetworking.handle(helper.getLevel(), near, false, false, false, order), "order refused with bots not allowed");
		helper.assertValueEqual(colony.orders(), Map.of(Items.OAK_PLANKS, 4), "orders");
		helper.assertTrue(ColonyNetworking.handle(helper.getLevel(), near, false, false, false, radius), "radius refused with bots not allowed");
		helper.assertValueEqual(colony.radius(), 48, "radius");
		helper.succeed();
	}

	private static boolean edit(GameTestHelper helper, BlockPos from, boolean requireOp, Colony colony, ColonyActions.Edit kind, String target, int count) {
		return ColonyNetworking.handle(helper.getLevel(), from, false, requireOp, true, new ColonyNetworking.EditRequest(colony.core(), kind, target, count));
	}

	private static boolean role(GameTestHelper helper, BlockPos from, boolean requireOp, Colony colony, AntBot bot, Optional<BotRole> role) {
		return ColonyNetworking.handle(helper.getLevel(), from, false, requireOp, true, new ColonyNetworking.RoleRequest(colony.core(), bot.getUUID(), role));
	}
}
