package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StorageAreaTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void oneChestSpotPerCategoryAtLeast() {
		assertTrue(new StorageArea(BlockPos.ZERO).chestSpots().size() >= StorageCategory.values().length);
	}

	@Test
	void aSavedOldStorageAreaStillLoads() {
		// Saves from before warehouses hold only the lowest corner of the fixed layout.
		StorageArea loaded = StorageArea.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("[3, 64, -2]")).getOrThrow();
		assertEquals(new StorageArea(new BlockPos(3, 64, -2)), loaded);
		assertEquals(new BlockPos(3, 64, -2), loaded.chestSpots().getFirst());
		assertEquals(new BoundingBox(3, 64, -2, 7, 65, 0), loaded.area());
	}

	@Test
	void aStorageAreaOfChestSpotsIsSavedAndLoaded() {
		StorageArea area = StorageArea.of(new BoundingBox(0, 64, 0, 3, 65, 2), List.of(new BlockPos(3, 64, 2), new BlockPos(0, 64, 0)));
		StorageArea loaded = StorageArea.CODEC.parse(JsonOps.INSTANCE, StorageArea.CODEC.encodeStart(JsonOps.INSTANCE, area).getOrThrow()).getOrThrow();
		assertEquals(area, loaded);
		assertEquals(List.of(new BlockPos(3, 64, 2), new BlockPos(0, 64, 0)), loaded.chestSpots());
	}
}
