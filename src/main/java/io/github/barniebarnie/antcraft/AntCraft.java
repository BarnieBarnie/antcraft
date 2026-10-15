package io.github.barniebarnie.antcraft;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AntCraft implements ModInitializer {
	public static final String MOD_ID = "antcraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("AntCraft loaded");
	}
}
