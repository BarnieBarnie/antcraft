package io.github.barniebarnie.antcraft.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import io.github.barniebarnie.antcraft.blueprint.LitematicaPlacement;
import io.github.barniebarnie.antcraft.network.BlueprintNetworking.PlacementPart;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The client command {@code /antcraft-placement [name]} that sends a Litematica placement to the server for bots to build
 * (docs/features/blueprints.md, Litematica placements). The only class that touches Litematica's: {@link AntCraftClient}
 * loads it only when Litematica is installed.
 */
final class LitematicaPlacements {
	private LitematicaPlacements() {
	}

	static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
			ClientCommands.literal("antcraft-placement")
				.executes(command -> send(command.getSource(), null))
				.then(ClientCommands.argument("name", StringArgumentType.greedyString())
					.suggests((command, builder) -> SharedSuggestionProvider.suggest(placements().stream().map(SchematicPlacement::getName), builder))
					.executes(command -> send(command.getSource(), StringArgumentType.getString(command, "name"))))));
	}

	private static List<SchematicPlacement> placements() {
		return DataManager.getSchematicPlacementManager().getAllSchematicsPlacements();
	}

	/** Sends the placement named {@code name}, or the selected one when it is null. */
	private static int send(FabricClientCommandSource source, @Nullable String name) {
		SchematicPlacement placement = name == null
			? DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement()
			: placements().stream().filter(p -> name.equals(p.getName())).findFirst().orElse(null);
		if (placement == null) {
			source.sendError(name == null ? Component.translatable("message.antcraft.placement.none_selected")
				: Component.translatable("message.antcraft.placement.not_found", name));
			return 0;
		}
		if (!ClientPlayNetworking.canSend(PlacementPart.TYPE)) {
			source.sendError(Component.translatable("message.antcraft.placement.no_server"));
			return 0;
		}
		List<LitematicaPlacement.Region> regions = placement.getAllSubRegionsPlacements().stream().map(LitematicaPlacements::region).toList();
		Component message = new LitematicaPlacement(placement.getName(), placement.getOrigin(), placement.getRotation(), placement.getMirror(), regions)
			.send(read(placement.getSchematicFile()), ClientPlayNetworking::send);
		source.sendFeedback(message);
		return 1;
	}

	private static LitematicaPlacement.Region region(SubRegionPlacement region) {
		return new LitematicaPlacement.Region(region.getName(), region.getPos(), region.getDefaultPos(), region.getRotation(),
			region.getMirror(), region.isEnabled());
	}

	/** The schematic file's bytes; null when it is not saved or does not read. */
	private static byte @Nullable [] read(@Nullable Path file) {
		try {
			return file == null ? null : Files.readAllBytes(file);
		} catch (IOException e) {
			return null;
		}
	}
}
