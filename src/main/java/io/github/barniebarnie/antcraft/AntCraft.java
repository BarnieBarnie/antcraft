package io.github.barniebarnie.antcraft;

import io.github.barniebarnie.antcraft.command.AntCraftCommands;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.registry.ModItems;
import net.fabricmc.api.ModInitializer;
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
		ModEntities.register();
		ModItems.register();
		AntCraftCommands.register();
		LOGGER.info("AntCraft loaded");
	}
}
