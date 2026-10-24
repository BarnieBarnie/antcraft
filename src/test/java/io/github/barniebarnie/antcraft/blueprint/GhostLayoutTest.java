package io.github.barniebarnie.antcraft.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GhostLayoutTest {
	private static final BlockPos AT = new BlockPos(8, 64, 8);

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static BlueprintSummary.Block block(BlockPos pos, BlockState state) {
		return new BlueprintSummary.Block(pos, state);
	}

	@Test
	void aLoneBlockShowsEveryFace() {
		List<GhostLayout.Piece> pieces = GhostLayout.pieces(List.of(block(AT, Blocks.STONE.defaultBlockState())));
		assertEquals(List.of(new GhostLayout.Piece(AT, Blocks.STONE.defaultBlockState(), Set.of())), pieces);
	}

	@Test
	void facesAgainstASolidGhostBlockAreHidden() {
		List<GhostLayout.Piece> pieces = GhostLayout.pieces(List.of(
			block(AT, Blocks.STONE.defaultBlockState()),
			block(AT.east(), Blocks.COBBLESTONE.defaultBlockState()),
			block(AT.above(), Blocks.GLASS.defaultBlockState())));
		// Stone hides its east face behind the cobblestone but shows its top through the glass.
		assertEquals(EnumSet.of(Direction.EAST), pieces.get(0).hidden());
		assertEquals(EnumSet.of(Direction.WEST), pieces.get(1).hidden());
		// The glass sits on stone, so its bottom face is hidden.
		assertEquals(EnumSet.of(Direction.DOWN), pieces.get(2).hidden());
	}

	@Test
	void airIsNotDrawnAndDoesNotHideFaces() {
		List<GhostLayout.Piece> pieces = GhostLayout.pieces(List.of(
			block(AT, Blocks.STONE.defaultBlockState()),
			block(AT.north(), Blocks.AIR.defaultBlockState())));
		assertEquals(List.of(new GhostLayout.Piece(AT, Blocks.STONE.defaultBlockState(), Set.of())), pieces);
	}

	@Test
	void atMostMaxBlocksAreDrawn() {
		List<BlueprintSummary.Block> blocks = new ArrayList<>();
		for (int i = 0; i < GhostLayout.MAX_BLOCKS + 10; i++) {
			blocks.add(block(new BlockPos(i * 2, 64, 0), Blocks.STONE.defaultBlockState()));
		}
		List<GhostLayout.Piece> pieces = GhostLayout.pieces(blocks);
		assertEquals(GhostLayout.MAX_BLOCKS, pieces.size());
		assertEquals(BlockPos.ZERO.atY(64), pieces.get(0).pos());
	}

	@Test
	void theTintIsSeeThroughAndPaledSoTexturesShow() {
		int tint = GhostLayout.tint(0xFF4040);
		assertEquals(GhostLayout.ALPHA, ARGB.alpha(tint));
		assertTrue(GhostLayout.ALPHA > 0 && GhostLayout.ALPHA < 0xFF, "translucent");
		// Red stays the strongest channel, but green and blue are lifted so the texture is not drowned.
		assertEquals(0xFF, ARGB.red(tint));
		assertTrue(ARGB.green(tint) > 0x40 && ARGB.green(tint) < 0xFF);
		assertEquals(ARGB.green(tint), ARGB.blue(tint));
	}

	@Test
	void aBiomeColourIsMixedUnderTheGhostTint() {
		int plain = GhostLayout.tint(0x80C0FF);
		// Plains grass, as a biome colour source gives it: no alpha of its own.
		int grass = 0x91BD59;
		int tint = GhostLayout.tint(0x80C0FF, grass);
		// Still as see-through as any ghost block.
		assertEquals(GhostLayout.ALPHA, ARGB.alpha(tint));
		// Each channel is the ghost tint multiplied by the biome colour, so grass shows green under the pale blue.
		assertEquals(ARGB.red(plain) * ARGB.red(grass) / 255, ARGB.red(tint), 1);
		assertEquals(ARGB.green(plain) * ARGB.green(grass) / 255, ARGB.green(tint), 1);
		assertEquals(ARGB.blue(plain) * ARGB.blue(grass) / 255, ARGB.blue(tint), 1);
		assertTrue(ARGB.green(tint) > ARGB.red(tint) && ARGB.green(tint) > ARGB.blue(tint), "green");
		// A white biome colour, as for an untinted layer, leaves the ghost tint as it is.
		assertEquals(plain, GhostLayout.tint(0x80C0FF, 0xFFFFFFFF));
		assertEquals(plain, GhostLayout.tint(0x80C0FF, 0xFFFFFF));
	}
}
