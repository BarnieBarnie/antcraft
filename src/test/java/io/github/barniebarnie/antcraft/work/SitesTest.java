package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

class SitesTest {
	private static final BlockPos CORE = new BlockPos(0, 64, 0);

	@Test
	void aBoxIsNotPutAboveAnAreaToAvoid() {
		// A field at the core's level beside it; only boxes two blocks up, above the field's top, fit.
		BoundingBox field = new BoundingBox(2, 63, -2, 6, 65, 2);

		BoundingBox box = Sites.box(CORE, 10, List.of(field), new Vec3i(3, 3, 3), pos -> pos.getY() >= 66).orElseThrow();

		assertFalse(Sites.sharesColumns(box, field), box + " shares columns with the field " + field);
	}
}
