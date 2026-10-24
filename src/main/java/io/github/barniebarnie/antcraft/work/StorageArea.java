package io.github.barniebarnie.antcraft.work;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Where haulers keep the colony's bulk storage: the chest spots and the box they stand in. A storage area an older colony
 * picked itself ({@link #StorageArea(BlockPos)}, kept for older saves) is two rows of chests with an aisle between them, from the lowest corner
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
}
