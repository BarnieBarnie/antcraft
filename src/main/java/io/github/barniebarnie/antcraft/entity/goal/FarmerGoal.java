package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.work.Farming;
import io.github.barniebarnie.antcraft.work.Field;
import io.github.barniebarnie.antcraft.work.Storage;
import io.github.barniebarnie.antcraft.work.ToolKind;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Farmer work loop: harvest ripe crops from the job board, replant them, store the produce.
 * It also tills and plants the colony field, breaking grass for seeds when the colony has none.
 * Now and then it swaps its hoe for a better one in the core, or orders the best one the colony can make.
 */
public class FarmerGoal extends ColonyWorkGoal {
	/** Seeds a farmer keeps for replanting. */
	private static final int KEEP_SEEDS = 16;
	private static final int MIN_FREE_SLOTS = 2;

	private static final int GRASS_SCAN_HEIGHT = 4;
	private static final Set<JobType> JOBS = EnumSet.of(JobType.HARVEST_CROP, JobType.PLANT_CROP);

	private boolean depositing;
	/** Grass the farmer is breaking for seeds. */
	private @Nullable BlockPos grass;

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
		if (this.upgradeTool(level, colony, ToolKind.HOE)) {
			return;
		}
		if (freeSlots(this.bot.getInventory()) < MIN_FREE_SLOTS) {
			this.depositing = true;
			return;
		}

		Optional<Job> job = colony.board().claim(this.bot.getUUID(), JOBS, this.bot.blockPosition(), level.getGameTime(),
			open -> open.type() != JobType.PLANT_CROP || this.canPlant(level, open.target()));
		this.hasJob = job.isPresent();
		if (job.isEmpty()) {
			if (this.needsSeeds(colony)) {
				this.getSeeds(level, colony);
			} else if (this.hasProduce()) {
				this.depositing = true;
			} else {
				this.cooldown = IDLE_TICKS;
			}
			return;
		}

		BlockPos crop = job.get().target();
		if (job.get().type() == JobType.PLANT_CROP) {
			this.plant(level, colony, job.get());
			return;
		}
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

	/** Clears the spot, tills the soil if needed and plants a seed, for a job in the colony field. */
	private void plant(ServerLevel level, Colony colony, Job job) {
		BlockPos crop = job.target();
		BlockState state = level.getBlockState(crop);
		if (state.getBlock() instanceof CropBlock || !Farming.isOpen(state) || !Farming.isSoil(level.getBlockState(crop.below()))) {
			// Planted already, or built over.
			colony.board().complete(job.id());
			this.hasJob = false;
			return;
		}
		if (!this.canPlant(level, crop)) {
			colony.board().release(this.bot.getUUID());
			this.hasJob = false;
			return;
		}
		if (!this.reach(crop)) {
			return;
		}
		if (!state.isAir()) {
			// Grass in the way; it may drop seeds.
			this.breaker.tick(level, crop, true);
			return;
		}
		if (!level.getBlockState(crop.below()).is(Blocks.FARMLAND)) {
			this.till(level, colony, crop.below());
		}
		int slot = findSlot(this.bot.getInventory(), Farming::isSeed);
		Block seed = ((BlockItem) this.bot.getInventory().getItem(slot).getItem()).getBlock();
		if (seed.defaultBlockState().canSurvive(level, crop)) {
			level.setBlockAndUpdate(crop, seed.defaultBlockState());
			this.bot.getInventory().getItem(slot).shrink(1);
		}
		colony.board().complete(job.id());
		this.hasJob = false;
	}

	private void till(ServerLevel level, Colony colony, BlockPos soil) {
		ItemStack hand = this.bot.getMainHandItem();
		if (!ToolKind.HOE.is(hand)) {
			int slot = findSlot(this.bot.getInventory(), ToolKind.HOE::is);
			this.bot.setItemSlot(EquipmentSlot.MAINHAND, this.bot.getInventory().getItem(slot));
			this.bot.getInventory().setItem(slot, hand);
		}
		this.bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		level.setBlockAndUpdate(soil, Blocks.FARMLAND.defaultBlockState());
		this.bot.getMainHandItem().hurtAndBreak(1, this.bot, EquipmentSlot.MAINHAND);
		// ponytail: the water comes for free; a bucket trip if that ever matters.
		colony.field().map(Field::center).filter(center -> !level.getBlockState(center).is(Blocks.WATER))
			.ifPresent(center -> level.setBlockAndUpdate(center, Blocks.WATER.defaultBlockState()));
	}

	/** Whether this farmer can plant at {@code crop} now: it carries a seed and the soil is tilled, or it has a hoe. */
	private boolean canPlant(ServerLevel level, BlockPos crop) {
		if (findSlot(this.bot.getInventory(), Farming::isSeed) < 0) {
			return false;
		}
		BlockState soil = level.getBlockState(crop.below());
		return soil.is(Blocks.FARMLAND) || Farming.isTillable(soil)
			&& (ToolKind.HOE.is(this.bot.getMainHandItem()) || findSlot(this.bot.getInventory(), ToolKind.HOE::is) >= 0);
	}

	private boolean needsSeeds(Colony colony) {
		return findSlot(this.bot.getInventory(), Farming::isSeed) < 0
			&& colony.board().jobs().stream().anyMatch(job -> job.isOpen() && job.type() == JobType.PLANT_CROP);
	}

	/** Takes seeds from the core, or breaks grass in the territory until some drop. */
	private void getSeeds(ServerLevel level, Colony colony) {
		Container core = Storage.containerAt(level, colony.core());
		if (core != null && findSlot(core, Farming::isSeed) >= 0) {
			if (this.reach(colony.core())) {
				Storage.moveItems(core, this.bot.getInventory(), Farming::isSeed, KEEP_SEEDS);
			}
			return;
		}
		if (this.grass == null || !Farming.isSeedGrass(level.getBlockState(this.grass))) {
			this.grass = this.nearestGrass(level, colony);
			if (this.grass == null) {
				this.cooldown = IDLE_TICKS;
				return;
			}
		}
		if (this.reach(this.grass) && this.breaker.tick(level, this.grass, true)) {
			this.grass = null;
		}
	}

	private @Nullable BlockPos nearestGrass(ServerLevel level, Colony colony) {
		BlockPos core = colony.core();
		int r = colony.radius();
		BlockPos nearest = null;
		for (BlockPos pos : BlockPos.betweenClosed(core.offset(-r, -GRASS_SCAN_HEIGHT, -r), core.offset(r, GRASS_SCAN_HEIGHT, r))) {
			if (Farming.isSeedGrass(level.getBlockState(pos)) && (nearest == null || pos.distSqr(this.bot.blockPosition()) < nearest.distSqr(this.bot.blockPosition()))) {
				nearest = pos.immutable();
			}
		}
		return nearest;
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
			if (stack.isEmpty() || ToolKind.HOE.is(stack)) {
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

	/** Puts everything in the core except hoes and the seeds needed for replanting. */
	private void storeProduce(Container core) {
		SimpleContainer inventory = this.bot.getInventory();
		int keptSeeds = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty() || ToolKind.HOE.is(stack)) {
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
