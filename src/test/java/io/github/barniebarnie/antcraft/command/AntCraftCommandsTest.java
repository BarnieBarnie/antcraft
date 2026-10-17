package io.github.barniebarnie.antcraft.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.barniebarnie.antcraft.entity.BotRole;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
	void roleReplyNamesTheRoleWithItsLangKey() {
		TranslatableContents reply = translatable(AntCraftCommands.roleReply(3, BotRole.LUMBERJACK));
		assertEquals("commands.antcraft.role", reply.getKey());
		assertEquals(3, reply.getArgs()[0]);
		assertEquals(BotRole.LUMBERJACK.translationKey(), translatable(reply.getArgs()[1]).getKey());
	}

	@Test
	void roleCountsNameEachRoleWithItsLangKey() {
		Map<BotRole, Integer> roles = new EnumMap<>(BotRole.class);
		roles.put(BotRole.LUMBERJACK, 1);
		roles.put(BotRole.MINER, 2);
		List<Component> parts = AntCraftCommands.roleCounts(roles);
		assertEquals(2, parts.size());
		TranslatableContents lumberjack = translatable(parts.get(0));
		assertEquals("commands.antcraft.colony.role_count", lumberjack.getKey());
		assertEquals(BotRole.LUMBERJACK.translationKey(), translatable(lumberjack.getArgs()[0]).getKey());
		assertEquals(1, lumberjack.getArgs()[1]);
		TranslatableContents miner = translatable(parts.get(1));
		assertEquals(BotRole.MINER.translationKey(), translatable(miner.getArgs()[0]).getKey());
		assertEquals(2, miner.getArgs()[1]);
	}
}
