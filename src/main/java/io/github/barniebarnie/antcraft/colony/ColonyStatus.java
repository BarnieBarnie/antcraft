package io.github.barniebarnie.antcraft.colony;

import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.GameRecipeBook;
import io.github.barniebarnie.antcraft.work.Gatherables;
import io.github.barniebarnie.antcraft.work.Storage;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A snapshot of what a colony is doing: its bots, jobs, stock, orders and builds, the build steps that are
 * blocked, the trees and branches given up on as out of reach, and the room left in its core. {@code /antcraft colony info}
 * prints it and the colony status screen shows it, so the two cannot disagree.
 */
public record ColonyStatus(BlockPos core, int members, Map<UUID, Bot> bots, Map<BotRole, Integer> roles, int idle, int radius,
		Map<JobType, Integer> openJobs, Map<JobType, Integer> claimedJobs, List<Stock> stock, List<Order> orders,
		List<Build> builds, List<BlockPos> blocked, List<GivenUp> givenUp, Room room) {

	/** Why the storage area cannot take what the core has no room for, if it cannot. */
	public enum StorageState {
		READY("commands.antcraft.colony.storage.ready"),
		NO_HAULER("commands.antcraft.colony.storage.no_hauler"),
		NO_SITE("commands.antcraft.colony.storage.no_site"),
		NO_CHEST("commands.antcraft.colony.storage.no_chest");

		private final String key;

		StorageState(String key) {
			this.key = key;
		}

		/** The lang key that says it. */
		public String key() {
			return this.key;
		}
	}

	/** How many slots the core has free, and whether storage can take the overflow. */
	public record Room(int freeSlots, StorageState storage) {
		public boolean coreFull() {
			return this.freeSlots == 0;
		}

		static final StreamCodec<ByteBuf, Room> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, Room::freeSlots, enumCodec(StorageState.values()), Room::storage, Room::new);
	}

	/** A job bots gave up on, such as a tree with logs out of reach or a branch with cells out of reach. */
	public record GivenUp(JobType type, BlockPos target) {
		static final StreamCodec<ByteBuf, GivenUp> STREAM_CODEC = StreamCodec.composite(
			enumCodec(JobType.values()), GivenUp::type, BlockPos.STREAM_CODEC, GivenUp::target, GivenUp::new);
	}

	/** A loaded member bot; {@code setByPlayer} when a player chose its role, so the colony leaves it. */
	public record Bot(String name, BotRole role, boolean idle, boolean setByPlayer) {
	}

	/** A stock target with how many the colony has. */
	public record Stock(String name, int have, int want) {
		public boolean isShort() {
			return this.have < this.want;
		}

		static final StreamCodec<ByteBuf, Stock> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, Stock::name, ByteBufCodecs.VAR_INT, Stock::have, ByteBufCodecs.VAR_INT, Stock::want, Stock::new);
	}

	/** An order, the parts of it the colony placed itself, and what is still to gather or missing to craft it. */
	public record Order(Item item, int count, Map<Colony.OrderPurpose, Integer> forColony, Map<Item, Integer> toGather, Map<Item, Integer> missing) {
		/** How many of {@link #count} a player ordered. */
		public int byPlayer() {
			return Math.max(0, this.count - this.forColony.values().stream().mapToInt(Integer::intValue).sum());
		}

		static final StreamCodec<RegistryFriendlyByteBuf, Order> STREAM_CODEC = StreamCodec.composite(
			ITEM, Order::item, ByteBufCodecs.VAR_INT, Order::count,
			ByteBufCodecs.map(n -> new EnumMap<>(Colony.OrderPurpose.class), enumCodec(Colony.OrderPurpose.values()), ByteBufCodecs.VAR_INT), Order::forColony,
			ITEM_COUNTS, Order::toGather, ITEM_COUNTS, Order::missing, Order::new);
	}

	/** A batch of a build plan with how many of its steps are left, and whether it waits on blocked steps, its own or those of a batch it needs. */
	public record Batch(int left, int steps, boolean blocked) {
		static final StreamCodec<ByteBuf, Batch> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, Batch::left, ByteBufCodecs.VAR_INT, Batch::steps, ByteBufCodecs.BOOL, Batch::blocked, Batch::new);
	}

	/** A build plan with how many of its steps are left, whether it is the mineshaft or an area to level, and its batches in order. */
	public record Build(BlockPos target, int left, int steps, boolean mineshaft, boolean terraform, List<Batch> batches) {
		static final StreamCodec<ByteBuf, Build> STREAM_CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, Build::target, ByteBufCodecs.VAR_INT, Build::left, ByteBufCodecs.VAR_INT, Build::steps,
			ByteBufCodecs.BOOL, Build::mineshaft, ByteBufCodecs.BOOL, Build::terraform, Batch.STREAM_CODEC.apply(ByteBufCodecs.list()), Build::batches,
			Build::new);
	}

	// Here, not in Bot: Bot is built before ColonyStatus is loaded, and would see this class's codecs half made.
	private static final StreamCodec<ByteBuf, Bot> BOT = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8, Bot::name, enumCodec(BotRole.values()), Bot::role, ByteBufCodecs.BOOL, Bot::idle,
		ByteBufCodecs.BOOL, Bot::setByPlayer, Bot::new);
	private static final StreamCodec<RegistryFriendlyByteBuf, Item> ITEM = ByteBufCodecs.registry(Registries.ITEM);
	private static final StreamCodec<RegistryFriendlyByteBuf, Map<Item, Integer>> ITEM_COUNTS = ByteBufCodecs.map(LinkedHashMap::new, ITEM, ByteBufCodecs.VAR_INT);
	private static final StreamCodec<ByteBuf, Map<JobType, Integer>> JOB_COUNTS =
		ByteBufCodecs.map(n -> new EnumMap<>(JobType.class), enumCodec(JobType.values()), ByteBufCodecs.VAR_INT);

	public static final StreamCodec<RegistryFriendlyByteBuf, ColonyStatus> STREAM_CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, ColonyStatus::core,
		ByteBufCodecs.VAR_INT, ColonyStatus::members,
		ByteBufCodecs.map(LinkedHashMap::new, UUIDUtil.STREAM_CODEC, BOT), ColonyStatus::bots,
		ByteBufCodecs.map(n -> new EnumMap<>(BotRole.class), enumCodec(BotRole.values()), ByteBufCodecs.VAR_INT), ColonyStatus::roles,
		ByteBufCodecs.VAR_INT, ColonyStatus::idle,
		ByteBufCodecs.VAR_INT, ColonyStatus::radius,
		JOB_COUNTS, ColonyStatus::openJobs,
		JOB_COUNTS, ColonyStatus::claimedJobs,
		Stock.STREAM_CODEC.apply(ByteBufCodecs.list()), ColonyStatus::stock,
		Order.STREAM_CODEC.apply(ByteBufCodecs.list()), ColonyStatus::orders,
		Build.STREAM_CODEC.apply(ByteBufCodecs.list()), ColonyStatus::builds,
		BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()), ColonyStatus::blocked,
		GivenUp.STREAM_CODEC.apply(ByteBufCodecs.list()), ColonyStatus::givenUp,
		Room.STREAM_CODEC, ColonyStatus::room,
		ColonyStatus::new);

	private static <E extends Enum<E>> StreamCodec<ByteBuf, E> enumCodec(E[] values) {
		return ByteBufCodecs.idMapper(i -> values[i], Enum::ordinal);
	}

	/** Jobs on the board, open and claimed. */
	public int jobs() {
		return total(this.openJobs) + this.claimed();
	}

	public int claimed() {
		return total(this.claimedJobs);
	}

	/** The lang key that lists the given-up jobs of {@code type}: trees, or branches for any other type. */
	public static String givenUpKey(JobType type) {
		return type == JobType.CHOP_TREE ? "commands.antcraft.colony.given_up" : "commands.antcraft.colony.given_up_branches";
	}

	/** Where the jobs of {@code type} that bots gave up on are. */
	public List<BlockPos> givenUp(JobType type) {
		return this.givenUp.stream().filter(job -> job.type() == type).map(GivenUp::target).toList();
	}

	/** How many of {@code steps} the world does not match yet. */
	private static int left(List<BuildPlan.Step> steps, Function<BlockPos, BlockState> world) {
		return (int) steps.stream().filter(step -> !step.isDone(world.apply(step.pos()))).count();
	}

	private static int total(Map<?, Integer> counts) {
		return counts.values().stream().mapToInt(Integer::intValue).sum();
	}

	/** The status of a colony in the world, counting the stock it {@linkplain Storage#held holds}, what its bots carry included. */
	public static ColonyStatus of(ServerLevel level, Colony colony) {
		Map<UUID, Bot> bots = new HashMap<>();
		for (UUID member : colony.members()) {
			if (level.getEntity(member) instanceof AntBot bot) {
				bots.put(member, new Bot(bot.getName().getString(), bot.getRole(), bot.getIdleTicks() >= RoleAssigner.IDLE_TICKS_BEFORE_REASSIGN,
					bot.isRoleSetByPlayer()));
			}
		}
		Container core = Storage.containerAt(level, colony.core());
		Map<Item, Integer> stock = core == null ? Map.of() : Storage.held(level, colony);
		int freeSlots = core == null ? 0 : freeSlots(core);
		boolean chestWithRoom = Storage.storageChests(level, colony).values().stream().anyMatch(chest -> freeSlots(chest) > 0);
		return of(colony, bots, stock, GameRecipeBook.of(level.getServer()), Gatherables::isGatherable, level::getBlockState, freeSlots, chestWithRoom);
	}

	private static int freeSlots(Container container) {
		int free = 0;
		for (int i = 0; i < container.getContainerSize(); i++) {
			free += container.getItem(i).isEmpty() ? 1 : 0;
		}
		return free;
	}

	/**
	 * @param bots the colony's members that are loaded
	 * @param stock what the colony holds
	 * @param world the block at a position, to tell which build steps are done
	 * @param coreFreeSlots how many slots the core has free
	 * @param chestWithRoom whether a storage chest has a free slot
	 */
	public static ColonyStatus of(Colony colony, Map<UUID, Bot> bots, Map<Item, Integer> stock, TaskPlanner.RecipeBook<Item> book,
			Predicate<Item> gatherable, Function<BlockPos, BlockState> world, int coreFreeSlots, boolean chestWithRoom) {
		Map<BotRole, Integer> roles = new EnumMap<>(BotRole.class);
		Map<UUID, Bot> loaded = new LinkedHashMap<>();
		int idle = 0;
		for (UUID member : colony.members()) {
			Bot bot = bots.get(member);
			if (bot != null) {
				loaded.put(member, bot);
				roles.merge(bot.role(), 1, Integer::sum);
				idle += bot.idle() ? 1 : 0;
			}
		}

		Map<JobType, Integer> open = new EnumMap<>(JobType.class);
		Map<JobType, Integer> claimed = new EnumMap<>(JobType.class);
		List<GivenUp> givenUp = new ArrayList<>();
		for (Job job : colony.board().jobs()) {
			if (job.givenUp()) {
				givenUp.add(new GivenUp(job.type(), job.target()));
			} else {
				(job.isOpen() ? open : claimed).merge(job.type(), 1, Integer::sum);
			}
		}

		List<Stock> stocks = new ArrayList<>();
		for (StockTarget target : colony.stockTargets()) {
			stocks.add(new Stock(target.name(), StockPlanner.have(target.toPlannerTarget(), stock), target.count()));
		}
		// Stable, so short items keep the order of their targets.
		stocks.sort(Comparator.comparing(s -> !s.isShort()));

		List<Order> orders = new ArrayList<>();
		colony.orders().forEach((item, count) -> {
			Map<Item, Integer> available = new HashMap<>(stock);
			available.remove(item);
			TaskPlanner.Plan<Item> plan = TaskPlanner.plan(item, count, available, book, gatherable);
			Map<Colony.OrderPurpose, Integer> forColony = new EnumMap<>(Colony.OrderPurpose.class);
			for (Colony.OrderPurpose purpose : Colony.OrderPurpose.values()) {
				int placed = Math.min(colony.ordersFor(purpose).getOrDefault(item, 0), count);
				if (placed > 0) {
					forColony.put(purpose, placed);
				}
			}
			orders.add(new Order(item, count, forColony, plan.toGather(), plan.missing()));
		});

		List<Build> builds = new ArrayList<>();
		for (BuildPlan plan : colony.builds()) {
			Set<Integer> blocked = colony.blockedBatches(plan);
			List<Batch> batches = new ArrayList<>();
			for (int i = 0; i < plan.batches().size(); i++) {
				List<BuildPlan.Step> steps = plan.batch(i).steps();
				batches.add(new Batch(left(steps, world), steps.size(), blocked.contains(i)));
			}
			boolean mineshaft = colony.mineshaft().map(shaft -> shaft.plan().equals(plan)).orElse(false);
			builds.add(new Build(plan.target(), left(plan.steps(), world), plan.steps().size(), mineshaft, plan.kind() == BuildPlan.Kind.TERRAFORM,
				batches));
		}

		StorageState storage = roles.getOrDefault(BotRole.HAULER, 0) == 0 ? StorageState.NO_HAULER
			: colony.storage().isEmpty() ? StorageState.NO_SITE
			: !chestWithRoom ? StorageState.NO_CHEST
			: StorageState.READY;

		return new ColonyStatus(colony.core(), colony.members().size(), loaded, roles, idle, colony.radius(), open, claimed, stocks, orders, builds,
			colony.blockedSteps(), givenUp, new Room(coreFreeSlots, storage));
	}
}
