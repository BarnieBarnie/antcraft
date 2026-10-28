package io.github.barniebarnie.antcraft.gametest;

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
import io.github.barniebarnie.antcraft.network.BlueprintNetworking;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class BlueprintGameTest implements CustomTestMethodInvoker {
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
		BlueprintNetworking.BlueprintList list = BlueprintNetworking.BlueprintList.of(helper.getLevel().getServer());
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
	 * does not know it gets none.
	 */
	@GameTest(maxTicks = 5)
	public void aClientAskingForABlueprintsBlocksGetsThem(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		BlueprintBlocks step = BlueprintNetworking.blocks(server, new BlueprintNetworking.BlueprintBlocksRequest(COBBLE_STEP)).getFirst().blocks();
		helper.assertValueEqual(step.id(), COBBLE_STEP, "id of the reply");
		helper.assertValueEqual(step.size(), new Vec3i(2, 2, 1), "size of the cobble step");
		helper.assertValueEqual(step.blocks().stream().filter(block -> block.state().is(Blocks.OAK_STAIRS)).map(BlueprintSummary.Block::pos).toList(),
			List.of(new BlockPos(0, 1, 0)), "stairs of the cobble step");
		helper.assertValueEqual(step.blocks().size(), 3, "blocks of the cobble step");
		BlueprintBlocks castle = BlueprintNetworking.blocks(server, new BlueprintNetworking.BlueprintBlocksRequest(AntCraft.id("castle"))).getFirst().blocks();
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

		helper.assertFalse(BlueprintNetworking.handle(player, new BlueprintNetworking.ChooseBlueprint(InteractionHand.MAIN_HAND, AntCraft.id("castle"))),
			"an unknown blueprint was chosen");
		helper.assertFalse(BlueprintNetworking.handle(player, new BlueprintNetworking.ChooseBlueprint(InteractionHand.OFF_HAND, COBBLE_STEP)),
			"paper was set to a blueprint");
		helper.assertValueEqual(BlueprintItem.chosen(player.getItemInHand(InteractionHand.MAIN_HAND)), Optional.empty(), "chosen before");

		helper.assertTrue(BlueprintNetworking.handle(player, new BlueprintNetworking.ChooseBlueprint(InteractionHand.MAIN_HAND, COBBLE_STEP)), "not chosen");
		ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
		helper.assertValueEqual(BlueprintItem.chosen(held), Optional.of(new ChosenBlueprint(COBBLE_STEP, "blueprint.antcraft.cobble_step")), "chosen");
		List<Component> tooltip = held.getTooltipLines(context, player, TooltipFlag.NORMAL);
		helper.assertTrue(says(tooltip, "item.antcraft.blueprint.chosen") && says(tooltip, "blueprint.antcraft.cobble_step"),
			"tooltip does not name the blueprint: " + tooltip);
		helper.succeed();
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
}
