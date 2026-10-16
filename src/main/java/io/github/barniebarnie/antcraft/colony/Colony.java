package io.github.barniebarnie.antcraft.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mojang.datafixers.util.Either;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Field;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.StorageArea;
import net.minecraft.world.item.Item;

/** A colony: its core block (storage), member bots, work radius and job board. */
public class Colony {
	public static final int DEFAULT_RADIUS = 32;

	/** Plans by the id of their build job. Saves from before plans had ids hold a list; those get new ids. */
	private static final Codec<Map<UUID, BuildPlan>> BUILDS = Codec.withAlternative(
		Codec.unboundedMap(UUIDUtil.STRING_CODEC, BuildPlan.CODEC),
		BuildPlan.CODEC.listOf().xmap(Colony::withIds, plans -> List.copyOf(plans.values())));
	private static final Codec<Map<Item, Integer>> ITEM_COUNTS = Codec.unboundedMap(BuiltInRegistries.ITEM.byNameCodec(), Codec.INT);

	public static final Codec<Colony> CODEC = RecordCodecBuilder.create(i -> i.group(
		UUIDUtil.CODEC.fieldOf("id").forGetter(Colony::id),
		BlockPos.CODEC.fieldOf("core").forGetter(Colony::core),
		UUIDUtil.CODEC.listOf().optionalFieldOf("members", List.of()).forGetter(c -> List.copyOf(c.members)),
		// Lenient: jobs of a type that no longer exists (the quarry's mine_layer) drop the list; the next survey posts them again.
		JobBoard.CODEC.lenientOptionalFieldOf("jobs", new JobBoard()).forGetter(Colony::board),
		Codec.INT.optionalFieldOf("radius", DEFAULT_RADIUS).forGetter(Colony::radius),
		ITEM_COUNTS.optionalFieldOf("orders", Map.of()).forGetter(Colony::orders),
		// Old saves may still have a "quarry"; it is ignored.
		Mineshaft.CODEC.optionalFieldOf("mineshaft").forGetter(Colony::mineshaft),
		// Colonies saved before stock targets existed get the defaults.
		StockTarget.CODEC.listOf().optionalFieldOf("stock", DefaultStock.TARGETS).forGetter(Colony::stockTargets),
		BUILDS.optionalFieldOf("builds", Map.of()).forGetter(c -> c.builds),
		BlockPos.CODEC.listOf().optionalFieldOf("protected", List.of()).forGetter(c -> List.copyOf(c.protectedBlocks)),
		Field.CODEC.optionalFieldOf("field").forGetter(Colony::field),
		StorageArea.CODEC.optionalFieldOf("storage").forGetter(Colony::storage),
		ITEM_COUNTS.optionalFieldOf("build_orders", Map.of()).forGetter(c -> c.buildOrders),
		BlockPos.CODEC.listOf().optionalFieldOf("blocked_steps", List.of()).forGetter(Colony::blockedSteps)
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
	/** Build plans by the id of their build job, so plans that start at the same spot get a job each. */
	private final Map<UUID, BuildPlan> builds;
	/** Blocks build plans placed, which bots leave standing. */
	private final Set<BlockPos> protectedBlocks;
	private Optional<Field> field;
	private Optional<StorageArea> storage;
	/** The part of {@link #orders} placed for build plans, which is withdrawn once the plans no longer need it. */
	private final Map<Item, Integer> buildOrders;
	/** Steps finished plans left undone because an unbreakable block was in the way. */
	private final List<BlockPos> blockedSteps;
	/** Game time of the last tree survey, or -1 if the colony has not surveyed yet. */
	long lastSurvey = -1;

	Colony(UUID id, BlockPos core, List<UUID> members, JobBoard board, int radius, Map<Item, Integer> orders, Optional<Mineshaft> mineshaft,
			List<StockTarget> stock, Map<UUID, BuildPlan> builds, List<BlockPos> protectedBlocks, Optional<Field> field,
			Optional<StorageArea> storage, Map<Item, Integer> buildOrders, List<BlockPos> blockedSteps) {
		this.buildOrders = new LinkedHashMap<>(buildOrders);
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
		this.protectedBlocks = new HashSet<>(protectedBlocks);
	}

	private static Map<UUID, BuildPlan> withIds(List<BuildPlan> plans) {
		Map<UUID, BuildPlan> builds = new LinkedHashMap<>();
		plans.forEach(plan -> builds.put(UUID.randomUUID(), plan));
		return builds;
	}

	/** Build plans the colony's builders still have to carry out, in the order they were added. */
	public List<BuildPlan> builds() {
		return List.copyOf(this.builds.values());
	}

	public void addBuild(BuildPlan plan) {
		this.builds.put(UUID.randomUUID(), plan);
		this.lastSurvey = -1;
	}

	/** Drops the plan, finished or cancelled; the next survey withdraws its job and what it ordered. */
	public void removeBuild(BuildPlan plan) {
		this.builds.values().remove(plan);
		this.lastSurvey = -1;
	}

	/** The plan a build job is for. */
	public Optional<BuildPlan> build(UUID job) {
		return Optional.ofNullable(this.builds.get(job));
	}

	/** Posts a build job for each plan, keyed by the plan, and drops open jobs of plans that are gone. */
	public void postBuildJobs() {
		this.board.removeOpenIf(job -> job.type() == JobType.BUILD && !this.builds.containsKey(job.id()));
		this.builds.forEach((id, plan) -> this.board.post(id, JobType.BUILD, plan.target()));
	}

	/**
	 * Sets what build plans need ordered: orders grow by what plans need beyond what they already
	 * ordered, and shrink by what they no longer need, such as when the items turned up some other
	 * way or the plan is gone. Other orders are left alone.
	 */
	public void orderForBuilds(Map<Item, Integer> needed) {
		Set<Item> items = new HashSet<>(this.buildOrders.keySet());
		items.addAll(needed.keySet());
		for (Item item : items) {
			int ordered = this.orders.getOrDefault(item, 0);
			// The order may have been cancelled meanwhile.
			int forBuilds = Math.min(this.buildOrders.getOrDefault(item, 0), ordered);
			int want = needed.getOrDefault(item, 0);
			setCount(this.orders, item, ordered - forBuilds + want);
			setCount(this.buildOrders, item, want);
		}
	}

	private static void setCount(Map<Item, Integer> counts, Item item, int count) {
		if (count > 0) {
			counts.put(item, count);
		} else {
			counts.remove(item);
		}
	}

	/** Steps finished plans had to leave undone because an unbreakable block was in the way. */
	public List<BlockPos> blockedSteps() {
		return List.copyOf(this.blockedSteps);
	}

	public void reportBlocked(Collection<BlockPos> steps) {
		steps.stream().filter(pos -> !this.blockedSteps.contains(pos)).forEach(pos -> this.blockedSteps.add(pos.immutable()));
	}

	/** Whether a build plan placed the block at {@code pos}, so bots must not break it. */
	public boolean isProtected(BlockPos pos) {
		return this.protectedBlocks.contains(pos) || this.shaftBlocks.contains(pos);
	}

	public void protect(BlockPos pos) {
		this.protectedBlocks.add(pos.immutable());
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

	/** Drops the whole order for {@code item}, including the part build plans placed. */
	public void cancelOrder(Item item) {
		this.orders.remove(item);
		this.buildOrders.remove(item);
	}

	/** Records that {@code count} of an ordered item were made, for build plans first; finished orders are removed. */
	public void fulfil(Item item, int count) {
		this.orders.computeIfPresent(item, (key, left) -> left - count > 0 ? left - count : null);
		this.buildOrders.computeIfPresent(item, (key, left) -> left - count > 0 ? left - count : null);
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
