package io.github.barniebarnie.antcraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.client.render.AntBotRenderer;
import io.github.barniebarnie.antcraft.client.render.GhostRenderer;
import io.github.barniebarnie.antcraft.item.BlueprintItem;
import io.github.barniebarnie.antcraft.network.BlueprintNetworking;
import io.github.barniebarnie.antcraft.network.ScriptNetworking;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderers;

public class AntCraftClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(AntCraft.id("main"));

	@Override
	public void onInitializeClient() {
		EntityRenderers.register(ModEntities.ANT_BOT, AntBotRenderer::new);

		KeyMapping rotateBlueprint = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.antcraft.rotate_blueprint", InputConstants.Type.KEYBOARD, InputConstants.KEY_R, CATEGORY));
		KeyMapping mirrorBlueprint = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.antcraft.mirror_blueprint", InputConstants.Type.KEYBOARD, InputConstants.KEY_M, CATEGORY));
		ClientTickEvents.END_CLIENT_TICK.register(client -> BlueprintPreview.tick(client, rotateBlueprint, mirrorBlueprint));
		// With the frame's other submits, so the translucent ghost blocks are sorted with the rest of the world's.
		LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
			BlueprintPreview.draw(Minecraft.getInstance(), context);
			GhostRenderer.endFrame();
		});
		// Ghost colours worked out before a chunk's biomes arrived are worked out again with them.
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> GhostRenderer.chunkLoaded(level, chunk.getPos()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> BlueprintPreview.reset());
		BlueprintItem.setPicker(BlueprintPickerScreen::open);
		ClientPlayNetworking.registerGlobalReceiver(BlueprintNetworking.BlueprintList.TYPE, (list, context) -> ClientBlueprints.set(list.blueprints()));
		ClientPlayNetworking.registerGlobalReceiver(BlueprintNetworking.BlueprintBlocksReply.TYPE, (reply, context) -> ClientBlueprints.receive(reply));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientBlueprints.set(List.of()));
		ClientPlayNetworking.registerGlobalReceiver(ScriptNetworking.OpenScriptEditor.TYPE, (script, context) -> ScriptEditorScreen.open(script));
		ClientPlayNetworking.registerGlobalReceiver(ScriptNetworking.ScriptSaved.TYPE, (reply, context) -> ScriptEditorScreen.saved(reply));
		// Litematica is optional: its classes are only touched with it installed.
		if (FabricLoader.getInstance().isModLoaded("litematica")) {
			LitematicaPlacements.register();
		}
	}
}
