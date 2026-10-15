package io.github.barniebarnie.antcraft.client;

import io.github.barniebarnie.antcraft.client.render.AntBotRenderer;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class AntCraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(ModEntities.ANT_BOT, AntBotRenderer::new);
	}
}
