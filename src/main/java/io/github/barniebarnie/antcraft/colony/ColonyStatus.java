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
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A snapshot of what a colony is doing: its bots, jobs, stock, orders and builds, the build steps that are
 * blocked and the trees given up on with logs out of reach. {@code /antcraft colony info}
 * prints it and the colony status screen shows it, so the two cannot disagree.
 */
public record ColonyStatus(BlockPos core, int members, Map<UUID, Bot> bots, Map<BotRole, Integer> roles, int idle, int radius,
		Map<JobType, Integer> openJobs, Map<JobType, Integer> claimedJobs, List<Stock> stock, List<Order> orders,
		List<Build> builds, List<BlockPos> blocked, List<BlockPos> givenUp) {

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

	/** A build plan with how many of its steps are left. */
	public record Build(BlockPos target, int left, int steps, boolean mineshaft) {
		static final StreamCodec<ByteBuf, Build> STREAM_CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, Build::target, ByteBufCodecs.VAR_INT, Build::left, ByteBufCodecs.VAR_INT, Build::steps,
			ByteBufCodecs.BOOL, Build::mineshaft, Build::new);
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
		BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()), ColonyStatus::givenUp,
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

	private static int total(Map<?, Integer> counts) {
		return counts.values().stream().mapToInt(Integer::intValue).sum();
	}

	/** The status of a colony in the world, counting the stock in its core and storage. */
	public static ColonyStatus of(ServerLevel level, Colony colony) {
		Map<UUID, Bot> bots = new HashMap<>();
		for (UUID member : colony.members()) {
			if (level.getEntity(member) instanceof AntBot bot) {
				bots.put(member, new Bot(bot.getName().getString(), bot.getRole(), bot.getIdleTicks() >= RoleAssigner.IDLE_TICKS_BEFORE_REASSIGN,
					bot.isRoleSetByPlayer()));
			}
		}
		Map<Item, Integer> stock = Storage.containerAt(level, colony.core()) == null ? Map.of() : Storage.colonyStock(level, colony);
		return of(colony, bots, stock, GameRecipeBook.of(level.getServer()), Gatherables::isGatherable, level::getBlockState);
	}

	/**
	 * @param bots the colony's members that are loaded
	 * @param stock what the colony holds
	 * @param world the block at a position, to tell which build steps are done
	 */
	public static ColonyStatus of(Colony colony, Map<UUID, Bot> bots, Map<Item, Integer> stock, TaskPlanner.RecipeBook<Item> book,
			Predicate<Item> gatherable, Function<BlockPos, BlockState> world) {
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
		List<BlockPos> givenUp = new ArrayList<>();
		for (Job job : colony.board().jobs()) {
			(job.isOpen() ? open : claimed).merge(job.type(), 1, Integer::sum);
			if (job.type() == JobType.CHOP_TREE && job.givenUp()) {
				givenUp.add(job.target());
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
			int left = (int) plan.steps().stream().filter(step -> !step.isDone(world.apply(step.pos()))).count();
			boolean mineshaft = colony.mineshaft().map(shaft -> shaft.plan().equals(plan)).orElse(false);
			builds.add(new Build(plan.target(), left, plan.steps().size(), mineshaft));
		}

		return new ColonyStatus(colony.core(), colony.members().size(), loaded, roles, idle, colony.radius(), open, claimed, stocks, orders, builds,
			colony.blockedSteps(), givenUp);
	}
}
