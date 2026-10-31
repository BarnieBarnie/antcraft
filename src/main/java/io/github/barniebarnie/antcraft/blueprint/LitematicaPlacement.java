package io.github.barniebarnie.antcraft.blueprint;

import io.github.barniebarnie.antcraft.network.BlueprintNetworking.PlacementPart;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.jspecify.annotations.Nullable;

/**
 * A Litematica placement as the client reads it, without Litematica's classes, so it is sent as {@link PlacementPart}s
 * (docs/features/blueprints.md, Litematica placements). The client integration fills it in; this decides what is sent.
 *
 * @param regions the placement's sub-regions; one changed on its own refuses the placement, as the server builds every
 *                region where the schematic saved it
 */
public record LitematicaPlacement(String name, BlockPos origin, Rotation rotation, Mirror mirror, List<Region> regions) {
	/** A sub-region: where it is and where the schematic saved it, its own rotation and mirror, and whether it is on. */
	public record Region(String name, BlockPos pos, BlockPos defaultPos, Rotation rotation, Mirror mirror, boolean enabled) {
		boolean changed() {
			return !this.enabled || !this.pos.equals(this.defaultPos) || this.rotation != Rotation.NONE || this.mirror != Mirror.NONE;
		}
	}

	/**
	 * Sends the placement with {@code file}, the bytes of its {@code .litematic}, through {@code send}; nothing is sent when it
	 * is refused.
	 *
	 * @param file null when the schematic is not saved to a file
	 * @return what to tell the player: sending, or refused with why
	 */
	public Component send(byte @Nullable [] file, Consumer<PlacementPart> send) {
		List<String> changed = this.regions.stream().filter(Region::changed).map(Region::name).toList();
		if (!changed.isEmpty()) {
			return Placements.refused(this.name, "sub-region " + String.join(", ", changed)
				+ " was moved, rotated, mirrored or switched off on its own; reset it in Litematica");
		}
		if (file == null) {
			return Placements.refused(this.name, "its schematic is not saved to a file");
		}
		if (this.name.length() > PlacementPart.NAME_LENGTH) {
			return Placements.refused(this.name, "its name is longer than " + PlacementPart.NAME_LENGTH + " characters");
		}
		PlacementPart.of(this.name, this.origin, this.rotation, this.mirror, file).forEach(send);
		return Component.translatable("message.antcraft.placement.sending", this.name);
	}
}
