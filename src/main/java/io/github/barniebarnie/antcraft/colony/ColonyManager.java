package io.github.barniebarnie.antcraft.colony;

import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.goal.BuilderGoal;
import io.github.barniebarnie.antcraft.entity.goal.CrafterGoal;
import io.github.barniebarnie.antcraft.entity.goal.HaulerGoal;
import io.github.barniebarnie.antcraft.pathing.LevelWorldView;
import io.github.barniebarnie.antcraft.registry.ModItems;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Farming;
import io.github.barniebarnie.antcraft.work.Field;
import io.github.barniebarnie.antcraft.work.GameRecipeBook;
import io.github.barniebarnie.antcraft.work.Gatherables;
import io.github.barniebarnie.antcraft.work.LevelTreeView;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.Mining;
import io.github.barniebarnie.antcraft.work.OreRevisit;
import io.github.barniebarnie.antcraft.work.PickaxeTier;
import io.github.barniebarnie.antcraft.work.Replanting;
import io.github.barniebarnie.antcraft.work.Storage;
import io.github.barniebarnie.antcraft.work.StorageArea;
import io.github.barniebarnie.antcraft.work.ToolKind;
import io.github.barniebarnie.antcraft.work.TreeScanner;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/** All colonies in one dimension, saved with the world. */
public class ColonyManager extends SavedData {
	/** Bot Cores activated this close to a colony core join that colony. */
	public static final int JOIN_DISTANCE = 32;
	private static final int SURVEY_INTERVAL = 100;
	/** How far above and below the core crops are looked for. */
	private static final int CROP_SCAN_HEIGHT = 4;
	/** What a field yields: the colony lays one out only while it wants some of it. */
	private static final Set<Item> FIELD_YIELDS = Set.of(Items.WHEAT, Items.WHEAT_SEEDS);
	/** A core with this few free slots left gets a storage area levelled when there is no flat spot for one. */
	private static final int NEARLY_FULL_FREE_SLOTS = 4;
	/** Jobs whose bot has not reported for this long are freed for others. */
	private static final long CLAIM_TIMEOUT = 600;

	private static final Codec<ColonyManager> CODEC = Colony.CODEC.listOf().xmap(ColonyManager::new, m -> List.copyOf(m.colonies.values()));

	// The fix type is null: Fabric API skips data fixing for mod saved data.
	public static final SavedDataType<ColonyManager> TYPE = new SavedDataType<>(AntCraft.id("colonies"), ColonyManager::new, CODEC, null);

	private final Map<UUID, Colony> colonies = new LinkedHashMap<>();

	public ColonyManager() {
	}

	private ColonyManager(List<Colony> colonies) {
		colonies.forEach(colony -> this.colonies.put(colony.id(), colony));
	}

