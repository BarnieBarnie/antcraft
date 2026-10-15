package io.github.barniebarnie.antcraft.client;

import io.github.barniebarnie.antcraft.client.render.AntBotRenderer;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.entity.EntityRenderers;

public class AntCraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRenderers.register(ModEntities.ANT_BOT, AntBotRenderer::new);
	}
}
