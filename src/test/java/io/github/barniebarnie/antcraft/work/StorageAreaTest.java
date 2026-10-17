package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
