package io.github.barniebarnie.antcraft.client;

import io.github.barniebarnie.antcraft.blueprint.BlueprintGhost;
import io.github.barniebarnie.antcraft.blueprint.ChosenBlueprint;
import io.github.barniebarnie.antcraft.client.render.GhostRenderer;
import io.github.barniebarnie.antcraft.item.BlueprintItem;
import java.util.Optional;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The ghost of the blueprint a held Blueprint is set to, where the player looks: turned with a key or by scrolling while
 * sneaking, and mirrored with another key. Placing it for a colony to build is gone with the goal-driven colony; building
 * comes back as a script action in milestone 7 of docs/scripting.md.
 */
public final class BlueprintPreview {
	private static Rotation rotation = Rotation.NONE;
	private static Mirror mirror = Mirror.NONE;

	private BlueprintPreview() {
	}
	/** The blueprint the player's Blueprint is set to, the main hand first, if they hold one set to a blueprint. */
	static Optional<ChosenBlueprint> held(Player player) {
		for (InteractionHand hand : InteractionHand.values()) {
			Optional<ChosenBlueprint> chosen = BlueprintItem.chosen(player.getItemInHand(hand));
			if (player.getItemInHand(hand).getItem() instanceof BlueprintItem && chosen.isPresent()) {
				return chosen;
			}
		}
		return Optional.empty();
	}

	/**
	 * The ghost of {@code chosen} against {@code face} of {@code looked}, as shown now, if the client has the blueprint's
	 * blocks; asks the server for them the first time.
	 */
	static Optional<BlueprintGhost> ghost(ChosenBlueprint chosen, BlockPos looked, Direction face) {
		return ClientBlueprints.blocks(chosen.id()).map(blueprint -> BlueprintGhost.centredOn(blueprint, BlueprintGhost.target(looked, face), rotation, mirror));
	}

	/** Each client tick: the turn and mirror keys while a Blueprint is held. */
	static void tick(Minecraft client, KeyMapping rotate, KeyMapping flip) {
		boolean holding = client.player != null && held(client.player).isPresent();
		while (rotate.consumeClick()) {
			if (holding) {
				turn(client.player.isShiftKeyDown() ? -1 : 1);
			}
		}
		while (flip.consumeClick()) {
			if (holding) {
				mirror = BlueprintGhost.flip(mirror);
			}
		}
	}

	private static void turn(int steps) {
		rotation = BlueprintGhost.turn(rotation, steps);
	}

	/**
	 * The mouse wheel turns the ghost while the player sneaks with a Blueprint and no screen is open.
	 *
	 * @return whether the scroll turned the ghost, so it does not also change the selected slot
	 */
	public static boolean scroll(double amount) {
		Minecraft client = Minecraft.getInstance();
		if (amount == 0 || client.player == null || client.gui.screen() != null || !client.player.isShiftKeyDown() || held(client.player).isEmpty()) {
			return false;
		}
		turn(amount > 0 ? 1 : -1);
		return true;
	}

	/** Resets the turn, on leaving the server. */
	static void reset() {
		rotation = Rotation.NONE;
		mirror = Mirror.NONE;
	}

	/** Draws the ghost at the block the player looks at; called where the client collects what to draw for the frame. */
	static void draw(Minecraft client, LevelRenderContext context) {
		HitResult hit = client.hitResult;
		if (client.player == null || !(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
			return;
		}
		held(client.player).flatMap(chosen -> ghost(chosen, block.getBlockPos(), block.getDirection())).ifPresent(ghost ->
			GhostRenderer.submit(context, ghost, ghost::blocks, GhostRenderer.VALID));
	}
}
