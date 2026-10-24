package io.github.barniebarnie.antcraft.client;

import io.github.barniebarnie.antcraft.blueprint.BlueprintSummary;
import io.github.barniebarnie.antcraft.network.ColonyNetworking;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/**
 * The blueprints the server last sent ({@link ColonyNetworking.BlueprintList}, on joining and after each data reload),
 * for the picker and for previewing a placement. Empty until the first list arrives, and cleared on leaving.
 */
public final class ClientBlueprints {
	private static volatile List<BlueprintSummary> blueprints = List.of();

	private ClientBlueprints() {
	}

	/** Every blueprint the server sent, in id order. */
	public static List<BlueprintSummary> all() {
		return blueprints;
	}

	/** The blueprint with id {@code id}, with its size and blocks, if the server sent it. */
	public static Optional<BlueprintSummary> get(Identifier id) {
		return blueprints.stream().filter(blueprint -> blueprint.id().equals(id)).findFirst();
	}

	static void set(List<BlueprintSummary> list) {
		blueprints = List.copyOf(list);
	}
}
