package io.github.barniebarnie.antcraft.work;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * A crop field: the soil blocks (plots) crops are planted on, and the box they lie in. A field an older colony laid out
 * itself ({@link #Field(BlockPos)}, kept for older saves) is a 5x5 square round its middle soil block, which holds water; a built farm's
 * ({@link #of}) holds the plots of its blueprint, as built, and brings its own water.
 *
 * @param area the soil and the layers above it the crops need
 * @param plots the soil blocks crops go on, in the order they are planted
 * @param water where the farmer pours water for the field, if it needs any poured
 */
public record Field(BoundingBox area, List<BlockPos> plots, Optional<BlockPos> water) {
	/** Blocks from the middle to the edge: a 5x5 field, every block within reach of the water. */
	public static final int RADIUS = 2;
	/** What a field yields: a colony that wants some with no field needs a farm. */
	public static final Set<Item> YIELDS = Set.of(Items.WHEAT, Items.WHEAT_SEEDS);

	/** How many blocks above the soil the square field takes: the crop layer and the one above, room to walk and light. */
	private static final int CLEAR_HEIGHT = 2;

	private static final Codec<Field> PLOTS_CODEC = RecordCodecBuilder.create(instance -> instance.group(
		BoundingBox.CODEC.fieldOf("area").forGetter(Field::area),
		BlockPos.CODEC.listOf().fieldOf("plots").forGetter(Field::plots),
		BlockPos.CODEC.optionalFieldOf("water").forGetter(Field::water)
	).apply(instance, Field::new));
	/**
	 * The square field is saved as its middle alone, as before farm blueprints, so older versions still read it; other
	 * fields are saved with their box and plots.
	 */
	public static final Codec<Field> CODEC = Codec.either(BlockPos.CODEC, PLOTS_CODEC).xmap(
		either -> either.map(Field::new, Function.identity()),
		field -> field.equals(new Field(field.center())) ? Either.left(field.center()) : Either.right(field));

	public Field {
		plots = plots.stream().map(BlockPos::immutable).toList();
		water = water.map(BlockPos::immutable);
	}

	/** The square field round {@code center}, the soil block in the middle, which holds water; the soil around it is tilled and planted. */
	public Field(BlockPos center) {
		this(new BoundingBox(center.getX() - RADIUS, center.getY(), center.getZ() - RADIUS,
			center.getX() + RADIUS, center.getY() + CLEAR_HEIGHT, center.getZ() + RADIUS), square(center), Optional.of(center));
	}

	/** The field of {@code plots}, such as a built farm's, which lie in {@code area} and have their water already. */
	public static Field of(BoundingBox area, List<BlockPos> plots) {
		return new Field(area, plots, Optional.empty());
	}

	/** The soil block in the middle of the area: for the square field, where its water goes. */
	public BlockPos center() {
		return new BlockPos((this.area.minX() + this.area.maxX()) / 2, this.area.minY(), (this.area.minZ() + this.area.maxZ()) / 2);
	}

	/** The plots of the square field round {@code center}: every soil block but the middle one. */
	private static List<BlockPos> square(BlockPos center) {
		List<BlockPos> plots = new ArrayList<>();
		for (BlockPos soil : BlockPos.betweenClosed(center.offset(-RADIUS, 0, -RADIUS), center.offset(RADIUS, 0, RADIUS))) {
			if (!soil.equals(center)) {
				plots.add(soil.immutable());
			}
		}
		return plots;
	}

	/** Where crops go (above the plots) that do not have one yet. */
	public List<BlockPos> toPlant(Predicate<BlockPos> hasCrop) {
		return this.plots.stream().map(BlockPos::above).filter(crop -> !hasCrop.test(crop)).toList();
	}
}
