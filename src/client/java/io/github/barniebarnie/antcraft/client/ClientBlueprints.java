package io.github.barniebarnie.antcraft.client;

import io.github.barniebarnie.antcraft.blueprint.BlueprintBlocks;
import io.github.barniebarnie.antcraft.blueprint.BlueprintBlocksCache;
import io.github.barniebarnie.antcraft.blueprint.BlueprintSummary;
import io.github.barniebarnie.antcraft.network.BlueprintNetworking;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.resources.Identifier;

/**
 * The blueprints the server last sent ({@link BlueprintNetworking.BlueprintList}, on joining and after each data reload), for
 * the picker, and the blocks of those previewed, asked for one blueprint at a time ({@link BlueprintNetworking.BlueprintBlocksRequest}).
 * Empty until the first list arrives; each new list drops the blocks, as a reload may have changed them, and leaving
 * clears both.
 */
public final class ClientBlueprints {
	private static volatile List<BlueprintSummary> blueprints = List.of();
	private static final BlueprintBlocksCache BLOCKS = new BlueprintBlocksCache();

	private ClientBlueprints() {
	}

	/** Every blueprint the server sent, in id order. */
	public static List<BlueprintSummary> all() {
		return blueprints;
	}

	/**
	 * The size and blocks of blueprint {@code id}, if the server sent them; the first time they are wanted, asks the server
	 * for them, so the preview shows once the reply arrives. If the request cannot be sent, it is tried again the next
	 * time they are wanted, at most once a second, and if no reply came it is sent again after a while (see
	 * {@link BlueprintBlocksCache}).
	 */
	public static Optional<BlueprintBlocks> blocks(Identifier id) {
		return BLOCKS.get(id, ask -> {
			if (!ClientPlayNetworking.canSend(BlueprintNetworking.BlueprintBlocksRequest.TYPE)) {
				return false;
			}
			ClientPlayNetworking.send(new BlueprintNetworking.BlueprintBlocksRequest(ask));
			return true;
		});
	}

	static void set(List<BlueprintSummary> list) {
		blueprints = List.copyOf(list);
		BLOCKS.clear();
	}

	static void receive(BlueprintNetworking.BlueprintBlocksReply reply) {
		BLOCKS.receive(reply.blocks(), reply.part(), reply.parts());
	}
}
