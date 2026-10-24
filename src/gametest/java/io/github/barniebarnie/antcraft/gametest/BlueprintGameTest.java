package io.github.barniebarnie.antcraft.gametest;

import com.mojang.datafixers.util.Either;
import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.blueprint.Blueprint;
import io.github.barniebarnie.antcraft.blueprint.BlueprintBlocks;
import io.github.barniebarnie.antcraft.blueprint.BlueprintSummary;
import io.github.barniebarnie.antcraft.blueprint.Blueprints;
import io.github.barniebarnie.antcraft.blueprint.ChosenBlueprint;
import io.github.barniebarnie.antcraft.item.BlueprintItem;
import io.github.barniebarnie.antcraft.registry.ModItems;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import io.github.barniebarnie.antcraft.colony.BlueprintPlacement;
import io.github.barniebarnie.antcraft.colony.BuildGhost;
import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.colony.PlacedBlueprint;
import io.github.barniebarnie.antcraft.colony.SiteRules;
import io.github.barniebarnie.antcraft.colony.StockTarget;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.network.ColonyNetworking;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.RegionPlan;
import io.github.barniebarnie.antcraft.work.Storage;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class BlueprintGameTest implements CustomTestMethodInvoker {
	private static final BlockPos CORE = new BlockPos(1, 1, 1);
	private static final Identifier COBBLE_STEP = AntCraft.id("cobble_step");

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		method.invoke(this, helper);
	}

	/** The server's data load fills the blueprint library with the bundled blueprints, each pointing at a structure the server has. */
	@GameTest(maxTicks = 5)
	public void theServerListsTheBundledBlueprints(GameTestHelper helper) {
		for (String name : List.of("starter_house", "cobble_step", "warehouse", "farm", "cobble_generator")) {
			Identifier id = AntCraft.id(name);
			Optional<Blueprint> blueprint = Blueprints.get(id);
			helper.assertTrue(blueprint.isPresent(), "no blueprint " + id + " in " + Blueprints.ids());
			helper.assertTrue(Blueprints.ids().contains(id), id + " not listed in " + Blueprints.ids());
			helper.assertTrue(helper.getLevel().getServer().getStructureTemplateManager().get(blueprint.get().structure()).isPresent(),
				"no structure " + blueprint.get().structure());
		}
		helper.succeed();
	}

	/** The list the server sends clients holds every bundled blueprint, with its structure's size and materials for the picker. */
	@GameTest(maxTicks = 5)
	public void theBlueprintListForClientsHoldsEveryBundledBlueprint(GameTestHelper helper) {
		ColonyNetworking.BlueprintList list = ColonyNetworking.BlueprintList.of(helper.getLevel().getServer());
		List<Identifier> ids = list.blueprints().stream().map(BlueprintSummary::id).toList();
		helper.assertValueEqual(ids, Blueprints.ids(), "listed blueprints");
		for (String name : List.of("starter_house", "cobble_step")) {
			BlueprintSummary summary = list.blueprints().stream().filter(blueprint -> blueprint.id().equals(AntCraft.id(name))).findFirst().orElseThrow();
			helper.assertValueEqual(summary.name(), "blueprint.antcraft." + name, "name of " + name);
			helper.assertTrue(summary.size().getX() > 0 && !summary.materials().isEmpty(), name + " has no size or materials: " + summary);
		}
		helper.succeed();
	}

	/**
	 * A client previewing a blueprint asks for its blocks and gets the structure's, with its size; for an id the server
	 * does not know it gets none, so its ghost is refused as having no structure.
	 */
	@GameTest(maxTicks = 5)
	public void aClientAskingForABlueprintsBlocksGetsThem(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		BlueprintBlocks step = ColonyNetworking.blocks(server, new ColonyNetworking.BlueprintBlocksRequest(COBBLE_STEP)).getFirst().blocks();
		helper.assertValueEqual(step.id(), COBBLE_STEP, "id of the reply");
		helper.assertValueEqual(step.size(), new Vec3i(2, 2, 1), "size of the cobble step");
		helper.assertValueEqual(step.blocks().stream().filter(block -> block.state().is(Blocks.OAK_STAIRS)).map(BlueprintSummary.Block::pos).toList(),
			List.of(new BlockPos(0, 1, 0)), "stairs of the cobble step");
		helper.assertValueEqual(step.blocks().size(), 3, "blocks of the cobble step");
		BlueprintBlocks castle = ColonyNetworking.blocks(server, new ColonyNetworking.BlueprintBlocksRequest(AntCraft.id("castle"))).getFirst().blocks();
		helper.assertValueEqual(castle, new BlueprintBlocks(AntCraft.id("castle"), Vec3i.ZERO, List.of()), "blocks of an unknown blueprint");
		helper.succeed();
	}

	/** Choosing in the picker sets the Blueprint item in the player's hand, which then names the blueprint in its tooltip. */
	@GameTest(maxTicks = 5)
	public void choosingABlueprintSetsTheHeldItem(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		ItemStack stack = new ItemStack(ModItems.BLUEPRINT);
		player.setItemInHand(InteractionHand.MAIN_HAND, stack);
		player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.PAPER));
		Item.TooltipContext context = Item.TooltipContext.of(helper.getLevel());
		helper.assertTrue(says(stack.getTooltipLines(context, player, TooltipFlag.NORMAL), "item.antcraft.blueprint.none"), "no blueprint chosen yet");

		helper.assertFalse(ColonyNetworking.handle(player, new ColonyNetworking.ChooseBlueprint(InteractionHand.MAIN_HAND, AntCraft.id("castle"))),
			"an unknown blueprint was chosen");
		helper.assertFalse(ColonyNetworking.handle(player, new ColonyNetworking.ChooseBlueprint(InteractionHand.OFF_HAND, COBBLE_STEP)),
			"paper was set to a blueprint");
		helper.assertValueEqual(BlueprintItem.chosen(player.getItemInHand(InteractionHand.MAIN_HAND)), Optional.empty(), "chosen before");

		helper.assertTrue(ColonyNetworking.handle(player, new ColonyNetworking.ChooseBlueprint(InteractionHand.MAIN_HAND, COBBLE_STEP)), "not chosen");
		ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
		helper.assertValueEqual(BlueprintItem.chosen(held), Optional.of(new ChosenBlueprint(COBBLE_STEP, "blueprint.antcraft.cobble_step")), "chosen");
		List<Component> tooltip = held.getTooltipLines(context, player, TooltipFlag.NORMAL);
		helper.assertTrue(says(tooltip, "item.antcraft.blueprint.chosen") && says(tooltip, "blueprint.antcraft.cobble_step"),
			"tooltip does not name the blueprint: " + tooltip);
		helper.succeed();
	}

	private static Colony colony(GameTestHelper helper) {
		helper.setBlock(CORE, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(CORE)).orElseThrow();
		colony.setRadius(6);
		// No default stock targets, so the bot only builds.
		colony.setStockTargets(List.of());
		return colony;
	}

	/** Runs a command as an operator standing at the colony core, and returns the messages it sent back. */
	private static List<Component> command(GameTestHelper helper, String command) {
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
			.withPermission(PermissionSet.ALL_PERMISSIONS);
		helper.getLevel().getServer().getCommands().performPrefixedCommand(source, command);
		return messages;
	}

	/** Whether one of {@code messages}, or a component among its arguments, is the translation {@code key}. */
	private static boolean says(List<Component> messages, String key) {
		return messages.stream().anyMatch(message -> holds(message, key));
	}

	private static boolean holds(Component component, String key) {
		if (component.getContents() instanceof TranslatableContents translatable
				&& (translatable.getKey().equals(key) || Stream.of(translatable.getArgs()).anyMatch(arg -> arg instanceof Component inner && holds(inner, key)))) {
			return true;
		}
		return component.getSiblings().stream().anyMatch(sibling -> holds(sibling, key));
	}

	private static String coords(GameTestHelper helper, int x, int y, int z) {
		BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	private static void assertState(GameTestHelper helper, int x, int y, int z, BlockState state) {
		BlockState actual = helper.getBlockState(new BlockPos(x, y, z));
		helper.assertTrue(actual.equals(state), "expected " + state + " at " + x + "," + y + "," + z + " but found " + actual);
	}

	/**
	 * The cobble_step blueprint placed on a stone platform with a hole under it and a dirt block in its way: the colony
	 * levels the site first, filling the hole and clearing the dirt, then builds the step. The build list names it.
	 */
	@GameTest(maxTicks = 200)
	public void placingABlueprintOnUnevenGroundLevelsTheSiteThenBuildsIt(GameTestHelper helper) {
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		Colony colony = colony(helper);
		for (int x = 3; x <= 6; x++) {
			for (int z = 3; z <= 5; z++) {
				helper.setBlock(x, 1, z, Blocks.STONE);
			}
		}
		helper.setBlock(5, 1, 4, Blocks.AIR);
		helper.setBlock(5, 3, 4, Blocks.DIRT);
		Container core = (Container) helper.getBlockEntity(CORE, BlockEntity.class);
		core.setItem(0, new ItemStack(Items.COBBLESTONE, 2));
		core.setItem(1, new ItemStack(Items.OAK_STAIRS, 1));
		core.setItem(2, new ItemStack(Items.DIRT, 2));

		List<Component> placed = command(helper, "antcraft colony build place antcraft:cobble_step " + coords(helper, 4, 2, 4));
		helper.assertTrue(says(placed, "commands.antcraft.colony.build.placed"), "not placed: " + placed);
		helper.assertValueEqual(colony.builds().stream().map(BuildPlan::kind).toList(), List.of(BuildPlan.Kind.TERRAFORM), "builds at first");
		List<Component> listed = command(helper, "antcraft colony build list");
		helper.assertTrue(says(listed, "blueprint.antcraft.cobble_step"), "build list does not name the blueprint: " + listed);
		ColonyManager.get(helper.getLevel()).join(helper.spawn(ModEntities.ANT_BOT, 2, 1, 2), colony);
		GameTests.failOnGiveUp(helper, colony);
		helper.onEachTick(() -> {
			if (!helper.getBlockState(new BlockPos(4, 2, 4)).isAir() && helper.getBlockState(new BlockPos(5, 1, 4)).isAir()) {
				helper.fail("built before the site was levelled");
			}
		});

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.DIRT, new BlockPos(5, 1, 4));
			assertState(helper, 4, 2, 4, Blocks.COBBLESTONE.defaultBlockState());
			assertState(helper, 5, 2, 4, Blocks.COBBLESTONE.defaultBlockState());
			assertState(helper, 4, 3, 4, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
			helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(5, 3, 4));
			helper.assertTrue(colony.builds().isEmpty(), "builds left: " + colony.builds());
			helper.assertValueEqual(colony.blueprints().stream().map(PlacedBlueprint::finished).toList(), List.of(true), "placed blueprints finished");
		});
	}

	/**
	 * The cobble_step blueprint placed on a stone floor one block thick with air under it: the site is flat, so the
	 * colony builds the structure at once, without levelling. With a hole in the floor, only that hole is filled, not the
	 * air below it.
	 */
	@GameTest(maxTicks = 5)
	public void aBlueprintOnAFlatFloorWithAirBelowIsNotLevelled(GameTestHelper helper) {
		Colony colony = colony(helper);
		// A stone floor one block thick, with air under it in place of the test platform's barrier.
		for (int x = 3; x <= 6; x++) {
			for (int z = 3; z <= 7; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
				for (int y = -3; y <= -1; y++) {
					helper.setBlock(x, y, z, Blocks.AIR);
				}
			}
		}
		Either<BlueprintPlacement.Refusal, PlacedBlueprint> flat = BlueprintPlacement.place(helper.getLevel(), colony, true, COBBLE_STEP,
			helper.absolutePos(new BlockPos(4, 1, 4)), Rotation.NONE, Mirror.NONE);
		helper.assertTrue(flat.right().isPresent(), "not placed: " + flat.left());
		helper.assertValueEqual(colony.builds().stream().map(BuildPlan::kind).toList(), List.of(BuildPlan.Kind.BUILD), "builds on flat ground");

		helper.setBlock(5, 0, 6, Blocks.AIR);
		Either<BlueprintPlacement.Refusal, PlacedBlueprint> holed = BlueprintPlacement.place(helper.getLevel(), colony, true, COBBLE_STEP,
			helper.absolutePos(new BlockPos(4, 1, 6)), Rotation.NONE, Mirror.NONE);
		helper.assertTrue(holed.right().isPresent(), "not placed: " + holed.left());
		BuildPlan levelling = colony.builds().stream().filter(plan -> plan.kind() == BuildPlan.Kind.TERRAFORM).findFirst().orElseThrow();
		helper.assertValueEqual(levelling.steps().stream().map(BuildPlan.Step::pos).toList(), List.of(helper.absolutePos(new BlockPos(5, 0, 6))),
			"levelling steps");
		helper.succeed();
	}

	/** Only the colony's owner and the players it trusts may place blueprints for it; the client's request is checked like the command. */
	@GameTest(maxTicks = 5)
	public void aPlayerWhoIsNotOwnerOrTrustedCannotPlaceABlueprint(GameTestHelper helper) {
		Colony colony = colony(helper);
		colony.setOwner(UUID.randomUUID());
		BlockPos near = helper.absolutePos(new BlockPos(4, 1, 4));
		ColonyNetworking.PlaceBlueprint request = new ColonyNetworking.PlaceBlueprint(colony.core(), COBBLE_STEP, helper.absolutePos(new BlockPos(4, 1, 4)),
			Rotation.NONE, Mirror.NONE);

		UUID stranger = UUID.randomUUID();
		Either<BlueprintPlacement.Refusal, PlacedBlueprint> refused =
			ColonyNetworking.handle(helper.getLevel(), new ColonyNetworking.Requester(stranger, near, false, false, true), request);
		helper.assertValueEqual(refused.left(), Optional.of(BlueprintPlacement.Refusal.NOT_ALLOWED), "a stranger's placement");
		helper.assertValueEqual(colony.blueprints(), List.of(), "placed blueprints");
		helper.assertValueEqual(colony.builds(), List.of(), "builds");

		colony.trust(stranger);
		Either<BlueprintPlacement.Refusal, PlacedBlueprint> trusted =
			ColonyNetworking.handle(helper.getLevel(), new ColonyNetworking.Requester(stranger, near, false, false, true), request);
		helper.assertTrue(trusted.right().isPresent(), "a trusted player's placement refused: " + trusted.left());
		helper.assertValueEqual(colony.blueprints().size(), 1, "placed blueprints");
		helper.succeed();
	}

	/**
	 * A client holding a Blueprint learns the placement rules of the nearest colony it may place for, to tint the ghost;
	 * a player who may not act on the colony learns of none.
	 */
	@GameTest(maxTicks = 5)
	public void theClientLearnsTheRulesOfTheColonyItMayPlaceFor(GameTestHelper helper) {
		Colony colony = colony(helper);
		colony.setOwner(UUID.randomUUID());
		colony.addBuild(new BuildPlan(List.of(new BuildPlan.Step(helper.absolutePos(new BlockPos(5, 1, 5)), Blocks.STONE.defaultBlockState()))));
		BlockPos near = helper.absolutePos(new BlockPos(4, 1, 4));
		UUID stranger = UUID.randomUUID();

		ColonyNetworking.SiteRulesReply refused = ColonyNetworking.siteRules(helper.getLevel(), new ColonyNetworking.Requester(stranger, near, false, false, true));
		// Colonies of tests running alongside, with no owner, may be in range; this one is not offered.
		helper.assertFalse(refused.rules().map(SiteRules::core).equals(Optional.of(colony.core())), "a stranger got the rules: " + refused.rules());

		colony.trust(stranger);
		ColonyNetworking.SiteRulesReply trusted = ColonyNetworking.siteRules(helper.getLevel(), new ColonyNetworking.Requester(stranger, near, false, false, true));
		helper.assertValueEqual(trusted.rules(), Optional.of(SiteRules.of(colony)), "a trusted player's rules");
		helper.assertValueEqual(trusted.rules().orElseThrow().check(new BoundingBox(helper.absolutePos(new BlockPos(5, 1, 5)))),
			Optional.of(BlueprintPlacement.Refusal.OVERLAPS_BUILD), "a site over the build");
		helper.succeed();
	}

	/**
	 * The colony's players see what is left to build of a placed blueprint as ghosts: each block it still has to place,
	 * gone once it stands. A stranger sees none of it and may not cancel it; a trusted player cancels it by its ghost.
	 */
	@GameTest(maxTicks = 5)
	public void ghostsShowWhatIsLeftToBuildAndCancelThePlacedBuild(GameTestHelper helper) {
		Colony colony = colony(helper);
		colony.setOwner(UUID.randomUUID());
		for (int x = 3; x <= 6; x++) {
			for (int z = 3; z <= 5; z++) {
				helper.setBlock(x, 1, z, Blocks.STONE);
			}
		}
		Either<BlueprintPlacement.Refusal, PlacedBlueprint> placed = BlueprintPlacement.place(helper.getLevel(), colony, true, COBBLE_STEP,
			helper.absolutePos(new BlockPos(4, 2, 4)), Rotation.NONE, Mirror.NONE);
		helper.assertTrue(placed.right().isPresent(), "not placed: " + placed.left());
		UUID project = placed.right().orElseThrow().project();
		BlockPos near = helper.absolutePos(new BlockPos(4, 1, 4));
		UUID stranger = UUID.randomUUID();
		ColonyNetworking.Requester requester = new ColonyNetworking.Requester(stranger, near, false, false, true);

		// Colonies of tests running alongside, with no owner, may be in range; this one's builds are not sent.
		helper.assertFalse(ColonyNetworking.ghosts(helper.getLevel(), requester).builds().stream().anyMatch(ghost -> ghost.project().equals(project)),
			"a stranger got the ghosts");
		Either<Component, PlacedBlueprint> refused = ColonyNetworking.handle(helper.getLevel(), requester,
			new ColonyNetworking.CancelBuild(colony.core(), project));
		helper.assertTrue(refused.left().isPresent(), "a stranger cancelled the build");
		helper.assertValueEqual(colony.blueprints().size(), 1, "placed blueprints");

		colony.trust(stranger);
		List<BuildGhost> ghosts = ColonyNetworking.ghosts(helper.getLevel(), requester).builds();
		helper.assertValueEqual(ghosts.stream().map(BuildGhost::project).toList(), List.of(project), "ghosts");
		List<BlockPos> left = ghosts.getFirst().blocks().stream().map(BlueprintSummary.Block::pos).toList();
		helper.assertValueEqual(left, List.of(helper.absolutePos(new BlockPos(4, 2, 4)), helper.absolutePos(new BlockPos(5, 2, 4)),
			helper.absolutePos(new BlockPos(4, 3, 4))), "blocks left to place");

		helper.setBlock(4, 2, 4, Blocks.COBBLESTONE);
		List<BlockPos> after = ColonyNetworking.ghosts(helper.getLevel(), requester).builds().getFirst().blocks().stream()
			.map(BlueprintSummary.Block::pos).toList();
		helper.assertValueEqual(after, left.subList(1, 3), "blocks left once one stands");

		Either<Component, PlacedBlueprint> cancelled = ColonyNetworking.handle(helper.getLevel(), requester,
			new ColonyNetworking.CancelBuild(colony.core(), project));
		helper.assertTrue(cancelled.right().isPresent(), "a trusted player's cancel refused: " + cancelled.left());
		helper.assertValueEqual(colony.blueprints(), List.of(), "placed blueprints");
		helper.assertValueEqual(colony.builds(), List.of(), "builds");
		helper.assertValueEqual(ColonyNetworking.ghosts(helper.getLevel(), requester).builds(), List.of(), "ghosts once cancelled");
		helper.succeed();
	}

	/**
	 * A warehouse placed for the colony, turned a quarter: the bot builds its chests from the core, and once it stands
	 * the colony stores there, so the bot moves the logs the core does not need into its chests.
	 */
	@GameTest(maxTicks = 480)
	public void aPlacedWarehouseIsBuiltAndBotsStoreSurplusInIt(GameTestHelper helper) {
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		Colony colony = colony(helper);
		Container core = (Container) helper.getBlockEntity(CORE, BlockEntity.class);
		core.setItem(0, new ItemStack(Items.CHEST, 10));
		for (int i = 1; i <= 4; i++) {
			core.setItem(i, new ItemStack(Items.OAK_LOG, 64));
		}
		// A stone platform, so the site needs no levelling: turned a quarter, the warehouse is 3 wide and 5 deep.
		for (int x = 3; x <= 5; x++) {
			for (int z = 2; z <= 6; z++) {
				helper.setBlock(x, 1, z, Blocks.STONE);
			}
		}
		Either<BlueprintPlacement.Refusal, PlacedBlueprint> placed = BlueprintPlacement.place(helper.getLevel(), colony, true, AntCraft.id("warehouse"),
			helper.absolutePos(new BlockPos(3, 2, 2)), Rotation.CLOCKWISE_90, Mirror.NONE);
		helper.assertTrue(placed.right().isPresent(), "not placed: " + placed.left());
		List<BlockPos> chests = placed.right().orElseThrow().chests();
		helper.assertValueEqual(chests.size(), 10, "chest spots");
		ColonyManager.get(helper.getLevel()).join(helper.spawn(ModEntities.ANT_BOT, 6, 1, 6), colony);
		GameTests.failOnGiveUp(helper, colony);

		helper.succeedWhen(() -> {
			helper.assertValueEqual(colony.blueprints().stream().map(PlacedBlueprint::finished).toList(), List.of(true), "placed blueprints finished");
			int stored = 0;
			for (BlockPos spot : chests) {
				Container chest = Storage.containerAt(helper.getLevel(), spot);
				helper.assertTrue(chest != null, "no chest at " + spot);
				stored += chest.countItem(Items.OAK_LOG);
			}
			// Without stock targets, the core keeps one stack.
			helper.assertValueEqual(core.countItem(Items.OAK_LOG), 64, "logs in the core");
			helper.assertValueEqual(stored, 3 * 64, "logs in the warehouse");
		});
	}

	/** Where the generator tests place the cobblestone generator: its lowest corner, on the floor, leaving room for it turned either way. */
	private static final BlockPos GENERATOR = new BlockPos(1, 1, 4);

	/**
	 * Places the cobblestone generator, mirrored then turned, on a stone floor laid over the test area, has a bot join the
	 * colony, and fails the test once lava or water leaks out of it, obsidian or stone forms in it, or the bot is hurt.
	 * Returns the placed generator.
	 */
	private static PlacedBlueprint placeGenerator(GameTestHelper helper, Colony colony, Rotation rotation, Mirror mirror) {
		// A stone floor, so the site needs no levelling and the bot walks one step below its floor, as on level ground.
		for (int x = 0; x <= 7; x++) {
			for (int z = 0; z <= 7; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
			}
		}
		Either<BlueprintPlacement.Refusal, PlacedBlueprint> placed = BlueprintPlacement.place(helper.getLevel(), colony, true,
			AntCraft.id("cobble_generator"), helper.absolutePos(GENERATOR), rotation, mirror);
		helper.assertTrue(placed.right().isPresent(), "not placed: " + placed.left());
		PlacedBlueprint generator = placed.right().orElseThrow();
		helper.assertValueEqual(generator.role(), Optional.of(Blueprint.Role.GENERATOR), "role");
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 2);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		GameTests.failOnGiveUp(helper, colony);
		Vec3i size = helper.getLevel().getServer().getStructureTemplateManager().get(AntCraft.id("cobble_generator")).orElseThrow().getSize();
		BlockPos lava = RegionPlan.placed(new BlockPos(4, 1, 1), size, helper.absolutePos(GENERATOR), mirror, rotation);
		helper.onEachTick(() -> {
			helper.assertFalse(bot.isInLava() || bot.isOnFire() || bot.getHealth() < bot.getMaxHealth(), "bot hurt at " + bot.blockPosition().toShortString());
			for (BlockPos pos : BlockPos.betweenClosed(helper.absolutePos(new BlockPos(0, 1, 3)), helper.absolutePos(new BlockPos(7, 4, 7)))) {
				BlockState state = helper.getLevel().getBlockState(pos);
				if (state.is(Blocks.OBSIDIAN) || state.is(Blocks.STONE) || state.is(Blocks.LAVA) && !pos.equals(lava)) {
					helper.fail(state + " at " + TestPositions.relative(helper, pos).toShortString());
				}
				if (state.is(Blocks.WATER) && !generator.area().isInside(pos)) {
					helper.fail("water leaked to " + TestPositions.relative(helper, pos).toShortString());
				}
			}
		});
		return generator;
	}

	/**
	 * Once the generator stands, its mining block holds cobblestone; mined, cobblestone forms there again. Succeeds once
	 * it has formed twice.
	 */
	private static void succeedWhenItGeneratesCobblestone(GameTestHelper helper, Colony colony, PlacedBlueprint generator, Runnable more) {
		BlockPos mine = generator.workSpots().get(0);
		boolean[] mined = {false};
		helper.onEachTick(() -> {
			if (!mined[0] && generator(colony).finished() && helper.getLevel().getBlockState(mine).is(Blocks.COBBLESTONE)) {
				helper.getLevel().destroyBlock(mine, false);
				mined[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(mined[0], "no cobblestone formed in the finished generator yet");
			helper.assertTrue(helper.getLevel().getBlockState(mine).is(Blocks.COBBLESTONE), "no cobblestone formed again once mined");
			more.run();
		});
	}

	private static PlacedBlueprint generator(Colony colony) {
		return colony.blueprints().stream().filter(placed -> placed.blueprint().equals(AntCraft.id("cobble_generator"))).findFirst().orElseThrow();
	}

	/**
	 * Builders build the cobblestone generator from the core, pouring its water and lava from buckets, and it makes
	 * cobblestone. Turned half round, its lava comes before its water and walls in the build order.
	 */
	@GameTest(maxTicks = 420)
	public void aBuiltCobblestoneGeneratorFormsCobblestoneAtItsMiningBlock(GameTestHelper helper) {
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		Colony colony = colony(helper);
		Container core = (Container) helper.getBlockEntity(CORE, BlockEntity.class);
		core.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
		core.setItem(1, new ItemStack(Items.WATER_BUCKET));
		core.setItem(2, new ItemStack(Items.LAVA_BUCKET));
		PlacedBlueprint generator = placeGenerator(helper, colony, Rotation.CLOCKWISE_180, Mirror.NONE);
		succeedWhenItGeneratesCobblestone(helper, colony, generator, () -> { });
	}

	/**
	 * Once the generator stands and the colony wants cobblestone, a bot with a pickaxe stands in front of the mining block
	 * and mines the cobblestone as it forms, into the core, until the stock target is met; then it stops, leaving the
	 * cobblestone that formed last in place. The generator's other blocks and fluids stay as built (see {@link #placeGenerator}).
	 */
	@GameTest(maxTicks = 1000)
	public void aBotMinesCobblestoneAtTheGeneratorUntilTheStockTargetIsMet(GameTestHelper helper) {
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		Colony colony = colony(helper);
		Container core = (Container) helper.getBlockEntity(CORE, BlockEntity.class);
		// Just what the generator takes, so the core holds no cobblestone once it stands.
		core.setItem(0, new ItemStack(Items.COBBLESTONE, 32));
		core.setItem(1, new ItemStack(Items.WATER_BUCKET));
		core.setItem(2, new ItemStack(Items.LAVA_BUCKET));
		PlacedBlueprint placed = placeGenerator(helper, colony, Rotation.NONE, Mirror.LEFT_RIGHT);
		AntBot bot = helper.getLevel().getEntitiesOfClass(AntBot.class, new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(16)).stream()
			.filter(member -> colony.members().contains(member.getUUID())).findFirst().orElseThrow();
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		BlockPos mine = placed.workSpots().get(0);
		int target = 5;
		boolean[] wanted = {false};
		long[] metAt = {-1};
		helper.onEachTick(() -> {
			if (!wanted[0] && generator(colony).finished()) {
				helper.assertValueEqual(core.countItem(Items.COBBLESTONE), 0, "cobblestone left in the core once built");
				colony.setStockTarget(StockTarget.of(Items.COBBLESTONE, target));
				wanted[0] = true;
			}
			if (!wanted[0]) {
				return;
			}
			if (core.countItem(Items.COBBLESTONE) > target) {
				helper.fail("mined on past the target: " + core.countItem(Items.COBBLESTONE) + " in the core");
			}
			if (metAt[0] < 0 && core.countItem(Items.COBBLESTONE) == target) {
				metAt[0] = helper.getTick();
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(metAt[0] >= 0, "the core holds " + core.countItem(Items.COBBLESTONE) + " of " + target + " cobblestone");
			helper.assertValueEqual(bot.getInventory().countItem(Items.COBBLESTONE), 0, "cobblestone the bot carries");
			helper.assertTrue(colony.board().jobs().stream().noneMatch(job -> job.type() == JobType.MINE_GENERATOR), "generator job left up");
			// Long enough for cobblestone to form again and the bot to leave it.
			helper.assertTrue(helper.getTick() - metAt[0] >= 60, "not long since the target was met");
			helper.assertTrue(helper.getLevel().getBlockState(mine).is(Blocks.COBBLESTONE), "the last cobblestone formed was mined");
		});
	}

	/**
	 * With a warehouse built and the core filled up with dirt once the bot takes the generator job, the bot mines the
	 * cobblestone the stock target wants, stores it in the warehouse, as the core has no room, and completes the job
	 * with its pack empty of cobblestone, rather than leaving the job holding it.
	 */
	@GameTest(maxTicks = 800)
	public void theGeneratorMinerStoresItsCobblestoneInTheWarehouseWhenTheCoreIsFull(GameTestHelper helper) {
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		Colony colony = colony(helper);
		Container core = (Container) helper.getBlockEntity(CORE, BlockEntity.class);
		core.setItem(0, new ItemStack(Items.COBBLESTONE, 32));
		core.setItem(1, new ItemStack(Items.WATER_BUCKET));
		core.setItem(2, new ItemStack(Items.LAVA_BUCKET));
		// The warehouse, 5 wide and 3 deep, beside the core and clear of the generator; built at once, as builders would.
		Either<BlueprintPlacement.Refusal, PlacedBlueprint> warehouse = BlueprintPlacement.place(helper.getLevel(), colony, true, AntCraft.id("warehouse"),
			helper.absolutePos(new BlockPos(3, 1, 0)), Rotation.NONE, Mirror.NONE);
		helper.assertTrue(warehouse.right().isPresent(), "not placed: " + warehouse.left());
		for (int round = 0; round < 50 && !colony.builds().isEmpty(); round++) {
			colony.postBuildJobs();
			colony.builds().forEach(plan -> plan.steps().forEach(step -> helper.getLevel().setBlockAndUpdate(step.pos(), step.state())));
			colony.board().jobs().stream().filter(job -> job.isOpen() && colony.build(job.id()).isPresent()).toList().forEach(job -> {
				colony.finishBatch(job.id());
				colony.board().complete(job.id());
			});
		}
		List<BlockPos> chests = warehouse.right().orElseThrow().chests();
		helper.assertTrue(!Storage.storageChests(helper.getLevel(), colony).isEmpty(), "no warehouse chests");
		placeGenerator(helper, colony, Rotation.NONE, Mirror.LEFT_RIGHT);
		AntBot bot = helper.getLevel().getEntitiesOfClass(AntBot.class, new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(16)).stream()
			.filter(member -> colony.members().contains(member.getUUID())).findFirst().orElseThrow();
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		int target = 3;
		boolean[] wanted = {false};
		boolean[] onJob = {false};
		boolean[] done = {false};
		helper.onEachTick(() -> {
			if (!wanted[0] && generator(colony).finished()) {
				colony.setStockTarget(StockTarget.of(Items.COBBLESTONE, target));
				wanted[0] = true;
			}
			boolean claimed = colony.board().jobs().stream()
				.anyMatch(job -> job.type() == JobType.MINE_GENERATOR && job.claimedBy().equals(Optional.of(bot.getUUID())));
			if (claimed && !onJob[0]) {
				for (int i = 0; i < core.getContainerSize(); i++) {
					core.setItem(i, new ItemStack(Items.DIRT, 64));
				}
				onJob[0] = true;
			}
			if (!claimed && onJob[0] && !done[0]) {
				helper.assertValueEqual(bot.getInventory().countItem(Items.COBBLESTONE), 0, "cobblestone the bot left the job with");
				done[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(done[0], "the generator job is not done yet");
			int stored = chests.stream().map(spot -> Storage.containerAt(helper.getLevel(), spot)).filter(Objects::nonNull)
				.mapToInt(chest -> chest.countItem(Items.COBBLESTONE)).sum();
			helper.assertValueEqual(stored, target, "cobblestone in the warehouse");
			helper.assertTrue(colony.board().jobs().stream().noneMatch(job -> job.type() == JobType.MINE_GENERATOR), "generator job left up");
		});
	}

	/** Without lava in the core, the builder fills the empty bucket at a lava pool nearby, and the generator is built all the same. */
	@GameTest(maxTicks = 600)
	public void aCobblestoneGeneratorIsBuiltWithLavaFetchedFromAPool(GameTestHelper helper) {
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		Colony colony = colony(helper);
		Container core = (Container) helper.getBlockEntity(CORE, BlockEntity.class);
		core.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
		core.setItem(1, new ItemStack(Items.WATER_BUCKET));
		core.setItem(2, new ItemStack(Items.BUCKET));
		PlacedBlueprint generator = placeGenerator(helper, colony, Rotation.NONE, Mirror.NONE);
		// A lava source sunk into the floor, with stone under it, so it does not spread.
		BlockPos pool = new BlockPos(6, 0, 1);
		helper.setBlock(pool.below(), Blocks.STONE);
		helper.setBlock(pool, Blocks.LAVA);
		succeedWhenItGeneratesCobblestone(helper, colony, generator, () -> helper.assertBlockNotPresent(Blocks.LAVA, pool));
	}

	/**
	 * A builder needs water for a step and has an empty bucket. The water of the built farm and the water and lava of the
	 * built cobblestone generator are nearer than the pool in the corner, but it fills its bucket at the pool and leaves
	 * the buildings' fluids alone.
	 */
	@GameTest(maxTicks = 200)
	public void aBuilderFillsItsBucketAwayFromTheFluidsOfBuiltBuildings(GameTestHelper helper) {
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (int x = 0; x <= 7; x++) {
			for (int z = 0; z <= 7; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
			}
		}
		Colony colony = colony(helper);
		Container core = (Container) helper.getBlockEntity(CORE, BlockEntity.class);
		core.setItem(0, new ItemStack(Items.BUCKET));
		// The generator fills x = 5..7, z = 0..5 and the farm x = 0..4, z = 3..7.
		for (Either<BlueprintPlacement.Refusal, PlacedBlueprint> placed : List.of(
				BlueprintPlacement.place(helper.getLevel(), colony, true, AntCraft.id("cobble_generator"), helper.absolutePos(new BlockPos(5, 1, 0)),
					Rotation.CLOCKWISE_90, Mirror.NONE),
				BlueprintPlacement.place(helper.getLevel(), colony, true, AntCraft.id("farm"), helper.absolutePos(new BlockPos(0, 1, 3)),
					Rotation.NONE, Mirror.NONE))) {
			helper.assertTrue(placed.right().isPresent(), "not placed: " + placed.left());
		}
		GameTests.buildNow(helper, colony);
		List<BlockPos> fluids = new ArrayList<>();
		for (PlacedBlueprint placed : colony.blueprints()) {
			BlockPos.betweenClosedStream(placed.area()).filter(pos -> helper.getLevel().getBlockState(pos).getFluidState().isSource())
				.map(BlockPos::immutable).forEach(fluids::add);
		}
		helper.assertValueEqual(fluids.size(), 3, "fluid sources of the farm and generator");
		// The pool, sunk into the floor with stone under it, so it does not spread.
		BlockPos pool = new BlockPos(7, 0, 7);
		helper.setBlock(pool.below(), Blocks.STONE);
		helper.setBlock(pool, Blocks.WATER);
		// The step pours water into a pit in the floor.
		BlockPos pit = new BlockPos(3, 0, 1);
		helper.setBlock(pit.below(), Blocks.STONE);
		helper.setBlock(pit, Blocks.AIR);
		colony.addBuild(new BuildPlan(List.of(new BuildPlan.Step(helper.absolutePos(pit), Blocks.WATER.defaultBlockState()))));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 1);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		GameTests.failOnGiveUp(helper, colony);
		helper.onEachTick(() -> {
			for (BlockPos pos : fluids) {
				if (!helper.getLevel().getBlockState(pos).getFluidState().isSource()) {
					helper.fail("took the fluid at " + TestPositions.relative(helper, pos).toShortString());
				}
			}
		});

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.WATER, pit);
			helper.assertBlockNotPresent(Blocks.WATER, pool);
			helper.assertTrue(colony.builds().isEmpty(), "plan not finished");
		});
	}
}
