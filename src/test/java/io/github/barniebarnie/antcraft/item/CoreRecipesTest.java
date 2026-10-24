package io.github.barniebarnie.antcraft.item;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Checks the shaped recipes of the Colony Core and the Bot Core, which take copper rather than redstone. */
class CoreRecipesTest {
	private static final Path RECIPES = Path.of("src/main/resources/data/antcraft/recipe");

	@Test
	void colonyCoreTakesCopperBlocksOnTheSides() throws IOException {
		assertEquals(List.of(
				"minecraft:iron_ingot", "minecraft:copper_block", "minecraft:iron_ingot",
				"minecraft:copper_block", "minecraft:chest", "minecraft:copper_block",
				"minecraft:iron_ingot", "minecraft:copper_block", "minecraft:iron_ingot"),
				grid("colony_core.json"));
	}

	@Test
	void botCoreTakesACopperBlockInTheMiddle() throws IOException {
		assertEquals(List.of(
				"", "minecraft:carved_pumpkin", "",
				"minecraft:iron_ingot", "minecraft:copper_block", "minecraft:iron_ingot",
				"", "minecraft:iron_ingot", ""),
				grid("bot_core.json"));
	}

	/** The item in each slot of a 3x3 shaped recipe, row by row, with "" for an empty slot. */
	private static List<String> grid(String file) throws IOException {
		JsonObject recipe = JsonParser.parseString(Files.readString(RECIPES.resolve(file))).getAsJsonObject();
		assertEquals("minecraft:crafting_shaped", recipe.get("type").getAsString());
		Map<Character, String> key = new HashMap<>();
		recipe.getAsJsonObject("key").entrySet().forEach(e -> key.put(e.getKey().charAt(0), e.getValue().getAsString()));
		JsonArray pattern = recipe.getAsJsonArray("pattern");
		return pattern.asList().stream()
				.flatMap(row -> row.getAsString().chars().mapToObj(c -> key.getOrDefault((char) c, "")))
				.toList();
	}
}
