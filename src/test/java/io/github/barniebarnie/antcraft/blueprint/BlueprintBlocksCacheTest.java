package io.github.barniebarnie.antcraft.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The client asks the server for the blocks of a blueprint once, when it first previews it, and keeps them until the list changes. */
class BlueprintBlocksCacheTest {
	private static final Identifier STEP = Identifier.fromNamespaceAndPath("antcraft", "step");
	private static final Identifier HUT = Identifier.fromNamespaceAndPath("antcraft", "hut");

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static BlueprintBlocks blocks(Identifier id) {
		return new BlueprintBlocks(id, new Vec3i(1, 1, 1), List.of(new BlueprintSummary.Block(BlockPos.ZERO, Blocks.STONE.defaultBlockState())));
	}

	@Test
	void asksOnceAndKeepsTheReply() {
		BlueprintBlocksCache cache = new BlueprintBlocksCache(() -> 0);
		List<Identifier> asked = new ArrayList<>();
		assertEquals(Optional.empty(), cache.get(STEP, asked::add));
		assertEquals(Optional.empty(), cache.get(STEP, asked::add));
		assertEquals(List.of(STEP), asked, "asked again while waiting");
		cache.receive(blocks(STEP));
		assertEquals(Optional.of(blocks(STEP)), cache.get(STEP, asked::add));
		assertEquals(Optional.empty(), cache.get(HUT, asked::add));
		assertEquals(List.of(STEP, HUT), asked);
	}

	/** After a reload the blueprint may have changed: it is asked for again, and a reply to a request from before is dropped. */
	@Test
	void clearingForgetsTheBlocksAndRepliesStillUnderWay() {
		BlueprintBlocksCache cache = new BlueprintBlocksCache(() -> 0);
		List<Identifier> asked = new ArrayList<>();
		cache.get(STEP, asked::add);
		cache.receive(blocks(STEP));
		cache.get(HUT, asked::add);
		cache.clear();
		cache.receive(blocks(HUT));
		assertEquals(Optional.empty(), cache.get(STEP, asked::add));
		assertEquals(Optional.empty(), cache.get(HUT, asked::add));
		assertEquals(List.of(STEP, HUT, STEP, HUT), asked);
	}

	@Test
	void aReplyNotAskedForIsDropped() {
		BlueprintBlocksCache cache = new BlueprintBlocksCache(() -> 0);
		cache.receive(blocks(STEP));
		List<Identifier> asked = new ArrayList<>();
		assertEquals(Optional.empty(), cache.get(STEP, asked::add));
		assertEquals(List.of(STEP), asked);
	}

	/**
	 * A request the client could not send, say to a server without the payload, is asked again the next time the blocks
	 * are wanted, but at most once per {@link BlueprintBlocksCache#RETRY_INTERVAL}.
	 */
	@Test
	void aRequestThatCouldNotBeSentIsAskedAgainOncePerInterval() {
		long[] now = {1000};
		BlueprintBlocksCache cache = new BlueprintBlocksCache(() -> now[0]);
		List<Identifier> asked = new ArrayList<>();
		boolean[] sent = {false};
		Predicate<Identifier> ask = id -> {
			asked.add(id);
			return sent[0];
		};
		cache.get(STEP, ask);
		cache.get(STEP, ask);
		now[0] += BlueprintBlocksCache.RETRY_INTERVAL - 1;
		cache.get(STEP, ask);
		assertEquals(List.of(STEP), asked, "asked again within the interval");
		now[0] += 1;
		cache.get(STEP, ask);
		cache.get(STEP, ask);
		assertEquals(List.of(STEP, STEP), asked);
		sent[0] = true;
		now[0] += BlueprintBlocksCache.RETRY_INTERVAL;
		cache.get(STEP, ask);
		now[0] += BlueprintBlocksCache.RETRY_INTERVAL;
		cache.get(STEP, ask);
		assertEquals(List.of(STEP, STEP, STEP), asked, "asked again once sent");
		cache.receive(blocks(STEP));
		assertEquals(Optional.of(blocks(STEP)), cache.get(STEP, ask));
	}

	/** A request the server took but never answered is asked again after {@link BlueprintBlocksCache#REPLY_TIMEOUT}; a late reply still counts. */
	@Test
	void aRequestWithNoReplyIsAskedAgainAfterATimeout() {
		long[] now = {0};
		BlueprintBlocksCache cache = new BlueprintBlocksCache(() -> now[0]);
		List<Identifier> asked = new ArrayList<>();
		cache.get(STEP, asked::add);
		now[0] += BlueprintBlocksCache.REPLY_TIMEOUT - 1;
		cache.get(STEP, asked::add);
		assertEquals(List.of(STEP), asked, "asked again before the timeout");
		now[0] += 1;
		cache.get(STEP, asked::add);
		cache.get(STEP, asked::add);
		assertEquals(List.of(STEP, STEP), asked);
		cache.receive(blocks(STEP));
		now[0] += BlueprintBlocksCache.REPLY_TIMEOUT;
		assertEquals(Optional.of(blocks(STEP)), cache.get(STEP, asked::add));
		assertEquals(List.of(STEP, STEP), asked);
	}

	/** The parts of a reply come together in any order; parts from before a clear, or that do not belong together, are dropped. */
	@Test
	void partsArePutTogetherOnceAllArrived() {
		BlueprintBlocks whole = new BlueprintBlocks(STEP, new Vec3i(2, 1, 1), List.of(
			new BlueprintSummary.Block(BlockPos.ZERO, Blocks.STONE.defaultBlockState()),
			new BlueprintSummary.Block(new BlockPos(1, 0, 0), Blocks.DIRT.defaultBlockState())));
		BlueprintBlocks first = new BlueprintBlocks(STEP, whole.size(), whole.blocks().subList(0, 1));
		BlueprintBlocks second = new BlueprintBlocks(STEP, whole.size(), whole.blocks().subList(1, 2));
		BlueprintBlocksCache cache = new BlueprintBlocksCache(() -> 0);
		List<Identifier> asked = new ArrayList<>();
		cache.get(STEP, asked::add);
		cache.receive(second, 1, 2);
		cache.clear();
		cache.get(STEP, asked::add);
		cache.receive(first, 0, 2);
		assertEquals(Optional.empty(), cache.get(STEP, asked::add), "a part from before the clear was kept");
		cache.receive(second, 5, 2);
		cache.receive(second, 1, 1 << 20);
		assertEquals(Optional.empty(), cache.get(STEP, asked::add), "a part out of range was kept");
		cache.receive(second, 1, 2);
		assertEquals(Optional.of(whole), cache.get(STEP, asked::add));
		assertEquals(List.of(STEP, STEP), asked);
	}
}
