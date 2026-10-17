package io.github.barniebarnie.antcraft.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mojang.datafixers.util.Either;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.BiPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.util.StringRepresentable;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Field;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.OreRevisit;
import io.github.barniebarnie.antcraft.work.StorageArea;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** A colony: its core block (storage), member bots, work radius and job board. */
public class Colony {
	public static final int DEFAULT_RADIUS = 32;
	/** How often given-up trees and branches are put back up for one more try: a Minecraft day. */
	public static final long GIVEN_UP_RECHECK_TICKS = 24000;

	/** Plans by the id of their build job. Saves from before plans had ids hold a list; those get new ids. */
	private static final Codec<Map<UUID, BuildPlan>> BUILDS = Codec.withAlternative(
		Codec.unboundedMap(UUIDUtil.STRING_CODEC, BuildPlan.CODEC),
		BuildPlan.CODEC.listOf().xmap(Colony::withIds, plans -> List.copyOf(plans.values())));
	private static final Codec<Map<Item, Integer>> ITEM_COUNTS = Codec.unboundedMap(BuiltInRegistries.ITEM.byNameCodec(), Codec.INT);
	/** Saves from before protection kept the block hold only positions; those protect whatever is there on first check. */
	private static final Codec<List<Placed>> PROTECTED = Codec.withAlternative(
		Placed.CODEC.listOf(),
		BlockPos.CODEC.listOf().xmap(positions -> positions.stream().map(pos -> new Placed(pos, Optional.empty())).toList(),
			placed -> placed.stream().map(Placed::pos).toList()));
	/** Saves from before blocked steps kept their plan hold only positions; their plans are gone, so the next recheck drops them. */
	private static final Codec<List<Blocked>> BLOCKED = Codec.withAlternative(
		Blocked.CODEC.listOf(),
		BlockPos.CODEC.listOf().xmap(positions -> positions.stream().map(pos -> new Blocked(new UUID(0, 0), pos)).toList(),
			blocked -> blocked.stream().map(Blocked::pos).toList()));
	private static final Codec<Map<OrderPurpose, Map<Item, Integer>>> SYSTEM_ORDERS = Codec.unboundedMap(OrderPurpose.CODEC, ITEM_COUNTS);

	/** What the colony placed an order for itself, so it can withdraw the order once that no longer needs it. */
	public enum OrderPurpose implements StringRepresentable {
		/** Materials build plans still have to place. */
		BUILD,
		/** Chests for the storage area. */
		STORAGE;

		public static final Codec<OrderPurpose> CODEC = StringRepresentable.fromEnum(OrderPurpose::values);

		@Override
		public String getSerializedName() {
			return this.name().toLowerCase(Locale.ROOT);
		}
	}

	/** How far builders got with a batch of a plan; a batch not started yet has none. */
	enum BatchState implements StringRepresentable {
		/** Worked through as far as it goes, it waits for items or a fluid; the batches that need it may start. */
		WAITING,
		/** Finished: not posted again unless its blocks are broken since (see {@link Colony#recheckDone}). */
		DONE;

		static final Codec<BatchState> CODEC = StringRepresentable.fromEnum(BatchState::values);

		@Override
		public String getSerializedName() {
			return this.name().toLowerCase(Locale.ROOT);
		}
	}

	/** A block a build plan placed; an empty block is not known yet, from an old save. */
	record Placed(BlockPos pos, Optional<Block> block) {
		static final Codec<Placed> CODEC = RecordCodecBuilder.create(i -> i.group(
			BlockPos.CODEC.fieldOf("pos").forGetter(Placed::pos),
			BuiltInRegistries.BLOCK.byNameCodec().optionalFieldOf("block").forGetter(Placed::block)
		).apply(i, Placed::new));
	}

	/** A step that a builder could not do, of the batch of build job {@code plan} (named so before plans had batches). */
	record Blocked(UUID plan, BlockPos pos) {
		static final Codec<Blocked> CODEC = RecordCodecBuilder.create(i -> i.group(
			UUIDUtil.STRING_CODEC.fieldOf("plan").forGetter(Blocked::plan),
			BlockPos.CODEC.fieldOf("pos").forGetter(Blocked::pos)
		).apply(i, Blocked::new));
	}

	/*
	 * The saved fields come in groups, since one RecordCodecBuilder takes at most 16. Each group is a
	 * MapCodec over the same map, so every field keeps its own top-level key: saves stay flat, and
	 * saves from before the split load unchanged. A new field goes in the group it belongs with, or
	 * in a new group.
	 */

