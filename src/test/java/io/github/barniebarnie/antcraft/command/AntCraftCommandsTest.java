package io.github.barniebarnie.antcraft.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.barniebarnie.antcraft.colony.ColonyStatus;
import io.github.barniebarnie.antcraft.colony.JobType;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AntCraftCommandsTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static TranslatableContents translatable(Object component) {
		return assertInstanceOf(TranslatableContents.class, assertInstanceOf(Component.class, component).getContents());
	}

	@Test
	void botLineNamesTheBotsJobWithItsLangKey() {
		TranslatableContents line = translatable(AntCraftCommands.botLine(new ColonyStatus.Bot("Alice", Optional.of(JobType.CHOP_TREE), false)));
		assertEquals("commands.antcraft.colony.bot", line.getKey());
		assertEquals("Alice", line.getArgs()[0]);
		assertEquals(JobType.CHOP_TREE.translationKey(), translatable(line.getArgs()[1]).getKey());
	}

	@Test
	void botLineOfABotWithoutAJobSaysSo() {
		TranslatableContents line = translatable(AntCraftCommands.botLine(new ColonyStatus.Bot("Bob", Optional.empty(), true)));
		assertEquals(JobType.NO_JOB_KEY, translatable(line.getArgs()[1]).getKey());
	}
}
