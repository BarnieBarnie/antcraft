package io.github.barniebarnie.antcraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.barniebarnie.antcraft.blueprint.BlueprintSummary;
import io.github.barniebarnie.antcraft.blueprint.GhostCache;
import io.github.barniebarnie.antcraft.blueprint.GhostLayout;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Draws blocks that are not there yet as see-through ghosts: each block's own model with its textures, translucent and
 * tinted (see {@link GhostLayout#tint(int)}), fully lit so a ghost shows at night too. Faces against another solid block of the
 * same ghost are left out ({@link GhostLayout#pieces}). Faces the game tints, such as grass tops and leaves, are first
 * multiplied by the block's colour where the ghost stands, so they show in their biome's green
 * ({@link GhostLayout#tint(int, int)}). Water, and the water of a waterlogged block, is drawn as the game's still water
 * texture on its surface and bottom and the flowing texture on its sides, like the game draws it: the surface sloped
 * towards lower water of the ghost ({@link GhostLayout.Corners}), each face shaded as the level shades it, in the biome's
 * water colour under the ghost tint, and without faces against more of it ({@link GhostLayout.Fluid}); lava likewise,
 * untinted by biome. The surface keeps the still texture, without the direction the water would flow in. A block with neither a model nor a fluid is drawn as the
 * outline of its shape instead. It submits to the frame's {@link SubmitNodeCollector}, so call it from Fabric's
 * {@code LevelRenderEvents.COLLECT_SUBMITS}, and {@link #endFrame()} after the frame's last ghost. Used for the preview of a
 * blueprint before placing it and for the ghosts of placed builds.
 *
 * <p>What a ghost draws, its models, faces and colours, is worked out once and kept while the ghost, its tint, the level
 * and the loaded models stay the same ({@link GhostCache}), so the biome colours are not looked up again every frame. When
 * a chunk loads near a ghost, its colours are worked out again ({@link #chunkLoaded}), as they may have been worked out
 * before the chunk's biomes were known.
 */
public final class GhostRenderer {
	/** A ghost that may be placed: pale blue. */
	public static final int VALID = 0x80C0FF;
	/** A ghost the server would refuse: red. */
	public static final int REFUSED = 0xFF4040;
	/** A placed build's blocks still to be built: amber. */
	public static final int PLANNED = 0xFFC040;
	private static final int STROKE_ALPHA = 0xC0;
	private static final float STROKE_WIDTH = 2.0F;
	private static final Direction[] DIRECTIONS = Direction.values();
	private static final GhostCache<Key, Layout> CACHE = new GhostCache<>();

	private GhostRenderer() {
	}

	/**
	 * What a ghost was worked out for. The ghost comes last so that it, whose {@code equals} may go through its blocks, is
	 * compared only when the rest match.
	 */
	private record Key(int rgb, @Nullable ClientLevel level, BlockStateModelSet models, FluidStateModelSet fluids, Object ghost) {
	}

	/** A ghost as it is drawn: its blocks, and the box around them, whose biomes colour them. */
	private record Layout(Optional<BoundingBox> area, List<Drawn> blocks) {
	}

	/** A block of a ghost as it is drawn: its model parts and their colours, its fluid, or the outline of its shape. */
	private record Drawn(
		BlockPos pos, Vec3 offset, List<BlockStateModelPart> parts, Set<Direction> hidden, int[] tints,
		Optional<FluidFaces> fluid, Optional<VoxelShape> outline
	) {
	}

	/**
	 * A fluid as it is drawn: its still texture (surface and bottom) and flowing texture (sides), the colour of each face
	 * by {@link Direction#ordinal()} (biome colour under the ghost tint, shaded), its surface's corner heights and the
	 * faces left out.
	 */
	private record FluidFaces(
		TextureAtlasSprite still, TextureAtlasSprite flowing, int[] colors, GhostLayout.Corners corners, Set<Direction> hidden
	) {
	}

	/**
	 * Submits the blocks of {@code ghost} at their positions for this frame, tinted {@code rgb} (see {@link #VALID} and
	 * {@link #REFUSED}). {@code blocks} is asked for only when the ghost is worked out anew: when no ghost equal to
	 * {@code ghost} was drawn in the last frame with the same tint.
	 */
	public static void submit(
		LevelRenderContext context, Object ghost, Supplier<? extends Collection<BlueprintSummary.Block>> blocks, int rgb
	) {
		Minecraft minecraft = Minecraft.getInstance();
		Key key = new Key(rgb, minecraft.level, minecraft.getModelManager().getBlockStateModelSet(),
			minecraft.getModelManager().getFluidStateModelSet(), ghost);
		List<Drawn> drawn = CACHE.get(key, k -> layOut(k, blocks.get())).blocks();
		Vec3 camera = context.levelState().cameraRenderState.pos;
		PoseStack poseStack = context.poseStack();
		SubmitNodeCollector collector = context.submitNodeCollector();
		RenderType translucent = Sheets.translucentBlockItemSheet();
		int tint = GhostLayout.tint(rgb);
		for (Drawn block : drawn) {
			BlockPos pos = block.pos();
			poseStack.pushPose();
			poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
			block.outline().ifPresent(shape ->
				collector.submitShapeOutline(poseStack, shape, RenderTypes.linesTranslucent(), ARGB.color(STROKE_ALPHA, rgb), STROKE_WIDTH, false));
			block.fluid().ifPresent(fluid -> collector.submitCustomGeometry(poseStack, translucent, (pose, buffer) -> putFluid(pose, buffer, fluid)));
			if (!block.parts().isEmpty()) {
				poseStack.translate(block.offset());
				collector.submitCustomGeometry(poseStack, translucent, (pose, buffer) -> putQuads(pose, buffer, block.parts(), block.hidden(), tint, block.tints()));
			}
			poseStack.popPose();
		}
	}

	/** Forgets the ghosts not drawn in this frame; call once a frame after the last {@link #submit}. */
	public static void endFrame() {
		CACHE.endFrame();
	}

	/**
	 * Forgets the colours of the ghosts in {@code level} that the biomes of {@code chunk} may colour, so they are worked out
	 * again with its biomes; call when a chunk loads.
	 */
	public static void chunkLoaded(ClientLevel level, ChunkPos chunk) {
		CACHE.forgetIf((key, layout) -> key.level() == level && layout.area().filter(area -> GhostLayout.biomesReach(chunk, area)).isPresent());
	}

	/** Works out how each block of a ghost is drawn: its model, the colours of its tinted faces, its fluid. */
	private static Layout layOut(Key key, Collection<BlueprintSummary.Block> blocks) {
		BlockColors blockColors = Minecraft.getInstance().getBlockColors();
		RandomSource random = RandomSource.create();
		List<GhostLayout.Piece> pieces = GhostLayout.pieces(blocks);
		List<Drawn> drawn = new ArrayList<>(pieces.size());
		for (GhostLayout.Piece piece : pieces) {
			BlockPos pos = piece.pos();
			BlockState state = piece.state();
			List<BlockStateModelPart> parts = new ArrayList<>();
			if (state.getRenderShape() == RenderShape.MODEL) {
				random.setSeed(state.getSeed(pos));
				key.models().get(state).collectParts(random, parts);
			}
			Optional<FluidFaces> fluid = piece.fluid().map(f -> fluidFaces(key, state, pos, f));
			Optional<VoxelShape> outline = parts.isEmpty() && fluid.isEmpty() ? Optional.of(outline(state)) : Optional.empty();
			int[] tints = parts.isEmpty() ? new int[0] : tints(blockColors, key.level(), state, pos, key.rgb());
			drawn.add(new Drawn(pos, state.getOffset(pos), List.copyOf(parts), piece.hidden(), tints, fluid, outline));
		}
		return new Layout(GhostLayout.area(pieces), List.copyOf(drawn));
	}

	/**
	 * The fluid {@code fluid} of {@code state} at {@code pos}: its textures, and its biome colour under the ghost tint
	 * shaded for each face as the level shades it.
	 */
	private static FluidFaces fluidFaces(Key key, BlockState state, BlockPos pos, GhostLayout.Fluid fluid) {
		FluidModel model = key.fluids().get(fluid.state());
		BlockTintSource source = model.tintSource();
		int color;
		if (source == null) {
			color = GhostLayout.tint(key.rgb());
		} else {
			color = GhostLayout.tint(key.rgb(), key.level() == null ? source.color(state) : source.colorInWorld(state, key.level(), pos));
		}
		CardinalLighting lighting = key.level() == null ? CardinalLighting.DEFAULT : key.level().cardinalLighting();
		int[] colors = new int[DIRECTIONS.length];
		for (Direction direction : DIRECTIONS) {
			colors[direction.ordinal()] = ARGB.scaleRGB(color, lighting.byFace(direction));
		}
		return new FluidFaces(model.stillMaterial().sprite(), model.flowingMaterial().sprite(), colors, fluid.corners(), fluid.hidden());
	}

	/**
	 * The colour of each tint layer of {@code state} at {@code pos} for a ghost tinted {@code rgb}: the block's colour in
	 * the world (its biome's grass or foliage colour) under the ghost tint, as the game colours the block when placed.
	 */
	private static int[] tints(BlockColors blockColors, @Nullable ClientLevel level, BlockState state, BlockPos pos, int rgb) {
		List<BlockTintSource> sources = blockColors.getTintSources(state);
		int[] tints = new int[sources.size()];
		for (int i = 0; i < tints.length; i++) {
			BlockTintSource source = sources.get(i);
			tints[i] = GhostLayout.tint(rgb, level == null ? source.color(state) : source.colorInWorld(state, level, pos));
		}
		return tints;
	}

	private static void putQuads(
		PoseStack.Pose pose, VertexConsumer buffer, List<BlockStateModelPart> parts, Set<Direction> hidden, int tint, int[] tints
	) {
		QuadInstance instance = new QuadInstance();
		instance.setLightCoords(LightCoordsUtil.FULL_BRIGHT);
		instance.setOverlayCoords(OverlayTexture.NO_OVERLAY);
		for (BlockStateModelPart part : parts) {
			for (Direction direction : DIRECTIONS) {
				if (!hidden.contains(direction)) {
					for (BakedQuad quad : part.getQuads(direction)) {
						putQuad(pose, buffer, quad, instance, tint, tints);
					}
				}
			}
			for (BakedQuad quad : part.getQuads(null)) {
				putQuad(pose, buffer, quad, instance, tint, tints);
			}
		}
	}

	/** Puts {@code quad} in the ghost tint, or for a tinted quad in its tint layer's colour (see {@link #tints}). */
	private static void putQuad(PoseStack.Pose pose, VertexConsumer buffer, BakedQuad quad, QuadInstance instance, int tint, int[] tints) {
		int layer = quad.materialInfo().tintIndex();
		instance.setColor(layer >= 0 && layer < tints.length ? tints[layer] : tint);
		buffer.putBakedQuad(pose, quad, instance);
	}

	/**
	 * Puts the faces of a fluid not left out, each counter-clockwise seen from outside: the surface at its corner heights
	 * and the bottom with the still texture, and each side up to the surface with the top half of the flowing texture, as
	 * the game draws a fluid's sides.
	 */
	private static void putFluid(PoseStack.Pose pose, VertexConsumer buffer, FluidFaces fluid) {
		GhostLayout.Corners c = fluid.corners();
		float nw = c.northWest();
		float ne = c.northEast();
		float se = c.southEast();
		float sw = c.southWest();
		for (Direction direction : DIRECTIONS) {
			if (fluid.hidden().contains(direction)) {
				continue;
			}
			// The corners top-left, bottom-left, bottom-right, top-right as seen from outside.
			float[][] corners = switch (direction) {
				case UP -> new float[][] {{0, nw, 0}, {0, sw, 1}, {1, se, 1}, {1, ne, 0}};
				case DOWN -> new float[][] {{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}};
				case NORTH -> new float[][] {{1, ne, 0}, {1, 0, 0}, {0, 0, 0}, {0, nw, 0}};
				case SOUTH -> new float[][] {{0, sw, 1}, {0, 0, 1}, {1, 0, 1}, {1, se, 1}};
				case WEST -> new float[][] {{0, nw, 0}, {0, 0, 0}, {0, 0, 1}, {0, sw, 1}};
				case EAST -> new float[][] {{1, se, 1}, {1, 0, 1}, {1, 0, 0}, {1, ne, 0}};
			};
			float[][] uvs;
			if (direction.getAxis() == Direction.Axis.Y) {
				TextureAtlasSprite still = fluid.still();
				uvs = new float[][] {{still.getU0(), still.getV0()}, {still.getU0(), still.getV1()}, {still.getU1(), still.getV1()}, {still.getU1(), still.getV0()}};
			} else {
				TextureAtlasSprite flowing = fluid.flowing();
				float left = flowing.getU(0.0F);
				float right = flowing.getU(0.5F);
				float bottom = flowing.getV(0.5F);
				uvs = new float[][] {{left, flowing.getV((1.0F - corners[0][1]) * 0.5F)}, {left, bottom}, {right, bottom},
					{right, flowing.getV((1.0F - corners[3][1]) * 0.5F)}};
			}
			int color = fluid.colors()[direction.ordinal()];
			for (int i = 0; i < 4; i++) {
				buffer.addVertex(pose, corners[i][0], corners[i][1], corners[i][2])
					.setColor(color)
					.setUv(uvs[i][0], uvs[i][1])
					.setOverlay(OverlayTexture.NO_OVERLAY)
					.setLight(LightCoordsUtil.FULL_BRIGHT)
					.setNormal(pose, direction.getStepX(), direction.getStepY(), direction.getStepZ());
			}
		}
	}

	/** The block's shape where it stands at the origin, or a full block when it has none. */
	private static VoxelShape outline(BlockState state) {
		VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		return shape.isEmpty() ? Shapes.block() : shape;
	}
}
