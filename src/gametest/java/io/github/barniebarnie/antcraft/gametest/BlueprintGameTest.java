package io.github.barniebarnie.antcraft.gametest;

import com.mojang.datafixers.util.Either;
import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.blueprint.Blueprint;
import io.github.barniebarnie.antcraft.blueprint.BlueprintSummary;
import io.github.barniebarnie.antcraft.blueprint.Blueprints;
import io.github.barniebarnie.antcraft.blueprint.ChosenBlueprint;
import io.github.barniebarnie.antcraft.item.BlueprintItem;
import io.github.barniebarnie.antcraft.registry.ModItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import io.github.barniebarnie.antcraft.colony.BlueprintPlacement;
import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.PlacedBlueprint;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.network.ColonyNetworking;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
		for (String name : List.of("starter_house", "cobble_step")) {
			Identifier id = AntCraft.id(name);
			Optional<Blueprint> blueprint = Blueprints.get(id);
			helper.assertTrue(blueprint.isPresent(), "no blueprint " + id + " in " + Blueprints.ids());
			helper.assertTrue(Blueprints.ids().contains(id), id + " not listed in " + Blueprints.ids());
			helper.assertTrue(helper.getLevel().getServer().getStructureTemplateManager().get(blueprint.get().structure()).isPresent(),
				"no structure " + blueprint.get().structure());
		}
		helper.succeed();
	}

	/** The list the server sends clients holds every bundled blueprint, with its structure's size and blocks for the picker and preview. */
	@GameTest(maxTicks = 5)
	public void theBlueprintListForClientsHoldsEveryBundledBlueprint(GameTestHelper helper) {
		ColonyNetworking.BlueprintList list = ColonyNetworking.BlueprintList.of(helper.getLevel().getServer());
		List<Identifier> ids = list.blueprints().stream().map(BlueprintSummary::id).toList();
		helper.assertValueEqual(ids, Blueprints.ids(), "listed blueprints");
		for (String name : List.of("starter_house", "cobble_step")) {
			BlueprintSummary summary = list.blueprints().stream().filter(blueprint -> blueprint.id().equals(AntCraft.id(name))).findFirst().orElseThrow();
			helper.assertValueEqual(summary.name(), "blueprint.antcraft." + name, "name of " + name);
			helper.assertTrue(summary.size().getX() > 0 && !summary.blocks().isEmpty() && !summary.materials().isEmpty(), name + " has no size or blocks: " + summary);
		}
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
}
