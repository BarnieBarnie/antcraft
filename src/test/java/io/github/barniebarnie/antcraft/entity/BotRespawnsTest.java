package io.github.barniebarnie.antcraft.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.util.OptionalLong;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BotRespawnsTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static AntCraftConfig.Values respawn(boolean on, int ticks) {
		return new AntCraftConfig.Values(true, 64, false, AntCraftConfig.DEFAULT_SCRIPT_INSTRUCTIONS_PER_TICK,
			Output.Level.ERROR, true, on, ticks);
	}

	@Test
	void aDeadBotRespawnsTheConfiguredTicksAfterItDied() {
		assertEquals(OptionalLong.of(1200), BotRespawns.respawnTime(respawn(true, 200), 1000));
		assertEquals(OptionalLong.of(1000), BotRespawns.respawnTime(respawn(true, 0), 1000));
	}

	@Test
	void withRespawnOffDeathIsFinal() {
		assertEquals(OptionalLong.empty(), BotRespawns.respawnTime(respawn(false, 200), 1000));
	}
}
