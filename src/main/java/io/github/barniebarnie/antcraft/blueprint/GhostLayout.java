package io.github.barniebarnie.antcraft.blueprint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * What a ghost of unbuilt blocks draws, worked out without a client so it can be tested: which blocks, which of their
 * faces are hidden behind another solid block of the same ghost (drawn see-through, such faces would show as a grid
 * inside the ghost), the water or other fluid a block holds with the faces of it to draw, and the tint the textured
 * blocks are drawn with.
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

	/**
	 * One block to draw, with the faces that another solid block of the ghost covers, and the fluid it holds, if any (water,
	 * or a waterlogged block's water), which the game draws apart from the block's model.
	 */
	public record Piece(BlockPos pos, BlockState state, Set<Direction> hidden, Optional<Fluid> fluid) {
		public Piece {
			hidden = copy(hidden);
		}

		/** A block holding no fluid. */
		public Piece(BlockPos pos, BlockState state, Set<Direction> hidden) {
			this(pos, state, hidden, Optional.empty());
		}
	}

	/**
	 * The fluid of a {@link Piece}: its surface {@code height} above the bottom of the block (the top of the block with more
	 * of the same fluid above, else the fluid's own height, as the game draws it), and the faces not to draw: against more
	 * of the same fluid in the ghost, against a solid block of the ghost (but for the surface, which shows under it), and
	 * where the block holding it covers the face itself, as a bottom slab covers its water's bottom.
	 */
	public record Fluid(FluidState state, float height, Set<Direction> hidden) {
		public Fluid {
			hidden = copy(hidden);
		}
	}

	private static Set<Direction> copy(Set<Direction> faces) {
		return faces.isEmpty() ? Set.of() : EnumSet.copyOf(faces);
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
			pieces.add(new Piece(block.pos(), block.state(), hidden, fluid(block, drawn)));
		}
		return pieces;
	}

	private static Optional<Fluid> fluid(BlueprintSummary.Block block, Map<BlockPos, BlockState> drawn) {
		FluidState fluid = block.state().getFluidState();
		if (fluid.isEmpty()) {
			return Optional.empty();
		}
		BlockState above = drawn.get(block.pos().above());
		float height = above != null && sameFluid(fluid, above) ? 1.0F : fluid.getOwnHeight();
		Set<Direction> hidden = EnumSet.noneOf(Direction.class);
		for (Direction direction : Direction.values()) {
			BlockState neighbour = drawn.get(block.pos().relative(direction));
			boolean covered = neighbour != null
				&& (sameFluid(fluid, neighbour) || direction != Direction.UP && neighbour.isSolidRender());
			if (covered || coversItself(block.state(), direction)) {
				hidden.add(direction);
			}
		}
		return Optional.of(new Fluid(fluid, height, hidden));
	}

	private static boolean sameFluid(FluidState fluid, BlockState neighbour) {
		return neighbour.getFluidState().getType().isSame(fluid.getType());
	}

	/** Whether {@code state}'s own face towards {@code direction} covers that face of the fluid in it, as the game tells. */
	private static boolean coversItself(BlockState state, Direction direction) {
		VoxelShape face = state.getFaceOcclusionShape(direction);
		if (face.isEmpty()) {
			return false;
		}
		return face == Shapes.block() || Shapes.blockOccludes(Shapes.block(), face, direction.getOpposite());
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
