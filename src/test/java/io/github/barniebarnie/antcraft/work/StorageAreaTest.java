package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StorageAreaTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

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

	/** A floor at y=0 with a stone bump on every other block, except one bump only in the area from (4,1,-1) to (8,1,1). */
	private static Function<BlockPos, BlockState> bumpy() {
		BoundingBox smooth = new BoundingBox(4, 1, -1, 8, 1, 1);
		return pos -> pos.getY() <= 0 || pos.getY() == 1 && (smooth.isInside(pos) ? pos.equals(new BlockPos(6, 1, 0)) : Math.floorMod(pos.getX() + pos.getZ(), 2) == 0)
			? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
	}

	@Test
	void withNoFlatSpotTheColonyLevelsTheOneNeedingTheLeastWork() {
		Function<BlockPos, BlockState> world = bumpy();
		assertEquals(Optional.empty(), StorageArea.choose(CORE, 10, List.of(), pos -> world.apply(pos).is(Blocks.STONE), pos -> world.apply(pos).isAir()));

		BuildPlan plan = StorageArea.levelling(CORE, 10, List.of(), world, pos -> true).orElseThrow();
		assertEquals(BuildPlan.Kind.TERRAFORM, plan.kind());
		assertEquals(List.of(BuildPlan.Step.clear(new BlockPos(6, 1, 0))), plan.steps());
	}

	@Test
	void levelsNoSpotWithBlocksTheBotsCannotWork() {
		assertEquals(Optional.empty(), StorageArea.levelling(CORE, 10, List.of(), bumpy(), pos -> false));
	}

	/** Other sites take every column a storage area within 8 blocks of the core would use. */
	private static final BoundingBox TAKEN = new BoundingBox(-10, -5, -10, 10, 5, 10);

	@Test
	void aSiteIsFoundFurtherOutWhenTheGroundNearTheCoreIsTaken() {
		StorageArea area = StorageArea.site(CORE, 32, List.of(TAKEN), bumpy(), pos -> true).orElseThrow();

		assertFalse(Sites.sharesColumns(area.area(), TAKEN), "area at " + area.area());
		assertTrue(area.area().maxX() <= 32 && area.area().minX() >= -32 && area.area().maxZ() <= 32 && area.area().minZ() >= -32, area.area().toString());
	}

	@Test
	void aSpotToLevelIsFoundFurtherOutWhenTheGroundNearTheCoreIsTaken() {
		BuildPlan plan = StorageArea.levelling(CORE, 32, List.of(TAKEN), bumpy(), pos -> true).orElseThrow();

		assertTrue(plan.steps().stream().allMatch(step -> !TAKEN.isInside(step.pos().atY(0))), plan.steps().toString());
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