	public static ColonyManager get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TYPE);
	}

	// Bots update job heartbeats constantly, so always save rather than tracking every change.
	@Override
	public boolean isDirty() {
		return true;
	}

	public Colony create(BlockPos core) {
		Optional<Colony> existing = this.byCore(core);
		if (existing.isPresent()) {
			return existing.get();
		}
		Colony colony = new Colony(UUID.randomUUID(), core.immutable(), List.of(), new JobBoard(), Colony.DEFAULT_RADIUS, Map.of(), Optional.empty(), DefaultStock.TARGETS,
			Map.of(), List.of(), Optional.empty(), Optional.empty(), Map.of(), List.of(), Map.of());
		this.colonies.put(colony.id(), colony);
		return colony;
	}

	public void remove(BlockPos core) {
		this.colonies.values().removeIf(colony -> colony.core().equals(core));
	}

	public Optional<Colony> byId(UUID id) {
		return Optional.ofNullable(this.colonies.get(id));
	}

	public Optional<Colony> byCore(BlockPos core) {
		return this.colonies.values().stream().filter(colony -> colony.core().equals(core)).findFirst();
	}

	public Optional<Colony> nearest(BlockPos pos, int maxDistance) {
		return this.colonies.values().stream()
			.filter(colony -> colony.core().closerThan(pos, maxDistance))
			.min(Comparator.comparingDouble(colony -> colony.core().distSqr(pos)));
	}

	/** Whether a colony build plan placed the block at {@code pos} and it is still there, so bots must not break it. */
	public static boolean isProtected(Level level, BlockPos pos) {
		if (!(level instanceof ServerLevel server)) {
			return false;
		}
		for (Colony colony : get(server).colonies.values()) {
			if (colony.isProtected(level::getBlockState, pos)) {
				return true;
			}
		}
		return false;
	}

	/** Where no path may place scaffold, for every colony (see {@link Colony#keepOpen}). */
	public static Set<BlockPos> keepOpen(Level level) {
		if (!(level instanceof ServerLevel server)) {
			return Set.of();
		}
		Set<BlockPos> open = new HashSet<>();
		get(server).colonies.values().forEach(colony -> open.addAll(colony.keepOpen()));
		return open;
	}

	public List<Colony> all() {
		return new ArrayList<>(this.colonies.values());
	}

	public void join(AntBot bot, Colony colony) {
		this.leave(bot);
		colony.members().add(bot.getUUID());
		bot.setColonyId(colony.id());
		bot.setHome(colony.core());
	}

	public void leave(AntBot bot) {
		if (bot.getColonyId() == null) {
			return;
		}
		this.byId(bot.getColonyId()).ifPresent(colony -> {
			colony.members().remove(bot.getUUID());
			colony.board().release(bot.getUUID());
		});
		bot.setColonyId(null);
	}

	/**
	 * Posts a harvest job for each ripe crop in the territory. A colony with no crops lays out a field once it wants
	 * what a field yields (see {@link #FIELD_YIELDS}), for stock targets or orders, and posts a plant job for each
	 * open spot of its field.
	 */
	private void surveyCrops(ServerLevel level, Colony colony) {
		colony.board().removeOpenIf(job -> job.type() == JobType.HARVEST_CROP && !Farming.isRipe(level.getBlockState(job.target())));
		BlockPos core = colony.core();
		int r = colony.radius();
		int crops = 0;
		for (BlockPos pos : BlockPos.betweenClosed(core.offset(-r, -CROP_SCAN_HEIGHT, -r), core.offset(r, CROP_SCAN_HEIGHT, r))) {
			BlockState state = level.getBlockState(pos);
			if (state.getBlock() instanceof CropBlock) {
				crops++;
			}
			if (Farming.isRipe(state)) {
				colony.board().post(JobType.HARVEST_CROP, pos);
			}
		}
		if (colony.field().isEmpty() && crops == 0 && FIELD_YIELDS.stream().anyMatch(Storage.wanted(level, colony))) {
			Field.choose(core, r, occupied(colony), pos -> Farming.isSoil(level.getBlockState(pos)), pos -> Farming.isOpen(level.getBlockState(pos)))
				.ifPresent(colony::setField);
		}
		colony.field().ifPresent(field -> {
			List<BlockPos> toPlant = field.toPlant(pos -> level.getBlockState(pos).getBlock() instanceof CropBlock);
			colony.board().removeOpenIf(job -> job.type() == JobType.PLANT_CROP && !toPlant.contains(job.target()));
			toPlant.forEach(pos -> colony.board().post(JobType.PLANT_CROP, pos, Farming.plantTool(level.getBlockState(pos.below()))));
		});
	}

	/**
	 * Mines on demand: only while the colony wants something mining yields (see {@link Mineshaft#yieldsWanted}),
	 * for stock targets or orders, does it pick a mineshaft site and post branch jobs, and order a pickaxe
	 * when it has none; otherwise the open ones come off the board. It posts a job for each segment with blocks
	 * left to dig (see {@link Mineshaft.Branch#segments}) of the branches at one mining level: the deepest dug one
	 * whose ores the colony wants. Each job needs the pickaxe its level does (see {@link PickaxeTier#forBranch}).
	 * A level whose branches are all dug gets the next round of them. While the core has torches,
	 * every dug segment with a dark torch spot gets a job too, so a miner goes back to light it.
	 * A branch with a segment whose job was given up, its cells left out of reach, counts as dug: its job stays
	 * on the board so it is not posted anew, until {@link Colony#recheckGivenUp} puts it back up.
	 * Once the colony's best pickaxe, among what it {@code holds} and its bots carry, beats the ones it
	 * had, dug branches whose walls hold wanted ores the new one harvests get a job again (see {@link OreRevisit}).
	 */
	private void surveyMine(ServerLevel level, Colony colony, Map<Item, Integer> holds) {
		// Plans the open orders, so at most once per survey, and only when needed.
		Supplier<Predicate<Item>> wanted = Suppliers.memoize(() -> Storage.wanted(level, colony));
		Set<BlockPos> posted = new HashSet<>();
		// With nothing mining yields wanted, no site is picked and no branch posted, so bots do other work.
		if (!Mineshaft.yieldsWanted(wanted.get())) {
			colony.board().removeOpenIf(job -> job.type() == JobType.MINE_BRANCH && !job.givenUp());
			return;
		}
		if (bestPickaxe(level, colony, holds) == null) {
			// No bot can take a branch job without one.
			ToolKind.PICKAXE.order(level, colony, null, ToolKind.PICKAXE.spare(level, colony));
		}
		if (colony.mineshaft().isEmpty()) {
			// The colony has no shaft yet, so this avoids the field and storage only.
			LevelWorldView world = new LevelWorldView(level, new SimpleContainer(0), ItemStack.EMPTY, Set.of());
			colony.setMineshaft(Mineshaft.nextTo(colony.core(), AntCraftConfig.mineshaftLevels(), occupied(colony), world));
		}
		// A job per segment of a branch, so several bots dig a hall one segment after the other. The pickaxe the
		// job needs, so a bot carrying one takes it (see JobScore).
		Consumer<Mineshaft.Branch> post = segment -> {
			posted.add(segment.start());
			colony.board().post(JobType.MINE_BRANCH, segment.start(), segment.cells(), Optional.empty(), level.getGameTime(),
				Optional.of(PickaxeTier.forBranch(segment.start().getY(), wanted.get())));
		};
		Container core = Storage.containerAt(level, colony.core());
		// In the core, or carried by a bot that took them from there.
		boolean torches = core != null && core.countItem(Items.TORCH) > 0 || colony.members().stream()
			.anyMatch(id -> level.getEntity(id) instanceof AntBot bot && bot.getInventory().countItem(Items.TORCH) > 0);
		Set<BlockPos> givenUp = new HashSet<>();
		colony.board().jobs().stream().filter(job -> job.type() == JobType.MINE_BRANCH && job.givenUp()).forEach(job -> givenUp.add(job.target()));
		colony.mineshaft().ifPresent(shaft -> {
			Map<Integer, List<Mineshaft.Branch>> work = new HashMap<>();
			// The builder has dug the way there, so miners can walk to the hall (of a spiral) or the branches (of an old shaft).
			List<Integer> open = shaft.levels().stream().filter(y -> {
				BlockPos reached = shaft.hallWork(y).isPresent() ? shaft.landing(y) : shaft.hall(y).getLast();
				return level.getBlockState(reached).getCollisionShape(level, reached).isEmpty();
			}).toList();
			for (int y : open) {
				// A spiral's hall first, then the branches off it.
				List<List<Mineshaft.Branch>> stages = new ArrayList<>();
				shaft.hallWork(y).ifPresent(hall -> stages.add(List.of(hall)));
				IntStream.range(0, shaft.rounds()).forEach(round -> stages.add(shaft.branches(y, round)));
				// The first stage with blocks left to dig; the ones before it are dug, so may need lighting.
				for (List<Mineshaft.Branch> branches : stages) {
					if (torches) {
						branches.stream().flatMap(branch -> branch.segments().stream()).filter(segment -> segment.darkTorch(level).isPresent()).forEach(post);
					}
					List<Mineshaft.Branch> left = branches.stream()
						.filter(branch -> !givenUp(branch, givenUp) && branch.cells().stream().anyMatch(pos -> Mining.isMinable(level, pos))).toList();
					if (!left.isEmpty()) {
						work.put(y, left);
						break;
					}
				}
			}
			Mineshaft.branchesToPost(work, wanted.get()).stream().flatMap(branch -> branch.segments().stream())
				.filter(segment -> segment.cells().stream().anyMatch(pos -> Mining.isMinable(level, pos))).forEach(post);
			colony.oreRevisit().update(bestPickaxe(level, colony, holds), start -> colony.board().has(JobType.MINE_BRANCH, start),
					() -> dugBranches(level, shaft, open, givenUp), level::getBlockState, pos -> Mining.isMinable(level, pos), wanted)
				.forEach(start -> shaft.branch(start).ifPresent(branch -> branch.segments().forEach(post)));
		});
		colony.board().removeOpenIf(job -> job.type() == JobType.MINE_BRANCH && !job.givenUp() && !posted.contains(job.target()));
	}

	/** The branches of the {@code levels} with nothing left to dig, the {@code givenUp} ones aside. */
	private static List<Mineshaft.Branch> dugBranches(ServerLevel level, Mineshaft shaft, List<Integer> levels, Set<BlockPos> givenUp) {
		List<Mineshaft.Branch> dug = new ArrayList<>();
		for (int y : levels) {
			for (int round = 0; round < shaft.rounds(); round++) {
				shaft.branches(y, round).stream()
					.filter(branch -> !givenUp(branch, givenUp) && branch.cells().stream().noneMatch(pos -> Mining.isMinable(level, pos)))
					.forEach(dug::add);
			}
		}
		return dug;
	}

	/** Whether a segment of {@code branch} was {@code givenUp}: the rest of it cannot be dug either, so it counts as dug. */
	private static boolean givenUp(Mineshaft.Branch branch, Set<BlockPos> givenUp) {
		return branch.segments().stream().anyMatch(segment -> givenUp.contains(segment.start()));
	}

	/** The best pickaxe in what the colony {@code holds} or in a member's hand, or null if it has none. */
	private static @Nullable Item bestPickaxe(ServerLevel level, Colony colony, Map<Item, Integer> holds) {
		ToolKind kind = ToolKind.PICKAXE;
		Item best = kind.betterIn(holds, null).orElse(null);
		for (UUID id : colony.members()) {
			if (level.getEntity(id) instanceof AntBot bot && kind.rank(kind.bestCarried(bot)) > kind.rank(best)) {
				best = kind.bestCarried(bot);
			}
		}
		return best;
	}

	/** Areas the colony already uses, which a new area must keep clear of. Add new kinds of areas here. */
	private static List<BoundingBox> occupied(Colony colony) {
		List<BoundingBox> areas = new ArrayList<>();
		colony.mineshaft().ifPresent(shaft -> areas.add(shaft.area()));
		colony.field().ifPresent(field -> areas.add(field.area()));
		colony.storage().ifPresent(storage -> areas.add(storage.area()));
		return areas;
	}

	/**
	 * Picks the storage area once the core is nearly full, levelling one first when there is no flat spot, and orders
	 * the chests it needs beyond the chests in {@code spare}; orders it no longer needs are withdrawn.
	 */
	private void surveyStorage(ServerLevel level, Colony colony, Map<Item, Integer> spare) {
		if (colony.storage().isPresent()) {
			int chests = HaulerGoal.chestsToOrder(level, colony, Math.max(0, spare.getOrDefault(Items.CHEST, 0)));
			colony.orderFor(Colony.OrderPurpose.STORAGE, chests > 0 ? Map.of(Items.CHEST, chests) : Map.of());
			return;
		}
		Container core = Storage.containerAt(level, colony.core());
		int free = 0;
		for (int i = 0; core != null && i < core.getContainerSize(); i++) {
			free += core.getItem(i).isEmpty() ? 1 : 0;
		}
		if (core == null || free > NEARLY_FULL_FREE_SLOTS) {
			return;
		}
		Optional<StorageArea> site = StorageArea.choose(colony.core(), colony.radius(), occupied(colony),
			pos -> level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP),
			pos -> level.getBlockState(pos).canBeReplaced() && level.getBlockState(pos).getFluidState().isEmpty());
		if (site.isPresent()) {
			colony.setStorage(site.get());
		} else if (!colony.hasTerraform()) {
			// No flat spot: the colony levels one, and picks it once it is flat.
			StorageArea.levelling(colony.core(), colony.radius(), occupied(colony), level::getBlockState, pos -> isWorkable(level, colony, pos))
				.ifPresent(colony::addBuild);
		}
	}

	/** Whether bots can dig or fill the block at {@code pos} for a site the colony levels: no fluid, unbreakable block, container or colony block. */
	private static boolean isWorkable(ServerLevel level, Colony colony, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.getFluidState().isEmpty() && state.getDestroySpeed(level, pos) >= 0 && !state.hasBlockEntity()
			&& !colony.isProtected(level::getBlockState, pos);
	}

	/**
	 * Posts a job per batch of each build plan, declaring what each still needs (see {@link Colony#declareBuildNeeds}),
	 * and orders what the plans still have to place beyond what the colony holds, its bots' inventories included.
	 * Orders plans no longer need are withdrawn.
	 */
	private void surveyBuilds(ServerLevel level, Colony colony, Map<Item, Integer> have) {
		colony.postBuildJobs();
		Item pickaxe = bestPickaxe(level, colony, have);
		// A pickaxe on order that the colony holds the materials for counts too, so no bot digs the batch by hand
		// while one is being made; a colony that has to gather them first digs by hand meanwhile.
		ToolKind kind = ToolKind.PICKAXE;
		Stream<Item> made = colony.orders().keySet().stream().filter(kind.tiers()::contains)
			.filter(order -> ToolUpgrade.makeableNow(order, have, GameRecipeBook.of(level.getServer()), item -> false));
		Item coming = Stream.concat(Stream.ofNullable(pickaxe), made).max(Comparator.comparingInt(kind::rank)).orElse(null);
		colony.declareBuildNeeds(level::getBlockState, step -> BuilderGoal.isPossible(level, step), tool -> kind.rank(coming) >= kind.rank(tool));
		// Steps a builder cannot do, such as fire that would go out, are reported blocked rather than ordered for. A pickaxe
		// is ordered until one is made, not just while none is coming, or the order would withdraw itself.
		Map<Item, Integer> materials = colony.planProjects(level::getBlockState, step -> BuilderGoal.isPossible(level, step), have,
			tool -> kind.rank(pickaxe) >= kind.rank(tool));
		// What builders will place is not there for anything else.
		materials.forEach((item, count) -> have.computeIfPresent(item, (key, held) -> held - count));
	}

	/** Orders whatever the colony is short of. */
	private void surveyStock(ServerLevel level, Colony colony) {
		Container core = Storage.containerAt(level, colony.core());
		if (core == null || colony.stockTargets().isEmpty()) {
			return;
		}
		List<StockPlanner.Target<Item>> targets = colony.stockTargets().stream().map(StockTarget::toPlannerTarget).toList();
		StockPlanner.ordersToAdd(targets, Storage.held(level, colony), colony.orders(), GameRecipeBook.of(level.getServer()), Gatherables::isGatherable)
			.forEach(colony::addOrder);
	}

	/**
	 * Posts a craft job at the core for each item the colony crafts there (see {@link CrafterGoal#coreCrafts}),
	 * a job to activate the Bot Cores stored in the core, and a haul job for each mineshaft landing chest that
	 * holds something to bring up. Such jobs no longer needed are taken down unless a bot works on them.
	 */
	private void surveyCoreJobs(ServerLevel level, Colony colony) {
		Container core = Storage.containerAt(level, colony.core());
		if (core == null) {
			return;
		}
		BlockPos at = colony.core();
		JobBoard board = colony.board();
		colony.postCraftJobs(CrafterGoal.coreCrafts(level, colony, core), Storage.held(level, colony), GameRecipeBook.of(level.getServer()),
			Gatherables::isGatherable);
		if (core.countItem(ModItems.BOT_CORE) > 0) {
			board.post(JobType.ACTIVATE_BOT, at);
		} else {
			board.removeOpenIf(job -> job.type() == JobType.ACTIVATE_BOT);
		}
		List<BlockPos> chests = HaulerGoal.chestsToHaul(level, colony);
		board.removeOpenIf(job -> job.type() == JobType.HAUL && !chests.contains(job.target()));
		chests.forEach(chest -> board.post(JobType.HAUL, chest));
	}

	/**
	 * Posts jobs for the trees in a build's way, so miners leave their logs to lumberjacks, and for the trees
	 * nearest the core while the colony is short of logs; orders include what builds need.
	 */
	private void surveyTrees(ServerLevel level, Colony colony, LevelTreeView trees) {
		List<TreeScanner.Tree> all = TreeScanner.findAll(trees, colony.core(), colony.radius());
		Set<BlockPos> building = colony.builds().stream().flatMap(plan -> plan.steps().stream()).map(BuildPlan.Step::pos).collect(Collectors.toSet());
		Function<TreeScanner.Tree, Optional<Item>> sapling = tree -> Replanting.saplingFor(level.getBlockState(tree.logs().getFirst()).getBlock());
		all.stream().filter(tree -> tree.logs().stream().anyMatch(building::contains))
			.forEach(tree -> colony.board().postTree(tree, sapling.apply(tree)));
		List<StockPlanner.Target<Item>> targets = colony.stockTargets().stream().map(StockTarget::toPlannerTarget).toList();
		int logs = Stockkeeping.logsShort(targets, colony.orders(), Storage.held(level, colony), GameRecipeBook.of(level.getServer()),
			Gatherables::isGatherable, item -> item.builtInRegistryHolder().is(ItemTags.LOGS));
		if (logs > 0) {
			colony.board().postTrees(all, colony.core(), logs, sapling);
		}
	}

	public void tick(ServerLevel level) {
		long now = level.getGameTime();
		for (Colony colony : this.colonies.values()) {
			if (colony.lastSurvey >= 0 && now - colony.lastSurvey < SURVEY_INTERVAL) {
				continue;
			}
			colony.lastSurvey = now;
			colony.board().releaseStale(now, CLAIM_TIMEOUT);
			// A player may have opened the way to a tree or branch given up on since.
			colony.recheckGivenUp(now);
			colony.forgetReplaced(pos -> level.isLoaded(pos) ? level.getBlockState(pos) : null);
			colony.recheckBlocked(pos -> level.isLoaded(pos) ? level.getBlockState(pos) : null, BuilderGoal.workable(level, colony));
			// A block of a finished batch broken since, such as a torch off the mineshaft stairs, is built again.
			colony.recheckDone(pos -> level.isLoaded(pos) ? level.getBlockState(pos) : null);
			LevelTreeView trees = new LevelTreeView(level);
			// A log job goes once its log does; a tree job stays to pick up the drops and replant the tree.
			colony.board().removeOpenIf(job -> job.type() == JobType.CHOP_LOG && !trees.isLog(job.target()));
			// Replanted since, or built over.
			colony.board().removeOpenIf(job -> job.type() == JobType.REPLANT
				&& job.blocks().stream().noneMatch(spot -> Replanting.isPlantable(level, spot)));
			this.surveyCrops(level, colony);
			Map<Item, Integer> have = new HashMap<>(Storage.held(level, colony));
			this.surveyMine(level, colony, have);
			this.surveyBuilds(level, colony, have);
			this.surveyStorage(level, colony, have);
			this.surveyStock(level, colony);
			this.surveyCoreJobs(level, colony);
			this.surveyTrees(level, colony, trees);
		}
	}
}
