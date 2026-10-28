package io.github.barniebarnie.antcraft.script;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import io.github.barniebarnie.antcraft.blueprint.Blueprint;
import io.github.barniebarnie.antcraft.blueprint.BlueprintSummary;
import io.github.barniebarnie.antcraft.script.lang.PyDict;
import io.github.barniebarnie.antcraft.script.lang.PyList;
import io.github.barniebarnie.antcraft.script.lang.PyNone;
import io.github.barniebarnie.antcraft.script.lang.PyTuple;
import io.github.barniebarnie.antcraft.work.VanillaData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** The sense {@code blueprint(id)} (docs/scripting.md, Senses): a blueprint as plain data for planning. */
class BuildApiTest {
	private static PyDict described(String name) throws IOException {
		Blueprint blueprint = Blueprint.parse(JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/antcraft/blueprint/" + name + ".json"))))
			.getOrThrow();
		Identifier id = Identifier.fromNamespaceAndPath("antcraft", name);
		return BuildApi.describe(blueprint, BlueprintSummary.of(id, blueprint, Optional.of(VanillaData.structure(blueprint.structure().getPath()))));
	}

	@Test
	void blueprintGivesSizeRoleMaterialsAndSpots() throws IOException {
		PyDict step = described("cobble_step");
		assertEquals(PyTuple.of(2L, 2L, 1L), step.get("size"));
		assertEquals(PyNone.NONE, step.get("role"));
		PyDict materials = (PyDict) step.get("materials");
		assertEquals(List.of("minecraft:cobblestone", "minecraft:oak_stairs"), materials.keys());
		assertEquals(2L, materials.get("minecraft:cobblestone"));
		assertEquals(1L, materials.get("minecraft:oak_stairs"));
		assertEquals(List.of(), ((PyList) step.get("chests")).items);
		assertEquals(List.of(), ((PyList) step.get("work_spots")).items);

		PyDict generator = described("cobble_generator");
		assertEquals("generator", generator.get("role"));
		assertEquals(List.of(new Pos(3, 1, 1), new Pos(3, 1, 2)), ((PyList) generator.get("work_spots")).items);

		PyDict warehouse = described("warehouse");
		assertEquals("storage", warehouse.get("role"));
		List<Object> chests = ((PyList) warehouse.get("chests")).items;
		assertEquals(10, chests.size());
		assertEquals(new Pos(4, 0, 2), chests.getLast());
	}
}
