package io.github.barniebarnie.antcraft.pathing;

import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * One move of a path. {@code to} is the feet position after the move. Blocks in
 * {@code breaks} must be broken and blocks in {@code places} placed (in order) before
 * or while moving.
 */
public record Step(MoveType type, BlockPos to, List<BlockPos> breaks, List<BlockPos> places) {
}
