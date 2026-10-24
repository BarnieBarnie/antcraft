package io.github.barniebarnie.antcraft.colony;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.StreamSupport;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;

/**
 * How many of something the colony wants in its core. {@code match} is one item or an
 * item tag ("any pickaxe"); {@code make} lists what to craft when short, in order of
 * preference, and is empty to mean "whatever matches".
 */
public record StockTarget(Either<TagKey<Item>, Item> match, int count, List<Item> make) {
	public static final Codec<Either<TagKey<Item>, Item>> MATCH_CODEC = Codec.either(TagKey.hashedCodec(Registries.ITEM), BuiltInRegistries.ITEM.byNameCodec());

	public static final Codec<StockTarget> CODEC = RecordCodecBuilder.create(i -> i.group(
		MATCH_CODEC.fieldOf("match").forGetter(StockTarget::match),
		Codec.INT.fieldOf("count").forGetter(StockTarget::count),
		BuiltInRegistries.ITEM.byNameCodec().listOf().optionalFieldOf("make", List.of()).forGetter(StockTarget::make)
	).apply(i, StockTarget::new));

	public StockTarget {
		make = List.copyOf(make);
	}

	public static StockTarget of(Item item, int count) {
		return new StockTarget(Either.right(item), count, List.of());
	}

	public static StockTarget of(TagKey<Item> tag, int count, Item... make) {
		return new StockTarget(Either.left(tag), count, List.of(make));
	}

	public StockTarget withCount(int count) {
		return new StockTarget(this.match, count, this.make);
	}

	public boolean matches(Item item) {
		return this.match.map(tag -> item.builtInRegistryHolder().is(tag), item::equals);
	}

	/**
	 * What to craft when short: the listed items, else the item itself or everything in the tag. Like
	 * {@link #options()}, a tag with no listed items needs the item tags bound.
	 */
	public List<Item> makeOptions() {
		if (!this.make.isEmpty()) {
			return this.make;
		}
		return this.match.map(
			tag -> StreamSupport.stream(BuiltInRegistries.ITEM.getTagOrEmpty(tag).spliterator(), false).map(Holder::value).toList(),
			List::of);
	}

	/**
	 * Every item that could meet the target: the {@linkplain #makeOptions make options} first, then whatever
	 * else it matches, such as carrots for a food target that makes bread. For a tag this needs the item tags
	 * bound, or it throws "Tags not bound": in unit tests, call {@code VanillaData.bindTags()} first.
	 */
	public List<Item> options() {
		List<Item> options = new ArrayList<>(this.makeOptions());
		this.match.ifLeft(tag -> BuiltInRegistries.ITEM.getTagOrEmpty(tag).forEach(holder -> {
			if (!options.contains(holder.value())) {
				options.add(holder.value());
			}
		}));
		return options;
	}

	/** {@code #namespace:tag} or {@code namespace:item}, as typed in commands. */
	public String name() {
		return name(this.match);
	}

	public static String name(Either<TagKey<Item>, Item> match) {
		return match.map(tag -> "#" + tag.location(), item -> BuiltInRegistries.ITEM.getKey(item).toString());
	}

	/**
	 * The target as {@link StockPlanner} plans it. When none of the make options can be made now, it also orders
	 * any other food it matches, such as baked potatoes for a food target that makes bread, the best food first.
	 * Other items it matches are not ordered, so a pickaxe target that lists no golden pickaxe never spends gold on one.
	 * A target that lists nothing to make orders the food its tag holds best first too, anything else in tag order.
	 * Like {@link #options()}, it needs the item tags bound (in unit tests, {@code VanillaData.bindTags()}).
	 */
	public StockPlanner.Target<Item> toPlannerTarget() {
		List<Item> make = this.make.isEmpty() ? bestFoodFirst(this.makeOptions()) : this.make;
		List<Item> otherFood = bestFoodFirst(this.options().stream().filter(item -> !make.contains(item) && item.components().has(DataComponents.FOOD)).toList());
		return new StockPlanner.Target<>(this::matches, make, this.count, otherFood);
	}

	/** {@code items} with the food that fills most (nutrition plus saturation) first, the rest after it in their order. */
	private static List<Item> bestFoodFirst(List<Item> items) {
		return items.stream().sorted(Comparator.comparingDouble(StockTarget::foodValue).reversed()).toList();
	}

	private static double foodValue(Item item) {
		FoodProperties food = item.components().get(DataComponents.FOOD);
		return food == null ? 0 : food.nutrition() + food.saturation();
	}
}
