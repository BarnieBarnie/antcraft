package io.github.barniebarnie.antcraft.blueprint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a ghost of unbuilt blocks draws, worked out without a client so it can be tested: which blocks, which of their
 * faces are hidden behind another solid block of the same ghost (drawn see-through, such faces would show as a grid
 * inside the ghost), and the tint the textured blocks are drawn with.
 */
public final class GhostLayout {
	/** At most this many blocks are drawn, so a huge structure cannot stall the frame. */
	public static final int MAX_BLOCKS = 4096;
	/** How opaque a ghost block is, out of 255. */
	public static final int ALPHA = 0x99;
	/** How far the tint is paled towards white, so the textures still show under it. */
	private static final float PALE = 0.4F;

	private GhostLayout() {
	}

	/** One block to draw, with the faces that another solid block of the ghost covers. */
	public record Piece(BlockPos pos, BlockState state, Set<Direction> hidden) {
		public Piece {
			hidden = hidden.isEmpty() ? Set.of() : EnumSet.copyOf(hidden);
		}
	}

	/** The blocks of {@code blocks} to draw, in their order, without air and at most {@link #MAX_BLOCKS}. */
	public static List<Piece> pieces(Collection<BlueprintSummary.Block> blocks) {
		Map<BlockPos, BlockState> drawn = new HashMap<>();
		List<BlueprintSummary.Block> shown = new ArrayList<>();
		for (BlueprintSummary.Block block : blocks) {
			if (shown.size() == MAX_BLOCKS) {
				break;
			}
			if (!block.state().isAir()) {
				shown.add(block);
				drawn.put(block.pos(), block.state());
			}
		}
		List<Piece> pieces = new ArrayList<>(shown.size());
		for (BlueprintSummary.Block block : shown) {
			Set<Direction> hidden = EnumSet.noneOf(Direction.class);
			for (Direction direction : Direction.values()) {
				BlockState neighbour = drawn.get(block.pos().relative(direction));
				if (neighbour != null && neighbour.isSolidRender()) {
					hidden.add(direction);
				}
			}
			pieces.add(new Piece(block.pos(), block.state(), hidden));
		}
		return pieces;
	}

	/** The colour textured ghost blocks are multiplied by for a ghost tinted {@code rgb}: see-through and paled. */
	public static int tint(int rgb) {
		return ARGB.color(ALPHA, ARGB.srgbLerp(PALE, ARGB.opaque(rgb), 0xFFFFFFFF) & 0xFFFFFF);
	}

	/**
	 * The colour a tinted face (grass top, leaves, vines) of a ghost tinted {@code rgb} is multiplied by: the ghost's
	 * {@link #tint(int)} multiplied by the block's own colour {@code blockColor} where it stands, such as the biome's grass
	 * colour. The block colour's alpha is ignored, so the face is as see-through as the rest of the ghost.
	 */
	public static int tint(int rgb, int blockColor) {
		return ARGB.multiply(tint(rgb), ARGB.opaque(blockColor));
	}
}
