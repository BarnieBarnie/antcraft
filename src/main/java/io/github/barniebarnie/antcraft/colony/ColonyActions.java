package io.github.barniebarnie.antcraft.colony;

import com.mojang.datafixers.util.Either;
import io.github.barniebarnie.antcraft.command.AntCraftCommands;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Changes a player makes to a colony, from {@code /antcraft colony} commands or the colony screen. The screen's
 * requests come from clients, so everything here checks its input and leaves the colony alone when it is wrong.
 */
public final class ColonyActions {
	/** Most items an order or stock target may ask for. */
	public static final int MAX_COUNT = 4096;
	public static final int MIN_RADIUS = 4;
	public static final int MAX_RADIUS = 128;

	/** What the colony screen can change, besides roles. */
	public enum Edit {
		/** Order {@code count} of an item. */
		ORDER,
		/** Cancel the player's part of an order; the colony's own part stays. */
		CANCEL,
		/** Set the stock target for an item or {@code #tag}. */
		SET_STOCK,
		REMOVE_STOCK,
		/** Set the radius to {@code count}. */
		RADIUS
	}

	private ColonyActions() {
	}

	/**
	 * Whether a player at {@code from} may change {@code colony}: anyone in range, or only operators if
	 * {@code requireOp}. Colonies have no owners yet.
	 */
	public static boolean mayAct(Colony colony, BlockPos from, boolean isOp, boolean requireOp) {
		return (isOp || !requireOp) && colony.core().closerThan(from, AntCraftCommands.COLONY_RANGE);
	}

	/** Whether the bot with id {@code bot}, which says it belongs to {@code botColony}, is a member of {@code colony}. */
	public static boolean isMember(Colony colony, UUID bot, @Nullable UUID botColony) {
		return colony.id().equals(botColony) && colony.members().contains(bot);
	}

	/**
	 * Applies an edit from the colony screen. {@code target} is an item id, or for stock targets also a {@code #tag}.
	 *
	 * @return false, leaving the colony unchanged, if the target or count is invalid
	 */
	public static boolean edit(Colony colony, Edit edit, String target, int count) {
		boolean countOk = count >= 1 && count <= MAX_COUNT;
		return switch (edit) {
			case ORDER -> countOk && item(target).map(item -> {
				colony.addOrder(item, count);
				return true;
			}).orElse(false);
			case CANCEL -> item(target).map(item -> {
				colony.cancelPlayerOrder(item);
				return true;
			}).orElse(false);
			case SET_STOCK -> countOk && match(target).map(match -> {
				setStock(colony, match, count);
				return true;
			}).orElse(false);
			case REMOVE_STOCK -> match(target).map(colony::removeStockTarget).orElse(false);
			case RADIUS -> {
				if (count < MIN_RADIUS || count > MAX_RADIUS) {
					yield false;
				}
				colony.setRadius(count);
				yield true;
			}
		};
	}

	/** Sets the stock target for {@code match}, keeping what an existing target prefers to make, such as stone before wooden pickaxes. */
	public static StockTarget setStock(Colony colony, Either<TagKey<Item>, Item> match, int count) {
		StockTarget target = colony.stockTargets().stream().filter(t -> t.match().equals(match)).findFirst()
			.map(t -> t.withCount(count))
			.orElse(new StockTarget(match, count, List.of()));
		colony.setStockTarget(target);
		return target;
	}

	/** The item with id {@code id}, if there is one. */
	static Optional<Item> item(String id) {
		return Optional.ofNullable(Identifier.tryParse(id)).flatMap(BuiltInRegistries.ITEM::getOptional).filter(item -> item != Items.AIR);
	}

	/** An item, or a tag for {@code #namespace:path}; any well-formed tag goes, as in the stock command. */
	static Optional<Either<TagKey<Item>, Item>> match(String text) {
		if (text.startsWith("#")) {
			return Optional.ofNullable(Identifier.tryParse(text.substring(1))).map(id -> Either.left(TagKey.create(Registries.ITEM, id)));
		}
		return item(text).map(Either::right);
	}
}
