package io.github.barniebarnie.antcraft.script.editor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** The names the editor colours as builtins and natives. */
class ScriptNamesTest {
	@Test
	void builtinsBotNativesAndExceptionTypesButNoMethodsOrInternals() {
		Set<String> names = ScriptNames.natives();
		for (String name : new String[] {"print", "range", "say", "goto", "craft", "Pos", "ValueError", "memory"}) {
			assertTrue(names.contains(name), name + " in " + names);
		}
		assertFalse(names.stream().anyMatch(n -> n.contains(".") || n.startsWith("$")), names.toString());
	}
}
