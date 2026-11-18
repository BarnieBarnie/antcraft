package io.github.barniebarnie.antcraft.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.barniebarnie.antcraft.script.lang.ScriptError;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/** The arguments of the block actions (docs/reference/index.md, Blocks). */
class WorkApiTest {
	/** A bad face names the argument it was given as, face for use, not place's facing (#893). */
	@Test
	void aBadFaceNamesTheArgument() {
		assertEquals(Direction.NORTH, WorkApi.facing("north", "face"));
		assertEquals("face must be north, south, east, west, up or down, not 'sideways'",
			assertThrows(ScriptError.class, () -> WorkApi.facing("sideways", "face")).getMessage());
		assertEquals("face must be a str, not 'int'", assertThrows(ScriptError.class, () -> WorkApi.facing(3L, "face")).getMessage());
	}
}
