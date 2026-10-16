package io.github.barniebarnie.antcraft.colony;

import com.mojang.serialization.Codec;
import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.work.Farming;
import io.github.barniebarnie.antcraft.work.Field;
import io.github.barniebarnie.antcraft.work.GameRecipeBook;
import io.github.barniebarnie.antcraft.work.Gatherables;
import io.github.barniebarnie.antcraft.work.LevelTreeView;
import io.github.barniebarnie.antcraft.work.Mining;
import io.github.barniebarnie.antcraft.work.Replanting;
import io.github.barniebarnie.antcraft.work.Storage;
import io.github.barniebarnie.antcraft.work.TreeScanner;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** All colonies in one dimension, saved with the world. */
public class ColonyManager extends SavedData {
	/** Bot Cores activated this close to a colony core join that colony. */
	public static final int JOIN_DISTANCE = 32;
	private static final int SURVEY_INTERVAL = 100;
	/** Quarry layers open for mining at once, so a couple of miners can work side by side. */
	private static final int MINING_LAYERS = 2;
	/** How far above and below the core crops are looked for. */
	private static final int CROP_SCAN_HEIGHT = 4;
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
			List.of(), List.of(), Optional.empty());
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

	/** Whether a colony build plan placed the block at {@code pos}, so bots must not break it. */
	public static boolean isProtected(Level level, BlockPos pos) {
		return level instanceof ServerLevel server && get(server).colonies.values().stream().anyMatch(colony -> colony.isProtected(pos));
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
		if (colony.field().isEmpty() && crops == 0 && this.hasFarmer(level, colony)) {
			List<BoundingBox> avoid = colony.quarry().map(q -> List.of(BoundingBox.fromCorners(q.min(), q.max()))).orElse(List.of());
			Field.choose(core, r, avoid, pos -> Farming.isSoil(level.getBlockState(pos)), pos -> Farming.isOpen(level.getBlockState(pos)))
				.ifPresent(colony::setField);
		}
		colony.field().ifPresent(field -> {
			List<BlockPos> toPlant = field.toPlant(pos -> level.getBlockState(pos).getBlock() instanceof CropBlock);
			colony.board().removeOpenIf(job -> job.type() == JobType.PLANT_CROP && !toPlant.contains(job.target()));
			toPlant.forEach(pos -> colony.board().post(JobType.PLANT_CROP, pos));
		});
	}

	private boolean hasFarmer(ServerLevel level, Colony colony) {
		return colony.members().stream().anyMatch(id -> level.getEntity(id) instanceof AntBot bot && bot.getRole() == BotRole.FARMER);
	}

	/** Orders whatever the colony is short of. */
	private void surveyStock(ServerLevel level, Colony colony) {
		Container core = Storage.containerAt(level, colony.core());
		if (core == null || colony.stockTargets().isEmpty()) {
			return;
		}
		List<StockPlanner.Target<Item>> targets = colony.stockTargets().stream().map(StockTarget::toPlannerTarget).toList();
		StockPlanner.ordersToAdd(targets, Storage.stock(core), colony.orders(), GameRecipeBook.of(level.getServer()), Gatherables::isGatherable)
			.forEach(colony::addOrder);
	}

	/**
	 * Gives unassigned members (such as freshly built bots) whatever work the colony needs
	 * most, and moves idle members the colony assigned to roles that have work.
	 */
	private void assignRoles(ServerLevel level, Colony colony) {
		List<AntBot> members = new ArrayList<>();
		for (UUID id : colony.members()) {
			if (level.getEntity(id) instanceof AntBot bot) {
				members.add(bot);
			}
		}
		Map<BotRole, Integer> roles = new EnumMap<>(BotRole.class);
		members.forEach(bot -> roles.merge(bot.getRole(), 1, Integer::sum));
		Map<JobType, Integer> openJobs = new EnumMap<>(JobType.class);
		colony.board().jobs().stream().filter(Job::isOpen).forEach(job -> openJobs.merge(job.type(), 1, Integer::sum));

		boolean hasOrders = !colony.orders().isEmpty();
		for (AntBot bot : members) {
			BotRole current = bot.getRole();
			BotRole role = current == BotRole.NONE
				? RoleAssigner.choose(roles, openJobs, hasOrders)
				: RoleAssigner.reassign(current, bot.isRoleSetByPlayer(), bot.getIdleTicks(), roles, openJobs, hasOrders);
			if (role == current) {
				continue;
			}
			bot.assignRole(role);
			roles.merge(role, 1, Integer::sum);
			roles.merge(current, -1, Integer::sum);
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
			LevelTreeView trees = new LevelTreeView(level);
			// A tree job stays while any of its logs stand, even once the leaves have decayed.
			colony.board().removeOpenIf(job -> job.type() == JobType.CHOP_TREE && !trees.isLog(job.target())
				&& TreeScanner.remaining(trees, job.area()).isEmpty());
			for (TreeScanner.Tree tree : TreeScanner.findAll(trees, colony.core(), colony.radius())) {
				// Covering its logs keeps the rest of a tree being felled from being posted as a new tree.
				colony.board().post(JobType.CHOP_TREE, tree.base(), tree.logs());
			}
			// Replanted since, or built over.
			colony.board().removeOpenIf(job -> job.type() == JobType.REPLANT && !Replanting.isPlantable(level, job.target()));
			this.surveyCrops(level, colony);
			colony.quarry().ifPresent(quarry -> {
				Set<BlockPos> layers = new HashSet<>();
				for (int y : quarry.layersToWork(pos -> Mining.isMinable(level, pos), MINING_LAYERS)) {
					layers.add(quarry.layerTarget(y));
					colony.board().post(JobType.MINE_LAYER, quarry.layerTarget(y));
				}
				colony.board().removeOpenIf(job -> job.type() == JobType.MINE_LAYER && !layers.contains(job.target()));
			});
			colony.builds().forEach(plan -> colony.board().post(JobType.BUILD, plan.target()));
			this.surveyStock(level, colony);
			this.assignRoles(level, colony);
		}
	}
}
