package io.github.barniebarnie.antcraft.work;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
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
 * Where haulers keep the colony's bulk storage: the chest spots and the box they stand in. A storage area the colony
 * picks itself ({@link #StorageArea(BlockPos)}) is two rows of chests with an aisle between them, from the lowest corner
 * of its chest layer; a built warehouse's ({@link #of}) holds the chest spots of its blueprint, as built.
 *
 * @param area the chest layer and the headroom above it
 * @param chestSpots where chests go, in the order haulers fill them
 */
public record StorageArea(BoundingBox area, List<BlockPos> chestSpots) {
	private static final int WIDTH = 5;
	private static final int DEPTH = 3;

	private static final Codec<StorageArea> SPOTS_CODEC = RecordCodecBuilder.create(instance -> instance.group(
		BoundingBox.CODEC.fieldOf("area").forGetter(StorageArea::area),
		BlockPos.CODEC.listOf().fieldOf("chests").forGetter(StorageArea::chestSpots)
	).apply(instance, StorageArea::new));
	/**
	 * The fixed layout is saved as its lowest corner only, as before warehouses, so older versions still read it; other
	 * storage areas are saved with their box and chest spots.
	 */
	public static final Codec<StorageArea> CODEC = Codec.either(BlockPos.CODEC, SPOTS_CODEC).xmap(
		either -> either.map(StorageArea::new, Function.identity()),
		storage -> storage.equals(new StorageArea(storage.origin())) ? Either.left(storage.origin()) : Either.right(storage));

	public StorageArea {
		chestSpots = chestSpots.stream().map(BlockPos::immutable).toList();
	}

	/** The fixed layout of two rows of chests with an aisle, from {@code origin}, the lowest corner of the chest layer. */
	public StorageArea(BlockPos origin) {
		this(BoundingBox.fromCorners(origin, origin.offset(WIDTH - 1, 1, DEPTH - 1)), layout(origin.immutable()));
	}

	/** The storage of chests at {@code chestSpots}, such as a warehouse's, which fills {@code area}. */
	public static StorageArea of(BoundingBox area, List<BlockPos> chestSpots) {
		return new StorageArea(area, chestSpots);
	}

	private BlockPos origin() {
		return new BlockPos(this.area.minX(), this.area.minY(), this.area.minZ());
	}

	/** The chest spots of the fixed layout, nearest the origin first. */
	private static List<BlockPos> layout(BlockPos origin) {
		List<BlockPos> spots = new ArrayList<>();
		for (int z = 0; z < DEPTH; z += DEPTH - 1) {
			for (int x = 0; x < WIDTH; x++) {
				spots.add(origin.offset(x, 0, z));
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
				BlockPos from = storage.origin().below();
				BlockPos to = storage.origin().offset(WIDTH - 1, 1, DEPTH - 1);
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
		Terraform.plan(world, this.origin().below(), this.origin().offset(WIDTH - 1, 1, DEPTH - 1), this.origin().getY() - 1).ifPresent(level -> {
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
				for (BlockPos pos : BlockPos.betweenClosed(storage.origin().below(), storage.origin().offset(WIDTH - 1, 1, DEPTH - 1))) {
					if (!workable.test(pos)) {
						return OptionalInt.empty();
					}
				}
				return OptionalInt.of(storage.plan(world).map(plan -> plan.steps().size()).orElse(0));
			});
	}

	private boolean isFlat(Predicate<BlockPos> isFloor, Predicate<BlockPos> isOpen) {
		for (BlockPos pos : BlockPos.betweenClosed(this.origin(), this.origin().offset(WIDTH - 1, 0, DEPTH - 1))) {
			if (!isFloor.test(pos.below()) || !isOpen.test(pos) || !isOpen.test(pos.above())) {
				return false;
			}
		}
		return true;
	}
}
