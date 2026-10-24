package io.github.barniebarnie.antcraft.client;

import io.github.barniebarnie.antcraft.client.render.GhostRenderer;
import io.github.barniebarnie.antcraft.colony.BuildGhost;
import io.github.barniebarnie.antcraft.colony.CancelConfirm;
import io.github.barniebarnie.antcraft.network.ColonyNetworking;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The ghosts of the player-placed builds of the nearest colony the player may act on, as the server last sent them
 * ({@link ColonyNetworking.BuildGhosts}): drawn see-through where blocks are still to be placed, and cancelled by sneaking
 * and right-clicking one with a Blueprint twice (see {@link CancelConfirm}), which the server checks.
 */
public final class PlacedGhosts {
	/** How far away a ghost can be clicked. */
	private static final double REACH = 32;

	private static volatile List<BuildGhost> ghosts = List.of();
	private static final CancelConfirm CONFIRM = new CancelConfirm();

	private PlacedGhosts() {
	}

	/** The ghosts the server last sent. */
	static void set(List<BuildGhost> received) {
		ghosts = List.copyOf(received);
	}

	/** Forgets the ghosts, on leaving the server. */
	static void reset() {
		ghosts = List.of();
		CONFIRM.reset();
	}

	/** Draws every ghost, the one armed to cancel in red; called where the client collects gizmos for the frame. */
	static void draw(Minecraft client) {
		if (client.level == null) {
			return;
		}
		Optional<UUID> armed = CONFIRM.armed(client.level.getGameTime());
		for (BuildGhost ghost : ghosts) {
			GhostRenderer.draw(ghost.blocks(), armed.equals(Optional.of(ghost.project())) ? GhostRenderer.REFUSED : GhostRenderer.PLANNED);
		}
	}

	/**
	 * A sneaking right-click with a Blueprint: if the player looks at a ghost before any block, the first click asks on the
	 * action bar and the second, on the same build soon after, asks the server to cancel it.
	 *
	 * @return whether the player looked at a ghost, so the click does nothing else
	 */
	static boolean click(Player player) {
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getViewVector(1.0F).scale(REACH));
		HitResult block = player.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		if (block.getType() != HitResult.Type.MISS) {
			end = block.getLocation();
		}
		Optional<BuildGhost> looked = BuildGhost.lookedAt(ghosts, eye, end);
		if (looked.isEmpty()) {
			return false;
		}
		BuildGhost ghost = looked.get();
		if (!CONFIRM.click(ghost.project(), player.level().getGameTime())) {
			player.sendOverlayMessage(Component.translatable("commands.antcraft.colony.build.cancel.confirm", Component.translatable(ghost.name())));
		} else if (ClientPlayNetworking.canSend(ColonyNetworking.CancelBuild.TYPE)) {
			ClientPlayNetworking.send(new ColonyNetworking.CancelBuild(ghost.core(), ghost.project()));
		}
		return true;
	}
}
