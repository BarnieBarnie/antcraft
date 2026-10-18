package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Mining;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;

/**
 * Dig jobs ({@link JobType#CLEAR_AREA}): batches of a build plan that only clear blocks, such as
 * the ground above a terraform's height. Miners dig them, and builders while the colony has no
 * miner. The logs of a tree that lumberjacks are to fell are left to them. Water in the way, or
 * beside a block to dig, is left to builders too: they scoop or fill it and wall off water from
 * outside the plan, which miners do not.
 */
public final class Digging {
	private Digging() {
	}

	/** Whether the block at {@code pos} is a log of a tree a lumberjack is to fell: one a tree job covers that is not given up. */
	static boolean isForLumberjacks(ServerLevel level, Colony colony, BlockPos pos) {
		return level.getBlockState(pos).is(BlockTags.LOGS) && colony.board().jobs().stream()
			.anyMatch(job -> job.type() == JobType.CHOP_TREE && !job.givenUp() && job.blocks().contains(pos));
	}

	/** Whether a miner can dig {@code step}'s block now: it is in the way, breakable, holds back no fluid, and is no tree's. */
	public static boolean isDiggable(ServerLevel level, Colony colony, BuildPlan.Step step) {
		return step.needsClearing(level.getBlockState(step.pos())) && Mining.isMinable(level, step.pos()) && !isForLumberjacks(level, colony, step.pos());
	}

	/** Whether {@code step}'s block is water or touches it, so a miner cannot dig it. Lava a terraform leaves alone (see {@link io.github.barniebarnie.antcraft.work.Terraform#touchesLava}). */
	private static boolean isWet(ServerLevel level, BuildPlan.Step step) {
		if (level.getFluidState(step.pos()).is(FluidTags.WATER)) {
			return true;
		}
		for (Direction side : Direction.values()) {
			if (level.getFluidState(step.pos().relative(side)).is(FluidTags.WATER)) {
				return true;
			}
		}
		return false;
	}

	/** Whether {@code batch} still has a step left in or beside water, which builders clear, while the colony has a builder. */
	static boolean needsBuilder(ServerLevel level, Colony colony, BuildPlan batch) {
		return has(level, colony, BotRole.BUILDER)
			&& batch.steps().stream().anyMatch(step -> !step.isDone(level.getBlockState(step.pos())) && isWet(level, step));
	}

	/** Whether {@code batch} still has logs in it that lumberjacks are to fell. */
	static boolean waitsForLumberjacks(ServerLevel level, Colony colony, BuildPlan batch) {
		return batch.steps().stream().anyMatch(step -> !step.isDone(level.getBlockState(step.pos())) && isForLumberjacks(level, colony, step.pos()));
	}

	/** Whether a miner has something to do in the batch of dig job {@code job}, or can finish it or report it blocked. */
	static boolean isReady(ServerLevel level, Colony colony, Job job) {
		return colony.build(job.id()).filter(batch -> batch.nextStep(level::getBlockState, step -> isDiggable(level, colony, step)).isPresent()
			|| !waitsForLumberjacks(level, colony, batch) && !needsBuilder(level, colony, batch)).isPresent();
	}

	/** Whether the colony has a miner, which takes its dig jobs instead of its builders. */
	static boolean hasMiner(ServerLevel level, Colony colony) {
		return has(level, colony, BotRole.MINER);
	}

	private static boolean has(ServerLevel level, Colony colony, BotRole role) {
		return colony.members().stream().anyMatch(id -> level.getEntity(id) instanceof AntBot bot && bot.getRole() == role);
	}

	/**
	 * The bot {@code bot} has nothing left to dig in the batch of {@code job}. While lumberjacks still
	 * have trees to fell in it, or builders water to clear, the batch waits and the job is freed for
	 * them; the batches that need it wait too, as what they build could bury a tree. Otherwise the
	 * batch is done, or the steps left are reported as blocked, such as water with no builder to
	 * clear it, or blocks out of reach.
	 */
	static void putAside(ServerLevel level, Colony colony, Job job, UUID bot) {
		Optional<BuildPlan> batch = colony.build(job.id());
		boolean water = batch.isPresent() && needsBuilder(level, colony, batch.get());
		if (water || batch.isPresent() && waitsForLumberjacks(level, colony, batch.get())) {
			colony.batchWaiting(job.id(), water ? Colony.Wait.FLUID : Colony.Wait.TREES);
			colony.board().release(bot);
			return;
		}
		batch.ifPresent(b -> {
			List<BlockPos> left = b.steps().stream().filter(step -> !step.isDone(level.getBlockState(step.pos()))).map(BuildPlan.Step::pos).toList();
			if (left.isEmpty()) {
				colony.finishBatch(job.id());
			} else {
				colony.reportBlocked(job.id(), left);
			}
		});
		colony.board().complete(job.id());
	}
}
