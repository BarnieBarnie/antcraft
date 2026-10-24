package io.github.barniebarnie.antcraft.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
		BlueprintBlocksCache cache = new BlueprintBlocksCache();
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
		BlueprintBlocksCache cache = new BlueprintBlocksCache();
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
		BlueprintBlocksCache cache = new BlueprintBlocksCache();
		cache.receive(blocks(STEP));
		List<Identifier> asked = new ArrayList<>();
		assertEquals(Optional.empty(), cache.get(STEP, asked::add));
		assertEquals(List.of(STEP), asked);
	}
}
