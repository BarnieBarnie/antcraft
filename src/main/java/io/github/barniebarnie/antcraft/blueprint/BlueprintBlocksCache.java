package io.github.barniebarnie.antcraft.blueprint;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.resources.Identifier;

/**
 * The blueprint blocks a client got from the server, asked for one blueprint at a time as the player previews it. Each is
 * asked for once and kept until {@link #clear()}, which the client calls when a new blueprint list arrives (the server
 * reloaded its data, so a structure may have changed) and on leaving the server.
 */
public final class BlueprintBlocksCache {
	private final Map<Identifier, BlueprintBlocks> received = new HashMap<>();
	private final Set<Identifier> asked = new HashSet<>();

	/**
	 * The blocks of blueprint {@code id} if they arrived; if not, and they were not asked for yet, asks for them with
	 * {@code ask}.
	 */
	public synchronized Optional<BlueprintBlocks> get(Identifier id, Consumer<Identifier> ask) {
		BlueprintBlocks blocks = this.received.get(id);
		if (blocks == null && this.asked.add(id)) {
			ask.accept(id);
		}
		return Optional.ofNullable(blocks);
	}

	/** Keeps blocks the server sent, if they were asked for since the last {@link #clear()}; older replies are dropped. */
	public synchronized void receive(BlueprintBlocks blocks) {
		if (this.asked.contains(blocks.id())) {
			this.received.put(blocks.id(), blocks);
		}
	}

	/** Forgets every blueprint's blocks, and any request still under way, so each is asked for again. */
	public synchronized void clear() {
		this.received.clear();
		this.asked.clear();
	}
}
