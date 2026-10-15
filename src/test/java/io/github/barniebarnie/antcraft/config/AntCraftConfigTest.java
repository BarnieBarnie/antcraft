package io.github.barniebarnie.antcraft.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import org.junit.jupiter.api.Test;

class AntCraftConfigTest {
	@Test
	void defaultsWhenEmpty() {
		AntCraftConfig.Values values = AntCraftConfig.parse(new Properties());
		assertFalse(values.enableOnDedicatedServer());
		assertEquals(64, values.maxBotsPerLevel());
	}

	@Test
	void readsValues() {
		Properties props = new Properties();
		props.setProperty("enableOnDedicatedServer", "true");
		props.setProperty("maxBotsPerLevel", " 10 ");
		AntCraftConfig.Values values = AntCraftConfig.parse(props);
		assertTrue(values.enableOnDedicatedServer());
		assertEquals(10, values.maxBotsPerLevel());
	}

	@Test
	void invalidNumberFallsBackToDefault() {
		Properties props = new Properties();
		props.setProperty("maxBotsPerLevel", "lots");
		assertEquals(64, AntCraftConfig.parse(props).maxBotsPerLevel());
	}

	@Test
	void negativeNumberClampsToZero() {
		Properties props = new Properties();
		props.setProperty("maxBotsPerLevel", "-5");
		assertEquals(0, AntCraftConfig.parse(props).maxBotsPerLevel());
	}

	@Test
	void singleplayerAlwaysAllowed() {
		assertTrue(AntCraftConfig.botsAllowed(false, false));
		assertTrue(AntCraftConfig.botsAllowed(false, true));
	}

	@Test
	void dedicatedServerRequiresOptIn() {
		assertFalse(AntCraftConfig.botsAllowed(true, false));
		assertTrue(AntCraftConfig.botsAllowed(true, true));
	}
}
