package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.TaskPlanner;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.colony.Territory;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.work.Farming;
import io.github.barniebarnie.antcraft.work.Field;
import io.github.barniebarnie.antcraft.work.GameRecipeBook;
import io.github.barniebarnie.antcraft.work.Storage;
import io.github.barniebarnie.antcraft.work.ToolKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Farm work. As the executor of crop jobs for {@link WorkerGoal}, through {@link #begin} and {@link #work}, it
 * gets any colony bot to harvest a ripe crop and replant it, or to till and plant a spot in the colony field,
 * fetching seeds and a hoe from the core or breaking grass for seeds, and watering the field's middle with a
 * bucket first. It stores the produce after each harvest.
 * Between jobs it shears sheep in the territory while the colony is short of wool, fetching shears from the core
 * or a storage chest or ordering a pair, and picks flowers the colony wants for dye.
 */
public class FarmerGoal extends ColonyWorkGoal implements WorkerGoal.Executor {
	/** Seeds a farmer keeps for replanting. */
	private static final int KEEP_SEEDS = 16;
	private static final int MIN_FREE_SLOTS = 2;

	/** How often a farmer with an empty bucket looks for water to fill it at. */
	private static final int WATER_SCAN_TICKS = 100;
	/** How long a bot that found no seeds anywhere leaves plant jobs alone. */
	private static final int NO_SEEDS_TICKS = 200;
	/** How often an idle farmer looks for a sheep to shear. */
	private static final int SHEEP_SCAN_TICKS = 100;
	/** Wool the colony keeps in stock; farmers stop shearing at this much. */
	private static final int KEEP_WOOL = 32;
	/** How close a farmer gets to a sheep to shear it. */
	private static final double SHEAR_REACH = 2.0;
	/** How far from a shorn sheep the farmer picks up the wool, and for how long. */
	private static final int FLEECE_RADIUS = 4;
	private static final int FLEECE_TICKS = 60;
	/** How long a farmer with produce and no room for it in the core or a storage chest farms on before it looks again. */
	private static final int NO_ROOM_TICKS = 200;

	private boolean depositing;
	/** When the farmer may look for room for its produce again, after it found none. */
	private long nextDeposit;
	/** The crop job begun through {@link #begin}, until it is done. */
	private @Nullable Job job;
	/** What became of the job begun, for {@link WorkerGoal} to report to the board. */
	private WorkerGoal.@Nullable Outcome outcome;
	/** Until when the bot leaves plant jobs alone, having found no seeds. */
	private long noSeedsUntil;
	/** Grass the farmer is breaking for seeds. */
	private @Nullable BlockPos grass;
	/** Grass the farmer found no spot to see from, skipped until it finds no other. */
	private final Set<BlockPos> hiddenGrass = new HashSet<>();
	/** Water the farmer is filling its bucket at. */
	private @Nullable BlockPos water;
	/** Water the farmer found no spot to see from, skipped until it finds no other. */
	private final Set<BlockPos> hiddenWater = new HashSet<>();
	private long nextWaterScan;
	/** The sheep the farmer is on its way to shear. */
	private @Nullable Sheep sheep;
	private long nextSheepScan;
	/** Where the farmer last sheared a sheep, to pick up the wool, until {@link #fleeceUntil}. */
	private @Nullable BlockPos fleece;
	private long fleeceUntil;
	/** The flower the farmer is on its way to pick. */
	private @Nullable BlockPos flower;
	/** Flowers the farmer found no spot to see from, skipped until it finds no other. */
	private final Set<BlockPos> hiddenFlowers = new HashSet<>();
	private long nextFlowerScan;

	public FarmerGoal(AntBot bot) {
		super(bot);
	}

	/**
	 * Farm work between jobs, for any bot: storing produce, shearing sheep while the colony is short of wool,
	 * and picking flowers the colony wants, for dye.
	 */
	@Override
	protected boolean betweenJobs(ServerLevel level, Colony colony) {
		if (this.depositing) {
			this.deposit(level, colony);
			return true;
		}
		return this.shearSheep(level, colony) || this.pickFlower(level, colony);
	}

	@Override
	public boolean isReady(ServerLevel level, Colony colony, Job job) {
		return job.type() != JobType.PLANT_CROP || level.getGameTime() >= this.noSeedsUntil
			|| findSlot(this.bot.getInventory(), Farming::isSeed) >= 0;
	}

	@Override
	public boolean begin(ServerLevel level, Job job) {
		this.outcome = null;
		BlockPos crop = job.target();
		BlockState state = level.getBlockState(crop);
		boolean todo = job.type() == JobType.HARVEST_CROP ? Farming.isRipe(state)
			// Not planted already, nor built over.
			: !(state.getBlock() instanceof CropBlock) && Farming.isOpen(state) && Farming.isSoil(level.getBlockState(crop.below()));
		if (!todo) {
			return false;
		}
		this.job = job;
		this.depositing = false;
		this.resetMovement();
		return true;
	}

	@Override
	public boolean work(ServerLevel level) {
		Optional<Colony> colony = Optional.ofNullable(this.bot.getColonyId()).flatMap(id -> ColonyManager.get(level).byId(id));
		if (colony.isEmpty()) {
			return false;
		}
		this.bot.setWorking(this.job != null);
		if (this.cooldown > 0) {
			this.cooldown--;
			return true;
		}
		if (this.job != null) {
			if (this.job.type() == JobType.PLANT_CROP) {
				this.plant(level, colony.get(), this.job);
			} else {
				this.harvest(level, this.job);
			}
			return true;
		}
		if (this.depositing) {
			this.deposit(level, colony.get());
			return true;
		}
		return false;
	}

	@Override
	public Optional<WorkerGoal.Outcome> takeOutcome() {
		Optional<WorkerGoal.Outcome> taken = Optional.ofNullable(this.outcome);
		this.outcome = null;
		return taken;
	}

	@Override
	public void reset() {
		this.job = null;
		this.outcome = null;
		this.depositing = false;
		this.resetMovement();
	}

	private void endJob(WorkerGoal.Outcome how) {
		this.outcome = how;
		this.job = null;
		this.bot.setWorking(false);
	}

	/** Harvests the ripe crop and replants it, then stores the produce, which it would otherwise keep from the colony. */
	private void harvest(ServerLevel level, Job job) {
		BlockPos crop = job.target();
		BlockState state = level.getBlockState(crop);
		if (!Farming.isRipe(state)) {
			this.endJob(WorkerGoal.Outcome.COMPLETE);
			return;
		}
		PathFollower.Status status = this.inSightOf(crop);
		if (status == PathFollower.Status.FAILED) {
			this.endJob(WorkerGoal.Outcome.RETRY);
		} else if (status == PathFollower.Status.ARRIVED && this.breaker.tick(level, crop, true)) {
			this.replant(level, crop, state.getBlock());
			this.endJob(WorkerGoal.Outcome.COMPLETE);
			this.depositing = !this.produce().isEmpty() && this.mayDeposit(level);
		}
	}

	/**
	 * Waters the field, gets seeds and a hoe if it needs them, then clears the spot, tills the soil if
	 * needed and plants a seed, for a job in the colony field. Gives the job back when it finds no seeds or hoe.
	 */
	private void plant(ServerLevel level, Colony colony, Job job) {
		BlockPos crop = job.target();
		BlockState state = level.getBlockState(crop);
		if (state.getBlock() instanceof CropBlock || !Farming.isOpen(state) || !Farming.isSoil(level.getBlockState(crop.below()))) {
			// Planted already, or built over.
			this.endJob(WorkerGoal.Outcome.COMPLETE);
			return;
		}
		if (this.waterField(level, colony)) {
			return;
		}
		if (findSlot(this.bot.getInventory(), Farming::isSeed) < 0) {
			if (!this.getSeeds(level, colony)) {
				this.noSeedsUntil = level.getGameTime() + NO_SEEDS_TICKS;
				this.endJob(WorkerGoal.Outcome.RELEASE);
			}
			return;
		}
		boolean tilled = level.getBlockState(crop.below()).is(Blocks.FARMLAND);
		if (!tilled && !ToolKind.HOE.is(this.bot.getMainHandItem()) && findSlot(this.bot.getInventory(), ToolKind.HOE::is) < 0) {
			Container core = Storage.containerAt(level, colony.core());
			if (core == null || findSlot(core, ToolKind.HOE::is) < 0) {
				this.cooldown = IDLE_TICKS;
				this.endJob(WorkerGoal.Outcome.RELEASE);
			} else if (this.reach(colony.core())) {
				Storage.moveItems(core, this.bot.getInventory(), ToolKind.HOE::is, 1);
			}
			return;
		}
		PathFollower.Status status = this.inSightOf(crop);
		if (status == PathFollower.Status.FAILED) {
			this.endJob(WorkerGoal.Outcome.RETRY);
		}
		if (status != PathFollower.Status.ARRIVED) {
			return;
		}
		if (!state.isAir()) {
			// Grass in the way; it may drop seeds.
			this.breaker.tick(level, crop, true);
			return;
		}
		if (!tilled) {
			this.till(level, crop.below());
		}
		int slot = findSlot(this.bot.getInventory(), Farming::isSeed);
		Block seed = ((BlockItem) this.bot.getInventory().getItem(slot).getItem()).getBlock();
		if (seed.defaultBlockState().canSurvive(level, crop)) {
			level.setBlockAndUpdate(crop, seed.defaultBlockState());
			this.bot.getInventory().getItem(slot).shrink(1);
		}
		this.endJob(WorkerGoal.Outcome.COMPLETE);
	}

	/**
	 * Moves to where the bot sees {@code pos}, as a player must to break or use the block: ARRIVED once there,
	 * FAILED, after which it waits a while, when there is no such spot it can get to.
	 */
	private PathFollower.Status inSightOf(BlockPos pos) {
		PathFollower.Status status = this.approach(this.inSight(pos), pos);
		if (status == PathFollower.Status.FAILED) {
			this.cooldown = IDLE_TICKS;
		}
		return status;
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
	 * one at the nearest water in the territory, or fetches one from the core or a storage chest. Without any bucket in
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
			if (this.inSightOf(center) == PathFollower.Status.ARRIVED) {
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
		BlockPos from = Storage.holding(level, colony, FarmerGoal::isBucket);
		if (from != null) {
			if (this.reach(from)) {
				Storage.moveItems(Storage.containerAt(level, from), inventory, FarmerGoal::isBucket, 1);
			}
			return true;
		}
		// ponytail: a spare bucket in another bot's pack counts as owned but is not fetched.
		Map<Item, Integer> stock = Storage.spare(level, colony, bot -> carried(bot, FarmerGoal::isBucket));
		if (stock.getOrDefault(Items.BUCKET, 0) + stock.getOrDefault(Items.WATER_BUCKET, 0) == 0 && !colony.orders().containsKey(Items.BUCKET)) {
			colony.addOrder(Items.BUCKET, 1);
		}
		return false;
	}

	/** Walks to where it sees the nearest water source it can, and fills the bucket in {@code slot}; false when there is no water to fill it at. */
	private boolean fillBucket(ServerLevel level, Colony colony, int slot) {
		if (this.water == null || !isWaterSource(level.getBlockState(this.water))) {
			this.water = null;
			if (level.getGameTime() < this.nextWaterScan) {
				return false;
			}
			this.nextWaterScan = level.getGameTime() + WATER_SCAN_TICKS;
			this.water = nearest(level, colony, this.bot.blockPosition(), pos -> isWaterSource(level.getBlockState(pos)) && !this.hiddenWater.contains(pos));
			if (this.water == null) {
				// Looked at again on the next scan: a wall may be gone by then.
				this.hiddenWater.clear();
				return false;
			}
		}
		PathFollower.Status status = this.inSightOf(this.water);
		if (status == PathFollower.Status.FAILED) {
			this.hiddenWater.add(this.water);
			this.water = null;
			this.nextWaterScan = 0;
		} else if (status == PathFollower.Status.ARRIVED) {
			this.scoop(level, this.water, slot);
			this.water = null;
		}
		return true;
	}

	private static boolean isWaterSource(BlockState state) {
		return state.is(Blocks.WATER) && state.getFluidState().isSource();
	}

	/** The item of the first stack {@code bot} carries, hand first, that {@code matches}; null without one: the one it works with. */
	private static @Nullable Item carried(AntBot bot, Predicate<ItemStack> matches) {
		if (matches.test(bot.getMainHandItem())) {
			return bot.getMainHandItem().getItem();
		}
		int slot = findSlot(bot.getInventory(), matches);
		return slot < 0 ? null : bot.getInventory().getItem(slot).getItem();
	}

	private static boolean isBucket(ItemStack stack) {
		return stack.is(Items.BUCKET) || stack.is(Items.WATER_BUCKET);
	}

	/**
	 * Shears the nearest sheep in the territory while the colony is short of wool, then picks up the
	 * wool; the farmer stores it with its other produce. Fetches shears from the core, or orders a
	 * pair when the colony has none. Returns true while busy.
	 */
	private boolean shearSheep(ServerLevel level, Colony colony) {
		if (this.fleece != null) {
			if (this.collectFleece(level, colony)) {
				return true;
			}
			this.fleece = null;
		}
		// Straight to the core with it, also wool walked over after the pickup ended: a farmer
		// waiting for seeds would otherwise keep it.
		if (findSlot(this.bot.getInventory(), stack -> stack.is(ItemTags.WOOL)) >= 0 && this.mayDeposit(level)) {
			this.depositing = true;
			return true;
		}
		if (this.sheep == null || !this.sheep.isAlive() || !this.sheep.readyForShearing()) {
			this.sheep = null;
			if (level.getGameTime() < this.nextSheepScan) {
				return false;
			}
			this.nextSheepScan = level.getGameTime() + SHEEP_SCAN_TICKS;
			this.sheep = this.findSheep(level, colony);
			if (this.sheep == null) {
				return false;
			}
		}
		SimpleContainer inventory = this.bot.getInventory();
		int shears = findSlot(inventory, stack -> stack.is(Items.SHEARS));
		if (shears < 0) {
			BlockPos from = Storage.holding(level, colony, stack -> stack.is(Items.SHEARS));
			if (from != null) {
				if (this.reach(from)) {
					Storage.moveItems(Storage.containerAt(level, from), inventory, stack -> stack.is(Items.SHEARS), 1);
				}
				return true;
			}
			// ponytail: spare shears another bot carries count as owned but are not fetched.
			if (Storage.spare(level, colony, bot -> carried(bot, stack -> stack.is(Items.SHEARS))).getOrDefault(Items.SHEARS, 0) == 0 && !colony.orders().containsKey(Items.SHEARS)) {
				colony.addOrder(Items.SHEARS, 1);
			}
			this.sheep = null;
			return false;
		}
		boolean inSight = this.bot.hasLineOfSight(this.sheep);
		if (this.bot.distanceToSqr(this.sheep) > SHEAR_REACH * SHEAR_REACH || !inSight) {
			PathFollower.Status status = this.travel(new Goal.Near(this.sheep.blockPosition(), 1));
			// Next to it but not in sight of it, as through a fence or glass: a player could not shear it from there either.
			if (status == PathFollower.Status.FAILED || status == PathFollower.Status.ARRIVED && !inSight) {
				this.sheep = null;
				this.cooldown = IDLE_TICKS;
			}
			return true;
		}
		this.stopTravel();
		this.bot.getLookControl().setLookAt(this.sheep);
		this.bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		ItemStack tool = inventory.getItem(shears);
		this.sheep.shear(level, SoundSource.NEUTRAL, tool);
		this.sheep.gameEvent(GameEvent.SHEAR, this.bot);
		tool.hurtAndBreak(1, level, null, item -> { });
		this.fleece = this.sheep.blockPosition();
		this.fleeceUntil = level.getGameTime() + FLEECE_TICKS;
		this.sheep = null;
		return true;
	}

	/** The nearest sheep in the territory ready for shearing; null without one, or when the colony has wool enough. */
	private @Nullable Sheep findSheep(ServerLevel level, Colony colony) {
		AABB territory = new AABB(colony.core()).inflate(colony.radius());
		List<Sheep> sheep = level.getEntitiesOfClass(Sheep.class, territory,
			candidate -> candidate.readyForShearing() && Territory.of(colony).contains(candidate.blockPosition()));
		if (sheep.isEmpty() || woolInStock(Storage.held(level, colony)) >= KEEP_WOOL) {
			return null;
		}
		return sheep.stream().min(Comparator.comparingDouble(this.bot::distanceToSqr)).orElse(null);
	}

	private static int woolInStock(Map<Item, Integer> stock) {
		int wool = 0;
		for (Map.Entry<Item, Integer> entry : stock.entrySet()) {
			if (entry.getKey().builtInRegistryHolder().is(ItemTags.WOOL)) {
				wool += entry.getValue();
			}
		}
		return wool;
	}

	/** Walks over the wool a shorn sheep dropped. Returns false once there is none left to pick up. */
	private boolean collectFleece(ServerLevel level, Colony colony) {
		if (level.getGameTime() > this.fleeceUntil) {
			return false;
		}
		ItemEntity wool = level.getEntitiesOfClass(ItemEntity.class, new AABB(this.fleece).inflate(FLEECE_RADIUS),
				item -> item.isAlive() && item.getItem().is(ItemTags.WOOL) && this.bot.wantsToPickUp(level, item.getItem())
				&& Territory.of(colony).contains(item.blockPosition()))
			.stream()
			.min(Comparator.comparingDouble(this.bot::distanceToSqr))
			.orElse(null);
		if (wool == null) {
			return false;
		}
		if (!wool.onGround()) {
			// Shorn wool pops up into the air: there is no standing in that block, so wait for it to land.
			return true;
		}
		return this.travel(new Goal.Near(wool.blockPosition(), 0)) != PathFollower.Status.FAILED;
	}

	/** Picks the nearest flower in the territory the colony wants, then stores it. Returns true while busy. */
	private boolean pickFlower(ServerLevel level, Colony colony) {
		if (this.flower == null || !isFlower(level.getBlockState(this.flower))) {
			this.flower = null;
			if (level.getGameTime() < this.nextFlowerScan) {
				return false;
			}
			this.nextFlowerScan = level.getGameTime() + SHEEP_SCAN_TICKS;
			// Plans the orders only once a flower turns up: most territories have flowers nobody wants.
			Predicate<Item>[] wanted = new Predicate[1];
			this.flower = nearest(level, colony, this.bot.blockPosition(), pos -> {
				BlockState state = level.getBlockState(pos);
				if (!isFlower(state) || this.hiddenFlowers.contains(pos)) {
					return false;
				}
				if (wanted[0] == null) {
					wanted[0] = Storage.wanted(level, colony);
				}
				return wantsFlower(state.getBlock().asItem(), wanted[0], GameRecipeBook.of(level.getServer()));
			});
			if (this.flower == null) {
				// Looked at again on the next scan: a wall may be gone by then.
				this.hiddenFlowers.clear();
				return false;
			}
		}
		PathFollower.Status status = this.approach(this.inSight(this.flower), this.flower);
		if (status == PathFollower.Status.FAILED) {
			this.hiddenFlowers.add(this.flower);
			this.flower = null;
			this.nextFlowerScan = 0;
			return false;
		}
		if (status == PathFollower.Status.ARRIVED && this.breaker.tick(level, this.flower, true)) {
			this.flower = null;
			this.depositing = this.mayDeposit(level);
		}
		return true;
	}

	/** A flower to pick for dye: a small one, or the lower half of a tall one, which drops the flower and takes the upper half with it. */
	private static boolean isFlower(BlockState state) {
		return state.is(BlockTags.SMALL_FLOWERS) || state.is(BlockTags.FLOWERS) && state.getBlock() instanceof DoublePlantBlock
			&& state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER;
	}

	/**
	 * Whether the colony wants {@code flower}: itself, or for a dye it wants another source of. The planner takes
	 * one recipe per dye, beetroot say, where a rose bush makes the same red dye.
	 * ponytail: a source wanted for something else, as beetroot for soup, makes its dye's flowers wanted too.
	 */
	private static boolean wantsFlower(Item flower, Predicate<Item> wanted, TaskPlanner.RecipeBook<Item> book) {
		if (wanted.test(flower)) {
			return true;
		}
		for (Item dye : Items.DYE.asList()) {
			Set<Item> sources = new HashSet<>();
			book.recipesFor(dye).forEach(recipe -> recipe.ingredients().keySet().forEach(sources::addAll));
			if (sources.contains(flower) && sources.stream().anyMatch(wanted)) {
				return true;
			}
		}
		return false;
	}

	/** Takes seeds from the core, or breaks grass in the territory until some drop. Returns false when there are none to get. */
	private boolean getSeeds(ServerLevel level, Colony colony) {
		Container core = Storage.containerAt(level, colony.core());
		if (core != null && findSlot(core, Farming::isSeed) >= 0) {
			if (this.reach(colony.core())) {
				Storage.moveItems(core, this.bot.getInventory(), Farming::isSeed, KEEP_SEEDS);
			}
			return true;
		}
		if (this.grass == null || !Farming.isSeedGrass(level.getBlockState(this.grass))) {
			this.grass = nearest(level, colony, this.bot.blockPosition(), pos -> Farming.isSeedGrass(level.getBlockState(pos)) && !this.hiddenGrass.contains(pos));
			if (this.grass == null) {
				// Looked at again once the bot next needs seeds: a wall may be gone by then.
				this.hiddenGrass.clear();
				return false;
			}
		}
		PathFollower.Status status = this.inSightOf(this.grass);
		if (status == PathFollower.Status.FAILED) {
			this.hiddenGrass.add(this.grass);
			this.grass = null;
		} else if (status == PathFollower.Status.ARRIVED && this.breaker.tick(level, this.grass, true)) {
			this.grass = null;
		}
		return true;
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

	/** Whether the farmer may look for room for its produce: not while waiting after finding none. */
	private boolean mayDeposit(ServerLevel level) {
		return level.getGameTime() >= this.nextDeposit;
	}

	/**
	 * Takes the produce to the core, or to a storage chest when the core has no room for any of it.
	 * With no room anywhere the farmer gives up for {@link #NO_ROOM_TICKS} and farms on.
	 */
	private void deposit(ServerLevel level, Colony colony) {
		List<ItemStack> produce = this.produce();
		Container core = Storage.containerAt(level, colony.core());
		if (core != null && produce.stream().anyMatch(stack -> Storage.hasRoomFor(core, stack))) {
			if (this.reach(colony.core())) {
				this.storeProduce(core, stack -> true);
				this.depositing = false;
			}
			return;
		}
		Map<BlockPos, Container> chests = Storage.storageChests(level, colony);
		BlockPos chest = produce.stream().map(stack -> Storage.chestFor(chests, stack)).filter(Objects::nonNull).findFirst().orElse(null);
		if (chest == null) {
			this.depositing = false;
			this.nextDeposit = level.getGameTime() + NO_ROOM_TICKS;
			return;
		}
		if (this.reach(chest)) {
			// Re-checked per stack: an empty chest takes the category of the first stack put in.
			Map<BlockPos, Container> target = Map.of(chest, chests.get(chest));
			this.storeProduce(chests.get(chest), stack -> Storage.chestFor(target, stack) != null);
			this.depositing = false;
		}
	}

	/** What the farmer stores, as it would split off its inventory: all but its tools and the seeds it keeps for replanting. */
	private List<ItemStack> produce() {
		SimpleContainer inventory = this.bot.getInventory();
		List<ItemStack> produce = new ArrayList<>();
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
			if (stack.getCount() > keep) {
				produce.add(stack.copyWithCount(stack.getCount() - keep));
			}
		}
		return produce;
	}

	/** Tools a farmer keeps when storing produce: hoes, buckets and shears. */
	private static boolean isKept(ItemStack stack) {
		return ToolKind.HOE.is(stack) || isBucket(stack) || stack.is(Items.SHEARS);
	}

	/** Puts what {@code takes} accepts in {@code to}, except the tools it keeps and the seeds needed for replanting. */
	private void storeProduce(Container to, Predicate<ItemStack> takes) {
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
			if (stack.getCount() == keep || !takes.test(stack)) {
				continue;
			}
			ItemStack rest = HopperBlockEntity.addItem(null, to, stack.split(stack.getCount() - keep), null);
			stack.grow(rest.getCount());
		}
		inventory.setChanged();
		to.setChanged();
	}
}
