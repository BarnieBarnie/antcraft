package io.github.barniebarnie.antcraft.colony;

import com.mojang.datafixers.util.Either;
import io.github.barniebarnie.antcraft.command.AntCraftCommands;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Changes a player makes to a colony, from {@code /antcraft colony} commands or the colony screen. The screen's
 * requests come from clients, so everything here checks its input and leaves the colony alone when it is wrong.
 */
public final class ColonyActions {
	/** Most items an order or stock target may ask for. */
	public static final int MAX_COUNT = 4096;
	public static final int MIN_RADIUS = 4;
	public static final int MAX_RADIUS = 128;

	/**
	 * What a player can change in a colony. The colony screen makes the first few; the rest only come from
	 * {@code /antcraft colony} commands, which need positions in the world ({@link #fromScreen}). Every colony-editing
	 * command checks its edit with {@link #mayEdit}, the same as the screen.
	 */
	public enum Edit {
		/** Order {@code count} of an item. */
		ORDER,
		/** Cancel the player's part of an order; the colony's own part stays. */
		CANCEL,
		/** Set the stock target for an item or {@code #tag}. */
		SET_STOCK,
		REMOVE_STOCK,
		/** Set the radius to {@code count}. */
		RADIUS,
		/** Plan the mineshaft ({@code colony mineshaft}). */
		MINESHAFT,
		/** Add a build copied from a region ({@code colony build copy}). */
		BUILD_COPY,
		/** Drop a build ({@code colony build cancel}). */
		BUILD_CANCEL,
		/** Add a terraform plan ({@code colony terraform}). */
		TERRAFORM,
		/** Give up on the blocked build steps ({@code colony blocked clear}). */
		CLEAR_BLOCKED,
		/** Drop a project with its builds ({@code colony project cancel}). */
		PROJECT_CANCEL,
		/** Set a project's priority ({@code colony project priority}). */
		PROJECT_PRIORITY;

		/**
		 * Whether this edit commands bots, and so needs bots allowed on the server. Each new edit has to answer this here.
		 * None does yet: they only change the colony's data, which waits for bots until they are allowed.
		 */
		boolean commandsBots() {
			return switch (this) {
				case ORDER, CANCEL, SET_STOCK, REMOVE_STOCK, RADIUS, MINESHAFT, BUILD_COPY, BUILD_CANCEL, TERRAFORM, CLEAR_BLOCKED, PROJECT_CANCEL,
					PROJECT_PRIORITY -> false;
			};
		}

		/** Whether the colony screen can make this edit through {@link ColonyActions#edit}; the others are commands only. */
		public boolean fromScreen() {
			return switch (this) {
				case ORDER, CANCEL, SET_STOCK, REMOVE_STOCK, RADIUS -> true;
				case MINESHAFT, BUILD_COPY, BUILD_CANCEL, TERRAFORM, CLEAR_BLOCKED, PROJECT_CANCEL, PROJECT_PRIORITY -> false;
			};
		}
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

	/**
	 * Whether a player at {@code from} may read {@code colony}'s status on the colony screen. This is the same rule as
	 * {@link #mayAct}, so a screen that would refuse every change does not show the colony either.
	 */
	public static boolean mayView(Colony colony, BlockPos from, boolean isOp, boolean requireOp) {
		return mayAct(colony, from, isOp, requireOp);
	}

	/**
	 * Whether {@code edit} may be made, from the colony screen or the matching {@code /antcraft colony} command, given
	 * whether bots are allowed on the server ({@link io.github.barniebarnie.antcraft.config.AntCraftConfig#botsAllowed}).
	 * The rule: changes to the colony's data (orders, stock targets, radius, the mineshaft, builds, terraform plans and
	 * blocked steps) do not need bots allowed, so a colony can be
	 * planned or tidied while its bots are off; anything that commands bots does (such as {@code /antcraft goto}).
	 */
	public static boolean mayEdit(Edit edit, boolean botsAllowed) {
		return botsAllowed || !edit.commandsBots();
	}

	/**
	 * Applies an edit from the colony screen. {@code target} is an item id, or for stock targets also a {@code #tag}.
	 *
	 * @return false, leaving the colony unchanged, if the target or count is invalid, or the edit is not one the screen
	 *     makes ({@link Edit#fromScreen})
	 */
	public static boolean edit(Colony colony, Edit edit, String target, int count) {
		boolean countOk = count >= 1 && count <= MAX_COUNT;
		return switch (edit) {
			case ORDER -> countOk && item(target).map(item -> {
				colony.addOrder(item, count);
				return true;
			}).orElse(false);
			case CANCEL -> item(target).map(item -> {
				cancelOrder(colony, item);
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
			case MINESHAFT, BUILD_COPY, BUILD_CANCEL, TERRAFORM, CLEAR_BLOCKED, PROJECT_CANCEL, PROJECT_PRIORITY -> false;
		};
	}

	/**
	 * Cancels the player's part of the order for {@code item}; the parts the colony placed itself, such as materials for
	 * its builds, stay. Both the colony screen and {@code /antcraft colony cancel} cancel this way.
	 */
	public static void cancelOrder(Colony colony, Item item) {
		colony.cancelPlayerOrder(item);
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
