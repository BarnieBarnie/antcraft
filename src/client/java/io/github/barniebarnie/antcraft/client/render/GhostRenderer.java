package io.github.barniebarnie.antcraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.barniebarnie.antcraft.blueprint.BlueprintSummary;
import io.github.barniebarnie.antcraft.blueprint.GhostLayout;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Draws blocks that are not there yet as see-through ghosts: each block's own model with its textures, translucent and
 * tinted (see {@link GhostLayout#tint(int)}), fully lit so a ghost shows at night too. Faces against another solid block of the
 * same ghost are left out ({@link GhostLayout#pieces}). Faces the game tints, such as grass tops and leaves, are first
 * multiplied by the block's colour where the ghost stands, so they show in their biome's green
 * ({@link GhostLayout#tint(int, int)}). A block with no model to draw, such as water, is drawn as the
 * outline of its shape instead. It submits to the frame's {@link SubmitNodeCollector}, so call it from Fabric's
 * {@code LevelRenderEvents.COLLECT_SUBMITS}. Used for the preview of a blueprint before placing it and for the ghosts of
 * placed builds.
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

	private GhostRenderer() {
	}

	/** Submits {@code blocks} at their positions for this frame, tinted {@code rgb} (see {@link #VALID} and {@link #REFUSED}). */
	public static void submit(LevelRenderContext context, Collection<BlueprintSummary.Block> blocks, int rgb) {
		Vec3 camera = context.levelState().cameraRenderState.pos;
		PoseStack poseStack = context.poseStack();
		SubmitNodeCollector collector = context.submitNodeCollector();
		BlockStateModelSet models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
		RenderType translucent = Sheets.translucentBlockItemSheet();
		int tint = GhostLayout.tint(rgb);
		RandomSource random = RandomSource.create();
		BlockColors blockColors = Minecraft.getInstance().getBlockColors();
		ClientLevel level = Minecraft.getInstance().level;
		for (GhostLayout.Piece piece : GhostLayout.pieces(blocks)) {
			BlockPos pos = piece.pos();
			BlockState state = piece.state();
			List<BlockStateModelPart> parts = new ArrayList<>();
			if (state.getRenderShape() == RenderShape.MODEL) {
				random.setSeed(state.getSeed(pos));
				models.get(state).collectParts(random, parts);
			}
			poseStack.pushPose();
			poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
			if (parts.isEmpty()) {
				collector.submitShapeOutline(poseStack, outline(state), RenderTypes.linesTranslucent(), ARGB.color(STROKE_ALPHA, rgb), STROKE_WIDTH, false);
			} else {
				poseStack.translate(state.getOffset(pos));
				Set<Direction> hidden = piece.hidden();
				int[] tints = tints(blockColors, level, state, pos, rgb);
				collector.submitCustomGeometry(poseStack, translucent, (pose, buffer) -> putQuads(pose, buffer, parts, hidden, tint, tints));
			}
			poseStack.popPose();
		}
	}

	/**
	 * The colour of each tint layer of {@code state} at {@code pos} for a ghost tinted {@code rgb}: the block's colour in
	 * the world (its biome's grass or foliage colour) under the ghost tint, as the game colours the block when placed.
	 */
	private static int[] tints(BlockColors blockColors, ClientLevel level, BlockState state, BlockPos pos, int rgb) {
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

	/** The block's shape where it stands at the origin, or a full block when it has none (such as water). */
	private static VoxelShape outline(BlockState state) {
		VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		return shape.isEmpty() ? Shapes.block() : shape;
	}
}
