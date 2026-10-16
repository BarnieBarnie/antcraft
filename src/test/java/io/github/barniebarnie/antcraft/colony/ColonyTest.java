package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ColonyTest {
	private static final Mineshaft SHAFT = new Mineshaft(new BlockPos(2, 64, 2), List.of(60), 1, 3, 4);

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static Colony colony() {
		return new Colony(UUID.randomUUID(), new BlockPos(0, 64, 0), List.of(), new JobBoard(), Colony.DEFAULT_RADIUS, Map.of(),
			Optional.empty(), List.of(), List.of(), List.of(), Optional.empty(), Optional.empty());
	}

	@Test
	void oldSaveWithAQuarryLoads() {
		String json = """
			{"id": [1, 2, 3, 4], "core": [0, 64, 0],
			 "quarry": {"min": [4, 50, 4], "max": [8, 60, 8]},
			 "jobs": [{"id": [5, 6, 7, 8], "type": "mine_layer", "target": [4, 60, 4]}]}
			""";
		Colony colony = Colony.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
		assertEquals(new BlockPos(0, 64, 0), colony.core());
		assertTrue(colony.mineshaft().isEmpty());
		// Jobs are posted again by the next survey, so dropping the old ones loses nothing.
		assertTrue(colony.board().jobs().isEmpty());
	}

	@Test
	void settingAMineshaftQueuesItsPlanAndProtectsItsStairs() {
		Colony colony = colony();
		colony.setMineshaft(SHAFT);
		assertEquals(List.of(SHAFT.plan()), colony.builds());
		assertTrue(colony.isProtected(SHAFT.stairs().getLast().below()));

		Mineshaft moved = new Mineshaft(new BlockPos(10, 64, 10), List.of(60), 1, 3, 4);
		colony.setMineshaft(moved);
		assertEquals(List.of(moved.plan()), colony.builds());
		assertFalse(colony.isProtected(SHAFT.stairs().getLast().below()));
	}

	@Test
	void mineshaftSurvivesSaveAndLoad() {
		Colony colony = colony();
		colony.setMineshaft(SHAFT);
		var json = Colony.CODEC.encodeStart(JsonOps.INSTANCE, colony).getOrThrow();
		Colony loaded = Colony.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
		assertEquals(Optional.of(SHAFT), loaded.mineshaft());
		assertTrue(loaded.isProtected(SHAFT.stairs().getLast().below()));
	}
}
