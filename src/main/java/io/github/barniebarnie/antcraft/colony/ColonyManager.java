package io.github.barniebarnie.antcraft.colony;

import com.mojang.serialization.Codec;
import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.entity.goal.HaulerGoal;
import io.github.barniebarnie.antcraft.work.Farming;
import io.github.barniebarnie.antcraft.work.Field;
import io.github.barniebarnie.antcraft.work.GameRecipeBook;
import io.github.barniebarnie.antcraft.work.Gatherables;
import io.github.barniebarnie.antcraft.work.LevelTreeView;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.Mining;
import io.github.barniebarnie.antcraft.work.Replanting;
import io.github.barniebarnie.antcraft.work.Storage;
import io.github.barniebarnie.antcraft.work.StorageArea;
import io.github.barniebarnie.antcraft.work.TreeScanner;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
			List.of(), List.of(), Optional.empty(), Optional.empty());
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
		if (colony.field().isEmpty() && crops == 0 && this.hasRole(level, colony, BotRole.FARMER)) {
			Field.choose(core, r, occupied(colony), pos -> Farming.isSoil(level.getBlockState(pos)), pos -> Farming.isOpen(level.getBlockState(pos)))
				.ifPresent(colony::setField);
		}
		colony.field().ifPresent(field -> {
			List<BlockPos> toPlant = field.toPlant(pos -> level.getBlockState(pos).getBlock() instanceof CropBlock);
			colony.board().removeOpenIf(job -> job.type() == JobType.PLANT_CROP && !toPlant.contains(job.target()));
			toPlant.forEach(pos -> colony.board().post(JobType.PLANT_CROP, pos));
		});
	}

	/**
	 * Picks a mineshaft site once the colony has a miner, and posts a job for each branch with
	 * blocks left to dig at one mining level: the deepest dug one whose ores the colony wants.
	 */
	private void surveyMine(ServerLevel level, Colony colony) {
		if (colony.mineshaft().isEmpty() && this.hasRole(level, colony, BotRole.MINER)) {
			// The colony has no shaft yet, so this avoids the field and storage only.
			colony.setMineshaft(Mineshaft.nextTo(colony.core(), AntCraftConfig.mineshaftLevels(), occupied(colony)));
		}
		Set<BlockPos> posted = new HashSet<>();
		colony.mineshaft().ifPresent(shaft -> {
			Map<Integer, List<Mineshaft.Branch>> work = new HashMap<>();
			for (int y : shaft.levels()) {
				// The builder has dug the hall, so miners can walk to the branches.
				BlockPos hallEnd = shaft.hall(y).getLast();
				if (!level.getBlockState(hallEnd).getCollisionShape(level, hallEnd).isEmpty()) {
					continue;
				}
				List<Mineshaft.Branch> left = shaft.branches(y).stream()
					.filter(branch -> branch.cells().stream().anyMatch(pos -> Mining.isMinable(level, pos))).toList();
				if (!left.isEmpty()) {
					work.put(y, left);
				}
			}
			Mineshaft.chooseLevel(List.copyOf(work.keySet()), item -> colony.orders().containsKey(item)
					|| colony.stockTargets().stream().anyMatch(target -> target.matches(item)))
				.ifPresent(y -> work.get(y).forEach(branch -> {
					posted.add(branch.start());
					colony.board().post(JobType.MINE_BRANCH, branch.start(), branch.cells());
				}));
		});
		colony.board().removeOpenIf(job -> job.type() == JobType.MINE_BRANCH && !posted.contains(job.target()));
	}

	private boolean hasRole(ServerLevel level, Colony colony, BotRole role) {
		return colony.members().stream().anyMatch(id -> level.getEntity(id) instanceof AntBot bot && bot.getRole() == role);
	}

	/** Areas the colony already uses, which a new area must keep clear of. Add new kinds of areas here. */
	private static List<BoundingBox> occupied(Colony colony) {
		List<BoundingBox> areas = new ArrayList<>();
		colony.mineshaft().ifPresent(shaft -> areas.add(shaft.area()));
		colony.field().ifPresent(field -> areas.add(field.area()));
		colony.storage().ifPresent(storage -> areas.add(storage.area()));
		return areas;
	}

	/** Picks the storage area once the colony has a hauler to keep it. */
	private void surveyStorage(ServerLevel level, Colony colony) {
		if (colony.storage().isPresent() || !this.hasRole(level, colony, BotRole.HAULER)) {
			return;
		}
		StorageArea.choose(colony.core(), colony.radius(), occupied(colony),
				pos -> level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP),
				pos -> level.getBlockState(pos).canBeReplaced() && level.getBlockState(pos).getFluidState().isEmpty())
			.ifPresent(colony::setStorage);
	}

	/** Orders whatever the colony is short of. */
	private void surveyStock(ServerLevel level, Colony colony) {
		Container core = Storage.containerAt(level, colony.core());
		if (core == null || colony.stockTargets().isEmpty()) {
			return;
		}
		List<StockPlanner.Target<Item>> targets = colony.stockTargets().stream().map(StockTarget::toPlannerTarget).toList();
		StockPlanner.ordersToAdd(targets, Storage.colonyStock(level, colony), colony.orders(), GameRecipeBook.of(level.getServer()), Gatherables::isGatherable)
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
		boolean needsHauler = HaulerGoal.needsHauler(level, colony);
		for (AntBot bot : members) {
			BotRole current = bot.getRole();
			BotRole role = current == BotRole.NONE
				? RoleAssigner.choose(roles, openJobs, hasOrders, needsHauler)
				: RoleAssigner.reassign(current, bot.isRoleSetByPlayer(), bot.getIdleTicks(), roles, openJobs, hasOrders, needsHauler);
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
			this.surveyMine(level, colony);
			colony.builds().forEach(plan -> colony.board().post(JobType.BUILD, plan.target()));
			this.surveyStorage(level, colony);
			this.surveyStock(level, colony);
			this.assignRoles(level, colony);
		}
	}
}
