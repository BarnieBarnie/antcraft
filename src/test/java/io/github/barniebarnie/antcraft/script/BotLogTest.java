package io.github.barniebarnie.antcraft.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.barniebarnie.antcraft.script.lang.Output.Level;
import java.util.List;
import org.junit.jupiter.api.Test;

class BotLogTest {
	@Test
	void keepsTheLastLinesUpToItsCapacity() {
		BotLog log = new BotLog(3);
		for (int i = 1; i <= 5; i++) {
			log.add(i, Level.INFO, "line " + i);
		}
		assertEquals(List.of("line 3", "line 4", "line 5"), log.last(10, Level.DEBUG).stream().map(BotLog.Entry::text).toList());
		assertEquals(3, log.last(10, Level.DEBUG).getFirst().tick());
	}

	@Test
	void givesTheLastNLinesAtOrAboveALevel() {
		BotLog log = new BotLog(10);
		log.add(1, Level.DEBUG, "d");
		log.add(2, Level.ERROR, "e1");
		log.add(3, Level.INFO, "i");
		log.add(4, Level.PRINT, "p");
		log.add(5, Level.WARN, "w");
		log.add(6, Level.ERROR, "e2");
		assertEquals(List.of("p", "w", "e2"), log.last(3, Level.DEBUG).stream().map(BotLog.Entry::text).toList());
		assertEquals(List.of("e1", "p", "w", "e2"), log.last(10, Level.WARN).stream().map(BotLog.Entry::text).toList());
		assertEquals(List.of("p", "e2"), log.last(2, Level.ERROR).stream().map(BotLog.Entry::text).toList());
	}

	/** docs/scripting.md, Output and logging: print always shows in chat, other lines at or above the chat level. */
	@Test
	void chatShowsPrintAlwaysAndOtherLinesFromTheChatLevelUp() {
		assertTrue(BotLog.showsInChat(Level.PRINT, Level.ERROR));
		assertTrue(BotLog.showsInChat(Level.ERROR, Level.ERROR));
		assertFalse(BotLog.showsInChat(Level.WARN, Level.ERROR));
		assertFalse(BotLog.showsInChat(Level.DEBUG, Level.INFO));
		assertTrue(BotLog.showsInChat(Level.INFO, Level.INFO));
		assertTrue(BotLog.showsInChat(Level.DEBUG, Level.DEBUG));
	}

	@Test
	void formatsALineWithItsTickAndLevel() {
		assertEquals("[120 warn] low on logs", new BotLog.Entry(120, Level.WARN, "low on logs").format());
		assertEquals("[7 print] hi", new BotLog.Entry(7, Level.PRINT, "hi").format());
	}
}
