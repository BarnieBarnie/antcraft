package io.github.barniebarnie.antcraft.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
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

	@Test
	void waterIsAFluidWithoutFacesAgainstMoreWaterOrSolidGhostBlocks() {
		BlockState water = Blocks.WATER.defaultBlockState();
		List<GhostLayout.Piece> pieces = GhostLayout.pieces(List.of(
			block(AT, water),
			block(AT.east(), water),
			block(AT.above(), water),
			block(AT.north(), Blocks.STONE.defaultBlockState())));
		float source = Fluids.WATER.defaultFluidState().getOwnHeight();
		GhostLayout.Fluid under = pieces.get(0).fluid().orElseThrow();
		assertEquals(Fluids.WATER, under.state().getType());
		// No face between two waters or against the stone; with water above, the surface is at the top of the block.
		assertEquals(EnumSet.of(Direction.EAST, Direction.UP, Direction.NORTH), under.hidden());
		assertEquals(1.0F, under.height());
		GhostLayout.Fluid beside = pieces.get(1).fluid().orElseThrow();
		assertEquals(EnumSet.of(Direction.WEST), beside.hidden());
		// A source's own height, as the game draws its surface: below the top of the block.
		assertEquals(source, beside.height());
		assertTrue(source < 1.0F);
		assertEquals(EnumSet.of(Direction.DOWN), pieces.get(2).fluid().orElseThrow().hidden());
		// Water does not hide the faces of a solid block, and the stone has no fluid.
		assertEquals(Set.of(), pieces.get(3).hidden());
		assertEquals(Optional.empty(), pieces.get(3).fluid());
	}

	@Test
	void aSolidBlockAboveDoesNotHideTheSurfaceBelowIt() {
		List<GhostLayout.Piece> pieces = GhostLayout.pieces(List.of(
			block(AT, Blocks.WATER.defaultBlockState()),
			block(AT.above(), Blocks.STONE.defaultBlockState())));
		// The surface sits below the stone, so it shows, as in the world.
		GhostLayout.Fluid water = pieces.get(0).fluid().orElseThrow();
		assertFalse(water.hidden().contains(Direction.UP));
		assertTrue(water.height() < 1.0F);
	}

	@Test
	void aWaterloggedBlockHasItsWaterTooButNotWhereItsOwnFaceCoversIt() {
		BlockState slab = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
		GhostLayout.Fluid water = GhostLayout.pieces(List.of(block(AT, slab))).get(0).fluid().orElseThrow();
		// A bottom slab's full bottom face covers the water's.
		assertEquals(EnumSet.of(Direction.DOWN), water.hidden());
		assertEquals(Optional.empty(), GhostLayout.pieces(List.of(block(AT, Blocks.OAK_SLAB.defaultBlockState()))).get(0).fluid());
	}
}
