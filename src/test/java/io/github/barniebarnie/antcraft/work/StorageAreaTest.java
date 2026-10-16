package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

class StorageAreaTest {
	private static final BlockPos CORE = new BlockPos(0, 1, 0);

	/** A flat floor at y=0 with open air above. */
	private static Optional<StorageArea> choose(int radius, List<BoundingBox> avoid) {
		return StorageArea.choose(CORE, radius, avoid, pos -> pos.getY() == 0, pos -> pos.getY() > 0);
	}

	@Test
	void chestsStandOnTheFloorNearTheCore() {
		StorageArea area = choose(10, List.of()).orElseThrow();
		assertTrue(area.chestSpots().stream().allMatch(pos -> pos.getY() == 1));
		assertFalse(area.area().intersects(BoundingBox.fromCorners(CORE.offset(-1, -1, -1), CORE.offset(1, 1, 1))), "too close to the core");
		assertTrue(area.area().getCenter().distManhattan(CORE) <= 6, "area is far away: " + area);
	}

	@Test
	void avoidsOtherAreas() {
		BoundingBox field = new BoundingBox(-10, 0, -10, 10, 2, -1);
		StorageArea area = choose(10, List.of(field)).orElseThrow();
		assertFalse(area.area().intersects(field));
	}

	@Test
	void oneChestSpotPerCategoryAtLeast() {
		assertTrue(new StorageArea(BlockPos.ZERO).chestSpots().size() >= StorageCategory.values().length);
	}

	@Test
	void noneWhenTheTerritoryIsTooSmall() {
		assertEquals(Optional.empty(), choose(2, List.of()));
	}
}
