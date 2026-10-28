package io.github.barniebarnie.antcraft.script;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlockInfoTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** {@code falls}: a block that falls when nothing holds it up, such as gravel, concrete powder or an anvil (#387). */
	@Test
	void fallsForEveryFallingBlock() {
		for (String id : List.of("minecraft:gravel", "minecraft:suspicious_sand", "minecraft:red_concrete_powder", "minecraft:anvil")) {
			assertEquals(true, block(id).attribute("falls"), id);
		}
		for (String id : List.of("minecraft:stone", "minecraft:air", "minecraft:water")) {
			assertEquals(false, block(id).attribute("falls"), id);
		}
	}

	private static BlockInfo block(String id) {
		return new BlockInfo(id, new Pos(0, 0, 0), false, false, List.of(), Map.of());
	}
}