	/** Who and where the colony is, and its jobs. */
	private record Basics(UUID id, BlockPos core, List<UUID> members, JobBoard board, int radius) {
		static final MapCodec<Basics> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			UUIDUtil.CODEC.fieldOf("id").forGetter(Basics::id),
			BlockPos.CODEC.fieldOf("core").forGetter(Basics::core),
			UUIDUtil.CODEC.listOf().optionalFieldOf("members", List.of()).forGetter(Basics::members),
			// Lenient: a new board per colony, never a shared default. Jobs of a type that no longer exists (the quarry's mine_layer) drop the list; the next survey posts them again.
			JobBoard.CODEC.lenientOptionalFieldOf("jobs").xmap(jobs -> jobs.orElseGet(JobBoard::new), Optional::of).forGetter(Basics::board),
			Codec.INT.optionalFieldOf("radius", DEFAULT_RADIUS).forGetter(Basics::radius)
		).apply(i, Basics::new));
	}

	/** What the colony wants: orders and stock targets. */
	private record Wants(Map<Item, Integer> orders, List<StockTarget> stock, Map<Item, Integer> buildOrders,
			Map<OrderPurpose, Map<Item, Integer>> systemOrders) {
		static final MapCodec<Wants> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			ITEM_COUNTS.optionalFieldOf("orders", Map.of()).forGetter(Wants::orders),
			// Colonies saved before stock targets existed get the defaults.
			StockTarget.CODEC.listOf().optionalFieldOf("stock", DefaultStock.TARGETS).forGetter(Wants::stock),
			// Saves from before storage orders hold only the build part, under its own key; it is never written.
			ITEM_COUNTS.optionalFieldOf("build_orders", Map.of()).forGetter(Wants::buildOrders),
			SYSTEM_ORDERS.optionalFieldOf("system_orders", Map.of()).forGetter(Wants::systemOrders)
		).apply(i, Wants::new));
	}

	/** What the colony builds and works: the mineshaft, build plans and their progress, the field and the storage area. */
	private record Works(Optional<Mineshaft> mineshaft, Map<UUID, BuildPlan> builds, List<Placed> placed, List<Blocked> blockedSteps,
			Map<UUID, BatchState> batchStates, Optional<Field> field, Optional<StorageArea> storage) {
		static final MapCodec<Works> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			// Old saves may still have a "quarry"; it is ignored.
			Mineshaft.CODEC.optionalFieldOf("mineshaft").forGetter(Works::mineshaft),
			BUILDS.optionalFieldOf("builds", Map.of()).forGetter(Works::builds),
			PROTECTED.optionalFieldOf("protected", List.of()).forGetter(Works::placed),
			BLOCKED.optionalFieldOf("blocked_steps", List.of()).forGetter(Works::blockedSteps),
			Codec.unboundedMap(UUIDUtil.STRING_CODEC, BatchState.CODEC).optionalFieldOf("batches", Map.of()).forGetter(Works::batchStates),
			Field.CODEC.optionalFieldOf("field").forGetter(Works::field),
			StorageArea.CODEC.optionalFieldOf("storage").forGetter(Works::storage)
		).apply(i, Works::new));
	}

	public static final Codec<Colony> CODEC = RecordCodecBuilder.create(i -> i.group(
		Basics.CODEC.forGetter(c -> new Basics(c.id, c.core, List.copyOf(c.members), c.board, c.radius)),
		Wants.CODEC.forGetter(c -> new Wants(c.orders, c.stock, Map.of(), c.systemOrders)),
		Works.CODEC.forGetter(c -> new Works(c.mineshaft, c.builds, c.placed(), c.blockedSteps, c.batchStates, c.field, c.storage))
	).apply(i, Colony::new));

	private final UUID id;
	private final BlockPos core;
	private final Set<UUID> members;
	private final JobBoard board;
	private int radius;
	private final Map<Item, Integer> orders;
	private Optional<Mineshaft> mineshaft;
	/** The mineshaft's stair floors and torches, which bots leave standing. */
	private Set<BlockPos> shaftBlocks;
	private final List<StockTarget> stock;
	/** Build plans by id, so plans that start at the same spot get jobs of their own. */
	private final Map<UUID, BuildPlan> builds;
	/** The plan and batch of each build job, by job id; see {@link #batchId}. Kept up to date with {@link #builds}. */
	private final Map<UUID, BatchRef> batchJobs = new HashMap<>();
	/** How far builders got with the batch of each build job, so the batches that need it get posted; dropped with their plan. */
	private final Map<UUID, BatchState> batchStates;
	/** Blocks build plans placed, by position, which bots leave standing while they are there; a null block is adopted on first check. */
	private final Map<BlockPos, Block> protectedBlocks = new HashMap<>();
	private Optional<Field> field;
	private Optional<StorageArea> storage;
	/** The parts of {@link #orders} the colony placed itself, by purpose, which are withdrawn once no longer needed. */
	private final Map<OrderPurpose, Map<Item, Integer>> systemOrders = new EnumMap<>(OrderPurpose.class);
	/** Steps builders could not do, such as behind bedrock; their plans wait without a job until a recheck finds them workable. */
	private final List<Blocked> blockedSteps;
	/** What each hauler is on its way to move; not saved, since trips are short. */
	private final TripClaims tripClaims = new TripClaims();
	/** Dug branches sent back to for the ores a better pickaxe can now harvest; not saved, see {@link OreRevisit}. */
	private final OreRevisit oreRevisit = new OreRevisit();
	/** Game time of the last tree survey, or -1 if the colony has not surveyed yet. */
	long lastSurvey = -1;
	/** Game time of the last recheck of given-up jobs, or -1 before the first; not saved, so a reload only delays it. */
	private long lastGivenUpRecheck = -1;

	private Colony(Basics basics, Wants wants, Works works) {
		this(basics.id(), basics.core(), basics.members(), basics.board(), basics.radius(), wants.orders(), works.mineshaft(), wants.stock(),
			works.builds(), works.placed(), works.field(), works.storage(), wants.buildOrders(), works.blockedSteps(), wants.systemOrders(),
			works.batchStates());
	}

	Colony(UUID id, BlockPos core, List<UUID> members, JobBoard board, int radius, Map<Item, Integer> orders, Optional<Mineshaft> mineshaft,
			List<StockTarget> stock, Map<UUID, BuildPlan> builds, List<Placed> protectedBlocks, Optional<Field> field,
			Optional<StorageArea> storage, Map<Item, Integer> buildOrders, List<Blocked> blockedSteps,
			Map<OrderPurpose, Map<Item, Integer>> systemOrders) {
		this(id, core, members, board, radius, orders, mineshaft, stock, builds, protectedBlocks, field, storage, buildOrders, blockedSteps,
			systemOrders, Map.of());
	}

	Colony(UUID id, BlockPos core, List<UUID> members, JobBoard board, int radius, Map<Item, Integer> orders, Optional<Mineshaft> mineshaft,
			List<StockTarget> stock, Map<UUID, BuildPlan> builds, List<Placed> protectedBlocks, Optional<Field> field,
			Optional<StorageArea> storage, Map<Item, Integer> buildOrders, List<Blocked> blockedSteps,
			Map<OrderPurpose, Map<Item, Integer>> systemOrders, Map<UUID, BatchState> batchStates) {
		this.batchStates = new HashMap<>(batchStates);
		systemOrders.forEach((purpose, counts) -> this.systemOrders.put(purpose, new LinkedHashMap<>(counts)));
		buildOrders.forEach((item, count) -> this.part(OrderPurpose.BUILD).merge(item, count, Integer::sum));
		this.blockedSteps = new ArrayList<>(blockedSteps);
		this.field = field;
		this.storage = storage;
		this.id = id;
		this.core = core;
		this.members = new LinkedHashSet<>(members);
		this.board = board;
		this.radius = radius;
		this.orders = new LinkedHashMap<>(orders);
		this.mineshaft = mineshaft;
		this.shaftBlocks = mineshaft.map(Mineshaft::protectedBlocks).orElse(Set.of());
		this.stock = new ArrayList<>(stock);
		this.builds = new LinkedHashMap<>(builds);
		this.indexBatches();
		protectedBlocks.forEach(placed -> this.protectedBlocks.put(placed.pos(), placed.block().orElse(null)));
	}

	private static Map<UUID, BuildPlan> withIds(List<BuildPlan> plans) {
		Map<UUID, BuildPlan> builds = new LinkedHashMap<>();
		plans.forEach(plan -> builds.put(UUID.randomUUID(), plan));
		return builds;
	}

	/** Build plans the colony's builders still have to carry out, in the order they were added; plans waiting on blocked steps are left out. */
	public List<BuildPlan> builds() {
		return this.builds.entrySet().stream().filter(e -> !this.isBlocked(e.getKey())).map(Map.Entry::getValue).toList();
	}

	/** Whether a batch of the plan with id {@code plan} has blocked steps. */
	private boolean isBlocked(UUID plan) {
		return this.blockedSteps.stream().anyMatch(blocked -> this.planOf(blocked.plan()).equals(plan));
	}

	/** A batch of a plan, by the plan's id and the batch's index. */
	private record BatchRef(UUID plan, int index) {
	}

	/**
	 * The id of the build job for a batch of a plan. The first batch's is the plan's own id, so jobs
	 * and blocked steps saved from before plans had batches still match.
	 */
	private static UUID batchId(UUID plan, int index) {
		return index == 0 ? plan : UUID.nameUUIDFromBytes((plan + "/" + index).getBytes(StandardCharsets.UTF_8));
	}

	private void indexBatches() {
		this.batchJobs.clear();
		this.builds.forEach((id, plan) -> {
			for (int i = 0; i < plan.batches().size(); i++) {
				this.batchJobs.put(batchId(id, i), new BatchRef(id, i));
			}
		});
	}

	/** The id of the plan a build job's batch belongs to, or the job's own id if it has no plan. */
	private UUID planOf(UUID job) {
		BatchRef batch = this.batchJobs.get(job);
		return batch == null ? job : batch.plan();
	}

	/** The batches of the plan with id {@code id} in {@code state}, by index. */
	private Set<Integer> batches(UUID id, BuildPlan plan, BatchState state) {
		Set<Integer> batches = new HashSet<>();
		for (int i = 0; i < plan.batches().size(); i++) {
			if (this.batchStates.get(batchId(id, i)) == state) {
				batches.add(i);
			}
		}
		return batches;
	}

	private List<Integer> ready(UUID id, BuildPlan plan) {
		return plan.ready(this.batches(id, plan, BatchState.DONE), this.batches(id, plan, BatchState.WAITING));
	}

	/** Where the batch of build job {@code job} comes in its plan, so builders take a plan's batches in order; 0 for any other job. */
	public int batchIndex(UUID job) {
		BatchRef batch = this.batchJobs.get(job);
		return batch == null ? 0 : batch.index();
	}

	public void addBuild(BuildPlan plan) {
		this.builds.put(UUID.randomUUID(), plan);
		this.indexBatches();
		this.lastSurvey = -1;
	}

	/** Drops the plan, finished or cancelled, with the open jobs of its batches; the next survey withdraws what it ordered. */
	public void removeBuild(BuildPlan plan) {
		this.builds.entrySet().stream().filter(e -> e.getValue().equals(plan)).map(Map.Entry::getKey).findFirst().ifPresent(this::removePlan);
	}

	private void removePlan(UUID id) {
		Set<UUID> jobs = new HashSet<>();
		for (int i = 0; i < this.builds.get(id).batches().size(); i++) {
			jobs.add(batchId(id, i));
		}
		this.builds.remove(id);
		this.indexBatches();
		this.batchStates.keySet().removeAll(jobs);
		this.board.removeOpenIf(job -> isBuildJob(job) && jobs.contains(job.id()));
		this.lastSurvey = -1;
	}

	/** The batch a build job is for, as a plan of its own. */
	public Optional<BuildPlan> build(UUID job) {
		return Optional.ofNullable(this.batchJobs.get(job)).map(batch -> this.builds.get(batch.plan()).batch(batch.index()));
	}

	/**
	 * Records that a builder finished the batch of build job {@code job}, and posts the batches that
	 * were waiting for it. Once every batch of its plan is done, the plan is finished and dropped.
	 */
	public void finishBatch(UUID job) {
		BatchRef batch = this.batchJobs.get(job);
		if (batch == null) {
			return;
		}
		this.batchStates.put(job, BatchState.DONE);
		BuildPlan plan = this.builds.get(batch.plan());
		if (this.batches(batch.plan(), plan, BatchState.DONE).size() == plan.batches().size()) {
			this.removePlan(batch.plan());
		} else {
			this.postBuildJobs();
		}
	}

	/**
	 * Records that the batch of build job {@code job} waits for items or a fluid with the rest of it
	 * worked through, so the batches that need it may start, such as a hall once the stairs reaching
	 * it are dug but not lit yet. The batch stays posted to be finished.
	 */
	public void batchWaiting(UUID job) {
		if (this.batchJobs.containsKey(job) && this.batchStates.putIfAbsent(job, BatchState.WAITING) == null) {
			this.postBuildJobs();
		}
	}

	/**
	 * Posts a build job for each batch whose needed batches are done or waiting, a dig job for a {@link BuildPlan.Batch#dig} batch, and drops open jobs of batches
	 * that are gone, done, or of a plan that is blocked.
	 */
	public void postBuildJobs() {
		Set<UUID> ready = new HashSet<>();
		this.builds.forEach((id, plan) -> {
			if (!this.isBlocked(id)) {
				this.ready(id, plan).forEach(i -> ready.add(batchId(id, i)));
			}
		});
		this.board.removeOpenIf(job -> isBuildJob(job) && !ready.contains(job.id()));
		this.builds.forEach((id, plan) -> this.ready(id, plan).stream()
			.filter(i -> ready.contains(batchId(id, i)))
			.forEach(i -> this.board.post(batchId(id, i), plan.batches().get(i).dig() ? JobType.CLEAR_AREA : JobType.BUILD, plan.batch(i).target())));
	}

	/** Whether {@code job} is for a batch of a build plan: a build job, or a dig job of a batch that only clears blocks. */
	private static boolean isBuildJob(Job job) {
		return job.type() == JobType.BUILD || job.type() == JobType.CLEAR_AREA;
	}

	/** Whether the colony has an area to level (see {@link io.github.barniebarnie.antcraft.work.Terraform}), blocked or not. */
	public boolean hasTerraform() {
		return this.builds.values().stream().anyMatch(plan -> plan.kind() == BuildPlan.Kind.TERRAFORM);
	}

	private Map<Item, Integer> part(OrderPurpose purpose) {
		return this.systemOrders.computeIfAbsent(purpose, key -> new LinkedHashMap<>());
	}

	/**
	 * Sets what {@code purpose} needs ordered: orders grow by what it needs beyond what it already
	 * ordered, and shrink by what it no longer needs, such as when the items turned up some other
	 * way. Player orders and orders for other purposes are left alone.
	 */
	public void orderFor(OrderPurpose purpose, Map<Item, Integer> needed) {
		Map<Item, Integer> part = this.part(purpose);
		Set<Item> items = new HashSet<>(part.keySet());
		items.addAll(needed.keySet());
		for (Item item : items) {
			int ordered = this.orders.getOrDefault(item, 0);
			// The order may have been cancelled meanwhile.
			int placed = Math.min(part.getOrDefault(item, 0), ordered);
			int want = needed.getOrDefault(item, 0);
			setCount(this.orders, item, ordered - placed + want);
			setCount(part, item, want);
		}
	}

	/** The part of {@link #orders} the colony placed itself for {@code purpose}. */
	public Map<Item, Integer> ordersFor(OrderPurpose purpose) {
		return Map.copyOf(this.systemOrders.getOrDefault(purpose, Map.of()));
	}

	private static void setCount(Map<Item, Integer> counts, Item item, int count) {
		if (count > 0) {
			counts.put(item, count);
		} else {
			counts.remove(item);
		}
	}

	/** Where builders could not do a step, such as behind bedrock. */
	public List<BlockPos> blockedSteps() {
		return this.blockedSteps.stream().map(Blocked::pos).toList();
	}

	/**
	 * Records the steps of the batch of build job {@code job} that a builder cannot do; its whole
	 * plan waits without jobs until a recheck frees it. The mineshaft does not wait: it drops the
	 * levels the steps keep builders from (see {@link Mineshaft#reachable}) and goes on without them.
	 */
	public void reportBlocked(UUID job, Collection<BlockPos> steps) {
		BatchRef batch = this.batchJobs.get(job);
		Optional<Mineshaft> shaft = this.mineshaft
			.filter(mine -> batch != null && this.builds.get(batch.plan()).equals(mine.plan()))
			.map(mine -> mine.reachable(steps))
			.filter(reachable -> !reachable.equals(this.mineshaft.get()));
		if (shaft.isPresent()) {
			this.setMineshaft(shaft.get());
			return;
		}
		steps.stream().map(pos -> new Blocked(job, pos.immutable())).filter(b -> !this.blockedSteps.contains(b)).forEach(this.blockedSteps::add);
	}

	/**
	 * Drops blocked steps whose plan is gone, and those that are done or that {@code workable},
	 * given the step's batch and the step, says a bot could do now. A plan with such a step gets
	 * all its blocked steps dropped, so it gets jobs again and its bots report whatever is still
	 * blocked. {@code world} gives null where it cannot tell, such as in unloaded chunks.
	 */
	public void recheckBlocked(Function<BlockPos, @Nullable BlockState> world, BiPredicate<BuildPlan, BuildPlan.Step> workable) {
		Set<UUID> freed = new HashSet<>();
		for (Blocked blocked : this.blockedSteps) {
			Optional<BuildPlan> batch = this.build(blocked.plan());
			Optional<BuildPlan.Step> step = batch.flatMap(plan -> plan.steps().stream().filter(s -> s.pos().equals(blocked.pos())).findFirst());
			BlockState state = world.apply(blocked.pos());
			if (step.isEmpty() || state != null && (step.get().isDone(state) || workable.test(batch.get(), step.get()))) {
				freed.add(this.planOf(blocked.plan()));
			}
		}
		if (this.blockedSteps.removeIf(blocked -> freed.contains(this.planOf(blocked.plan())))) {
			this.lastSurvey = -1;
		}
	}

	/**
	 * Reopens each done batch whose blocks no longer all match the world, such as a torch broken off
	 * the mineshaft stairs, so it is posted again and rebuilt. It goes back as waiting, so the batches
	 * that need it stay open meanwhile. {@code world} gives null where it cannot tell, such as in
	 * unloaded chunks; such blocks are taken as still done.
	 */
	public void recheckDone(Function<BlockPos, @Nullable BlockState> world) {
		boolean reopened = false;
		for (Map.Entry<UUID, BatchState> entry : this.batchStates.entrySet()) {
			if (entry.getValue() == BatchState.DONE && this.build(entry.getKey()).filter(batch -> batch.steps().stream().anyMatch(step -> {
				BlockState state = world.apply(step.pos());
				return state != null && !step.isDone(state);
			})).isPresent()) {
				entry.setValue(BatchState.WAITING);
				reopened = true;
			}
		}
		if (reopened) {
			this.postBuildJobs();
		}
	}

	/**
	 * Every {@link #GIVEN_UP_RECHECK_TICKS}, puts the jobs bots gave up on back up, so a tree or branch a player has
	 * since opened the way to gets done after all. Returns how many were reopened.
	 */
	public int recheckGivenUp(long now) {
		if (this.lastGivenUpRecheck < 0) {
			this.lastGivenUpRecheck = now;
		}
		if (now - this.lastGivenUpRecheck < GIVEN_UP_RECHECK_TICKS) {
			return 0;
		}
		this.lastGivenUpRecheck = now;
		return this.board.reopenGivenUp();
	}

	/** Gives up on every blocked step and the plans waiting on them. Returns how many steps there were. */
	public int clearBlocked() {
		int count = this.blockedSteps.size();
		this.blockedSteps.stream().map(blocked -> this.planOf(blocked.plan())).distinct().filter(this.builds::containsKey).toList()
			.forEach(this::removePlan);
		this.blockedSteps.clear();
		this.lastSurvey = -1;
		return count;
	}

	private List<Placed> placed() {
		return this.protectedBlocks.entrySet().stream().map(e -> new Placed(e.getKey(), Optional.ofNullable(e.getValue()))).toList();
	}

	/**
	 * Whether bots must leave the block at {@code pos} standing: a block a build plan placed that is
	 * still there, or part of the mineshaft. Once a placed block is gone or replaced its position is
	 * forgotten. The mineshaft stays protected even when broken, since its plan has the builder put it back.
	 */
	public boolean isProtected(Function<BlockPos, BlockState> world, BlockPos pos) {
		if (this.shaftBlocks.contains(pos)) {
			return true;
		}
		if (!this.protectedBlocks.containsKey(pos)) {
			return false;
		}
		Block block = world.apply(pos).getBlock();
		Block placed = this.protectedBlocks.get(pos);
		if (placed == null && block != Blocks.AIR) {
			this.protectedBlocks.put(pos, block);
			return true;
		}
		if (block == placed) {
			return true;
		}
		this.protectedBlocks.remove(pos);
		return false;
	}

	/** Forgets placed blocks that are gone or replaced, so the set does not grow for good. {@code world} gives null where it cannot tell. */
	void forgetReplaced(Function<BlockPos, @Nullable BlockState> world) {
		for (BlockPos pos : List.copyOf(this.protectedBlocks.keySet())) {
			BlockState state = world.apply(pos);
			if (state != null) {
				this.isProtected(p -> state, pos);
			}
		}
	}

	int protectedCount() {
		return this.protectedBlocks.size();
	}

	/** Protects {@code block}, just placed at {@code pos}, while it stays there. */
	public void protect(BlockPos pos, Block block) {
		this.protectedBlocks.put(pos.immutable(), block);
	}

	public void unprotect(BlockPos pos) {
		this.protectedBlocks.remove(pos);
	}

	/** Where the colony mines, once it has picked a site. */
	public Optional<Mineshaft> mineshaft() {
		return this.mineshaft;
	}

	/** Moves the mine: builders dig the new shaft instead of the old one, and miners wait for it. */
	public void setMineshaft(Mineshaft shaft) {
		this.mineshaft.ifPresent(old -> this.removeBuild(old.plan()));
		this.mineshaft = Optional.of(shaft);
		this.shaftBlocks = shaft.protectedBlocks();
		this.board.removeOpenIf(job -> job.type() == JobType.MINE_BRANCH);
		this.addBuild(shaft.plan());
	}

	/** The crop field the colony started itself, if it needed one. */
	public Optional<Field> field() {
		return this.field;
	}

	void setField(Field field) {
		this.field = Optional.of(field);
		this.board.removeOpenIf(job -> job.type() == JobType.PLANT_CROP);
	}

	/** Where haulers keep the colony's storage chests, once the colony has picked a spot. */
	public Optional<StorageArea> storage() {
		return this.storage;
	}

	public void setStorage(StorageArea storage) {
		this.storage = Optional.of(storage);
	}

	public UUID id() {
		return this.id;
	}

	public BlockPos core() {
		return this.core;
	}

	public Set<UUID> members() {
		return this.members;
	}

	public JobBoard board() {
		return this.board;
	}

	public TripClaims tripClaims() {
		return this.tripClaims;
	}

	public OreRevisit oreRevisit() {
		return this.oreRevisit;
	}

	/** How far from the core the colony looks for work. */
	public int radius() {
		return this.radius;
	}

	/** Items to craft, with how many are still to be made. */
	public Map<Item, Integer> orders() {
		return this.orders;
	}

	public void addOrder(Item item, int count) {
		this.orders.merge(item, count, Integer::sum);
	}

	/** Drops the whole order for {@code item}, including the parts the colony placed itself. */
	public void cancelOrder(Item item) {
		this.orders.remove(item);
		this.systemOrders.values().forEach(part -> part.remove(item));
	}

	/** Drops the part of the order for {@code item} a player placed; the parts the colony placed itself stay. */
	public void cancelPlayerOrder(Item item) {
		int placed = this.systemOrders.values().stream().mapToInt(part -> part.getOrDefault(item, 0)).sum();
		setCount(this.orders, item, Math.min(placed, this.orders.getOrDefault(item, 0)));
	}

	/** Records that {@code count} of an ordered item were made, for the colony's own orders first; finished orders are removed. */
	public void fulfil(Item item, int count) {
		this.orders.computeIfPresent(item, (key, left) -> left - count > 0 ? left - count : null);
		int left = count;
		for (Map<Item, Integer> part : this.systemOrders.values()) {
			int placed = part.getOrDefault(item, 0);
			setCount(part, item, placed - left);
			left -= Math.min(placed, left);
		}
	}

	/** What the colony keeps in stock; shortfalls become orders. */
	public List<StockTarget> stockTargets() {
		return List.copyOf(this.stock);
	}

	public void setStockTargets(List<StockTarget> targets) {
		this.stock.clear();
		this.stock.addAll(targets);
	}

	/** Adds the target, or replaces the one for the same item or tag. */
	public void setStockTarget(StockTarget target) {
		for (int i = 0; i < this.stock.size(); i++) {
			if (this.stock.get(i).match().equals(target.match())) {
				this.stock.set(i, target);
				return;
			}
		}
		this.stock.add(target);
	}

	public boolean removeStockTarget(Either<TagKey<Item>, Item> match) {
		return this.stock.removeIf(target -> target.match().equals(match));
	}

	public void setRadius(int radius) {
		this.radius = radius;
		this.lastSurvey = -1;
	}
}
