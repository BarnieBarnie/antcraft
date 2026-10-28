package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.barniebarnie.antcraft.script.Pos;
import io.github.barniebarnie.antcraft.script.lang.PyList;
import io.github.barniebarnie.antcraft.script.lang.PyNone;
import io.github.barniebarnie.antcraft.script.lang.PyTuple;
import io.github.barniebarnie.antcraft.script.lang.ScriptError;
import io.github.barniebarnie.antcraft.script.lang.Values;
import java.io.IOException;
import java.util.ArrayList;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ColonySaveTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * Colonies as the goal-driven colony saved them, with a job board, stock targets, an order, the mineshaft, a field
	 * and a project ({@code colonies_with_jobs.json}, written by the version before milestone 4), load with what a
	 * colony still keeps; the rest is ignored, and not written again.
	 */
	@Test
	void aColonySavedWithJobsLoadsWithoutThem() throws IOException {
		JsonElement saved;
		try (InputStream in = Objects.requireNonNull(ColonySaveTest.class.getResourceAsStream("/antcraft-test/colonies_with_jobs.json"))) {
			saved = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
		}
		List<Colony> colonies = Colony.CODEC.listOf().parse(JsonOps.INSTANCE, saved).getOrThrow();

		assertEquals(1, colonies.size());
		Colony colony = colonies.getFirst();
		assertEquals(UUID.fromString("00000000-0000-0000-0000-0000000c0104"), colony.id());
		assertEquals(new BlockPos(10, 64, 10), colony.core());
		assertEquals(Set.of(UUID.fromString("00000000-0000-0000-0000-00000000b075")), colony.members());
		assertEquals(40, colony.radius());
		assertEquals(Optional.of(UUID.fromString("00000000-0000-0000-0000-0000000000a1")), colony.owner());
		assertEquals(Set.of(UUID.fromString("00000000-0000-0000-0000-0000000000a2")), colony.trusted());
		Set<String> written = Colony.CODEC.encodeStart(JsonOps.INSTANCE, colony).getOrThrow().getAsJsonObject().keySet();
		assertEquals(Set.of("id", "core", "members", "radius", "owner", "trusted"), written);
		assertEquals("{}", Values.repr(colony.memory()), "a colony saved without colony memory has an empty one");
	}

	/**
	 * The colony's {@code colony_memory} (#365) is saved with the colonies of the world, and still takes plain data only
	 * once loaded.
	 */
	@Test
	void colonyMemoryRoundTripsThroughTheColoniesSavedData() {
		ColonyManager colonies = new ColonyManager();
		Colony colony = colonies.create(new BlockPos(1, 64, 2));
		colony.memory().put("chests", new PyList(new ArrayList<>(List.of(new Pos(3, 64, 4, "minecraft:overworld"), new Pos(5, 6, 7)))));
		colony.memory().put("built", PyTuple.of("house", 2L, 0.5, true, PyNone.NONE));
		Tag saved = ColonyManager.TYPE.codec().encodeStart(NbtOps.INSTANCE, colonies).getOrThrow();
		Colony loaded = ColonyManager.TYPE.codec().parse(NbtOps.INSTANCE, saved).getOrThrow().byId(colony.id()).orElseThrow();
		assertEquals(Values.repr(colony.memory()), Values.repr(loaded.memory()));
		assertEquals("minecraft:overworld", ((Pos) ((PyList) loaded.memory().get("chests")).items.getFirst()).dimension());
		ScriptError error = assertThrows(ScriptError.class, () -> loaded.memory().put("f", new Object()));
		assertTrue(error.getMessage().startsWith("memory holds only plain data"), error.getMessage());
	}
}
