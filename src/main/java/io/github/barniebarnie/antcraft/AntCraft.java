package io.github.barniebarnie.antcraft;

import io.github.barniebarnie.antcraft.blueprint.Blueprints;
import io.github.barniebarnie.antcraft.command.AntCraftCommands;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.BotChunks;
import io.github.barniebarnie.antcraft.entity.BotRespawns;
import io.github.barniebarnie.antcraft.network.BlueprintNetworking;
import io.github.barniebarnie.antcraft.profile.TickProfiler;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModComponents;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.registry.ModItems;
import io.github.barniebarnie.antcraft.script.ServerScripts;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AntCraft implements ModInitializer {
	public static final String MOD_ID = "antcraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		AntCraftConfig.load();
		ModBlocks.register();
		ModEntities.register();
		BotChunks.register();
		BotRespawns.register();
		ModComponents.register();
		ModItems.register();
		AntCraftCommands.register();
		ServerScripts.register();
		Blueprints.register();
		BlueprintNetworking.register();
		// The whole server tick.
		ServerTickEvents.START_SERVER_TICK.register(server -> TickProfiler.SERVER.startTick());
		ServerTickEvents.END_SERVER_TICK.register(server -> TickProfiler.SERVER.endTick());
		LOGGER.info("AntCraft loaded");
	}
}
