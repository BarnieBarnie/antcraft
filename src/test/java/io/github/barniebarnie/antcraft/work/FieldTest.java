package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

class FieldTest {
	private static final BlockPos CORE = new BlockPos(0, 1, 0);

	/** Flat soil at y=0 over the square of {@code size} around the core; everything above is open. */
	private static Set<BlockPos> flatSoil(int size) {
		Set<BlockPos> soil = new HashSet<>();
		for (BlockPos pos : BlockPos.betweenClosed(-size, 0, -size, size, 0, size)) {
			soil.add(pos.immutable());
		}
		return soil;
	}

	private static Optional<Field> choose(Set<BlockPos> soil, int radius, List<BoundingBox> avoid) {
		return Field.choose(CORE, radius, avoid, soil::contains, pos -> !soil.contains(pos));
	}

	private static boolean overlaps(Field field, BoundingBox box) {
		return field.area().intersects(box);
	}

	@Test
	void picksFlatSoilNearTheCoreInsideTheTerritory() {
		Field field = choose(flatSoil(20), 10, List.of()).orElseThrow();

		assertEquals(0, field.center().getY());
		// Inside the territory.
		assertTrue(Math.abs(field.center().getX()) + Field.RADIUS <= 10 && Math.abs(field.center().getZ()) + Field.RADIUS <= 10);
		// Near the core: right next to the space kept free around it.
		assertTrue(field.center().distManhattan(new BlockPos(0, 0, 0)) <= 5, "field at " + field.center());
	}

	@Test
	void avoidsTheCore() {
		Field field = choose(flatSoil(20), 10, List.of()).orElseThrow();

		assertFalse(field.area().isInside(CORE));
		assertFalse(field.area().isInside(CORE.below()));
	}

	@Test
	void avoidsGivenAreas() {
		BoundingBox quarry = new BoundingBox(-10, -5, -10, 10, 0, 3);
		Field field = choose(flatSoil(20), 10, List.of(quarry)).orElseThrow();

		assertFalse(overlaps(field, quarry), "field at " + field.center());
	}

	@Test
	void needsFlatTillableGround() {
		Set<BlockPos> soil = flatSoil(20);
		// A hole in every 5x5 square: no flat ground anywhere.
		soil.removeIf(pos -> Math.floorMod(pos.getX(), 3) == 0 && Math.floorMod(pos.getZ(), 3) == 0);

		assertTrue(choose(soil, 10, List.of()).isEmpty());
	}

	@Test
	void groundCoveredByBlocksIsNotUsed() {
		Set<BlockPos> soil = flatSoil(20);
		Field field = Field.choose(CORE, 10, List.of(), soil::contains, pos -> !soil.contains(pos) && pos.getX() >= 0).orElseThrow();

		assertTrue(field.center().getX() - Field.RADIUS >= 0, "field at " + field.center());
	}

	@Test
	void staysInsideTheTerritory() {
		assertTrue(choose(flatSoil(20), 3, List.of()).isEmpty());
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
}
