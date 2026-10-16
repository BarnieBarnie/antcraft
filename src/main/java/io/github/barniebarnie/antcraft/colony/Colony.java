package io.github.barniebarnie.antcraft.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mojang.datafixers.util.Either;
import java.util.ArrayList;
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

	public static final Codec<Colony> CODEC = RecordCodecBuilder.create(i -> i.group(
		UUIDUtil.CODEC.fieldOf("id").forGetter(Colony::id),
		BlockPos.CODEC.fieldOf("core").forGetter(Colony::core),
		UUIDUtil.CODEC.listOf().optionalFieldOf("members", List.of()).forGetter(c -> List.copyOf(c.members)),
		// Lenient: jobs of a type that no longer exists (the quarry's mine_layer) drop the list; the next survey posts them again.
		JobBoard.CODEC.lenientOptionalFieldOf("jobs", new JobBoard()).forGetter(Colony::board),
		Codec.INT.optionalFieldOf("radius", DEFAULT_RADIUS).forGetter(Colony::radius),
		Codec.unboundedMap(BuiltInRegistries.ITEM.byNameCodec(), Codec.INT).optionalFieldOf("orders", Map.of()).forGetter(Colony::orders),
		// Old saves may still have a "quarry"; it is ignored.
		Mineshaft.CODEC.optionalFieldOf("mineshaft").forGetter(Colony::mineshaft),
		// Colonies saved before stock targets existed get the defaults.
		StockTarget.CODEC.listOf().optionalFieldOf("stock", DefaultStock.TARGETS).forGetter(Colony::stockTargets),
		BuildPlan.CODEC.listOf().optionalFieldOf("builds", List.of()).forGetter(Colony::builds),
		BlockPos.CODEC.listOf().optionalFieldOf("protected", List.of()).forGetter(c -> List.copyOf(c.protectedBlocks)),
		Field.CODEC.optionalFieldOf("field").forGetter(Colony::field),
		StorageArea.CODEC.optionalFieldOf("storage").forGetter(Colony::storage)
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
	private final List<BuildPlan> builds;
	/** Blocks build plans placed, which bots leave standing. */
	private final Set<BlockPos> protectedBlocks;
	private Optional<Field> field;
	private Optional<StorageArea> storage;
	/** Game time of the last tree survey, or -1 if the colony has not surveyed yet. */
	long lastSurvey = -1;

	Colony(UUID id, BlockPos core, List<UUID> members, JobBoard board, int radius, Map<Item, Integer> orders, Optional<Mineshaft> mineshaft,
			List<StockTarget> stock, List<BuildPlan> builds, List<BlockPos> protectedBlocks, Optional<Field> field,
			Optional<StorageArea> storage) {
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
		this.builds = new ArrayList<>(builds);
		this.protectedBlocks = new HashSet<>(protectedBlocks);
	}

	/** Build plans the colony's builders still have to carry out, in the order they were added. */
	public List<BuildPlan> builds() {
		return List.copyOf(this.builds);
	}

	public void addBuild(BuildPlan plan) {
		this.builds.add(plan);
		this.lastSurvey = -1;
	}

	public void removeBuild(BuildPlan plan) {
		this.builds.remove(plan);
	}

	/** The plan a build job points at. */
	public Optional<BuildPlan> build(BlockPos target) {
		return this.builds.stream().filter(plan -> plan.target().equals(target)).findFirst();
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

	/** Records that {@code count} of an ordered item were made; finished orders are removed. */
	public void fulfil(Item item, int count) {
		this.orders.computeIfPresent(item, (key, left) -> left - count > 0 ? left - count : null);
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
