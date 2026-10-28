package io.github.barniebarnie.antcraft.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.barniebarnie.antcraft.script.lang.PyList;
import io.github.barniebarnie.antcraft.script.lang.PyTuple;
import io.github.barniebarnie.antcraft.script.lang.ScriptError;
import io.github.barniebarnie.antcraft.work.VanillaData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Parsing selectors, the strings that pick items, blocks and entities (docs/scripting.md, Selectors). */
class SelectorTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static String error(Object value) {
		return assertThrows(ScriptError.class, () -> Selector.parse(value)).getMessage();
	}

	@Test
	void aNameWithoutNamespaceIsInMinecraft() {
		assertEquals(new Selector(List.of("minecraft:oak_log"), List.of()), Selector.parse("oak_log"));
	}

	@Test
	void aNameKeepsItsNamespace() {
		assertEquals(new Selector(List.of("minecraft:oak_log"), List.of()), Selector.parse("minecraft:oak_log"));
		assertEquals(new Selector(List.of("antcraft:bot_core"), List.of()), Selector.parse("antcraft:bot_core"));
	}

	@Test
	void aHashSelectsATag() {
		assertEquals(new Selector(List.of(), List.of("minecraft:logs")), Selector.parse("#minecraft:logs"));
		assertEquals(new Selector(List.of(), List.of("c:ores/iron")), Selector.parse("#c:ores/iron"));
		assertEquals(new Selector(List.of(), List.of("minecraft:logs")), Selector.parse("#logs"));
	}

	@Test
	void aListOrTupleCombinesSelectors() {
		Selector expected = new Selector(List.of("minecraft:oak_log", "minecraft:dirt"), List.of("minecraft:planks"));
		assertEquals(expected, Selector.parse(new PyList(new ArrayList<>(List.of("oak_log", "#planks", "minecraft:dirt")))));
		assertEquals(expected, Selector.parse(new PyTuple(List.of("oak_log", "#planks", "minecraft:dirt"))));
	}

	@Test
	void printsAsTheSelectorsItHolds() {
		assertEquals("'minecraft:oak_log'", Selector.parse("oak_log").toString());
		assertEquals("['minecraft:oak_log', '#minecraft:logs']", Selector.parse(new PyList(new ArrayList<>(List.of("oak_log", "#logs")))).toString());
	}

	@Test
	void rejectsBadInput() {
		assertEquals("'' is not a selector: it is empty", error(""));
		assertEquals("'#' is not a selector: a tag needs a name after '#'", error("#"));
		assertEquals("'Oak Log' is not a selector: names use only a-z, 0-9, '_', '-', '.' and '/'", error("Oak Log"));
		assertEquals("'a:b:c' is not a selector: a name has at most one ':'", error("a:b:c"));
		assertEquals("':oak_log' is not a selector: the namespace before ':' is empty", error(":oak_log"));
		assertEquals("'minecraft:' is not a selector: the name after ':' is empty", error("minecraft:"));
		assertEquals("'mine/craft:x' is not a selector: a namespace uses only a-z, 0-9, '_', '-' and '.'", error("mine/craft:x"));
		assertEquals("a selector is a str or a list of str, not 'int'", error(3L));
		assertEquals("a selector is a str or a list of str, not 'list'", error(new PyList(new ArrayList<>(List.of(new PyList(new ArrayList<>()))))));
		assertEquals("an empty list selects nothing", error(new PyList(new ArrayList<>())));
	}

	@Test
	void aRepeatedSelectorIsParsedAndLookedUpOnce() {
		Selector.clearCache();
		Selector name = Selector.parse("oak_log");
		assertSame(name, Selector.parse("oak_log"));
		assertSame(name.items(), Selector.parse("oak_log").items());
		assertSame(name.blocks(), Selector.parse("oak_log").blocks());
		PyList list = new PyList(new ArrayList<>(List.of("oak_log", "dirt")));
		Selector both = Selector.parse(list);
		assertSame(both, Selector.parse(new PyTuple(List.of("oak_log", "dirt"))));
		list.items.add("stone");
		assertEquals(new Selector(List.of("minecraft:oak_log", "minecraft:dirt", "minecraft:stone"), List.of()), Selector.parse(list));
	}

	@Test
	void clearingTheCacheLooksSelectorsUpAgain() {
		Selector name = Selector.parse("oak_log");
		var items = name.items();
		Selector.clearCache();
		assertNotSame(name, Selector.parse("oak_log"));
		assertNotSame(items, Selector.parse("oak_log").items());
	}

	@Test
	void matchesAnItemIdOrStackAgainstASelector() {
		VanillaData.bindTags();
		assertTrue(Selector.matches("minecraft:birch_log", "birch_log"));
		assertTrue(Selector.matches("birch_log", "minecraft:birch_log"));
		assertFalse(Selector.matches("minecraft:birch_log", "oak_log"));
		assertTrue(Selector.matches("minecraft:birch_log", "#minecraft:logs"));
		assertTrue(Selector.matches(new Stack("minecraft:birch_log", 3), "#logs"));
		assertFalse(Selector.matches(new Stack("minecraft:dirt", 3), "#logs"));
		assertTrue(Selector.matches("minecraft:oak_planks", new PyList(new ArrayList<>(List.of("dirt", "#planks")))));
		assertFalse(Selector.matches("minecraft:stone", new PyTuple(List.of("dirt", "#planks"))));
	}

	@Test
	void matchesFailsOnAnUnknownSelectorOrItem() {
		VanillaData.bindTags();
		assertEquals("unknown item 'minecraft:no_such_item'",
			assertThrows(ScriptError.class, () -> Selector.matches("dirt", "no_such_item")).getMessage());
		assertEquals("unknown item tag '#minecraft:no_such_tag'",
			assertThrows(ScriptError.class, () -> Selector.matches("dirt", "#no_such_tag")).getMessage());
		assertEquals("unknown item 'minecraft:no_such_item'",
			assertThrows(ScriptError.class, () -> Selector.matches("no_such_item", "dirt")).getMessage());
		assertEquals("matches() item must be an item id or a Stack, not 'int'",
			assertThrows(ScriptError.class, () -> Selector.matches(3L, "dirt")).getMessage());
	}
}
