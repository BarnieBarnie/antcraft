package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.barniebarnie.antcraft.blueprint.Blueprint;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.RegionPlan;
import io.github.barniebarnie.antcraft.work.StorageArea;
import io.github.barniebarnie.antcraft.work.StorageCategory;
import io.github.barniebarnie.antcraft.work.VanillaData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The bundled warehouse blueprint, and a built warehouse as the colony's storage. */
class WarehouseTest {
	private static final Identifier WAREHOUSE = Identifier.fromNamespaceAndPath("antcraft", "warehouse");
	private static final BlockPos AT = new BlockPos(6, 64, 6);
	private static Blueprint blueprint;
	private static StructureTemplate structure;

	@BeforeAll
	static void bootstrap() throws IOException {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		blueprint = Blueprint.parse(JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/antcraft/blueprint/warehouse.json"))))
			.getOrThrow();
		structure = VanillaData.structure(blueprint.structure().getPath());
	}

	private static Colony colony() {
		return new Colony(UUID.randomUUID(), new BlockPos(0, 64, 0), List.of(), new JobBoard(), Colony.DEFAULT_RADIUS, Map.of(),
			Optional.empty(), List.of(), Map.of(), List.of(), Optional.empty(), Optional.empty(), Map.of(), List.of(), Map.of());
	}

	private static BuildPlan plan(BlockPos at, Mirror mirror, Rotation rotation) {
		return RegionPlan.load(structure, at, mirror, rotation, 0).orElseThrow();
	}

	/** Where the warehouse built at {@code at} puts its chests. */
	private static List<BlockPos> chestsBuilt(BlockPos at, Mirror mirror, Rotation rotation) {
		return plan(at, mirror, rotation).steps().stream().filter(step -> step.state().is(Blocks.CHEST)).map(BuildPlan.Step::pos).toList();
	}

	/** Places the warehouse for the colony to build, without levelling. */
	private static PlacedBlueprint place(Colony colony, BlockPos at, Mirror mirror, Rotation rotation) {
		PlacedBlueprint site = BlueprintPlacement.site(colony, WAREHOUSE, Optional.of(blueprint), Optional.of(structure.getSize()), at, rotation, mirror)
			.right().orElseThrow();
		return colony.placeBlueprint(site, Optional.empty(), plan(at, mirror, rotation));
	}

	/** Finishes every open build batch until none is left. */
	private static void finishAll(Colony colony) {
		for (int round = 0; round < 50 && !colony.builds().isEmpty(); round++) {
			colony.postBuildJobs();
			colony.board().jobs().stream().filter(job -> job.isOpen() && colony.build(job.id()).isPresent()).toList().forEach(job -> {
				colony.finishBatch(job.id());
				colony.board().complete(job.id());
			});
		}
	}

	@Test
	void theBundledWarehouseIsAStorageBuildingWithAChestSpotOnEveryChest() {
		assertEquals(Optional.of(Blueprint.Role.STORAGE), blueprint.role());
		assertTrue(blueprint.chests().size() >= StorageCategory.values().length, "too few chests: " + blueprint.chests());
		for (Mirror mirror : Mirror.values()) {
			for (Rotation rotation : Rotation.values()) {
				List<BlockPos> spots = blueprint.chestsAt(structure.getSize(), AT, mirror, rotation);
				assertEquals(new HashSet<>(chestsBuilt(AT, mirror, rotation)), new HashSet<>(spots), mirror + " " + rotation);
				assertEquals(spots.size(), new HashSet<>(spots).size(), "a chest spot twice");
			}
		}
	}

	@Test
	void aBuiltWarehouseTurnedAndMirroredIsTheColonyStorageInPlaceOfThePickedArea() {
		for (Mirror mirror : Mirror.values()) {
			for (Rotation rotation : Rotation.values()) {
				Colony colony = colony();
				StorageArea picked = new StorageArea(new BlockPos(-8, 64, -8));
				colony.setStorage(picked);
				PlacedBlueprint placed = place(colony, AT, mirror, rotation);
				assertEquals(Optional.of(picked), colony.storage(), "while the warehouse is being built");

				finishAll(colony);
				StorageArea storage = colony.storage().orElseThrow();
				assertEquals(blueprint.chestsAt(structure.getSize(), AT, mirror, rotation), storage.chestSpots(), mirror + " " + rotation);
				assertEquals(new HashSet<>(chestsBuilt(AT, mirror, rotation)), new HashSet<>(storage.chestSpots()), mirror + " " + rotation);
				assertEquals(placed.area(), storage.area());
			}
		}
	}

	@Test
	void theColonyStoresInTheChestsOfEveryBuiltWarehouseOldestFirst() {
		Colony colony = colony();
		place(colony, AT, Mirror.NONE, Rotation.NONE);
		BlockPos second = AT.offset(0, 0, -8);
		place(colony, second, Mirror.NONE, Rotation.CLOCKWISE_180);
		finishAll(colony);

		List<BlockPos> expected = new ArrayList<>(blueprint.chestsAt(structure.getSize(), AT, Mirror.NONE, Rotation.NONE));
		expected.addAll(blueprint.chestsAt(structure.getSize(), second, Mirror.NONE, Rotation.CLOCKWISE_180));
		assertEquals(expected, colony.storage().orElseThrow().chestSpots());
	}

	@Test
	void aColonyWithABuiltWarehouseStillStoresThereOnceLoaded() {
		Colony colony = colony();
		place(colony, AT, Mirror.NONE, Rotation.CLOCKWISE_90);
		finishAll(colony);
		Colony loaded = Colony.CODEC.parse(JsonOps.INSTANCE, Colony.CODEC.encodeStart(JsonOps.INSTANCE, colony).getOrThrow()).getOrThrow();
		assertEquals(colony.storage(), loaded.storage());
		assertTrue(loaded.storage().isPresent());
	}
}
