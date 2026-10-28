package io.github.barniebarnie.antcraft.colony;

import io.github.barniebarnie.antcraft.command.AntCraftCommands;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

/** Who may change a colony: its owner, the players it trusts, and operators. */
public final class ColonyActions {
	public static final int MIN_RADIUS = 4;
	public static final int MAX_RADIUS = 128;

	private ColonyActions() {
	}

	/**
	 * Whether {@code player} at {@code from} may change {@code colony}: its owner and the players it trusts, from
	 * anywhere, and operators. A colony with no owner yet, saved before colonies had owners, keeps the old rule: anyone
	 * in range. With {@code requireOp} only operators may. A Bot Core joins only a colony its player may change.
	 */
	public static boolean mayAct(Colony colony, UUID player, BlockPos from, boolean isOp, boolean requireOp) {
		if (isOp) {
			return true;
		}
		if (requireOp) {
			return false;
		}
		return colony.owner().isPresent() ? colony.isOwnerOrTrusted(player) : colony.core().closerThan(from, AntCraftCommands.COLONY_RANGE);
	}

	/** {@link #mayAct} for {@code player} where they stand, with the server's config. */
	public static boolean mayAct(Colony colony, Player player) {
		return mayAct(colony, player.getUUID(), player.blockPosition(), isOp(player), AntCraftConfig.colonyActionsRequireOp());
	}

	/**
	 * Whether {@code player} may trust players with {@code colony} or stop trusting them ({@code /antcraft colony
	 * trust|untrust}): its owner and operators. Trusted players may change the colony but not whom it trusts, and a
	 * colony with no owner yet has nobody to do this but operators. With {@code requireOp} only operators may, as for
	 * {@link #mayAct}.
	 */
	public static boolean mayTrust(Colony colony, UUID player, boolean isOp, boolean requireOp) {
		if (isOp) {
			return true;
		}
		return !requireOp && colony.owner().equals(Optional.of(player));
	}

	/** {@link #mayTrust} for {@code player}, with the server's config. */
	public static boolean mayTrust(Colony colony, Player player) {
		return mayTrust(colony, player.getUUID(), isOp(player), AntCraftConfig.colonyActionsRequireOp());
	}

	/** Whether {@code player} has the permission level the {@code /antcraft} commands need. */
	public static boolean isOp(Player player) {
		return Commands.LEVEL_GAMEMASTERS.check(player.permissions());
	}
}
