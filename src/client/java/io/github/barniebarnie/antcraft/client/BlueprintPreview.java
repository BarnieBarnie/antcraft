package io.github.barniebarnie.antcraft.client;

import io.github.barniebarnie.antcraft.blueprint.BlueprintGhost;
import io.github.barniebarnie.antcraft.blueprint.ChosenBlueprint;
import io.github.barniebarnie.antcraft.client.render.GhostRenderer;
import io.github.barniebarnie.antcraft.colony.BlueprintPlacement;
import io.github.barniebarnie.antcraft.colony.SiteRules;
import io.github.barniebarnie.antcraft.item.BlueprintItem;
import io.github.barniebarnie.antcraft.network.ColonyNetworking;
import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The ghost of the blueprint a held Blueprint is set to, where the player looks: turned with a key or by scrolling while
 * sneaking, mirrored with another key, red where the server would refuse it as far as the client knows, and placed with a
 * right-click (see {@link BlueprintItem#setPlacer}). The client learns the nearest colony it may place for, and its rules,
 * from the server ({@link ColonyNetworking.SiteRulesRequest}) every second while it holds a Blueprint.
 */
public final class BlueprintPreview {
	/** How often, in ticks, the client asks for the colony's rules while it holds a Blueprint. */
	private static final int RULES_INTERVAL = 20;

	private static Rotation rotation = Rotation.NONE;
	private static Mirror mirror = Mirror.NONE;
	private static volatile Optional<SiteRules> rules = Optional.empty();
	private static int ticks;

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

	/** Each client tick: the turn and mirror keys, and asking for the rules while a Blueprint is held. */
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
		if (!holding) {
			ticks = 0;
			return;
		}
		if (ticks++ % RULES_INTERVAL == 0 && ClientPlayNetworking.canSend(ColonyNetworking.SiteRulesRequest.TYPE)) {
			ClientPlayNetworking.send(new ColonyNetworking.SiteRulesRequest());
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

	/** The colony's rules the server last sent. */
	static void setRules(Optional<SiteRules> received) {
		rules = received;
	}

	/** Forgets the rules and resets the turn, on leaving the server. */
	static void reset() {
		rules = Optional.empty();
		rotation = Rotation.NONE;
		mirror = Mirror.NONE;
	}

	/** Draws the ghost at the block {@code hit} looks at; called where the client collects gizmos for the frame. */
	static void draw(Minecraft client, HitResult hit) {
		if (client.player == null || !(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
			return;
		}
		held(client.player).flatMap(chosen -> ghost(chosen, block.getBlockPos(), block.getDirection())).ifPresent(ghost ->
			GhostRenderer.draw(ghost.blocks(), ghost.refusal(rules).isPresent() ? GhostRenderer.REFUSED : GhostRenderer.VALID));
	}

	/**
	 * Asks the server to place the held blueprint where its ghost stands for the right-click {@code context}. With no colony
	 * known to place for, says so on the action bar instead; any other refusal is the server's to give.
	 */
	static void place(UseOnContext context) {
		Optional<ChosenBlueprint> chosen = BlueprintItem.chosen(context.getItemInHand());
		Optional<BlueprintGhost> ghost = chosen.flatMap(blueprint -> ghost(blueprint, context.getClickedPos(), context.getClickedFace()));
		Player player = context.getPlayer();
		if (ghost.isEmpty() || player == null || !ClientPlayNetworking.canSend(ColonyNetworking.PlaceBlueprint.TYPE)) {
			return;
		}
		Optional<SiteRules> known = rules;
		if (known.isEmpty()) {
			player.sendOverlayMessage(BlueprintPlacement.Refusal.NO_COLONY.message().copy().withStyle(ChatFormatting.RED));
			return;
		}
		BlueprintGhost placed = ghost.get();
		ClientPlayNetworking.send(new ColonyNetworking.PlaceBlueprint(known.get().core(), chosen.get().id(), placed.at(), placed.rotation(), placed.mirror()));
	}
}
