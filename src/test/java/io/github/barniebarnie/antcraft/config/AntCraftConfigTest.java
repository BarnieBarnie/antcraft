package io.github.barniebarnie.antcraft.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.barniebarnie.antcraft.script.lang.Output;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AntCraftConfigTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void defaultsWhenEmpty() {
		AntCraftConfig.Values values = AntCraftConfig.parse(new Properties());
		assertFalse(values.enableOnDedicatedServer());
		assertEquals(64, values.maxBotsPerLevel());
		assertFalse(values.colonyActionsRequireOp());
	}

	@Test
	void readsWhetherColonyActionsRequireOp() {
		Properties props = new Properties();
		props.setProperty("colonyActionsRequireOp", " true ");
		assertTrue(AntCraftConfig.parse(props).colonyActionsRequireOp());
	}

	/** A file written before the goal-driven colony went still loads: its options are ignored without a warning. */
	@Test
	void theRemovedColonyOptionsAreIgnored() {
		Properties props = new Properties();
		props.setProperty("mineshaftLevels", "16,15");
		props.setProperty("replantMatchWaitTicks", "forever");
		List<String> warnings = new ArrayList<>();
		assertEquals(AntCraftConfig.parse(new Properties()), AntCraftConfig.parse(props, warnings::add));
		assertEquals(List.of(), warnings);
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
	void scriptSettingsDefault() {
		AntCraftConfig.Values values = AntCraftConfig.parse(new Properties());
		assertEquals(2000, values.scriptInstructionsPerTick());
		assertEquals(Output.Level.ERROR, values.scriptChatLevel());
		assertEquals(2000, new AntCraftConfig.Values(true, 64).scriptInstructionsPerTick());
		assertEquals(Output.Level.ERROR, new AntCraftConfig.Values(true, 64).scriptChatLevel());
	}

	@Test
	void readsTheScriptBudget() {
		Properties props = new Properties();
		props.setProperty("scriptInstructionsPerTick", " 5000 ");
		List<String> warnings = new ArrayList<>();
		assertEquals(5000, AntCraftConfig.parse(props, warnings::add).scriptInstructionsPerTick());
		props.setProperty("scriptInstructionsPerTick", "20000");
		assertEquals(20000, AntCraftConfig.parse(props, warnings::add).scriptInstructionsPerTick());
		assertEquals(List.of(), warnings);
		props.setProperty("scriptInstructionsPerTick", "lots");
		assertEquals(2000, AntCraftConfig.parse(props).scriptInstructionsPerTick());
		// A bot that may run no instructions could never run a script.
		props.setProperty("scriptInstructionsPerTick", "0");
		assertEquals(1, AntCraftConfig.parse(props).scriptInstructionsPerTick());
	}

	@Test
	void aScriptBudgetAboveTheCapIsLoweredWithAWarning() {
		Properties props = new Properties();
		props.setProperty("scriptInstructionsPerTick", "50000");
		List<String> warnings = new ArrayList<>();
		assertEquals(20000, AntCraftConfig.parse(props, warnings::add).scriptInstructionsPerTick());
		assertEquals(1, warnings.size(), "warnings: " + warnings);
		String warning = warnings.getFirst();
		assertTrue(warning.contains("scriptInstructionsPerTick") && warning.contains("50000") && warning.contains("20000"), warning);
	}

	@Test
	void theConfigCommentWarnsOfTheLagRiskOfALargeScriptBudget() {
		String comment = AntCraftConfig.fileComment();
		assertTrue(comment.contains("scriptInstructionsPerTick") && comment.contains("20000") && comment.contains("lag"), comment);
	}

	@Test
	void botsLoadChunksUnlessTurnedOff() {
		assertTrue(AntCraftConfig.parse(new Properties()).botsLoadChunks());
		assertTrue(new AntCraftConfig.Values(true, 64).botsLoadChunks());
		Properties props = new Properties();
		props.setProperty("botsLoadChunks", " false ");
		assertFalse(AntCraftConfig.parse(props).botsLoadChunks());
	}

	@Test
	void reflexThresholdsDefaultAndRead() {
		AntCraftConfig.Values defaults = AntCraftConfig.parse(new Properties());
		assertEquals(6, defaults.botFleeHealth());
		assertEquals(100, defaults.botSurfaceAir());
		assertEquals(6, new AntCraftConfig.Values(true, 64).botFleeHealth());
		assertEquals(100, new AntCraftConfig.Values(true, 64).botSurfaceAir());
		Properties props = new Properties();
		props.setProperty("botFleeHealth", " 10 ");
		props.setProperty("botSurfaceAir", "200");
		AntCraftConfig.Values values = AntCraftConfig.parse(props);
		assertEquals(10, values.botFleeHealth());
		assertEquals(200, values.botSurfaceAir());
		// 0 turns a reflex off; below 0 is 0, and a number that is not one is the default.
		props.setProperty("botFleeHealth", "-3");
		props.setProperty("botSurfaceAir", "lots");
		values = AntCraftConfig.parse(props);
		assertEquals(0, values.botFleeHealth());
		assertEquals(100, values.botSurfaceAir());
	}

	@Test
	void botsAttackNoPlayersBotsOrTamedAnimalsUnlessAllowed() {
		for (AntCraftConfig.Values values : List.of(AntCraftConfig.parse(new Properties()), new AntCraftConfig.Values(true, 64))) {
			assertFalse(values.botsAttackPlayers());
			assertFalse(values.botsAttackBots());
			assertFalse(values.botsAttackTamed());
		}
		Properties props = new Properties();
		props.setProperty("botsAttackPlayers", " true ");
		props.setProperty("botsAttackBots", "true");
		props.setProperty("botsAttackTamed", "TRUE");
		AntCraftConfig.Values values = AntCraftConfig.parse(props);
		assertTrue(values.botsAttackPlayers());
		assertTrue(values.botsAttackBots());
		assertTrue(values.botsAttackTamed());
		String comment = AntCraftConfig.fileComment();
		assertTrue(comment.contains("botsAttackPlayers") && comment.contains("botsAttackBots") && comment.contains("botsAttackTamed"), comment);
	}

	@Test
	void theConfigCommentNamesTheReflexThresholds() {
		String comment = AntCraftConfig.fileComment();
		assertTrue(comment.contains("botFleeHealth") && comment.contains("botSurfaceAir"), comment);
	}

	@Test
	void readsTheScriptChatLevel() {
		Properties props = new Properties();
		props.setProperty("scriptChatLevel", " Warn ");
		assertEquals(Output.Level.WARN, AntCraftConfig.parse(props).scriptChatLevel());
		props.setProperty("scriptChatLevel", "debug");
		assertEquals(Output.Level.DEBUG, AntCraftConfig.parse(props).scriptChatLevel());
		List<String> warnings = new ArrayList<>();
		props.setProperty("scriptChatLevel", "loud");
		assertEquals(Output.Level.ERROR, AntCraftConfig.parse(props, warnings::add).scriptChatLevel());
		assertEquals(1, warnings.size(), "warnings: " + warnings);
		// print always shows; there is no level above error to choose.
		props.setProperty("scriptChatLevel", "print");
		assertEquals(Output.Level.ERROR, AntCraftConfig.parse(props).scriptChatLevel());
	}

	@Test
	void deadBotsRespawnAfterTenSecondsByDefault() {
		AntCraftConfig.Values values = AntCraftConfig.parse(new Properties());
		assertTrue(values.botRespawn());
		assertEquals(200, values.botRespawnTicks());
		assertTrue(new AntCraftConfig.Values(true, 64).botRespawn());
		assertEquals(200, new AntCraftConfig.Values(true, 64).botRespawnTicks());
	}

	@Test
	void readsTheRespawnSettings() {
		Properties props = new Properties();
		props.setProperty("botRespawn", " false ");
		props.setProperty("botRespawnTicks", " 40 ");
		AntCraftConfig.Values values = AntCraftConfig.parse(props);
		assertFalse(values.botRespawn());
		assertEquals(40, values.botRespawnTicks());
		props.setProperty("botRespawnTicks", "soon");
		assertEquals(200, AntCraftConfig.parse(props).botRespawnTicks());
		props.setProperty("botRespawnTicks", "-5");
		assertEquals(0, AntCraftConfig.parse(props).botRespawnTicks());
	}

	@Test
	void theConfigCommentNamesTheRespawnSettings() {
		String comment = AntCraftConfig.fileComment();
		assertTrue(comment.contains("botRespawn") && comment.contains("botRespawnTicks"), comment);
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
