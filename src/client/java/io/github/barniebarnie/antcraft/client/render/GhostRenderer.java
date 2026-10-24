package io.github.barniebarnie.antcraft.client.render;

import io.github.barniebarnie.antcraft.blueprint.BlueprintSummary;
import java.util.Collection;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Draws blocks that are not there yet as see-through ghosts: each block's shape as a tinted, translucent box with an
 * outline. It emits gizmos, so call it where the client collects them for the frame, such as Fabric's
 * {@code LevelExtractionEvents.AFTER_BLOCK_OUTLINE_EXTRACTION}. Used for the preview of a blueprint before placing it, and
 * meant for any other ghost of unbuilt blocks.
 */
public final class GhostRenderer {
	/** A ghost that may be placed: pale blue. */
	public static final int VALID = 0x80C0FF;
	/** A ghost the server would refuse: red. */
	public static final int REFUSED = 0xFF4040;
	/** A placed build's blocks still to be built: amber. */
	public static final int PLANNED = 0xFFC040;
	/** At most this many blocks are drawn, so a huge structure cannot stall the frame. */
	public static final int MAX_BLOCKS = 4096;
	private static final int FILL_ALPHA = 0x50;
	private static final int STROKE_ALPHA = 0xC0;
	private static final float STROKE_WIDTH = 1.5F;

	private GhostRenderer() {
	}

	/** Draws {@code blocks} at their positions, tinted {@code rgb} (see {@link #VALID} and {@link #REFUSED}). */
	public static void draw(Collection<BlueprintSummary.Block> blocks, int rgb) {
		GizmoStyle style = GizmoStyle.strokeAndFill(ARGB.color(STROKE_ALPHA, rgb), STROKE_WIDTH, ARGB.color(FILL_ALPHA, rgb));
		blocks.stream().limit(MAX_BLOCKS).forEach(block -> Gizmos.cuboid(box(block).move(block.pos()), style));
	}

	/** The block's shape where it stands at the origin, or a full block when it has none (such as a sign's post or air-like plants). */
	private static AABB box(BlueprintSummary.Block block) {
		VoxelShape shape = block.state().getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		return shape.isEmpty() ? new AABB(BlockPos.ZERO) : shape.bounds();
	}
}
