package io.github.barniebarnie.antcraft.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mojang.datafixers.util.Either;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
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
import io.github.barniebarnie.antcraft.work.ToolKind;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Items;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/** A colony: its core block (storage), member bots, work radius and job board. */
public class Colony {
	public static final int DEFAULT_RADIUS = 32;
	/** The name of the mineshaft's project. */
	public static final String MINESHAFT = "mineshaft";
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
		BlockPos.CODEC.listOf().xmap(positions -> positions.stream().map(pos -> new Blocked(new UUID(0, 0), pos, false)).toList(),
			blocked -> blocked.stream().map(Blocked::pos).toList()));
	private static final Codec<Map<OrderPurpose, Map<Item, Integer>>> SYSTEM_ORDERS = Codec.unboundedMap(OrderPurpose.CODEC, ITEM_COUNTS);

	/**
	 * The priority of the crafts for a player's order. A player asked for it, so it comes before work nothing waits for
	 * (priority 0), such as the next tree; a project the player raised higher still comes first.
	 */
	public static final int ORDER_PRIORITY = 1;

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
		/** Worked through as far as it goes, it waits for items; the batches that need it may start. */
		WAITING,
		/** Finished: not posted again unless its blocks are broken since (see {@link Colony#recheckDone}). */
		DONE,
		/** Given up with its blocked steps (see {@link Colony#clearBlocked}): never posted again, nor the batches that need it. */
		GIVEN_UP;

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

	/**
	 * A step that a builder could not do, of the batch of build job {@code plan} (named so before
	 * plans had batches); {@code unreachable} if it found no way to the step, rather than the step
	 * itself being impossible.
	 */
	record Blocked(UUID plan, BlockPos pos, boolean unreachable) {
		static final Codec<Blocked> CODEC = RecordCodecBuilder.create(i -> i.group(
			UUIDUtil.STRING_CODEC.fieldOf("plan").forGetter(Blocked::plan),
			BlockPos.CODEC.fieldOf("pos").forGetter(Blocked::pos),
			Codec.BOOL.optionalFieldOf("unreachable", false).forGetter(Blocked::unreachable)
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

	/** What the colony builds and works: the mineshaft, build plans and their progress, the projects they make up, the field and the storage area. */
	private record Works(Optional<Mineshaft> mineshaft, Map<UUID, BuildPlan> builds, List<Placed> placed, List<Blocked> blockedSteps,
			Map<UUID, BatchState> batchStates, Optional<Field> field, Optional<StorageArea> storage, List<Project> projects) {
		static final MapCodec<Works> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			// Old saves may still have a "quarry"; it is ignored.
			Mineshaft.CODEC.optionalFieldOf("mineshaft").forGetter(Works::mineshaft),
			BUILDS.optionalFieldOf("builds", Map.of()).forGetter(Works::builds),
			PROTECTED.optionalFieldOf("protected", List.of()).forGetter(Works::placed),
			BLOCKED.optionalFieldOf("blocked_steps", List.of()).forGetter(Works::blockedSteps),
			Codec.unboundedMap(UUIDUtil.STRING_CODEC, BatchState.CODEC).optionalFieldOf("batches", Map.of()).forGetter(Works::batchStates),
			Field.CODEC.optionalFieldOf("field").forGetter(Works::field),
			StorageArea.CODEC.optionalFieldOf("storage").forGetter(Works::storage),
			// Saves from before projects get a project for each plan.
			Project.CODEC.listOf().optionalFieldOf("projects", List.of()).forGetter(Works::projects)
		).apply(i, Works::new));
	}

	public static final Codec<Colony> CODEC = RecordCodecBuilder.create(i -> i.group(
		Basics.CODEC.forGetter(c -> new Basics(c.id, c.core, List.copyOf(c.members), c.board, c.radius)),
		Wants.CODEC.forGetter(c -> new Wants(c.orders, c.stock, Map.of(), c.systemOrders)),
		Works.CODEC.forGetter(c -> new Works(c.mineshaft, c.builds, c.placed(), c.blockedSteps, c.batchStates, c.field, c.storage, List.copyOf(c.projects)))
	).apply(i, Colony::new));

	private final UUID id;
	private final BlockPos core;
	private final Set<UUID> members;
	private final JobBoard board;
	private int radius;
	/** The game test area this colony is clipped to (see {@link Territory}); not saved, and null outside game tests. */
	private @Nullable BoundingBox bounds;
	private final Map<Item, Integer> orders;
	private Optional<Mineshaft> mineshaft;
	/** The mineshaft's stair floors and torches, which bots leave standing. */
	private Set<BlockPos> shaftBlocks;
	private Set<BlockPos> shaftOpen;
	private final List<StockTarget> stock;
	/** The projects the colony works on, in the order they were added; see {@link #projects}. */
	private final List<Project> projects = new ArrayList<>();
	/** What each project still waits for, by project id, as {@link #planProjects} last found; not saved. */
	private final Map<UUID, Map<Item, Integer>> waiting = new HashMap<>();
	/** How many cobblestone stairs the craft jobs at the batches make, as {@link #postStairCrafts} last posted them; not saved. */
	private int stairsOnSpot;
	/** Build plans by id, so plans that start at the same spot get jobs of their own. */
	private final Map<UUID, BuildPlan> builds;
	/** The plan and batch of each build job, by job id; see {@link #batchId}. Kept up to date with {@link #builds}. */
	private final Map<UUID, BatchRef> batchJobs = new HashMap<>();
	/** The job id of each batch of each plan, by plan id, made once since hashing them on every {@link #keepOpen} was most of its cost; dropped with the plan. */
	private final Map<UUID, List<UUID>> batchIds = new HashMap<>();
	/** Where each plan has steps, by plan id, collected once for {@link #keepOpen}; dropped with the plan. A plan's steps never change under its id. */
	private final Map<UUID, Set<BlockPos>> stepSets = new HashMap<>();
	/** How many plans' steps {@link #keepOpen} collected, for tests. */
	int stepSetsCollected;
	/** The batch of each build job as a plan of its own, made once rather than on every builder's tick; cleared with {@link #batchJobs}. */
	private final Map<UUID, BuildPlan> batchPlans = new HashMap<>();
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
			works.batchStates(), works.projects());
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
		this(id, core, members, board, radius, orders, mineshaft, stock, builds, protectedBlocks, field, storage, buildOrders, blockedSteps,
			systemOrders, batchStates, List.of());
	}

	Colony(UUID id, BlockPos core, List<UUID> members, JobBoard board, int radius, Map<Item, Integer> orders, Optional<Mineshaft> mineshaft,
			List<StockTarget> stock, Map<UUID, BuildPlan> builds, List<Placed> protectedBlocks, Optional<Field> field,
			Optional<StorageArea> storage, Map<Item, Integer> buildOrders, List<Blocked> blockedSteps,
			Map<OrderPurpose, Map<Item, Integer>> systemOrders, Map<UUID, BatchState> batchStates, List<Project> projects) {
		// Projects lose plans that are gone; a plan of no project, as in saves from before projects, gets one of its own.
		projects.forEach(project -> this.projects.add(new Project(project.id(), project.name(), project.priority(),
			project.plans().stream().filter(builds::containsKey).toList(), project.stock())));
		this.projects.removeIf(Project::isEmpty);
		builds.forEach((planId, plan) -> {
			if (this.projects.stream().noneMatch(project -> project.plans().contains(planId))) {
				this.projects.add(new Project(planId, mineshaft.filter(shaft -> shaft.isPlan(plan)).isPresent() ? MINESHAFT : defaultName(plan), 0,
					List.of(planId)));
			}
		});
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
		this.shaftOpen = mineshaft.map(Mineshaft::keepOpen).orElse(Set.of());
		this.stock = new ArrayList<>(stock);
		this.builds = new LinkedHashMap<>(builds);
		// A shaft plan saved by an older version, such as before the walls, gets the current one under its id, keeping its progress.
		mineshaft.ifPresent(shaft -> this.builds.replaceAll((planId, plan) -> shaft.isPlan(plan) ? shaft.plan() : plan));
		this.indexBatches();
		List.copyOf(this.builds.keySet()).forEach(this::settleEmpty);
		protectedBlocks.forEach(placed -> this.protectedBlocks.put(placed.pos(), placed.block().orElse(null)));
	}

	/** What a project of just {@code plan} is called when nobody named it. */
	private static String defaultName(BuildPlan plan) {
		return plan.kind().getSerializedName();
	}

	private static Map<UUID, BuildPlan> withIds(List<BuildPlan> plans) {
		Map<UUID, BuildPlan> builds = new LinkedHashMap<>();
		plans.forEach(plan -> builds.put(UUID.randomUUID(), plan));
		return builds;
	}

	/**
	 * Build plans the colony's builders still have to carry out, in the order they were added; plans
	 * whose batches are all done or wait on blocked steps (see {@link #blockedBatches}) are left out.
	 */
	public List<BuildPlan> builds() {
		return this.builds.entrySet().stream().filter(e -> this.hasWorkLeft(e.getKey(), e.getValue())).map(Map.Entry::getValue).toList();
	}

	/** Whether a batch of the plan with id {@code id} is neither done nor waiting on blocked steps. */
	private boolean hasWorkLeft(UUID id, BuildPlan plan) {
		Set<Integer> held = new HashSet<>(this.blockedBatches(id, plan));
		held.addAll(this.batches(id, plan, BatchState.DONE));
		return held.size() < plan.batches().size();
	}

	/**
	 * The batches of the plan with id {@code id} that wait on blocked steps, by index: those with
	 * blocked steps of their own or given up (see {@link #clearBlocked}), and those that need such a
	 * batch, directly or through others.
	 */
	private Set<Integer> blockedBatches(UUID id, BuildPlan plan) {
		Set<UUID> jobs = new HashSet<>();
		this.blockedSteps.forEach(blocked -> jobs.add(blocked.plan()));
		return this.heldUp(id, plan, job -> jobs.contains(job) || this.batchStates.get(job) == BatchState.GIVEN_UP);
	}

	/** The batches of the plan with id {@code id} given up, and those that need one, directly or through others. */
	private Set<Integer> givenUpBatches(UUID id, BuildPlan plan) {
		return this.heldUp(id, plan, job -> this.batchStates.get(job) == BatchState.GIVEN_UP);
	}

	/** The batches of the plan with id {@code id} whose job is {@code held}, and those that need one, directly or through others. */
	private Set<Integer> heldUp(UUID id, BuildPlan plan, Predicate<UUID> held) {
		Set<Integer> blocked = new HashSet<>();
		// A batch needs only batches before it, so one pass in order finds every batch held up.
		for (int i = 0; i < plan.batches().size(); i++) {
			if (held.test(this.batchId(id, plan, i)) || !blocked.isEmpty() && plan.batches().get(i).needs().stream().anyMatch(blocked::contains)) {
				blocked.add(i);
			}
		}
		return blocked;
	}

	/** {@code plan}, with id {@code id}, without its {@linkplain #blockedBatches blocked batches}: what there is to build of it now. */
	private BuildPlan unblocked(UUID id, BuildPlan plan) {
		Set<Integer> blocked = this.blockedBatches(id, plan);
		return blocked.isEmpty() ? plan : new BuildPlan(IntStream.range(0, plan.batches().size()).filter(i -> !blocked.contains(i))
			.boxed().flatMap(i -> plan.batch(i).steps().stream()).toList(), List.of(), plan.kind(), plan.ground());
	}

	/** The {@link #builds()} without their {@linkplain #blockedBatches blocked batches}, whose materials wait until they are freed. */
	public List<BuildPlan> openBuilds() {
		return this.builds.entrySet().stream().filter(e -> this.hasWorkLeft(e.getKey(), e.getValue()))
			.map(e -> this.unblocked(e.getKey(), e.getValue())).toList();
	}

	/** Whether every batch of the plan with id {@code id} is done or given up, or needs one given up. */
	private boolean isFinished(UUID id, BuildPlan plan) {
		Set<Integer> finished = this.givenUpBatches(id, plan);
		finished.addAll(this.batches(id, plan, BatchState.DONE));
		return finished.size() == plan.batches().size();
	}

	/** {@link #blockedBatches(UUID, BuildPlan)} of {@code plan}, which the colony builds; none if it does not. */
	Set<Integer> blockedBatches(BuildPlan plan) {
		return this.builds.entrySet().stream().filter(e -> e.getValue().equals(plan)).findFirst()
			.map(e -> this.blockedBatches(e.getKey(), e.getValue())).orElse(Set.of());
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

	/** {@link #batchId(UUID, int)}, made once per plan. */
	private UUID batchId(UUID id, BuildPlan plan, int index) {
		return this.batchIds.computeIfAbsent(id, planId -> IntStream.range(0, plan.batches().size()).mapToObj(i -> batchId(planId, i)).toList()).get(index);
	}

	private void indexBatches() {
		this.batchJobs.clear();
		this.batchPlans.clear();
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
			if (this.batchStates.get(this.batchId(id, plan, i)) == state) {
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

	/**
	 * Adds {@code plan} to build, with only the last step at each position it has more than one for
	 * (see {@link #withoutOverlaps}).
	 */
	public void addBuild(BuildPlan plan) {
		this.addBuild(defaultName(plan), plan);
	}

	/** {@link #addBuild(BuildPlan)} as a project of its own called {@code name}, at priority 0. */
	/**
	 * Areas the colony already uses, which a new area must keep clear of: the mineshaft, field and storage, and the
	 * bounds of each unfinished build plan (such as the house). Add new kinds of areas here.
	 */
	public List<BoundingBox> occupied() {
		List<BoundingBox> areas = new ArrayList<>();
		this.mineshaft.ifPresent(shaft -> areas.add(shaft.area()));
		this.field.ifPresent(field -> areas.add(field.area()));
		this.storage.ifPresent(storage -> areas.add(storage.area()));
		this.builds.values().forEach(plan -> BoundingBox.encapsulatingPositions(plan.steps().stream().map(BuildPlan.Step::pos).toList()).ifPresent(areas::add));
		return areas;
	}

	public Project addBuild(String name, BuildPlan plan) {
		UUID id = UUID.randomUUID();
		Project project = new Project(id, name, 0, List.of(id));
		this.projects.add(project);
		this.builds.put(id, withoutOverlaps(plan));
		this.indexBatches();
		this.settleEmpty(id);
		this.lastSurvey = -1;
		return project;
	}

	/** Adds a project of no plans called {@code name} that wants {@code stock} held, at priority 0. */
	public Project addProject(String name, List<StockTarget> stock) {
		Project project = new Project(UUID.randomUUID(), name, 0, List.of(), stock);
		this.projects.add(project);
		this.lastSurvey = -1;
		return project;
	}

	/** The colony's projects, highest priority first, and in the order they were added among equals. */
	public List<Project> projects() {
		return this.projects.stream().sorted(Comparator.comparingInt(Project::priority).reversed()).toList();
	}

	/** Sets the priority of the project with id {@code id}; its jobs get it once posted again. Returns whether there is such a project. */
	public boolean setPriority(UUID id, int priority) {
		for (int i = 0; i < this.projects.size(); i++) {
			if (this.projects.get(i).id().equals(id)) {
				this.projects.set(i, this.projects.get(i).withPriority(priority));
				this.lastSurvey = -1;
				return true;
			}
		}
		return false;
	}

	/** Drops the project with id {@code id} and its plans, with their open jobs (see {@link #removeBuild}). */
	public void cancelProject(UUID id) {
		this.projects.stream().filter(project -> project.id().equals(id)).findFirst()
			.ifPresent(project -> project.plans().forEach(this::removePlan));
		this.projects.removeIf(project -> project.id().equals(id));
		this.waiting.remove(id);
	}

	/** The build plans of {@code project}. */
	public List<BuildPlan> plans(Project project) {
		return project.plans().stream().map(this.builds::get).filter(Objects::nonNull).toList();
	}

	/** What the project with id {@code id} still waits for, as {@link #planProjects} last found: materials and tools to get. */
	public Map<Item, Integer> waitingFor(UUID id) {
		return Map.copyOf(this.waiting.getOrDefault(id, Map.of()));
	}

	/** The priority of the project plan {@code plan} belongs to; 0 if none. */
	private int priorityOf(UUID plan) {
		return this.projects.stream().filter(project -> project.plans().contains(plan)).mapToInt(Project::priority).findFirst().orElse(0);
	}

	/**
	 * Works out what each project still waits for (see {@link #waitingFor}), highest priority first, and orders it all
	 * for builds ({@link OrderPurpose#BUILD}): what its plans still have to place beyond what the colony {@code has}, and
	 * the pickaxe its blocks in the way need when the colony {@code hasTool} none as good, and its stock short of what
	 * the colony {@code has}, the last option of each target (the cheapest, such as a wooden pickaxe). A project takes
	 * from {@code has} before those below it. A project with no plans left whose stock the colony has is done. Steps that are not {@code possible} for a builder order nothing. Returns what the plans
	 * still have to place, which is there for nothing else.
	 */
	public Map<Item, Integer> planProjects(Function<BlockPos, BlockState> world, Predicate<BuildPlan.Step> possible, Map<Item, Integer> has,
			Predicate<Item> hasTool) {
		this.waiting.clear();
		Map<Item, Integer> left = new HashMap<>(has);
		Map<Item, Integer> placed = new HashMap<>();
		Map<Item, Integer> total = new HashMap<>();
		ToolKind pickaxes = ToolKind.PICKAXE;
		for (Project project : this.projects()) {
			// Blocked batches order nothing until they are freed.
			List<BuildPlan> plans = project.plans().stream().filter(id -> this.hasWorkLeft(id, this.builds.get(id)))
				.map(id -> this.unblocked(id, this.builds.get(id))).toList();
			Map<Item, Integer> materials = StockPlanner.total(plans.stream().map(plan -> plan.materials(world, possible)).toList());
			// Steps any solid block will do take whichever fill block the colony has: a terraform's fill sand too, a mineshaft's wall only one that holds.
			Map<Item, Integer> holding = StockPlanner.total(plans.stream().map(plan -> plan.fillMaterials(world, false)).toList());
			Map<Item, Integer> resting = StockPlanner.total(plans.stream().map(plan -> plan.fillMaterials(world, true)).toList());
			// A mineshaft's steps take whichever stone stairs the colony has; only what those lack is ordered, as cobblestone stairs.
			Map<Item, Integer> stairs = StockPlanner.total(plans.stream().map(plan -> plan.stoneStairMaterials(world)).toList());
			Map<Item, Integer> spares = new HashMap<>();
			Map<Item, Integer> needed = new LinkedHashMap<>(BuildPlan.shortfall(materials, holding, resting, stairs, left, spares));
			// A full bucket cannot be made, only filled at a source, so builders get an empty one to fill.
			if (needed.keySet().removeIf(item -> item instanceof BucketItem && item != Items.BUCKET) && left.getOrDefault(Items.BUCKET, 0) <= 0) {
				needed.put(Items.BUCKET, 1);
			}
			plans.stream().flatMap(plan -> plan.needs(world, possible).tool().stream()).filter(hasTool.negate())
				.max(Comparator.comparingInt(pickaxes::rank)).ifPresent(tool -> needed.put(tool, 1));
			for (StockTarget target : project.stock()) {
				StockPlanner.Target<Item> planned = target.toPlannerTarget();
				int have = StockPlanner.have(planned, left);
				if (have < target.count()) {
					needed.merge(planned.make().getLast(), target.count() - have, Math::max);
				}
				// What counts toward it is not there for the projects below.
				left.replaceAll((item, held) -> target.matches(item) ? 0 : held);
			}
			if (project.plans().isEmpty() && needed.isEmpty()) {
				this.projects.remove(project);
				continue;
			}
			materials.forEach((item, count) -> {
				left.computeIfPresent(item, (key, held) -> Math.max(0, held - count));
				placed.merge(item, count, Integer::sum);
			});
			// The spare fill blocks and stone stairs its steps take are not there for the projects below either.
			spares.forEach((item, count) -> left.computeIfPresent(item, (key, held) -> Math.max(0, held - count)));
			this.waiting.put(project.id(), needed);
			// One bucket or pickaxe does for every project.
			needed.forEach((item, count) -> total.merge(item, count, item == Items.BUCKET || pickaxes.tiers().contains(item) ? Math::max : Integer::sum));
		}
		// A better pickaxe ordered also counts for the projects that need a worse one.
		int better = 0;
		for (Item tier : pickaxes.tiers().reversed()) {
			int still = Math.max(0, total.getOrDefault(tier, 0) - better);
			if (still == 0) {
				total.remove(tier);
			} else {
				total.put(tier, still);
			}
			better += still;
		}
		this.orderFor(OrderPurpose.BUILD, total);
		return placed;
	}

	/**
	 * Posts a craft job at the core for each of {@code crafts}, the items the colony crafts there, and takes down the open
	 * ones for anything else. A craft for what a project waits for carries the project's priority, and one for a player's
	 * order {@link #ORDER_PRIORITY}, the highest of them when several do: what {@link TaskPlanner} plans to make it from
	 * {@code stock} with {@code book}.
	 */
	public void postCraftJobs(Set<Item> ordered, Map<Item, Integer> stock, TaskPlanner.RecipeBook<Item> book, Predicate<Item> gatherable) {
		// What a bot crafts on the spot (see postStairCrafts) is not crafted at the core as well, unless more stairs are ordered.
		Set<Item> crafts = new HashSet<>(ordered);
		if (this.board.jobs().stream().anyMatch(Colony::isStairCraft) && this.orders.getOrDefault(Items.COBBLESTONE_STAIRS, 0) <= this.stairsOnSpot) {
			crafts.remove(Items.COBBLESTONE_STAIRS);
		}
		this.board.removeOpenIf(job -> job.type() == JobType.CRAFT && job.target().equals(this.core) && job.item().filter(crafts::contains).isEmpty());
		Map<Item, Integer> priorities = new HashMap<>();
		BiConsumer<Map<Item, Integer>, Integer> feeds = (wanted, priority) -> wanted.forEach((item, count) -> {
			Map<Item, Integer> available = new HashMap<>(stock);
			available.remove(item);
			TaskPlanner.plan(item, count, available, book, gatherable).crafts()
				.forEach(craft -> priorities.merge(craft.recipe().output(), priority, Math::max));
		});
		this.projects().forEach(project -> feeds.accept(this.waiting.getOrDefault(project.id(), Map.of()), project.priority()));
		// The part of the orders a player placed: what is ordered beyond what the colony ordered itself.
		Map<Item, Integer> placed = new HashMap<>();
		this.systemOrders.values().forEach(part -> part.forEach((item, count) -> placed.merge(item, count, Integer::sum)));
		Map<Item, Integer> players = new HashMap<>();
		this.orders.forEach((item, count) -> setCount(players, item, count - placed.getOrDefault(item, 0)));
		feeds.accept(players, ORDER_PRIORITY);
		crafts.forEach(item -> this.board.postFor(JobType.CRAFT, this.core, item, Optional.empty(), priorities.getOrDefault(item, 0)));
	}

	/**
	 * Gives the tree and log jobs the priority of the highest project waiting for logs, the branch jobs that of the highest one
	 * waiting for what mining yields, and each vein job that of the highest one waiting for its ore (see {@link #priorityFor}).
	 */
	public void prioritizeGatherJobs(Map<Item, Integer> stock, TaskPlanner.RecipeBook<Item> book, Predicate<Item> gatherable) {
		int trees = this.priorityFor(item -> item.builtInRegistryHolder().is(ItemTags.LOGS), stock, book, gatherable);
		this.board.prioritize(job -> job.type() == JobType.CHOP_TREE || job.type() == JobType.CHOP_LOG, trees);
		// A vein job from before it recorded its ore counts as yielding all that mining does.
		int mine = this.priorityFor(Mineshaft::yields, stock, book, gatherable);
		this.board.prioritize(job -> job.type() == JobType.MINE_BRANCH || job.type() == JobType.MINE_VEIN && job.item().isEmpty(), mine);
		this.board.jobs().stream().filter(job -> job.type() == JobType.MINE_VEIN).flatMap(job -> job.item().stream()).distinct().toList()
			.forEach(ore -> this.board.prioritize(job -> job.type() == JobType.MINE_VEIN && job.item().equals(Optional.of(ore)),
				this.priorityFor(ore::equals, stock, book, gatherable)));
	}

	/**
	 * The priority of the highest project waiting for an item that is {@code wanted}, or for something {@link TaskPlanner}
	 * plans to make or gather it from with {@code stock} and {@code book}; 0 if none is.
	 */
	public int priorityFor(Predicate<Item> wanted, Map<Item, Integer> stock, TaskPlanner.RecipeBook<Item> book, Predicate<Item> gatherable) {
		for (Project project : this.projects()) {
			for (Map.Entry<Item, Integer> waits : this.waiting.getOrDefault(project.id(), Map.of()).entrySet()) {
				Map<Item, Integer> available = new HashMap<>(stock);
				available.remove(waits.getKey());
				TaskPlanner.Plan<Item> plan = TaskPlanner.plan(waits.getKey(), waits.getValue(), available, book, gatherable);
				if (wanted.test(waits.getKey()) || plan.toGather().keySet().stream().anyMatch(wanted)
						|| plan.crafts().stream().anyMatch(craft -> wanted.test(craft.recipe().output()))) {
					return project.priority();
				}
			}
		}
		return 0;
	}

	/** Where each batch of {@code plan} with steps is known by on the board. */
	private static Stream<BlockPos> batchTargets(BuildPlan plan) {
		return IntStream.range(0, plan.batches().size()).filter(i -> plan.batches().get(i).to() > plan.batches().get(i).from())
			.mapToObj(i -> plan.batch(i).target());
	}

	/** Whether {@code job} crafts a mineshaft batch's stairs at the batch (see {@link #postStairCrafts}). */
	public static boolean isStairCraft(Job job) {
		return job.type() == JobType.CRAFT && job.item().equals(Optional.of(Items.COBBLESTONE_STAIRS)) && job.tool().equals(Optional.of(Items.CRAFTING_TABLE));
	}

	/**
	 * While {@code onSpot}, as a bot carries a crafting table and the cobblestone, posts a craft job for cobblestone stairs at
	 * each batch posted to build whose stair steps the stone stairs the colony {@code has} do not cover, the batches in order,
	 * so the bot crafts them there rather than at the core, to be hauled down. Such jobs stop the core crafting them too
	 * unless more are ordered than they make (see {@link #postCraftJobs}). Without {@code onSpot} they come from the core, and the open such jobs are taken down.
	 */
	public void postStairCrafts(Function<BlockPos, BlockState> world, Map<Item, Integer> has, boolean onSpot) {
		int left = BuildPlan.STONE_STAIRS.stream().mapToInt(item -> has.getOrDefault(item, 0)).sum();
		Map<BlockPos, Integer> wanting = new HashMap<>();
		int missing = 0;
		for (Job job : this.board.jobs()) {
			Optional<BuildPlan> batch = job.type() == JobType.BUILD ? this.build(job.id()) : Optional.empty();
			if (batch.isPresent()) {
				int stairs = batch.get().stoneStairMaterials(world).values().stream().mapToInt(Integer::intValue).sum();
				if (stairs > left) {
					wanting.put(batch.get().target(), job.priority());
					missing += stairs - left;
				}
				left = Math.max(0, left - stairs);
			}
		}
		Set<BlockPos> batches = this.builds.values().stream().flatMap(Colony::batchTargets).collect(Collectors.toSet());
		this.board.removeOpenIf(job -> isStairCraft(job) && batches.contains(job.target()) && !(onSpot && wanting.containsKey(job.target())));
		this.stairsOnSpot = onSpot ? missing : 0;
		if (onSpot) {
			wanting.forEach((at, priority) -> this.board.postFor(JobType.CRAFT, at, Items.COBBLESTONE_STAIRS, Optional.of(Items.CRAFTING_TABLE), priority));
		}
	}

	/**
	 * Drops the plan with id {@code id} if it has no steps, and marks its empty batches done, as
	 * there is nothing to build there: an old save's plan or batch of only second halves (see
	 * {@link BuildPlan}), or a batch {@link #withoutOverlaps} emptied. Marked done rather than
	 * removed, empty batches keep the indexes that jobs, states and the batches needing them go by.
	 */
	private void settleEmpty(UUID id) {
		BuildPlan plan = this.builds.get(id);
		if (plan.steps().isEmpty()) {
			this.removePlan(id);
			return;
		}
		for (int i = 0; i < plan.batches().size(); i++) {
			if (plan.batches().get(i).from() == plan.batches().get(i).to()) {
				this.batchStates.put(batchId(id, i), BatchState.DONE);
			}
		}
	}

	/**
	 * {@code plan} without the steps that a later step at the same position overrides, such as a block
	 * a later batch replaces: the finished plan holds what the later step wants, so the earlier one
	 * would look undone and its batch be reopened, undoing the later one, for good. Batches keep their
	 * other steps, emptied ones included, so the batches that need them still match.
	 */
	static BuildPlan withoutOverlaps(BuildPlan plan) {
		Map<BlockPos, BuildPlan.Step> last = lastSteps(plan);
		if (plan.steps().stream().allMatch(step -> last.get(step.pos()).equals(step))) {
			return plan;
		}
		List<BuildPlan.Step> steps = new ArrayList<>();
		List<BuildPlan.Batch> batches = new ArrayList<>();
		for (BuildPlan.Batch batch : plan.batches()) {
			int from = steps.size();
			plan.steps().subList(batch.from(), batch.to()).stream().filter(step -> last.get(step.pos()).equals(step)).forEach(steps::add);
			batches.add(new BuildPlan.Batch(from, steps.size(), batch.needs(), batch.dig()));
		}
		return new BuildPlan(steps, batches, plan.kind(), plan.ground());
	}

	/** The last step of {@code plan} at each position it has a step for. */
	private static Map<BlockPos, BuildPlan.Step> lastSteps(BuildPlan plan) {
		Map<BlockPos, BuildPlan.Step> last = new HashMap<>();
		plan.steps().forEach(step -> last.put(step.pos(), step));
		return last;
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
		Set<BlockPos> targets = batchTargets(this.builds.get(id)).collect(Collectors.toSet());
		this.board.removeOpenIf(job -> isStairCraft(job) && targets.contains(job.target()));
		this.builds.remove(id);
		this.batchIds.remove(id);
		this.stepSets.remove(id);
		this.projects.replaceAll(project -> project.without(id));
		this.projects.stream().filter(Project::isEmpty).forEach(project -> this.waiting.remove(project.id()));
		this.projects.removeIf(Project::isEmpty);
		this.indexBatches();
		this.batchStates.keySet().removeAll(jobs);
		this.blockedSteps.removeIf(blocked -> jobs.contains(blocked.plan()));
		this.board.removeOpenIf(job -> isBuildJob(job) && jobs.contains(job.id()));
		this.lastSurvey = -1;
	}

	/** The batch a build job is for, as a plan of its own. */
	public Optional<BuildPlan> build(UUID job) {
		BatchRef batch = this.batchJobs.get(job);
		return batch == null ? Optional.empty()
			: Optional.of(this.batchPlans.computeIfAbsent(job, id -> this.builds.get(batch.plan()).batch(batch.index())));
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
		if (this.isFinished(batch.plan(), plan)) {
			this.removePlan(batch.plan());
		} else {
			this.postBuildJobs();
		}
	}

	/** What a batch set aside waits for. */
	public enum Wait {
		/** Items, such as torches or materials, with the rest of the batch worked through. */
		ITEMS,
		/** A fluid to settle, with blocks still to clear once it has. */
		FLUID,
		/** A tree standing in a dig batch to be felled, by any bot that takes its tree job, which what is built on it would bury. */
		TREES
	}

	/**
	 * Records that the batch of build job {@code job} waits for {@code reason}. One waiting only for
	 * items lets the batches that need it start, such as a hall once the stairs reaching it are dug
	 * but not lit yet; one waiting for a fluid or for trees does not, as blocks of it are still to clear. The batch
	 * stays posted to be finished.
	 */
	public void batchWaiting(UUID job, Wait reason) {
		if (reason == Wait.ITEMS && this.batchJobs.containsKey(job) && this.batchStates.putIfAbsent(job, BatchState.WAITING) == null) {
			this.postBuildJobs();
		}
	}

	/**
	 * Posts a build job for each batch whose needed batches are done or waiting, a dig job for a {@link BuildPlan.Batch#dig} batch, and drops open jobs of batches
	 * that are gone, done, or wait on blocked steps (see {@link #blockedBatches}).
	 */
	public void postBuildJobs() {
		Set<UUID> ready = new HashSet<>();
		this.builds.forEach((id, plan) -> {
			Set<Integer> blocked = this.blockedBatches(id, plan);
			this.ready(id, plan).stream().filter(i -> !blocked.contains(i)).forEach(i -> ready.add(batchId(id, i)));
		});
		this.board.removeOpenIf(job -> isBuildJob(job) && !ready.contains(job.id()));
		this.builds.forEach((id, plan) -> this.ready(id, plan).stream()
			.filter(i -> ready.contains(batchId(id, i)))
			// A plan's batches in order where they can: the first one first, such as a mineshaft's from the top down.
				.forEach(i -> this.board.post(batchId(id, i), plan.batches().get(i).dig() ? JobType.CLEAR_AREA : JobType.BUILD, plan.batch(i).target(),
				this.priorityOf(id) - i)));
	}

	/** Declares on each build job what its batch still needs with {@code world} as it is (see {@link BuildPlan#needs}), for the job score. */
	public void declareBuildNeeds(Function<BlockPos, BlockState> world) {
		this.declareBuildNeeds(world, step -> true, tool -> true);
	}

	/**
	 * {@link #declareBuildNeeds(Function)} for just the steps that are {@code possible}. A pickaxe the colony does not
	 * {@code have} is not declared: no bot could take the job, while one can dig by hand until a pickaxe is made.
	 */
	public void declareBuildNeeds(Function<BlockPos, BlockState> world, Predicate<BuildPlan.Step> possible, Predicate<Item> have) {
		for (Job job : this.board.jobs()) {
			if (isBuildJob(job)) {
				this.build(job.id()).map(batch -> batch.needs(world, possible))
					.ifPresent(needs -> this.board.declare(job.id(), needs.item(), needs.tool().filter(have)));
			}
		}
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

	/** Where builders found no way to a step; see {@link #reportBlocked(UUID, Collection, Collection)}. */
	public List<BlockPos> unreachableSteps() {
		return this.blockedSteps.stream().filter(Blocked::unreachable).map(Blocked::pos).toList();
	}

	/** {@link #reportBlocked(UUID, Collection, Collection)} with every step reachable. */
	public void reportBlocked(UUID job, Collection<BlockPos> steps) {
		this.reportBlocked(job, steps, List.of());
	}

	/**
	 * Records the steps of the batch of build job {@code job} that a builder cannot do; the batch,
	 * and the batches that need it, wait without jobs until a recheck frees it, while the plan's
	 * other batches go on. The mineshaft does not wait: it drops the
	 * levels the steps keep builders from (see {@link Mineshaft#reachable}) and goes on without them.
	 * Of those, the steps in {@code unreachable} are ones the builder found no way to.
	 */
	public void reportBlocked(UUID job, Collection<BlockPos> steps, Collection<BlockPos> unreachable) {
		BatchRef batch = this.batchJobs.get(job);
		Optional<Mineshaft> shaft = this.mineshaft
			.filter(mine -> batch != null && this.builds.get(batch.plan()).equals(mine.plan()))
			.map(mine -> mine.reachable(steps))
			.filter(reachable -> !reachable.equals(this.mineshaft.get()));
		if (shaft.isPresent()) {
			this.setMineshaft(shaft.get());
			return;
		}
		steps.stream().map(pos -> new Blocked(job, pos.immutable(), unreachable.contains(pos)))
			.filter(b -> this.blockedSteps.stream().noneMatch(old -> old.plan().equals(b.plan()) && old.pos().equals(b.pos())))
			.forEach(this.blockedSteps::add);
	}

	/**
	 * Drops blocked steps whose plan is gone, and those that are done or that {@code workable},
	 * given the step's batch and the step, says a bot could do now. A step no builder could get to
	 * is dropped only once done: {@code workable} cannot tell whether there is a way to it now, and
	 * trying it each survey would only fail again, so {@link #recheckGivenUp} retries it now and then. A batch with such a step gets
	 * all its blocked steps dropped, so it gets a job again and its bot reports whatever is still
	 * blocked. {@code world} gives null where it cannot tell, such as in unloaded chunks.
	 */
	public void recheckBlocked(Function<BlockPos, @Nullable BlockState> world, BiPredicate<BuildPlan, BuildPlan.Step> workable) {
		Set<UUID> freed = new HashSet<>();
		for (Blocked blocked : this.blockedSteps) {
			Optional<BuildPlan> batch = this.build(blocked.plan());
			Optional<BuildPlan.Step> step = batch.flatMap(plan -> plan.steps().stream().filter(s -> s.pos().equals(blocked.pos())).findFirst());
			BlockState state = world.apply(blocked.pos());
			if (step.isEmpty() || state != null && (step.get().isDone(state) || !blocked.unreachable() && workable.test(batch.get(), step.get()))) {
				freed.add(blocked.plan());
			}
		}
		if (this.blockedSteps.removeIf(blocked -> freed.contains(blocked.plan()))) {
			this.lastSurvey = -1;
		}
	}

	/**
	 * Reopens each done batch whose blocks no longer all match the world, such as a torch broken off
	 * the mineshaft stairs, so it is posted again and rebuilt. It goes back as waiting, so the batches
	 * that need it stay open meanwhile. {@code world} gives null where it cannot tell, such as in
	 * unloaded chunks; such blocks are taken as still done. A step that a later step of the plan
	 * overrides, as in a plan saved before {@link #withoutOverlaps}, is not checked: the block is the
	 * later step's to keep.
	 */
	public void recheckDone(Function<BlockPos, @Nullable BlockState> world) {
		boolean reopened = false;
		Map<UUID, Map<BlockPos, BuildPlan.Step>> lastSteps = new HashMap<>();
		for (Map.Entry<UUID, BatchState> entry : this.batchStates.entrySet()) {
			BatchRef ref = this.batchJobs.get(entry.getKey());
			if (entry.getValue() != BatchState.DONE || ref == null) {
				continue;
			}
			Map<BlockPos, BuildPlan.Step> last = lastSteps.computeIfAbsent(ref.plan(), id -> lastSteps(this.builds.get(id)));
			if (this.build(entry.getKey()).filter(batch -> batch.steps().stream().anyMatch(step -> {
				BlockState state = world.apply(step.pos());
				return last.get(step.pos()).equals(step) && state != null && !step.isDone(state);
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
	 * Every {@link #GIVEN_UP_RECHECK_TICKS}, puts the jobs bots gave up on back up, and frees the
	 * batches with steps builders found no way to, so a tree, branch or step a player has since
	 * opened the way to gets done after all. Returns how many jobs and batches were reopened.
	 */
	public int recheckGivenUp(long now) {
		if (this.lastGivenUpRecheck < 0) {
			this.lastGivenUpRecheck = now;
		}
		if (now - this.lastGivenUpRecheck < GIVEN_UP_RECHECK_TICKS) {
			return 0;
		}
		this.lastGivenUpRecheck = now;
		Set<UUID> freed = this.blockedSteps.stream().filter(Blocked::unreachable).map(Blocked::plan).collect(Collectors.toCollection(HashSet::new));
		if (this.blockedSteps.removeIf(blocked -> freed.contains(blocked.plan()))) {
			this.lastSurvey = -1;
		}
		return this.board.reopenGivenUp() + freed.size();
	}

	/**
	 * Gives up on every blocked step, the batches they are in and the batches that need those, for good. The rest of
	 * their plans goes on; a plan with nothing else left is dropped. Returns how many steps there were.
	 */
	public int clearBlocked() {
		int count = this.blockedSteps.size();
		Set<UUID> plans = new HashSet<>();
		for (Blocked blocked : this.blockedSteps) {
			if (this.batchJobs.containsKey(blocked.plan())) {
				this.batchStates.put(blocked.plan(), BatchState.GIVEN_UP);
				plans.add(this.planOf(blocked.plan()));
			}
		}
		this.blockedSteps.clear();
		// Their steps are no longer kept open.
		plans.forEach(this.stepSets::remove);
		plans.stream().filter(id -> this.isFinished(id, this.builds.get(id))).toList().forEach(this::removePlan);
		this.postBuildJobs();
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
		if (!Territory.inBounds(this.bounds, pos)) {
			return false;
		}
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

	/** Where paths must not place scaffold: the mineshaft's stairs, halls and branches, every build plan's step, which the plan wants as it says, and the ore veins posted. */
	public Predicate<BlockPos> keepOpen() {
		List<Set<BlockPos>> open = new ArrayList<>();
		open.add(this.shaftOpen);
		this.builds.forEach((id, plan) -> {
			if (this.hasWorkLeft(id, plan)) {
				open.add(this.stepSets.computeIfAbsent(id, planId -> {
					this.stepSetsCollected++;
					Set<Integer> givenUp = this.givenUpBatches(planId, plan);
					return IntStream.range(0, plan.batches().size()).filter(i -> !givenUp.contains(i))
						.boxed().flatMap(i -> plan.batch(i).steps().stream()).map(BuildPlan.Step::pos).collect(Collectors.toUnmodifiableSet());
				}));
			}
		});
		// The holes a miner digs out a vein through.
		Set<BlockPos> veins = new HashSet<>();
		this.board.jobs().stream().filter(job -> job.type() == JobType.MINE_VEIN).forEach(job -> veins.addAll(job.area()));
		open.add(veins);
		BoundingBox bounds = this.bounds;
		return pos -> Territory.inBounds(bounds, pos) && open.stream().anyMatch(set -> set.contains(pos));
	}

	/** Where paths must not break a block: the ground each terraform with work left levels, as it wants it (see {@link BuildPlan#ground}). */
	public Predicate<BlockPos> keepGround() {
		List<BoundingBox> ground = this.builds.entrySet().stream().filter(e -> e.getValue().ground().isPresent() && this.hasWorkLeft(e.getKey(), e.getValue()))
			.map(e -> e.getValue().ground().orElseThrow()).toList();
		BoundingBox bounds = this.bounds;
		return pos -> Territory.inBounds(bounds, pos) && ground.stream().anyMatch(box -> box.isInside(pos));
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
	public Project setMineshaft(Mineshaft shaft) {
		this.mineshaft.ifPresent(old -> this.removeBuild(old.plan()));
		this.mineshaft = Optional.of(shaft);
		this.shaftBlocks = shaft.protectedBlocks();
		this.shaftOpen = shaft.keepOpen();
		this.board.removeOpenIf(job -> job.type() == JobType.MINE_BRANCH);
		return this.addBuild(MINESHAFT, shaft.plan());
	}

	/** The crop field the colony started itself, if it needed one. */
	public Optional<Field> field() {
		return this.field;
	}

	public void setField(Field field) {
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

	public @Nullable BoundingBox bounds() {
		return this.bounds;
	}

	/** Clips the colony to a game test's area: its territory, and where its blocks are protected and kept open. */
	public void setBounds(@Nullable BoundingBox bounds) {
		this.bounds = bounds;
	}
}
