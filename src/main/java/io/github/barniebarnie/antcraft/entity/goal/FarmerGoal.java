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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Farmer work loop: harvest ripe crops from the job board, replant them, store the produce.
 * It also tills and plants the colony field, breaking grass for seeds when the colony has none, and
 * waters the field's middle with a bucket.
 * Now and then it swaps its hoe for a better one in the core, or orders the best one the colony can make.
 */
public class FarmerGoal extends ColonyWorkGoal {
	/** Seeds a farmer keeps for replanting. */
	private static final int KEEP_SEEDS = 16;
	private static final int MIN_FREE_SLOTS = 2;

	/** How often a farmer with an empty bucket looks for water to fill it at. */
	private static final int WATER_SCAN_TICKS = 100;
	private static final Set<JobType> JOBS = EnumSet.of(JobType.HARVEST_CROP, JobType.PLANT_CROP);

	private boolean depositing;
	/** Grass the farmer is breaking for seeds. */
	private @Nullable BlockPos grass;
	/** Water the farmer is filling its bucket at. */
	private @Nullable BlockPos water;
	private long nextWaterScan;

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
		if (this.waterField(level, colony)) {
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
			this.till(level, crop.below());
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

	private void till(ServerLevel level, BlockPos soil) {
		ItemStack hand = this.bot.getMainHandItem();
		if (!ToolKind.HOE.is(hand)) {
			int slot = findSlot(this.bot.getInventory(), ToolKind.HOE::is);
			this.bot.setItemSlot(EquipmentSlot.MAINHAND, this.bot.getInventory().getItem(slot));
			this.bot.getInventory().setItem(slot, hand);
		}
		this.bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		level.setBlockAndUpdate(soil, Blocks.FARMLAND.defaultBlockState());
		this.bot.getMainHandItem().hurtAndBreak(1, this.bot, EquipmentSlot.MAINHAND);
	}

	/**
	 * Waters the middle of the colony field with a bucket: it pours a water bucket, fills an empty
	 * one at the nearest water in the territory, or fetches one from the core. Without any bucket in
	 * the colony it orders one. Returns true while busy, so the caller skips its own work this tick.
	 * ponytail: the field is planted while the water waits; farmland under a crop stays tilled when
	 * dry, the crops just grow slower until the water comes.
	 */
	private boolean waterField(ServerLevel level, Colony colony) {
		BlockPos center = colony.field().map(Field::center).orElse(null);
		if (center == null || level.getBlockState(center).is(Blocks.WATER)) {
			return false;
		}
		SimpleContainer inventory = this.bot.getInventory();
		int full = findSlot(inventory, stack -> stack.is(Items.WATER_BUCKET));
		if (full >= 0) {
			if (this.reach(center)) {
				this.bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
				level.setBlockAndUpdate(center, Blocks.WATER.defaultBlockState());
				inventory.setItem(full, new ItemStack(Items.BUCKET));
			}
			return true;
		}
		int empty = findSlot(inventory, stack -> stack.is(Items.BUCKET));
		if (empty >= 0) {
			return this.fillBucket(level, colony, empty);
		}
		Container core = Storage.containerAt(level, colony.core());
		if (core != null && findSlot(core, FarmerGoal::isBucket) >= 0) {
			if (this.reach(colony.core())) {
				Storage.moveItems(core, inventory, FarmerGoal::isBucket, 1);
			}
			return true;
		}
		// ponytail: a bucket in a storage chest counts as owned but is not fetched; the hauler keeps a stack of anything in the core, so one only ends up there by hand.
		Map<Item, Integer> stock = Storage.colonyStock(level, colony);
		if (stock.getOrDefault(Items.BUCKET, 0) + stock.getOrDefault(Items.WATER_BUCKET, 0) == 0 && !colony.orders().containsKey(Items.BUCKET)) {
			colony.addOrder(Items.BUCKET, 1);
		}
		return false;
	}

	/** Walks to the nearest water source and fills the bucket in {@code slot}; false when there is no water to fill it at. */
	private boolean fillBucket(ServerLevel level, Colony colony, int slot) {
		if (this.water == null || !isWaterSource(level.getBlockState(this.water))) {
			this.water = null;
			if (level.getGameTime() < this.nextWaterScan) {
				return false;
			}
			this.nextWaterScan = level.getGameTime() + WATER_SCAN_TICKS;
			this.water = nearest(level, colony, this.bot.blockPosition(), pos -> isWaterSource(level.getBlockState(pos)));
			if (this.water == null) {
				return false;
			}
		}
		if (this.reach(this.water)) {
			this.scoop(level, this.water, slot);
			this.water = null;
		}
		return true;
	}

	private static boolean isWaterSource(BlockState state) {
		return state.is(Blocks.WATER) && state.getFluidState().isSource();
	}

	private static boolean isBucket(ItemStack stack) {
		return stack.is(Items.BUCKET) || stack.is(Items.WATER_BUCKET);
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
			this.grass = nearest(level, colony, this.bot.blockPosition(), pos -> Farming.isSeedGrass(level.getBlockState(pos)));
			if (this.grass == null) {
				this.cooldown = IDLE_TICKS;
				return;
			}
		}
		if (this.reach(this.grass) && this.breaker.tick(level, this.grass, true)) {
			this.grass = null;
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
			if (stack.isEmpty() || isKept(stack)) {
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

	/** Tools a farmer keeps when storing produce. */
	private static boolean isKept(ItemStack stack) {
		return ToolKind.HOE.is(stack) || isBucket(stack);
	}

	/** Puts everything in the core except hoes, buckets and the seeds needed for replanting. */
	private void storeProduce(Container core) {
		SimpleContainer inventory = this.bot.getInventory();
		int keptSeeds = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty() || isKept(stack)) {
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
