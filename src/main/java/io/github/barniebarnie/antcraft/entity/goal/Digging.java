package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;

/**
 * Dig jobs ({@link JobType#CLEAR_AREA}): batches of a build plan that only clear blocks, such as
 * the ground above a terraform's height. Any colony bot digs them out through {@link WorkerGoal}, on
 * {@link BuilderGoal}'s executor. The logs of a tree with a tree job are left to that job, which is
 * open to any bot too, so the tree is felled, not dug out: the batch waits for it.
 */
public final class Digging {
	private Digging() {
	}

	/** Whether the block at {@code pos} is a log of a tree to be felled: one a tree job covers that is not given up. */
	static boolean isForTreeJob(ServerLevel level, Colony colony, BlockPos pos) {
		return level.getBlockState(pos).is(BlockTags.LOGS) && colony.board().jobs().stream()
			.anyMatch(job -> job.type() == JobType.CHOP_TREE && !job.givenUp() && job.blocks().contains(pos));
	}

	/** Whether {@code batch} still has logs in it of a tree to be felled. */
	static boolean waitsForTrees(ServerLevel level, Colony colony, BuildPlan batch) {
		return batch.steps().stream().anyMatch(step -> !step.isDone(level.getBlockState(step.pos())) && isForTreeJob(level, colony, step.pos()));
	}
}
