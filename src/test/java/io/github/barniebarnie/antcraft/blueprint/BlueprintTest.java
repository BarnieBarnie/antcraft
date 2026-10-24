package io.github.barniebarnie.antcraft.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.RegionPlan;
import io.github.barniebarnie.antcraft.work.VanillaData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlueprintTest {
	private static final Path BLUEPRINTS = Path.of("src/main/resources/data/antcraft/blueprint");
	private static final Path LANG = Path.of("src/main/resources/assets/antcraft/lang/en_us.json");

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static DataResult<Blueprint> parse(String json) {
		return Blueprint.parse(JsonParser.parseString(json));
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath("antcraft", path);
	}

	@Test
	void parsesEveryField() {
		Blueprint blueprint = parse("""
			{
				"structure": "antcraft:farm_plot",
				"name": "blueprint.antcraft.farm_plot",
				"role": "farm",
				"chests": [[0, 1, 0]],
				"work_spots": [[1, 1, 2], [2, 1, 2]]
			}
			""").getOrThrow();
		assertEquals(id("farm_plot"), blueprint.structure());
		assertEquals("blueprint.antcraft.farm_plot", blueprint.name());
		assertEquals(Optional.of(Blueprint.Role.FARM), blueprint.role());
		assertEquals(List.of(new BlockPos(0, 1, 0)), blueprint.chests());
		assertEquals(List.of(new BlockPos(1, 1, 2), new BlockPos(2, 1, 2)), blueprint.workSpots());
	}

	@Test
	void optionalFieldsDefaultToNone() {
		Blueprint blueprint = parse("""
			{"structure": "antcraft:cobble_step", "name": "blueprint.antcraft.cobble_step"}
			""").getOrThrow();
		assertEquals(Optional.empty(), blueprint.role());
		assertEquals(List.of(), blueprint.chests());
		assertEquals(List.of(), blueprint.workSpots());
	}

	@Test
	void everyRoleParses() {
		assertEquals(List.of("storage", "farm", "generator"), Stream.of(Blueprint.Role.values()).map(Blueprint.Role::getSerializedName).toList());
		for (Blueprint.Role role : Blueprint.Role.values()) {
			Blueprint blueprint = parse("{\"structure\": \"antcraft:x\", \"name\": \"n\", \"role\": \"" + role.getSerializedName() + "\"}").getOrThrow();
			assertEquals(Optional.of(role), blueprint.role());
		}
	}

	@Test
	void unknownRoleFailsNamingTheRole() {
		DataResult<Blueprint> result = parse("""
			{"structure": "antcraft:cobble_step", "name": "n", "role": "castle"}
			""");
		assertTrue(result.isError());
		String message = result.error().orElseThrow().message();
		assertTrue(message.contains("castle"), message);
	}

	@Test
	void missingStructureFailsNamingTheField() {
		DataResult<Blueprint> result = parse("""
			{"name": "blueprint.antcraft.cobble_step"}
			""");
		assertTrue(result.isError());
		String message = result.error().orElseThrow().message();
		assertTrue(message.contains("structure"), message);
	}

	@Test
	void missingNameFails() {
		assertTrue(parse("{\"structure\": \"antcraft:cobble_step\"}").isError());
	}

	@Test
	void encodesBackToTheSameBlueprint() {
		Blueprint blueprint = new Blueprint(id("x"), "n", Optional.of(Blueprint.Role.GENERATOR),
			List.of(new BlockPos(1, 0, 0)), List.of(new BlockPos(0, 0, 2)));
		JsonElement json = Blueprint.CODEC.encodeStart(JsonOps.INSTANCE, blueprint).getOrThrow();
		assertTrue(json.getAsJsonObject().has("work_spots"), json.toString());
		assertEquals(blueprint, Blueprint.parse(json).getOrThrow());
	}

	private static ListTag ints(int... values) {
		ListTag list = new ListTag();
		for (int value : values) {
			list.add(IntTag.valueOf(value));
		}
		return list;
	}

	/** A template of {@code size} filled with stone, with dirt at {@code marker}. */
	private static StructureTemplate markedTemplate(Vec3i size, BlockPos marker) {
		ListTag palette = new ListTag();
		palette.add(NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
		palette.add(NbtUtils.writeBlockState(Blocks.DIRT.defaultBlockState()));
		ListTag blocks = new ListTag();
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(size.getX() - 1, size.getY() - 1, size.getZ() - 1))) {
			CompoundTag block = new CompoundTag();
			block.put("pos", ints(pos.getX(), pos.getY(), pos.getZ()));
			block.putInt("state", pos.equals(marker) ? 1 : 0);
			blocks.add(block);
		}
		CompoundTag tag = new CompoundTag();
		tag.put("size", ints(size.getX(), size.getY(), size.getZ()));
		tag.put("palette", palette);
		tag.put("blocks", blocks);
		tag.put("entities", new ListTag());
		StructureTemplate template = new StructureTemplate();
		template.load(BuiltInRegistries.BLOCK, tag);
		return template;
	}

	/** Where the build of {@code template}, placed like a structure block would, puts its one dirt block. */
	private static BlockPos dirtIn(StructureTemplate template, BlockPos at, Mirror mirror, Rotation rotation) {
		BuildPlan plan = RegionPlan.load(template, at, mirror, rotation, 0).orElseThrow();
		List<BlockPos> dirt = plan.steps().stream().filter(step -> step.state().is(Blocks.DIRT)).map(BuildPlan.Step::pos).toList();
		assertEquals(1, dirt.size());
		return dirt.getFirst();
	}

	@Test
	void spotsTurnAndMirrorWithTheStructure() {
		Vec3i size = new Vec3i(3, 2, 5);
		BlockPos at = new BlockPos(10, 64, -7);
		List<BlockPos> spots = List.of(new BlockPos(0, 0, 0), new BlockPos(2, 1, 4), new BlockPos(1, 0, 3), new BlockPos(2, 0, 1));
		Blueprint blueprint = new Blueprint(id("x"), "n", Optional.empty(), spots, spots.reversed());
		for (Mirror mirror : Mirror.values()) {
			for (Rotation rotation : Rotation.values()) {
				List<BlockPos> chests = blueprint.chestsAt(size, at, mirror, rotation);
				List<BlockPos> workSpots = blueprint.workSpotsAt(size, at, mirror, rotation);
				for (int i = 0; i < spots.size(); i++) {
					BlockPos expected = dirtIn(markedTemplate(size, spots.get(i)), at, mirror, rotation);
					assertEquals(expected, chests.get(i), mirror + " " + rotation + " chest " + spots.get(i));
					assertEquals(expected, workSpots.get(spots.size() - 1 - i), mirror + " " + rotation + " work spot " + spots.get(i));
				}
			}
		}
	}

	@Test
	void bundledBlueprintsParseAndPointAtBundledStructuresAndLangKeys() throws IOException {
		Set<String> lang = JsonParser.parseString(Files.readString(LANG)).getAsJsonObject().keySet();
		List<String> files = new ArrayList<>();
		try (Stream<Path> listing = Files.list(BLUEPRINTS)) {
			for (Path file : listing.sorted().toList()) {
				Blueprint blueprint = Blueprint.parse(JsonParser.parseString(Files.readString(file))).getOrThrow();
				assertEquals("antcraft", blueprint.structure().getNamespace(), file.toString());
				VanillaData.structure(blueprint.structure().getPath());
				assertTrue(lang.contains(blueprint.name()), "no lang key " + blueprint.name() + " for " + file);
				files.add(file.getFileName().toString());
			}
		}
		assertTrue(files.containsAll(List.of("starter_house.json", "cobble_step.json", "warehouse.json", "farm.json")), files.toString());
	}

	@Test
	void registryLooksUpAndListsWhatWasLoadedLast() {
		Blueprint step = parse("{\"structure\": \"antcraft:cobble_step\", \"name\": \"n\"}").getOrThrow();
		Blueprint house = parse("{\"structure\": \"antcraft:starter_house\", \"name\": \"m\"}").getOrThrow();
		Map<Identifier, Blueprint> before = Blueprints.all();
		try {
			Map<Identifier, Blueprint> loaded = new LinkedHashMap<>();
			loaded.put(id("starter_house"), house);
			loaded.put(id("cobble_step"), step);
			Blueprints.set(loaded);
			assertEquals(Optional.of(step), Blueprints.get(id("cobble_step")));
			assertEquals(Optional.empty(), Blueprints.get(id("nope")));
			assertEquals(List.of(id("cobble_step"), id("starter_house")), Blueprints.ids());
			Blueprints.set(Map.of(id("starter_house"), house));
			assertFalse(Blueprints.get(id("cobble_step")).isPresent());
		} finally {
			Blueprints.set(before);
		}
	}
}
