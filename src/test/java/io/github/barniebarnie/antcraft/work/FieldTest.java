package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FieldTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void emptyAndUntilledBlocksNeedPlantingButPlantedOnesDoNot() {
		Field field = new Field(new BlockPos(5, 0, 5));
		BlockPos planted = new BlockPos(4, 1, 4);

		List<BlockPos> toPlant = field.toPlant(pos -> pos.equals(planted));

		assertEquals(23, toPlant.size());
		assertFalse(toPlant.contains(planted));
		// The middle is kept for water.
		assertFalse(toPlant.contains(field.center().above()));
		assertTrue(toPlant.contains(new BlockPos(3, 1, 3)));
		assertTrue(toPlant.contains(new BlockPos(7, 1, 7)));
	}

	@Test
	void fullyPlantedFieldNeedsNothing() {
		assertTrue(new Field(new BlockPos(5, 0, 5)).toPlant(pos -> true).isEmpty());
	}

	@Test
	void aSavedOldFieldStillLoads() {
		// Saves from before farm blueprints hold only the middle of the fixed 5x5 field.
		Field loaded = Field.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("[4, 63, -7]")).getOrThrow();
		assertEquals(new Field(new BlockPos(4, 63, -7)), loaded);
		assertEquals(new BoundingBox(2, 63, -9, 6, 65, -5), loaded.area());
		assertEquals(Optional.of(new BlockPos(4, 63, -7)), loaded.water());
		assertEquals(24, loaded.plots().size());
		assertFalse(loaded.plots().contains(new BlockPos(4, 63, -7)), "the water is a plot");
		// And it is still saved the old way, so older versions read it.
		assertEquals(JsonParser.parseString("[4, 63, -7]"), Field.CODEC.encodeStart(JsonOps.INSTANCE, loaded).getOrThrow());
	}

	@Test
	void aFieldOfPlotsIsSavedAndLoaded() {
		Field field = Field.of(new BoundingBox(0, 64, 0, 2, 65, 1), List.of(new BlockPos(2, 64, 1), new BlockPos(0, 64, 0)));
		Field loaded = Field.CODEC.parse(JsonOps.INSTANCE, Field.CODEC.encodeStart(JsonOps.INSTANCE, field).getOrThrow()).getOrThrow();
		assertEquals(field, loaded);
		assertEquals(List.of(new BlockPos(2, 64, 1), new BlockPos(0, 64, 0)), loaded.plots());
		assertEquals(Optional.empty(), loaded.water());
	}

	@Test
	void aFieldOfPlotsPlantsAboveEachPlotWithoutACrop() {
		Field field = Field.of(new BoundingBox(0, 64, 0, 2, 65, 1), List.of(new BlockPos(2, 64, 1), new BlockPos(0, 64, 0)));
		assertEquals(List.of(new BlockPos(2, 65, 1), new BlockPos(0, 65, 0)), field.toPlant(pos -> false));
		assertEquals(List.of(new BlockPos(0, 65, 0)), field.toPlant(new BlockPos(2, 65, 1)::equals));
	}
}
