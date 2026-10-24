package io.github.barniebarnie.antcraft.colony;

import com.google.common.collect.Sets;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mojang.datafixers.util.Either;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.TagKey;
import net.minecraft.util.StringRepresentable;
import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.blueprint.Blueprint;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Field;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.OreRevisit;
import io.github.barniebarnie.antcraft.work.Quarry;
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
	/** How many finished terraforms' ground the colony keeps at most (see {@link #keepGround}); the oldest is forgotten first. */
	public static final int MAX_KEPT_GROUND = 32;
	/** How many placements dropped for a site that could not be levelled {@link #droppedUnlevelled} keeps. */
	public static final int MAX_DROPPED_UNLEVELLED = 8;
	/** How many lava sources bots saw the colony remembers at most (see {@link #sawLava}); the one seen longest ago is forgotten first. */
	public static final int MAX_SEEN_LAVA = 64;
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
		BlockPos.CODEC.listOf().xmap(positions -> positions.stream().map(pos -> new Blocked(new UUID(0, 0), pos, BlockReason.OTHER)).toList(),
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

	/** Why a builder could not do a step, where it can tell. */
	public enum BlockReason implements StringRepresentable {
		/** The step itself cannot be done, such as behind bedrock. */
		OTHER,
		/** The builder found no way to the step. */
		UNREACHABLE,
		/** A lava step with no lava bucket at hand and no lava source in reach to fill one at. */
		NO_LAVA,
		/** A water step with no water bucket at hand and no water source in reach to fill one at. */
		NO_WATER;

		public static final Codec<BlockReason> CODEC = StringRepresentable.fromEnum(BlockReason::values);

		@Override
		public String getSerializedName() {
			return this.name().toLowerCase(Locale.ROOT);
		}
	}

	/**
	 * A step that a builder could not do, of the batch of build job {@code plan} (named so before
	 * plans had batches), and why. Saves from before the reason say only whether it was unreachable,
	 * which is still written for them.
	 */
	record Blocked(UUID plan, BlockPos pos, BlockReason reason) {
		static final Codec<Blocked> CODEC = RecordCodecBuilder.create(i -> i.group(
			UUIDUtil.STRING_CODEC.fieldOf("plan").forGetter(Blocked::plan),
			BlockPos.CODEC.fieldOf("pos").forGetter(Blocked::pos),
			Codec.BOOL.optionalFieldOf("unreachable", false).forGetter(Blocked::unreachable),
			BlockReason.CODEC.optionalFieldOf("reason", BlockReason.OTHER).forGetter(Blocked::reason)
		).apply(i, (plan, pos, unreachable, reason) -> new Blocked(plan, pos, unreachable ? BlockReason.UNREACHABLE : reason)));

		/** Whether the builder found no way to the step, rather than the step itself being impossible. */
		boolean unreachable() {
			return this.reason == BlockReason.UNREACHABLE;
		}
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
			Map<UUID, BatchState> batchStates, Optional<Field> field, Optional<StorageArea> storage, List<Project> projects,
			List<BoundingBox> keptGround, Optional<Quarry> quarry, List<BlockPos> veinCells, List<BlockPos> seenLava) {
		static final MapCodec<Works> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			// Old saves may still have a "quarry" of the old kind, a box mined layer by layer; it is ignored.
			Mineshaft.CODEC.optionalFieldOf("mineshaft").forGetter(Works::mineshaft),
			BUILDS.optionalFieldOf("builds", Map.of()).forGetter(Works::builds),
			PROTECTED.optionalFieldOf("protected", List.of()).forGetter(Works::placed),
			BLOCKED.optionalFieldOf("blocked_steps", List.of()).forGetter(Works::blockedSteps),
			Codec.unboundedMap(UUIDUtil.STRING_CODEC, BatchState.CODEC).optionalFieldOf("batches", Map.of()).forGetter(Works::batchStates),
			Field.CODEC.optionalFieldOf("field").forGetter(Works::field),
			StorageArea.CODEC.optionalFieldOf("storage").forGetter(Works::storage),
			// Saves from before projects get a project for each plan.
			Project.CODEC.listOf().optionalFieldOf("projects", List.of()).forGetter(Works::projects),
			BoundingBox.CODEC.listOf().optionalFieldOf("kept_ground", List.of()).forGetter(Works::keptGround),
			Quarry.CODEC.optionalFieldOf("open_quarry").forGetter(Works::quarry),
			BlockPos.CODEC.listOf().optionalFieldOf("vein_cells", List.of()).forGetter(Works::veinCells),
			BlockPos.CODEC.listOf().optionalFieldOf("seen_lava", List.of()).forGetter(Works::seenLava)
		).apply(i, Works::new));
	}

	/** Who may change the colony: the player who placed its core, and the players they trust. Colonies saved before owners have none. */
	private record Access(Optional<UUID> owner, List<UUID> trusted) {
		static final MapCodec<Access> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(Access::owner),
			UUIDUtil.CODEC.listOf().optionalFieldOf("trusted", List.of()).forGetter(Access::trusted)
		).apply(i, Access::new));
	}

	/** The blueprints players placed for the colony to build, and those it built (see {@link PlacedBlueprint}). */
	private record Placements(List<PlacedBlueprint> blueprints) {
		static final MapCodec<Placements> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			PlacedBlueprint.CODEC.listOf().optionalFieldOf("blueprints", List.of()).forGetter(Placements::blueprints)
		).apply(i, Placements::new));
	}

	public static final Codec<Colony> CODEC = RecordCodecBuilder.create(i -> i.group(
		Basics.CODEC.forGetter(c -> new Basics(c.id, c.core, List.copyOf(c.members), c.board, c.radius)),
		Wants.CODEC.forGetter(c -> new Wants(c.orders, c.stock, Map.of(), c.systemOrders)),
		Works.CODEC.forGetter(c -> new Works(c.mineshaft, c.builds, c.placed(), c.blockedSteps, c.batchStates, c.field, c.storage, List.copyOf(c.projects),
			List.copyOf(c.keptGround), c.quarry, List.copyOf(c.veinCells), List.copyOf(c.seenLava))),
		Access.CODEC.forGetter(c -> new Access(c.owner, List.copyOf(c.trusted))),
		Placements.CODEC.forGetter(c -> new Placements(List.copyOf(c.blueprints)))
	).apply(i, Colony::new));

	private final UUID id;
	private final BlockPos core;
	private final Set<UUID> members;
	/** The player who placed the core, if known; see {@link ColonyActions#mayAct}. */
	private Optional<UUID> owner = Optional.empty();
	/** Players the owner lets change the colony, besides the owner. */
	private final Set<UUID> trusted = new LinkedHashSet<>();
	private final JobBoard board;
	private int radius;
	/** The game test area this colony is clipped to (see {@link Territory}); not saved, and null outside game tests. */
	private @Nullable BoundingBox bounds;
	private final Map<Item, Integer> orders;
	private Optional<Mineshaft> mineshaft;
	/** The mineshaft's stair floors and torches, which bots leave standing. */
	private Set<BlockPos> shaftBlocks;
	private Set<BlockPos> shaftOpen;
	/** The holes miners dug out ore veins through, off the mine's branches (see {@link #mineCells}). */
	private final Set<BlockPos> veinCells = new HashSet<>();
	/** Lava sources bots saw, such as from the mine's halls and branches, where builders fill buckets, oldest first (see {@link #sawLava}). */
	private final LinkedHashSet<BlockPos> seenLava = new LinkedHashSet<>();
	private final List<StockTarget> stock;
	/** The projects the colony works on, in the order they were added; see {@link #projects}. */
	private final List<Project> projects = new ArrayList<>();
	/** The blueprints players placed, built or still to build, in the order they were placed. */
	private final List<PlacedBlueprint> blueprints = new ArrayList<>();
	/**
	 * The placed blueprints dropped when their levelling, given up before {@link #clearBlocked} dropped such placements
	 * itself (saves from before #267), finished: at most {@link #MAX_DROPPED_UNLEVELLED}, newest last; not saved.
	 */
	private final List<PlacedBlueprint> droppedUnlevelled = new ArrayList<>();
	/** The ground of finished terraforms, oldest first, which paths leave standing for good (see {@link #keepGround}). */
	private final List<BoundingBox> keptGround = new ArrayList<>();
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
	/** The pit dug for cobblestone while the mineshaft yields nothing yet (see {@link #updateQuarry}), once the colony picked a site. */
	private Optional<Quarry> quarry = Optional.empty();
	/** Searches left to skip, by what the site is for, after a search found no room; not saved, so a reload only searches sooner. */
	private final Map<String, Integer> siteSkips = new HashMap<>();
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
	/** Whether {@link #lookUpFluids} ran since the colony was loaded. */
	private boolean fluidsLookedUp;
	/** Game time of the last recheck of given-up jobs, or -1 before the first; not saved, so a reload only delays it. */
	private long lastGivenUpRecheck = -1;

	private Colony(Basics basics, Wants wants, Works works, Access access, Placements placements) {
		this(basics.id(), basics.core(), basics.members(), basics.board(), basics.radius(), wants.orders(), works.mineshaft(), wants.stock(),
			works.builds(), works.placed(), works.field(), works.storage(), wants.buildOrders(), works.blockedSteps(), wants.systemOrders(),
			works.batchStates(), works.projects());
		this.keptGround.addAll(works.keptGround());
		this.quarry = works.quarry();
		this.veinCells.addAll(works.veinCells());
		works.seenLava().forEach(this::sawLava);
		this.owner = access.owner();
		this.trusted.addAll(access.trusted());
		access.owner().ifPresent(this.trusted::remove);
		// A placement still being built whose project is gone was cancelled.
		placements.blueprints().stream().filter(placed -> placed.finished() || this.hasProject(placed.project())).forEach(this.blueprints::add);
		// A plan an old save left with nothing to do but never finished, such as a levelling whose batches not given up
		// were all done or needed the given-up one, would wait for ever: finish it now, so finishBlueprintPlan reports its placement.
		this.builds.entrySet().stream().filter(e -> this.isFinished(e.getKey(), e.getValue())).map(Map.Entry::getKey).toList()
			.forEach(this::finishPlan);
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
			project.plans().stream().filter(builds::containsKey).toList(), project.stock(), project.starter())));
		this.projects.removeIf(Project::isEmpty);
		builds.forEach((planId, plan) -> {
			if (this.projects.stream().noneMatch(project -> project.plans().contains(planId))) {
				boolean shaft = mineshaft.filter(s -> s.isPlan(plan)).isPresent();
				this.projects.add(new Project(planId, shaft ? MINESHAFT : defaultName(plan), 0, List.of(planId), List.of(), shaft));
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

	/** The player who placed the core, or empty for a colony founded otherwise or saved before owners. */
	public Optional<UUID> owner() {
		return this.owner;
	}

	public void setOwner(UUID owner) {
		this.owner = Optional.of(owner);
		this.trusted.remove(owner);
	}

	/** The players besides the owner who may change the colony. */
	public Set<UUID> trusted() {
		return Collections.unmodifiableSet(this.trusted);
	}

	/** Whether {@code player} is the owner or trusted. */
	public boolean isOwnerOrTrusted(UUID player) {
		return this.owner.filter(player::equals).isPresent() || this.trusted.contains(player);
	}

	/** @return whether {@code player} was not trusted before; the owner never is */
	public boolean trust(UUID player) {
		return this.owner.filter(player::equals).isEmpty() && this.trusted.add(player);
	}

	/** @return whether {@code player} was trusted */
	public boolean untrust(UUID player) {
		return this.trusted.remove(player);
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

	/**
	 * Whether a block at {@code pos} that another batch of build job {@code job}'s plan places is in a
	 * {@linkplain #blockedBatches blocked batch}, so it is not placed until the player frees that batch: a fluid of the job's
	 * batch that waits for it (see {@link BuildPlan#walls}) would wait for ever, and is reported instead.
	 */
	public boolean isHeldUp(UUID job, BlockPos pos) {
		BatchRef ref = this.batchJobs.get(job);
		BuildPlan plan = ref == null ? null : this.builds.get(ref.plan());
		if (plan == null) {
			return false;
		}
		Set<Integer> blocked = this.blockedBatches(ref.plan(), plan);
		return blocked.stream().anyMatch(i -> i != ref.index() && plan.batch(i).steps().stream().anyMatch(step -> step.pos().equals(pos)));
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
	 * Areas the colony already uses, which a new area must keep clear of: the mineshaft, field, storage and quarry, and the
	 * bounds of each unfinished build plan (such as the house). Add new kinds of areas here.
	 */
	public List<BoundingBox> occupied() {
		List<BoundingBox> areas = new ArrayList<>();
		this.mineshaft.ifPresent(shaft -> areas.add(shaft.area()));
		this.field.ifPresent(field -> areas.add(field.area()));
		this.storage.ifPresent(storage -> areas.add(storage.area()));
		this.quarry.ifPresent(quarry -> areas.add(quarry.area()));
		this.builds.values().forEach(plan -> BoundingBox.encapsulatingPositions(plan.steps().stream().map(BuildPlan.Step::pos).toList()).ifPresent(areas::add));
		this.blueprints.forEach(placed -> areas.add(placed.area()));
		return areas;
	}

	/** Where blocks build plans placed are, which bots leave standing while they are there (see {@link #isProtected}). */
	public Set<BlockPos> placedBlocks() {
		return Set.copyOf(this.protectedBlocks.keySet());
	}

	/**
	 * Adds the blueprint {@code placed} (see {@link BlueprintPlacement}) as a project named after it: the {@code levelling}
	 * of its site first, if any, and once that is done the {@code structure}. Returns it with its project.
	 */
	public PlacedBlueprint placeBlueprint(PlacedBlueprint placed, Optional<BuildPlan> levelling, BuildPlan structure) {
		Project project = this.addBuild(placed.blueprint().toString(), levelling.orElse(structure));
		PlacedBlueprint added = placed.withFluidsOf(structure).withProject(project.id(), levelling.map(plan -> structure));
		this.blueprints.add(added);
		return added;
	}

	/** The blueprints players placed, in the order they were placed: those being built and those built. */
	public List<PlacedBlueprint> blueprints() {
		return List.copyOf(this.blueprints);
	}

	/**
	 * The placed blueprints dropped since the server started because their site levelling had been given up in a save from
	 * before {@link #clearBlocked} dropped them itself, oldest first; {@code colony info} names them.
	 */
	public List<PlacedBlueprint> droppedUnlevelled() {
		return List.copyOf(this.droppedUnlevelled);
	}

	/** The built blueprints with role {@code role}, oldest first, such as the storage buildings whose chests the colony may use. */
	public List<PlacedBlueprint> finishedBlueprints(Blueprint.Role role) {
		return this.blueprints.stream().filter(placed -> placed.finished() && placed.role().equals(Optional.of(role))).toList();
	}

	/** The mining blocks of the built cobblestone generators: their first work spot, where cobblestone forms. */
	public List<BlockPos> generatorMiningBlocks() {
		return this.finishedBlueprints(Blueprint.Role.GENERATOR).stream().filter(placed -> placed.workSpots().size() >= 2)
			.map(placed -> placed.workSpots().getFirst()).toList();
	}

	/** Whether {@code pos} is the mining block of a built cobblestone generator; a loop, as paths ask this of every block. */
	private boolean isGeneratorMiningBlock(BlockPos pos) {
		for (PlacedBlueprint placed : this.blueprints) {
			if (placed.finished() && placed.role().equals(Optional.of(Blueprint.Role.GENERATOR)) && placed.workSpots().size() >= 2
					&& placed.workSpots().getFirst().equals(pos)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether {@code pos} is a plot or the water of a built farm, which stay protected tilled, broken or gone: tilling
	 * turns the dirt a builder placed into farmland, and the farmer pours the water back. A loop, as paths ask this of
	 * every block.
	 */
	private boolean isFarmBlock(BlockPos pos) {
		for (PlacedBlueprint placed : this.blueprints) {
			if (placed.finished() && placed.role().equals(Optional.of(Blueprint.Role.FARM)) && placed.area().isInside(pos)
					&& (placed.workSpots().contains(pos) || placed.water().contains(pos))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Fills in where each placed blueprint saved without them pours water and lava, from {@code structure}, the plan of
	 * its structure, if the server has it: farms saved before they kept their water, and generators before they kept
	 * their lava. Once per load; a blueprint with no fluids is looked up each load, which costs only the structure.
	 */
	public void lookUpFluids(Function<PlacedBlueprint, Optional<BuildPlan>> structure) {
		if (this.fluidsLookedUp) {
			return;
		}
		this.fluidsLookedUp = true;
		this.blueprints.replaceAll(placed -> placed.lava().isEmpty()
				&& (placed.water().isEmpty() || placed.role().equals(Optional.of(Blueprint.Role.GENERATOR)))
			? structure.apply(placed).map(placed::withFluidsOf).orElse(placed) : placed);
	}

	/**
	 * Adds a plan to pour back the water and lava of each built blueprint that {@code world} shows gone, such as a farm's
	 * water or a generator's lava, unless a plan is at them already or the player cancelled or gave up such a plan before
	 * (see {@link #stopFluidRestore}). {@code world} gives null where it cannot tell, such as in unloaded chunks; such
	 * fluids are taken as still there. See {@link #fluidSteps} for the steps. Returns whether a generator waits for the
	 * water scooped out of it to drain before its mining block is mined, which takes a few ticks, so the caller can look
	 * again soon instead of at the next survey.
	 */
	public boolean restoreFluids(Function<BlockPos, @Nullable BlockState> world) {
		Set<BlockPos> planned = this.builds.values().stream().flatMap(plan -> plan.steps().stream()).map(BuildPlan.Step::pos)
			.collect(Collectors.toSet());
		boolean draining = false;
		for (PlacedBlueprint placed : List.copyOf(this.blueprints)) {
			Set<BlockPos> spots = placed.fluidSpots();
			if (!placed.finished() || !placed.restoreFluids() || spots.stream().anyMatch(planned::contains)) {
				continue;
			}
			FluidSteps steps = fluidSteps(placed, world);
			draining |= steps.draining();
			if (!steps.steps().isEmpty()) {
				this.addBuild(placed.blueprint().toString(), new BuildPlan(steps.steps()));
				planned.addAll(spots);
			}
		}
		return draining;
	}

	/** The steps {@link #fluidSteps} found, and whether it waits for water to drain beside a generator's mining block. */
	private record FluidSteps(List<BuildPlan.Step> steps, boolean draining) {
		static final FluidSteps NONE = new FluidSteps(List.of(), false);
	}

	/**
	 * The steps that pour back what of the water and lava of {@code placed} is gone in {@code world}: water first, so
	 * lava poured into a generator meets it. A generator's lava is walled in but for its mining block, so with that
	 * block standing a builder could not pour it, as a player could not; the generator is opened in stages, a plan for
	 * each: its water is scooped up first, so none flows beside the mining block, then once no fluid is left beside it
	 * the mining block is mined, and then both are poured back.
	 */
	private static FluidSteps fluidSteps(PlacedBlueprint placed, Function<BlockPos, @Nullable BlockState> world) {
		List<BlockPos> water = placed.water().stream().filter(pos -> isGone(world.apply(pos), Blocks.WATER)).toList();
		List<BlockPos> lava = placed.lava().stream().filter(pos -> isGone(world.apply(pos), Blocks.LAVA)).toList();
		if (!lava.isEmpty() && placed.role().equals(Optional.of(Blueprint.Role.GENERATOR)) && !placed.workSpots().isEmpty()) {
			BlockPos mine = placed.workSpots().getFirst();
			BlockState block = world.apply(mine);
			if (block == null) {
				return FluidSteps.NONE;
			}
			if (!block.canBeReplaced()) {
				List<BlockPos> standing = placed.water().stream().filter(pos -> !water.contains(pos)).toList();
				if (!standing.isEmpty()) {
					return new FluidSteps(standing.stream().map(BuildPlan.Step::clear).toList(), false);
				}
				boolean wet = Stream.of(Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST).map(mine::relative)
					.map(world).anyMatch(state -> state == null || !state.getFluidState().isEmpty());
				return wet ? new FluidSteps(List.of(), true) : new FluidSteps(List.of(BuildPlan.Step.clear(mine)), false);
			}
		}
		return new FluidSteps(Stream.concat(water.stream().map(pos -> new BuildPlan.Step(pos, Blocks.WATER.defaultBlockState())),
			lava.stream().map(pos -> new BuildPlan.Step(pos, Blocks.LAVA.defaultBlockState()))).toList(), false);
	}

	/**
	 * Stops pouring back the fluids of each built blueprint {@code plan}, cancelled or given up by the player, pours back
	 * (see {@link #restoreFluids}): one whose steps are all at its water, lava or generator mining block. A player who
	 * empties a farm or generator on purpose keeps it empty so.
	 */
	private void stopFluidRestore(@Nullable BuildPlan plan) {
		if (plan == null || plan.steps().isEmpty()) {
			return;
		}
		Set<BlockPos> steps = plan.steps().stream().map(BuildPlan.Step::pos).collect(Collectors.toSet());
		this.blueprints.replaceAll(placed -> placed.finished() && placed.restoreFluids() && placed.fluidSpots().containsAll(steps)
			? placed.withoutFluidRestore() : placed);
	}

	/** Whether {@code state}, if known, is not a source of {@code fluid}. */
	private static boolean isGone(@Nullable BlockState state, Block fluid) {
		return state != null && !(state.is(fluid) && state.getFluidState().isSource());
	}

	/**
	 * What of {@code wanted} the colony mines for, in the mineshaft or quarry: all of it, but cobblestone once it has a
	 * built cobblestone generator, which makes it instead, so the mineshaft is dug for ores.
	 */
	public Predicate<Item> wantedFromMining(Predicate<Item> wanted) {
		boolean generator = !this.generatorMiningBlocks().isEmpty();
		return item -> wanted.test(item) && !(generator && item == Items.COBBLESTONE);
	}

	/**
	 * Posts a {@link JobType#MINE_GENERATOR} job, which needs a pickaxe, at the mining block of each built cobblestone
	 * generator while the colony is {@code shortOfCobblestone}, for its stock targets or orders; one per generator, so
	 * one bot mines each. Otherwise the open ones come off the board; a bot on one ends it once the colony has enough.
	 */
	public void postGeneratorJobs(boolean shortOfCobblestone) {
		List<BlockPos> blocks = shortOfCobblestone ? this.generatorMiningBlocks() : List.of();
		this.board.removeOpenIf(job -> job.type() == JobType.MINE_GENERATOR && !blocks.contains(job.target()));
		blocks.forEach(pos -> this.board.post(JobType.MINE_GENERATOR, pos, Optional.of(Items.WOODEN_PICKAXE)));
	}

	/** The placed blueprint {@code plan} builds or levels the site of, if any. */
	public Optional<PlacedBlueprint> blueprintOf(BuildPlan plan) {
		Optional<UUID> id = this.builds.entrySet().stream().filter(e -> e.getValue().equals(plan)).map(Map.Entry::getKey).findFirst();
		return id.flatMap(planId -> this.projects.stream().filter(project -> project.plans().contains(planId)).findFirst())
			.flatMap(project -> this.blueprintOf(project.id()));
	}

	private Optional<PlacedBlueprint> blueprintOf(UUID project) {
		return this.blueprints.stream().filter(placed -> !placed.finished() && placed.project().equals(project)).findFirst();
	}

	/** The name to show for {@code project}: a placed blueprint's translated name, else {@link Project#displayName}. */
	public Component displayName(Project project) {
		return this.blueprintOf(project.id()).map(PlacedBlueprint::displayName).orElseGet(project::displayName);
	}

	private boolean hasProject(UUID id) {
		return this.projects.stream().anyMatch(project -> project.id().equals(id));
	}

	/**
	 * Before the finished plan with id {@code id} is dropped: a placed blueprint's levelling hands over to its structure,
	 * added to the same project, and its structure marks it built.
	 */
	private void finishBlueprintPlan(UUID id) {
		for (int i = 0; i < this.blueprints.size(); i++) {
			PlacedBlueprint placed = this.blueprints.get(i);
			Optional<Project> project = this.projects.stream().filter(p -> p.id().equals(placed.project()) && p.plans().contains(id)).findFirst();
			if (placed.finished() || project.isEmpty()) {
				continue;
			}
			if (placed.waiting().isPresent() && !this.givenUpBatches(id, this.builds.get(id)).isEmpty()) {
				// The levelling was given up: the structure does not start on an unlevelled site, and goes with the project.
				// Since #267 clearBlocked drops such placements and names them; this backstop for older saves says so too.
				AntCraft.LOGGER.warn("Colony at {} dropped {} at {}: its site could not be levelled", this.core.toShortString(),
					placed.blueprint(), placed.at().toShortString());
				this.droppedUnlevelled.add(placed);
				if (this.droppedUnlevelled.size() > MAX_DROPPED_UNLEVELLED) {
					this.droppedUnlevelled.removeFirst();
				}
				continue;
			}
			if (placed.waiting().isPresent()) {
				UUID structure = UUID.randomUUID();
				this.builds.put(structure, withoutOverlaps(placed.waiting().get()));
				this.projects.replaceAll(p -> p.id().equals(placed.project()) ? p.with(structure) : p);
				this.blueprints.set(i, placed.withProject(placed.project(), Optional.empty()));
				this.indexBatches();
				this.settleEmpty(structure);
			} else if (project.get().plans().equals(List.of(id))) {
				this.blueprints.set(i, placed.built());
			}
		}
	}

	public Project addBuild(String name, BuildPlan plan) {
		return this.addBuild(name, plan, false);
	}

	/** {@link #addBuild(String, BuildPlan)}, as a {@link Project#starter} project if {@code starter}. */
	public Project addBuild(String name, BuildPlan plan, boolean starter) {
		UUID id = UUID.randomUUID();
		Project project = new Project(id, name, 0, List.of(id), List.of(), starter);
		this.projects.add(project);
		this.builds.put(id, withoutOverlaps(plan));
		this.indexBatches();
		this.settleEmpty(id);
		this.lastSurvey = -1;
		return project;
	}

	/** Adds a project of no plans called {@code name} that wants {@code stock} held, at priority 0. */
	public Project addProject(String name, List<StockTarget> stock) {
		return this.addProject(name, stock, false);
	}

	/** {@link #addProject(String, List)}, as a {@link Project#starter} project if {@code starter}. */
	public Project addProject(String name, List<StockTarget> stock, boolean starter) {
		Project project = new Project(UUID.randomUUID(), name, 0, List.of(), stock, starter);
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
		this.projects.stream().filter(project -> project.id().equals(id)).findFirst().ifPresent(project -> project.plans().forEach(plan -> {
			this.stopFluidRestore(this.builds.get(plan));
			this.removePlan(plan);
		}));
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
	 * from {@code has} before those below it. An empty bucket is ordered too while an order waits for a full one, such as
	 * a lava bucket, and the colony {@code has} none to fill. A project with no plans left whose stock the colony has is done. Steps that are not {@code possible} for a builder order nothing. Returns what the plans
	 * still have to place, which is there for nothing else.
	 */
	public Map<Item, Integer> planProjects(Function<BlockPos, BlockState> world, Predicate<BuildPlan.Step> possible, Map<Item, Integer> has,
			Predicate<Item> hasTool) {
		return this.planProjects(world, possible, has, hasTool, item -> false);
	}

	/**
	 * {@link #planProjects(Function, Predicate, Map, Predicate)}, where a step any wooden trapdoor will do, such as a ladder
	 * shaft's hatch, that the colony has none for orders one it can make, a {@code makeable} one (see {@link BuildPlan#toMake}).
	 */
	public Map<Item, Integer> planProjects(Function<BlockPos, BlockState> world, Predicate<BuildPlan.Step> possible, Map<Item, Integer> has,
			Predicate<Item> hasTool, Predicate<Item> makeable) {
		return this.planProjects(world, possible, has, hasTool, makeable, item -> false);
	}

	/**
	 * {@link #planProjects(Function, Predicate, Map, Predicate, Predicate)}, where such a step the colony can make no wooden
	 * trapdoor for orders one it can make from the trees that grow nearby ({@code grows}), so gatherers fetch logs that are there.
	 */
	public Map<Item, Integer> planProjects(Function<BlockPos, BlockState> world, Predicate<BuildPlan.Step> possible, Map<Item, Integer> has,
			Predicate<Item> hasTool, Predicate<Item> makeable, Predicate<Item> grows) {
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
			// Likewise a ladder shaft's hatch takes whichever wooden trapdoor it has, else one of a wood it can make one of.
			Map<Item, Integer> stairs = StockPlanner.total(plans.stream().map(plan -> plan.standInMaterials(world)).toList());
			Map<Item, Integer> spares = new HashMap<>();
			Map<Item, Integer> needed = new LinkedHashMap<>(BuildPlan.shortfall(materials, holding, resting, stairs, left, spares, makeable, grows));
			// A full bucket cannot be made, only filled at a source, so builders get an empty one to fill.
			if (needed.keySet().removeIf(Colony::isFullBucket) && left.getOrDefault(Items.BUCKET, 0) <= 0) {
				needed.put(Items.BUCKET, 1);
			}
			plans.stream().flatMap(plan -> plan.needs(world, possible).tool().stream()).filter(hasTool.negate())
				.max(Comparator.comparingInt(pickaxes::rank)).ifPresent(tool -> needed.put(tool, 1));
			for (StockTarget target : project.stock()) {
				StockPlanner.Target<Item> planned = target.toPlannerTarget();
				int have = StockPlanner.have(planned, left);
				// A tag that matches nothing, or is not bound, has nothing to make: it orders nothing.
				if (have < target.count() && !planned.make().isEmpty()) {
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
		// So do orders for a full bucket, such as lava: without an empty bucket to fill they would wait for ever.
		if (this.orders.keySet().stream().anyMatch(Colony::isFullBucket) && has.getOrDefault(Items.BUCKET, 0) <= 0) {
			total.merge(Items.BUCKET, 1, Math::max);
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

	/** Whether {@code item} is a full bucket, which cannot be crafted, only filled at a source. */
	private static boolean isFullBucket(Item item) {
		return item instanceof BucketItem && item != Items.BUCKET;
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
		feeds.accept(this.playerOrders(), ORDER_PRIORITY);
		crafts.forEach(item -> this.board.postFor(JobType.CRAFT, this.core, item, Optional.empty(), priorities.getOrDefault(item, 0)));
	}

	/** The part of the orders a player placed: what is ordered beyond what the colony ordered itself. */
	private Map<Item, Integer> playerOrders() {
		Map<Item, Integer> placed = new HashMap<>();
		this.systemOrders.values().forEach(part -> part.forEach((item, count) -> placed.merge(item, count, Integer::sum)));
		Map<Item, Integer> players = new HashMap<>();
		this.orders.forEach((item, count) -> setCount(players, item, count - placed.getOrDefault(item, 0)));
		return players;
	}

	/**
	 * Gives the tree and log jobs the priority of the highest project or player's order waiting for logs, the branch jobs that of
	 * the highest one waiting for what mining yields, and each vein job that of the highest one waiting for its ore (see
	 * {@link #priorityFor}).
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
		// At most that of the branches, so a branch for ores a higher project waits for comes first; at the same priority
		// the generator scores better (see JobScore).
		this.board.prioritize(job -> job.type() == JobType.MINE_GENERATOR, this.priorityFor(Items.COBBLESTONE::equals, stock, book, gatherable));
	}

	/**
	 * The priority of the highest project waiting for an item that is {@code wanted}, or for something {@link TaskPlanner}
	 * plans to make or gather it from with {@code stock} and {@code book}, or {@link #ORDER_PRIORITY} if a player's order
	 * waits for it and no higher project does; 0 if none is.
	 */
	public int priorityFor(Predicate<Item> wanted, Map<Item, Integer> stock, TaskPlanner.RecipeBook<Item> book, Predicate<Item> gatherable) {
		int priority = waitsFor(this.playerOrders(), wanted, stock, book, gatherable) ? ORDER_PRIORITY : 0;
		for (Project project : this.projects()) {
			if (waitsFor(this.waiting.getOrDefault(project.id(), Map.of()), wanted, stock, book, gatherable)) {
				return Math.max(priority, project.priority());
			}
		}
		return priority;
	}

	/** Whether any of {@code waiting} is {@code wanted} or planned from something that is (see {@link #priorityFor}). */
	private static boolean waitsFor(Map<Item, Integer> waiting, Predicate<Item> wanted, Map<Item, Integer> stock,
			TaskPlanner.RecipeBook<Item> book, Predicate<Item> gatherable) {
		for (Map.Entry<Item, Integer> waits : waiting.entrySet()) {
			Map<Item, Integer> available = new HashMap<>(stock);
			available.remove(waits.getKey());
			TaskPlanner.Plan<Item> plan = TaskPlanner.plan(waits.getKey(), waits.getValue(), available, book, gatherable);
			if (wanted.test(waits.getKey()) || plan.toGather().keySet().stream().anyMatch(wanted)
					|| plan.crafts().stream().anyMatch(craft -> wanted.test(craft.recipe().output()))) {
				return true;
			}
		}
		return false;
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
		this.stopFluidRestore(plan);
		this.builds.entrySet().stream().filter(e -> e.getValue().equals(plan)).map(Map.Entry::getKey).findFirst().ifPresent(this::removePlan);
	}

	/** Drops the finished plan with id {@code id}, keeping its ground, if any, for good (see {@link #keepGround}). */
	private void finishPlan(UUID id) {
		this.finishBlueprintPlan(id);
		this.builds.get(id).ground().ifPresent(ground -> {
			this.keptGround.removeIf(kept -> ground.isInside(kept.minX(), kept.minY(), kept.minZ())
				&& ground.isInside(kept.maxX(), kept.maxY(), kept.maxZ()));
			this.keptGround.add(ground);
			// ponytail: a cap, oldest out; merge touching boxes if colonies level more than this.
			if (this.keptGround.size() > MAX_KEPT_GROUND) {
				this.keptGround.removeFirst();
			}
		});
		this.removePlan(id);
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
		// A placed blueprint cancelled before it was built is forgotten.
		this.blueprints.removeIf(placed -> !placed.finished() && !this.hasProject(placed.project()));
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
			this.finishPlan(batch.plan());
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

	/**
	 * Whether a bot could take an open build or dig job now: the item it declares (see {@link #declareBuildNeeds}) is one
	 * the colony {@code has}, or, declaring none, its batch has a step left with {@code world} as it is that needs no
	 * particular item or places one the colony has (see {@link BuildPlan#hasStepFor}).
	 */
	public boolean hasBuildToDo(Function<BlockPos, BlockState> world, Map<Item, Integer> has) {
		Predicate<Item> held = item -> has.getOrDefault(item, 0) > 0;
		return this.board.jobs().stream().anyMatch(job -> isBuildJob(job) && job.isOpen() && !job.givenUp()
			&& job.item().map(held::test).orElseGet(() -> this.build(job.id()).filter(batch -> batch.hasStepFor(world, held)).isPresent()));
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

	/** Where builders found no way to a step; see {@link #reportBlocked(UUID, Map)}. */
	public List<BlockPos> unreachableSteps() {
		return this.blockedSteps.stream().filter(Blocked::unreachable).map(Blocked::pos).toList();
	}

	/** Why builders could not do the step at {@code pos}: the reason of the first blocked step there, {@link BlockReason#OTHER} if none. */
	public BlockReason blockReason(BlockPos pos) {
		return this.blockedSteps.stream().filter(b -> b.pos().equals(pos)).map(Blocked::reason).findFirst().orElse(BlockReason.OTHER);
	}

	/** {@link #reportBlocked(UUID, Collection, Collection)} with every step reachable. */
	public void reportBlocked(UUID job, Collection<BlockPos> steps) {
		this.reportBlocked(job, steps, List.of());
	}

	/** {@link #reportBlocked(UUID, Map)} with the steps in {@code unreachable} found unreachable and the rest blocked otherwise. */
	public void reportBlocked(UUID job, Collection<BlockPos> steps, Collection<BlockPos> unreachable) {
		Map<BlockPos, BlockReason> reasons = new LinkedHashMap<>();
		steps.forEach(pos -> reasons.put(pos, unreachable.contains(pos) ? BlockReason.UNREACHABLE : BlockReason.OTHER));
		this.reportBlocked(job, reasons);
	}

	/**
	 * Records the steps of the batch of build job {@code job} that a builder cannot do; the batch,
	 * and the batches that need it, wait without jobs until a recheck frees it, while the plan's
	 * other batches go on. The mineshaft does not wait: it drops the
	 * levels the steps keep builders from (see {@link Mineshaft#reachable}) and goes on without them.
	 * Each step comes with why it is blocked.
	 */
	public void reportBlocked(UUID job, Map<BlockPos, BlockReason> blocked) {
		BatchRef batch = this.batchJobs.get(job);
		Optional<Mineshaft> shaft = this.mineshaft
			.filter(mine -> batch != null && this.builds.get(batch.plan()).equals(mine.plan()))
			.map(mine -> mine.reachable(blocked.keySet()))
			.filter(reachable -> !reachable.equals(this.mineshaft.get()));
		if (shaft.isPresent()) {
			this.setMineshaft(shaft.get());
			return;
		}
		blocked.forEach((pos, reason) -> {
			if (this.blockedSteps.stream().noneMatch(old -> old.plan().equals(job) && old.pos().equals(pos))) {
				this.blockedSteps.add(new Blocked(job, pos.immutable(), reason));
			}
		});
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
	 * What {@link #clearBlocked} gave up on.
	 *
	 * @param steps how many blocked steps there were
	 * @param unlevelled the placed blueprints whose site levelling was given up, dropped rather than built on an unlevelled site
	 */
	public record Cleared(int steps, List<PlacedBlueprint> unlevelled) {
		public Cleared {
			unlevelled = List.copyOf(unlevelled);
		}
	}

	/**
	 * Gives up on every blocked step, the batches they are in and the batches that need those, for good. The rest of
	 * their plans goes on; a plan with nothing else left is dropped. A placed blueprint whose site levelling is given up
	 * is dropped with its project, so it is not built on an unlevelled site; the player may place it again.
	 */
	public Cleared clearBlocked() {
		int count = this.blockedSteps.size();
		Set<UUID> plans = new HashSet<>();
		for (Blocked blocked : this.blockedSteps) {
			if (this.batchJobs.containsKey(blocked.plan())) {
				this.batchStates.put(blocked.plan(), BatchState.GIVEN_UP);
				plans.add(this.planOf(blocked.plan()));
			}
		}
		this.blockedSteps.clear();
		plans.forEach(plan -> this.stopFluidRestore(this.builds.get(plan)));
		// Their steps are no longer kept open.
		plans.forEach(this.stepSets::remove);
		List<PlacedBlueprint> unlevelled = this.blueprints.stream().filter(placed -> !placed.finished() && placed.waiting().isPresent()
			&& this.projects.stream().anyMatch(project -> project.id().equals(placed.project()) && project.plans().stream().anyMatch(plans::contains)))
			.toList();
		unlevelled.forEach(placed -> this.cancelProject(placed.project()));
		plans.stream().filter(this.builds::containsKey).filter(id -> this.isFinished(id, this.builds.get(id))).toList().forEach(this::finishPlan);
		this.postBuildJobs();
		this.lastSurvey = -1;
		return new Cleared(count, unlevelled);
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
		// A generator's mining block too, mined or formed again, which only its generator job breaks, and a built farm's plots and water.
		if (this.shaftBlocks.contains(pos) || this.isGeneratorMiningBlock(pos) || this.isFarmBlock(pos)) {
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

	/** Where paths must not place scaffold: the mineshaft's stairs, halls and branches, every build plan's step, which the plan wants as it says, the ore veins posted and the generators' mining blocks. */
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
		// The generators' mining blocks, where cobblestone forms again once mined.
		open.add(Set.copyOf(this.generatorMiningBlocks()));
		BoundingBox bounds = this.bounds;
		return pos -> Territory.inBounds(bounds, pos) && open.stream().anyMatch(set -> set.contains(pos));
	}

	/**
	 * Where paths must not break a block: the ground each terraform with work left levels, as it wants it (see
	 * {@link BuildPlan#ground}), and the ground of the last {@link #MAX_KEPT_GROUND} terraforms finished, but for where a
	 * plan with work left has steps (inside the box round them), as that plan's own steps dig and fill there.
	 */
	public Predicate<BlockPos> keepGround() {
		List<BoundingBox> ground = new ArrayList<>();
		List<BoundingBox> planned = new ArrayList<>();
		this.builds.entrySet().stream().filter(e -> this.hasWorkLeft(e.getKey(), e.getValue())).map(Map.Entry::getValue).forEach(plan -> {
			plan.ground().ifPresent(ground::add);
			BoundingBox.encapsulatingPositions(plan.steps().stream().map(BuildPlan.Step::pos).toList()).ifPresent(planned::add);
		});
		List<BoundingBox> kept = List.copyOf(this.keptGround);
		BoundingBox bounds = this.bounds;
		return pos -> Territory.inBounds(bounds, pos) && (ground.stream().anyMatch(box -> box.isInside(pos))
			|| kept.stream().anyMatch(box -> box.isInside(pos)) && planned.stream().noneMatch(box -> box.isInside(pos)));
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

	/** Where bots walk in the mine: its stairs, halls, every round of branches and the vein pockets dug off them; none before it has a site. */
	public Set<BlockPos> mineCells() {
		return Sets.union(this.shaftOpen, this.veinCells);
	}

	/**
	 * Remembers a lava source a bot saw, so lava fetching finds it, also where it does not look itself, such as off the
	 * mine's halls. At most {@link #MAX_SEEN_LAVA} are remembered: past that the one seen longest ago is forgotten.
	 */
	public void sawLava(BlockPos pos) {
		BlockPos seen = pos.immutable();
		// Seen again, it is the newest.
		this.seenLava.remove(seen);
		this.seenLava.add(seen);
		while (this.seenLava.size() > MAX_SEEN_LAVA) {
			this.seenLava.removeFirst();
		}
	}

	/** Forgets a lava source a bot saw, once it is gone. */
	public void forgetLava(BlockPos pos) {
		this.seenLava.remove(pos);
	}

	/** The lava sources bots saw and that were not forgotten since (see {@link #sawLava}). */
	public Set<BlockPos> seenLava() {
		return Collections.unmodifiableSet(this.seenLava);
	}

	/** Adds the cells of a vein job's area to the mine, once a miner is done with it; any still solid hold no bot or mob. */
	public void dugVein(Collection<BlockPos> area) {
		this.veinCells.addAll(area);
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
		this.veinCells.clear();
		this.board.removeOpenIf(job -> job.type() == JobType.MINE_BRANCH);
		return this.addBuild(MINESHAFT, shaft.plan(), true);
	}

	/**
	 * Where farmers plant: the plots of every built farm (a placed blueprint with role {@link Blueprint.Role#FARM}),
	 * oldest first; with none built, the field the colony laid out itself, if it needed one.
	 */
	public Optional<Field> field() {
		List<PlacedBlueprint> farms = this.finishedBlueprints(Blueprint.Role.FARM);
		if (farms.isEmpty()) {
			return this.field;
		}
		List<BlockPos> plots = farms.stream().flatMap(placed -> placed.workSpots().stream()).toList();
		return BoundingBox.encapsulatingBoxes(farms.stream().map(PlacedBlueprint::area).toList()).map(area -> Field.of(area, plots));
	}

	/**
	 * Where the farmer keeps water for the field: the middle of the field the colony laid out itself, if it has one and
	 * no farm is built. A built farm's water is poured back by builders (see {@link #restoreFluids}), not by the farmer.
	 */
	public List<BlockPos> fieldWater() {
		return this.finishedBlueprints(Blueprint.Role.FARM).isEmpty() ? this.field.flatMap(Field::water).stream().toList() : List.of();
	}

	public void setField(Field field) {
		this.field = Optional.of(field);
		this.board.removeOpenIf(job -> job.type() == JobType.PLANT_CROP);
	}

	/**
	 * Where haulers keep the colony's storage chests: the chests of every built warehouse (a placed blueprint with role
	 * {@link Blueprint.Role#STORAGE}), oldest first, then those of the area the colony picked itself before warehouses, if
	 * it has one. The picked area stays storage once a warehouse stands, so what its chests hold is still counted and fetched;
	 * its chests come last, so haulers fill the warehouses first.
	 */
	public Optional<StorageArea> storage() {
		List<PlacedBlueprint> warehouses = this.finishedBlueprints(Blueprint.Role.STORAGE);
		if (warehouses.isEmpty()) {
			return this.storage;
		}
		List<BlockPos> chests = new ArrayList<>(warehouses.stream().flatMap(placed -> placed.chests().stream()).toList());
		List<BoundingBox> areas = new ArrayList<>(warehouses.stream().map(PlacedBlueprint::area).toList());
		this.storage.ifPresent(picked -> {
			chests.addAll(picked.chestSpots());
			areas.add(picked.area());
		});
		return BoundingBox.encapsulatingBoxes(areas).map(area -> StorageArea.of(area, chests));
	}

	public void setStorage(StorageArea storage) {
		this.storage = Optional.of(storage);
	}

	public Optional<Quarry> quarry() {
		return this.quarry;
	}

	/**
	 * Opens the quarry while the colony is {@code shortOfStone} and the mineshaft has no branch job yet, so it gets
	 * cobblestone for stone tools and stairs early: picks a site the first time (see {@link Quarry#site}) and adds the
	 * {@link Project#QUARRY} project to dig what is left of the pit, at the mineshaft's priority, which keeps the shaft
	 * first. Otherwise takes the project down; the site stays, to go on with later. Every block of the pit must be
	 * {@code workable}.
	 */
	public void updateQuarry(boolean shortOfStone, Function<BlockPos, BlockState> world, Predicate<BlockPos> workable) {
		Optional<Project> open = this.projects.stream().filter(project -> project.name().equals(Project.QUARRY)).findFirst();
		if (!shortOfStone || this.board.jobs().stream().anyMatch(job -> job.type() == JobType.MINE_BRANCH)) {
			open.ifPresent(project -> this.cancelProject(project.id()));
			return;
		}
		if (open.isPresent()) {
			return;
		}
		if (this.quarry.isEmpty()) {
			this.quarry = this.findSite("quarry", () -> Quarry.site(this.core, this.radius, this.occupied(), world, workable));
		}
		int priority = this.projects.stream().filter(project -> project.name().equals(MINESHAFT)).mapToInt(Project::priority).findFirst().orElse(0);
		this.quarry.flatMap(quarry -> quarry.plan(world)).ifPresent(plan -> this.setPriority(this.addBuild(Project.QUARRY, plan, true).id(), priority));
	}

	/** How many searches for a site are skipped after one found no room: a search can scan the whole territory. */
	private static final int SITE_SKIPS = 10;

	/**
	 * Runs {@code search} for a site for {@code what}, unless one found nothing within the last {@link #SITE_SKIPS} calls;
	 * a skipped search finds nothing. Room seldom opens up, and a search that finds none scans the whole territory.
	 */
	<T> Optional<T> findSite(String what, Supplier<Optional<T>> search) {
		int skips = this.siteSkips.getOrDefault(what, 0);
		if (skips > 0) {
			this.siteSkips.put(what, skips - 1);
			return Optional.empty();
		}
		Optional<T> site = search.get();
		if (site.isEmpty()) {
			this.siteSkips.put(what, SITE_SKIPS);
		} else {
			this.siteSkips.remove(what);
		}
		return site;
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
