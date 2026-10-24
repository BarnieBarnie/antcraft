package io.github.barniebarnie.antcraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.client.render.AntBotRenderer;
import io.github.barniebarnie.antcraft.item.BlueprintItem;
import io.github.barniebarnie.antcraft.network.ColonyNetworking;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderers;

public class AntCraftClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(AntCraft.id("main"));

	@Override
	public void onInitializeClient() {
		EntityRenderers.register(ModEntities.ANT_BOT, AntBotRenderer::new);

		KeyMapping colonyStatus = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.antcraft.colony_status", InputConstants.Type.KEYBOARD, InputConstants.KEY_K, CATEGORY));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (colonyStatus.consumeClick()) {
				if (client.gui.screen() == null && ClientPlayNetworking.canSend(ColonyNetworking.StatusRequest.TYPE)) {
					client.gui.setScreen(new ColonyStatusScreen());
					ColonyStatusScreen.request();
				}
			}
		});
		KeyMapping rotateBlueprint = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.antcraft.rotate_blueprint", InputConstants.Type.KEYBOARD, InputConstants.KEY_R, CATEGORY));
		KeyMapping mirrorBlueprint = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.antcraft.mirror_blueprint", InputConstants.Type.KEYBOARD, InputConstants.KEY_M, CATEGORY));
		ClientTickEvents.END_CLIENT_TICK.register(client -> BlueprintPreview.tick(client, rotateBlueprint, mirrorBlueprint));
		// Before the frame's gizmos are handed to the renderer, so the ghost shows in the same frame.
		LevelExtractionEvents.AFTER_BLOCK_OUTLINE_EXTRACTION.register((context, hit) -> {
			PlacedGhosts.draw(Minecraft.getInstance());
			BlueprintPreview.draw(Minecraft.getInstance(), hit);
		});
		ClientPlayNetworking.registerGlobalReceiver(ColonyNetworking.BuildGhosts.TYPE, (ghosts, context) -> PlacedGhosts.set(ghosts.builds()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PlacedGhosts.reset());
		BlueprintItem.setGhostClicker(PlacedGhosts::click);
		ClientPlayNetworking.registerGlobalReceiver(ColonyNetworking.SiteRulesReply.TYPE, (reply, context) -> BlueprintPreview.setRules(reply.rules()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> BlueprintPreview.reset());
		BlueprintItem.setPlacer(BlueprintPreview::place);
		BlueprintItem.setPicker(BlueprintPickerScreen::open);
		ClientPlayNetworking.registerGlobalReceiver(ColonyNetworking.BlueprintList.TYPE, (list, context) -> ClientBlueprints.set(list.blueprints()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientBlueprints.set(List.of()));
		// Replies that arrive after the screen closed are dropped.
		ClientPlayNetworking.registerGlobalReceiver(ColonyNetworking.StatusReply.TYPE, (reply, context) -> {
			if (context.client().gui.screen() instanceof ColonyStatusScreen screen) {
				screen.update(reply);
			}
		});
	}
}
