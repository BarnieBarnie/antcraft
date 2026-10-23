package io.github.barniebarnie.antcraft.work;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Where haulers keep the colony's bulk storage: two rows of chests with an aisle between them.
 * {@code origin} is the lowest corner of the chest layer.
 */
public record StorageArea(BlockPos origin) {
	private static final int WIDTH = 5;
	private static final int DEPTH = 3;

	public static final Codec<StorageArea> CODEC = BlockPos.CODEC.xmap(StorageArea::new, StorageArea::origin);

	public StorageArea {
		origin = origin.immutable();
	}

	/** The chest layer and the headroom above it. */
	public BoundingBox area() {
		return BoundingBox.fromCorners(this.origin, this.origin.offset(WIDTH - 1, 1, DEPTH - 1));
	}

	/** Where chests go, nearest the origin first. */
	public List<BlockPos> chestSpots() {
		List<BlockPos> spots = new ArrayList<>();
		for (int z = 0; z < DEPTH; z += DEPTH - 1) {
			for (int x = 0; x < WIDTH; x++) {
				spots.add(this.origin.offset(x, 0, z));
			}
		}
		return spots;
	}

	/**
	 * The area nearest the core inside the territory, on flat ground and clear of the core and of the
	 * {@code avoid} areas (such as the field).
	 *
	 * @param isFloor whether a chest can stand on the block
	 * @param isOpen whether the block is free for a chest or for walking
	 */
	public static Optional<StorageArea> choose(BlockPos core, int radius, List<BoundingBox> avoid, Predicate<BlockPos> isFloor, Predicate<BlockPos> isOpen) {
		return Sites.nearest(core, radius - WIDTH / 2, avoid, pos -> new StorageArea(pos.offset(-WIDTH / 2, 0, -DEPTH / 2)), StorageArea::area,
			storage -> storage.isFlat(isFloor, isOpen));
	}

	/**
	 * For when {@link #choose} finds no flat spot: the plan that levels the area near the core needing
	 * the fewest steps, at the core's level (see {@link Terraform}). Its floor becomes solid and its
	 * chest layer and headroom are cleared. Only areas whose every block {@code workable} allows,
	 * such as no fluid, unbreakable block or container, are levelled.
	 */
	public static Optional<BuildPlan> levelling(BlockPos core, int radius, List<BoundingBox> avoid, Function<BlockPos, BlockState> world,
			Predicate<BlockPos> workable) {
		Map<StorageArea, BuildPlan> plans = new HashMap<>();
		Optional<StorageArea> site = Sites.cheapest(core, radius - WIDTH / 2, avoid,
			pos -> new StorageArea(pos.offset(-WIDTH / 2, 0, -DEPTH / 2)), StorageArea::area, storage -> {
				BlockPos from = storage.origin.below();
				BlockPos to = storage.origin.offset(WIDTH - 1, 1, DEPTH - 1);
				for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
					if (!workable.test(pos)) {
						return OptionalInt.empty();
					}
				}
				Optional<BuildPlan> plan = Terraform.plan(world, from, to, from.getY());
				plan.ifPresent(p -> plans.put(storage, p));
				return plan.map(p -> OptionalInt.of(p.steps().size())).orElse(OptionalInt.empty());
			});
		return site.map(plans::get);
	}

	/**
	 * The plan that sets the area up: levelled at its floor (see {@link Terraform}), then the first chest placed.
	 * Empty if both are done.
	 */
	public Optional<BuildPlan> plan(Function<BlockPos, BlockState> world) {
		List<BuildPlan.Step> steps = new ArrayList<>();
		List<BuildPlan.Batch> batches = new ArrayList<>();
		Terraform.plan(world, this.origin.below(), this.origin.offset(WIDTH - 1, 1, DEPTH - 1), this.origin.getY() - 1).ifPresent(level -> {
			steps.addAll(level.steps());
			batches.addAll(level.batches());
		});
		BuildPlan.Step chest = new BuildPlan.Step(this.chestSpots().getFirst(), Blocks.CHEST.defaultBlockState());
		if (!chest.isDone(world.apply(chest.pos()))) {
			batches.add(new BuildPlan.Batch(steps.size(), steps.size() + 1, IntStream.range(0, batches.size()).boxed().toList()));
			steps.add(chest);
		}
		return steps.isEmpty() ? Optional.empty() : Optional.of(new BuildPlan(steps, batches));
	}

	/**
	 * Where a new colony sets up its storage: at the core's level near it, clear of the core and of the {@code avoid}
	 * areas, with every block {@code workable}, needing the smallest {@link #plan}, the nearest of those.
	 */
	public static Optional<StorageArea> site(BlockPos core, int radius, List<BoundingBox> avoid, Function<BlockPos, BlockState> world,
			Predicate<BlockPos> workable) {
		return Sites.cheapest(core, radius - WIDTH / 2, avoid, pos -> new StorageArea(pos.offset(-WIDTH / 2, 0, -DEPTH / 2)),
			StorageArea::area, storage -> {
				for (BlockPos pos : BlockPos.betweenClosed(storage.origin.below(), storage.origin.offset(WIDTH - 1, 1, DEPTH - 1))) {
					if (!workable.test(pos)) {
						return OptionalInt.empty();
					}
				}
				return OptionalInt.of(storage.plan(world).map(plan -> plan.steps().size()).orElse(0));
			});
	}

	private boolean isFlat(Predicate<BlockPos> isFloor, Predicate<BlockPos> isOpen) {
		for (BlockPos pos : BlockPos.betweenClosed(this.origin, this.origin.offset(WIDTH - 1, 0, DEPTH - 1))) {
			if (!isFloor.test(pos.below()) || !isOpen.test(pos) || !isOpen.test(pos.above())) {
				return false;
			}
		}
		return true;
	}
}
