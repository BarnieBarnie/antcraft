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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
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
	/**
	 * How far, in blocks sideways, the biomes around a block colour it: the game's widest biome blend (7) and a block more,
	 * as biomes are kept per four blocks.
	 */
	public static final int BIOME_REACH = 8;
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
	 * of the same fluid above, else the fluid's own height, as the game draws it), the height of the surface at each corner
	 * of the block, and the faces not to draw: against more of the same fluid in the ghost, against a solid block of the
	 * ghost (but for the surface, which shows under it), and where the block holding it covers the face itself, as a bottom
	 * slab covers its water's bottom.
	 */
	public record Fluid(FluidState state, float height, Corners corners, Set<Direction> hidden) {
		public Fluid {
			hidden = copy(hidden);
		}
	}

	/**
	 * The height of a fluid's surface at the corners of its block, as the game slopes it towards lower fluid beside it: each
	 * corner is the average of the heights of the blocks around it, a nearly full one weighed ten times, and the top of the
	 * block where one of them has the fluid above it. Only the ghost's own blocks count: a block of the ghost that holds
	 * none of the fluid and is not solid counts as empty, and blocks outside the ghost (air in it among them, which is not
	 * drawn) are left out, so the surface of a pool ghost stays flat up to its edges.
	 */
	public record Corners(float northWest, float northEast, float southEast, float southWest) {
		/** A flat surface at {@code height}. */
		public static Corners flat(float height) {
			return new Corners(height, height, height, height);
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
		boolean full = above != null && sameFluid(fluid, above);
		float height = full ? 1.0F : fluid.getOwnHeight();
		Corners corners = full ? Corners.flat(1.0F) : corners(fluid, block.pos(), height, drawn);
		Set<Direction> hidden = EnumSet.noneOf(Direction.class);
		for (Direction direction : Direction.values()) {
			BlockState neighbour = drawn.get(block.pos().relative(direction));
			boolean covered = neighbour != null
				&& (sameFluid(fluid, neighbour) || direction != Direction.UP && neighbour.isSolidRender());
			if (covered || coversItself(block.state(), direction)) {
				hidden.add(direction);
			}
		}
		return Optional.of(new Fluid(fluid, height, corners, hidden));
	}

	/** The corner heights of {@code fluid} at {@code pos} with its own surface at {@code height}, as the game works them out. */
	private static Corners corners(FluidState fluid, BlockPos pos, float height, Map<BlockPos, BlockState> drawn) {
		float north = height(fluid, pos.north(), drawn);
		float south = height(fluid, pos.south(), drawn);
		float east = height(fluid, pos.east(), drawn);
		float west = height(fluid, pos.west(), drawn);
		return new Corners(
			corner(fluid, height, north, west, pos.north().west(), drawn),
			corner(fluid, height, north, east, pos.north().east(), drawn),
			corner(fluid, height, south, east, pos.south().east(), drawn),
			corner(fluid, height, south, west, pos.south().west(), drawn));
	}

	/**
	 * One corner's height between the block's own {@code height}, its two neighbours' heights and, if either neighbour
	 * holds some, the diagonal block's, as {@code FluidRenderer.calculateAverageHeight} works it out.
	 */
	private static float corner(FluidState fluid, float height, float side, float otherSide, BlockPos diagonal, Map<BlockPos, BlockState> drawn) {
		if (side >= 1.0F || otherSide >= 1.0F) {
			return 1.0F;
		}
		float[] weighed = new float[2];
		if (side > 0.0F || otherSide > 0.0F) {
			float diagonalHeight = height(fluid, diagonal, drawn);
			if (diagonalHeight >= 1.0F) {
				return 1.0F;
			}
			weigh(weighed, diagonalHeight);
		}
		weigh(weighed, height);
		weigh(weighed, otherSide);
		weigh(weighed, side);
		return weighed[0] / weighed[1];
	}

	/** Adds {@code height} to the weighed sum: ten times when nearly full, once when there, not at all when negative. */
	private static void weigh(float[] weighed, float height) {
		if (height >= 0.8F) {
			weighed[0] += height * 10.0F;
			weighed[1] += 10.0F;
		} else if (height >= 0.0F) {
			weighed[0] += height;
			weighed[1]++;
		}
	}

	/**
	 * The height of {@code fluid} in the ghost's block at {@code pos} for the corners beside it: the top with the same fluid
	 * above, its own height, 0 for a block of the ghost without the fluid that is not solid, and -1 (left out) for a solid
	 * block or none.
	 */
	private static float height(FluidState fluid, BlockPos pos, Map<BlockPos, BlockState> drawn) {
		BlockState state = drawn.get(pos);
		if (state == null) {
			return -1.0F;
		}
		if (sameFluid(fluid, state)) {
			BlockState above = drawn.get(pos.above());
			return above != null && sameFluid(fluid, above) ? 1.0F : state.getFluidState().getOwnHeight();
		}
		return state.isSolid() ? -1.0F : 0.0F;
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

	/** The box around the blocks of {@code pieces}, or none without blocks. */
	public static Optional<BoundingBox> area(Collection<Piece> pieces) {
		return BoundingBox.encapsulatingPositions(pieces.stream().map(Piece::pos).toList());
	}

	/**
	 * Whether the biomes of {@code chunk} may colour a ghost standing in {@code area}: the chunk is within
	 * {@link #BIOME_REACH} blocks of it sideways, as the game blends biome colours over the blocks around one.
	 */
	public static boolean biomesReach(ChunkPos chunk, BoundingBox area) {
		return chunk.getMinBlockX() <= area.maxX() + BIOME_REACH && chunk.getMaxBlockX() >= area.minX() - BIOME_REACH
			&& chunk.getMinBlockZ() <= area.maxZ() + BIOME_REACH && chunk.getMaxBlockZ() >= area.minZ() - BIOME_REACH;
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
