package io.github.barniebarnie.antcraft.blueprint;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import net.minecraft.util.Util;
import net.minecraft.resources.Identifier;

/**
 * The blueprint blocks a client got from the server, asked for one blueprint at a time as the player previews it. Each is
 * asked for once and kept until {@link #clear()}, which the client calls when a new blueprint list arrives (the server
 * reloaded its data, so a structure may have changed) and on leaving the server. A request that could not be sent is
 * asked again the next time the blocks are wanted, at most once per {@link #RETRY_INTERVAL}; one sent but not answered
 * within {@link #REPLY_TIMEOUT} is asked again. Blocks too many for one payload come in parts
 * ({@link BlueprintBlocks#parts()}), kept until every part arrived.
 */
public final class BlueprintBlocksCache {
	/** How long after a request that could not be sent it is tried again, in milliseconds. */
	public static final long RETRY_INTERVAL = 1000;

	/** How long a sent request waits for its reply before it is asked again, in milliseconds. */
	public static final long REPLY_TIMEOUT = 30_000;

	private final LongSupplier clock;
	private final Map<Identifier, BlueprintBlocks> received = new HashMap<>();
	private final Map<Identifier, BlueprintBlocks[]> parts = new HashMap<>();
	/** The blueprints asked for since the last {@link #clear()}, whose replies are kept. */
	private final Set<Identifier> asked = new HashSet<>();
	/** When each blueprint not received yet may be asked for again, on {@link #clock}. */
	private final Map<Identifier, Long> askAgain = new HashMap<>();

	/** A cache on the game's clock in milliseconds. */
	public BlueprintBlocksCache() {
		this(Util::getMillis);
	}

	/** A cache timing its retries on {@code clock}, in milliseconds. */
	public BlueprintBlocksCache(LongSupplier clock) {
		this.clock = clock;
	}

	/**
	 * The blocks of blueprint {@code id} if they arrived; if not, and no request for them is under way, asks for them with
	 * {@code ask}, which tells whether it could send the request: if not, they are asked for again on a call
	 * {@link #RETRY_INTERVAL} later, and if so, {@link #REPLY_TIMEOUT} later unless the reply came.
	 */
	public synchronized Optional<BlueprintBlocks> get(Identifier id, Predicate<Identifier> ask) {
		BlueprintBlocks blocks = this.received.get(id);
		if (blocks != null) {
			return Optional.of(blocks);
		}
		long now = this.clock.getAsLong();
		Long due = this.askAgain.get(id);
		if (due == null || now >= due) {
			if (ask.test(id)) {
				this.asked.add(id);
				this.askAgain.put(id, now + REPLY_TIMEOUT);
			} else {
				this.askAgain.put(id, now + RETRY_INTERVAL);
			}
		}
		return Optional.empty();
	}

	/** Keeps blocks the server sent in one part. */
	public void receive(BlueprintBlocks blocks) {
		this.receive(blocks, 0, 1);
	}

	/**
	 * Keeps part {@code part} of {@code parts} of the blocks the server sent, if they were asked for since the last
	 * {@link #clear()}; older replies, and parts out of range, are dropped. Once every part arrived they are put back
	 * together ({@link BlueprintBlocks#join}).
	 */
	public synchronized void receive(BlueprintBlocks blocks, int part, int parts) {
		Identifier id = blocks.id();
		if (!this.asked.contains(id) || this.received.containsKey(id) || parts < 1 || parts > BlueprintBlocks.MAX_PARTS || part < 0 || part >= parts) {
			return;
		}
		BlueprintBlocks[] got = this.parts.get(id);
		if (got == null || got.length != parts) {
			got = new BlueprintBlocks[parts];
			this.parts.put(id, got);
		}
		got[part] = blocks;
		if (Arrays.stream(got).allMatch(Objects::nonNull)) {
			this.parts.remove(id);
			this.received.put(id, BlueprintBlocks.join(Arrays.asList(got)));
			this.askAgain.remove(id);
		}
	}

	/** Forgets every blueprint's blocks, and any request or parts still under way, so each is asked for again. */
	public synchronized void clear() {
		this.received.clear();
		this.parts.clear();
		this.asked.clear();
		this.askAgain.clear();
	}
}
