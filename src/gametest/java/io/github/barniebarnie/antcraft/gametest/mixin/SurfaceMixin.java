package io.github.barniebarnie.antcraft.gametest.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import io.github.barniebarnie.antcraft.script.SenseApi;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Leaves barrier out of surface() (#790): the framework roofs a test area with barrier (#753), which the heightmap
 * counts, so surface() gave the top of the ceiling, a goal no bot reaches. It is the top of the area's own blocks instead.
 */
@Mixin(SenseApi.class)
abstract class SurfaceMixin {
	@ModifyReturnValue(method = "surfaceY", at = @At("RETURN"))
	private static int antcraft$underBarrier(int y, LevelChunk chunk, BlockPos at) {
		BlockPos.MutableBlockPos p = at.mutable().setY(y - 1);
		if (!chunk.getBlockState(p).is(Blocks.BARRIER)) {
			return y;
		}
		while (p.getY() > chunk.getMinY()) {
			BlockState state = chunk.getBlockState(p.move(0, -1, 0));
			if (!state.is(Blocks.BARRIER) && Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(state)) {
				return p.getY() + 1;
			}
		}
		return y;
	}
}
