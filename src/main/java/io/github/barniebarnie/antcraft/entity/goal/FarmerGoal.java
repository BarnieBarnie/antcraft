package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.work.Farming;
import io.github.barniebarnie.antcraft.work.Storage;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Farmer work loop: harvest ripe crops from the job board, replant them, store the produce. */
public class FarmerGoal extends ColonyWorkGoal {
	/** Seeds a farmer keeps for replanting. */
	private static final int KEEP_SEEDS = 16;
	private static final int MIN_FREE_SLOTS = 2;

	private boolean depositing;

	public FarmerGoal(AntBot bot) {
		super(bot, BotRole.FARMER);
	}

	@Override
	protected void work(ServerLevel level, Colony colony) {
		if (this.depositing) {
			Container core = Storage.containerAt(level, colony.core());
			if (core == null) {
				this.depositing = false;
			} else if (this.reach(colony.core())) {
				this.storeProduce(core);
				this.depositing = false;
			}
			return;
		}
		if (freeSlots(this.bot.getInventory()) < MIN_FREE_SLOTS) {
			this.depositing = true;
			return;
		}

		Optional<Job> job = colony.board().claim(this.bot.getUUID(), Set.of(JobType.HARVEST_CROP), this.bot.blockPosition(), level.getGameTime());
		this.hasJob = job.isPresent();
		if (job.isEmpty()) {
			if (this.hasProduce()) {
				this.depositing = true;
			} else {
				this.cooldown = IDLE_TICKS;
			}
			return;
		}

		BlockPos crop = job.get().target();
		BlockState state = level.getBlockState(crop);
		if (!Farming.isRipe(state)) {
			colony.board().complete(job.get().id());
			return;
		}
		if (this.reach(crop) && this.breaker.tick(level, crop, true)) {
			this.replant(level, crop, state.getBlock());
			colony.board().complete(job.get().id());
			this.hasJob = false;
		}
	}

	private void replant(ServerLevel level, BlockPos pos, Block crop) {
		if (!level.getBlockState(pos).isAir()) {
			return;
		}
		int slot = findSlot(this.bot.getInventory(), stack -> Farming.plants(stack, crop));
		if (slot >= 0 && crop.defaultBlockState().canSurvive(level, pos)) {
			level.setBlockAndUpdate(pos, crop.defaultBlockState());
			this.bot.getInventory().getItem(slot).shrink(1);
		}
	}

	private boolean hasProduce() {
		SimpleContainer inventory = this.bot.getInventory();
		int seeds = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty()) {
				continue;
			}
			if (Farming.isSeed(stack)) {
				seeds += stack.getCount();
			} else {
				return true;
			}
		}
		return seeds > KEEP_SEEDS;
	}

	/** Puts everything in the core except the seeds needed for replanting. */
	private void storeProduce(Container core) {
		SimpleContainer inventory = this.bot.getInventory();
		int keptSeeds = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty()) {
				continue;
			}
			int keep = 0;
			if (Farming.isSeed(stack)) {
				keep = Math.min(stack.getCount(), KEEP_SEEDS - keptSeeds);
				keptSeeds += keep;
			}
			ItemStack rest = HopperBlockEntity.addItem(null, core, stack.split(stack.getCount() - keep), null);
			stack.grow(rest.getCount());
		}
		inventory.setChanged();
		core.setChanged();
	}
}
