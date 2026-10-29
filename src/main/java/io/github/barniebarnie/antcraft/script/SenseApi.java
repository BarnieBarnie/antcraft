package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.pathing.LoadedBlocks;
import io.github.barniebarnie.antcraft.pathing.PathBudget;
import io.github.barniebarnie.antcraft.script.lang.Args;
import io.github.barniebarnie.antcraft.script.lang.NativeCall;
import io.github.barniebarnie.antcraft.script.lang.Natives;
import io.github.barniebarnie.antcraft.script.lang.PyDict;
import io.github.barniebarnie.antcraft.script.lang.PyList;
import io.github.barniebarnie.antcraft.script.lang.PyNone;
import io.github.barniebarnie.antcraft.script.lang.ScriptError;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

/**
 * The senses of the bot API (docs/scripting.md, Senses): {@code inventory}, {@code count}, {@code has}, {@code matches}, {@code held},
 * {@code block}, {@code find_nearest}, {@code scan}, {@code entities}, {@code items_near}, {@code container},
 * {@code time}, {@code is_day} and {@code colony}. They read the world around the script's bot and return at once; only a block search
 * that the server's {@link #SCAN_BUDGET} cannot finish this tick goes on over the next ticks, pausing the script.
 */
public final class SenseApi {
	/**
	 * How many blocks all scripts together may read in block searches in one server tick, shared like
	 * {@link PathBudget#SERVER}. A search of radius 16 reads about 17,000 blocks, one of radius 32 about 137,000. Measured
	 * with the bot benchmark (#322): all of it, used by 30 bots searching without end, costs about 2 ms a tick.
	 */
	public static final int BLOCKS_PER_TICK = 32_768;
	public static final PathBudget SCAN_BUDGET = new PathBudget(BLOCKS_PER_TICK);
	/**
	 * A block search counts one script instruction per this many blocks read. A block read costs some 6 instructions of
	 * time (#322), but {@link #SCAN_BUDGET} bounds what searches cost the server, and a charge at cost would hold a script
	 * up for seconds after each search of radius 32.
	 */
	public static final int BLOCKS_PER_INSTRUCTION = 8;
	/** The largest radius {@code entities} and {@code items_near} look in. */
	public static final int MAX_ENTITY_RADIUS = 64;

	private SenseApi() {
	}

	/** {@code colony()}: plain data, with the core in the dimension of its level, which need not be the bot's (#353). */
	private static PyDict colony(ColonyManager.Found found) {
		Colony colony = found.colony();
		BlockPos core = colony.core();
		PyDict dict = new PyDict();
		dict.put("core", new Pos(core.getX(), core.getY(), core.getZ(), found.level().dimension().identifier().toString()));
		dict.put("radius", (long) colony.radius());
		dict.put("members", new PyList(new ArrayList<>(colony.members().stream().map(UUID::toString).toList())));
		dict.put("owner", colony.owner().<Object>map(UUID::toString).orElse(PyNone.NONE));
		return dict;
	}

	public static void register(Natives natives, ScriptRun run) {
		natives.register("inventory", 30, call -> {
			Args.bind("inventory", call, 0);
			PyList stacks = new PyList();
			carried(bot(run, call)).forEach(stack -> stacks.items.add(stack(stack)));
			return stacks;
		});
		natives.register("free_slots", 20, call -> {
			Args.bind("free_slots", call, 0);
			return bot(run, call).getInventory().getItems().stream().filter(ItemStack::isEmpty).count();
		});
		natives.register("count", 20, call -> {
			Object[] args = Args.bind("count", call, 1, "item");
			return count(bot(run, call), Selector.parse(args[0]).items());
		});
		natives.register("has", 20, call -> {
			Object[] args = Args.bind("has", call, 1, "item", "n");
			long n = Args.absent(args[1]) ? 1 : Args.integer(args[1]);
			return count(bot(run, call), Selector.parse(args[0]).items()) >= n;
		});
		natives.register("matches", 15, call -> {
			Object[] args = Args.bind("matches", call, 2, "item", "selector");
			return Selector.matches(args[0], args[1]);
		});
		natives.register("held", call -> {
			Args.bind("held", call, 0);
			ItemStack hand = bot(run, call).getMainHandItem();
			return hand.isEmpty() ? PyNone.NONE : stack(hand);
		});
		natives.register("block", 30, call -> {
			Object[] args = Args.bind("block", call, 1, "pos");
			Pos pos = Pos.of(args[0]);
			AntBot bot = bot(run, call);
			if (run.elsewhere(pos)) {
				return PyNone.NONE;
			}
			BlockPos at = blockPos(pos);
			BlockState state = new LoadedBlocks(bot.level()).loaded(at);
			return state == null ? PyNone.NONE : block(state, pos);
		});
		natives.register("find_nearest", call -> {
			Object[] args = Args.bind("find_nearest", call, 1, "what", "radius");
			return search(run, call, args[0], radius(args[1], 32, BlockScan.MAX_RADIUS), 1, true);
		});
		natives.register("scan", call -> {
			Object[] args = Args.bind("scan", call, 1, "what", "radius", "limit");
			long limit = Args.absent(args[2]) ? 64 : Args.integer(args[2]);
			if (limit < 0) {
				throw new ScriptError("scan() limit must not be negative, not " + limit);
			}
			return search(run, call, args[0], radius(args[1], 16, BlockScan.MAX_RADIUS), (int) Math.min(limit, Integer.MAX_VALUE), false);
		});
		natives.register("entities", 120, call -> {
			Object[] args = Args.bind("entities", call, 0, "what", "radius");
			Predicate<Entity> what = Args.absent(args[0]) ? entity -> true : Selector.parse(args[0]).entities();
			return near(bot(run, call), Entity.class, what, radius(args[1], 16, MAX_ENTITY_RADIUS));
		});
		natives.register("items_near", 10, call -> {
			Object[] args = Args.bind("items_near", call, 0, "item", "radius");
			Predicate<ItemStack> item = Args.absent(args[0]) ? stack -> true : Selector.parse(args[0]).items();
			return near(bot(run, call), ItemEntity.class, entity -> item.test(entity.getItem()), radius(args[1], 16, MAX_ENTITY_RADIUS));
		});
		natives.register("container", 30, call -> {
			Object[] args = Args.bind("container", call, 1, "pos");
			AntBot bot = bot(run, call);
			Pos pos = Pos.of(args[0]);
			BlockPos at = blockPos(pos);
			if (run.elsewhere(pos) || new LoadedBlocks(bot.level()).loaded(at) == null) {
				return PyNone.NONE;
			}
			Container container = HopperBlockEntity.getContainerAt(bot.level(), at);
			if (container == null) {
				return PyNone.NONE;
			}
			PyList stacks = new PyList();
			for (int i = 0; i < container.getContainerSize(); i++) {
				ItemStack stack = container.getItem(i);
				if (!stack.isEmpty()) {
					stacks.items.add(stack(stack));
				}
			}
			return stacks;
		});
		natives.register("colony", 35, call -> {
			Args.bind("colony", call, 0);
			return bot(run, call).getColony().<Object>map(SenseApi::colony).orElse(PyNone.NONE);
		});
		natives.register("deaths", call -> {
			Args.bind("deaths", call, 0);
			return (long) bot(run, call).getDeaths();
		});
		natives.register("last_death", call -> {
			Args.bind("last_death", call, 0);
			GlobalPos at = bot(run, call).getLastDeath();
			return at == null ? PyNone.NONE
				: new Pos(at.pos().getX(), at.pos().getY(), at.pos().getZ()).in(at.dimension().identifier().toString());
		});
		natives.register("time", call -> {
			Args.bind("time", call, 0);
			return Math.floorMod(bot(run, call).level().getDefaultClockTime(), 24000L);
		});
		natives.register("is_day", call -> {
			Args.bind("is_day", call, 0);
			return bot(run, call).level().isBrightOutside();
		});
	}

	private static AntBot bot(ScriptRun run, NativeCall call) {
		AntBot bot = run.host().bot();
		if (bot == null || !(bot.level() instanceof ServerLevel)) {
			throw new ScriptError(call.name() + "() needs a bot in a world");
		}
		return bot;
	}

	/** The radius argument {@code value}: {@code fallback} when left out, otherwise an int from 0 to {@code max}. */
	private static int radius(@Nullable Object value, int fallback, int max) {
		if (Args.absent(value)) {
			return fallback;
		}
		long radius = Args.integer(value);
		if (radius < 0 || radius > max) {
			throw new ScriptError("radius must be from 0 to " + max + ", not " + radius);
		}
		return (int) radius;
	}

	/** The stacks the bot carries: its hand first, then its inventory. */
	private static List<ItemStack> carried(AntBot bot) {
		List<ItemStack> stacks = new ArrayList<>();
		if (!bot.getMainHandItem().isEmpty()) {
			stacks.add(bot.getMainHandItem());
		}
		for (ItemStack stack : bot.getInventory().getItems()) {
			if (!stack.isEmpty()) {
				stacks.add(stack);
			}
		}
		return stacks;
	}

	private static long count(AntBot bot, Predicate<ItemStack> item) {
		return carried(bot).stream().filter(item).mapToLong(ItemStack::getCount).sum();
	}

	static Stack stack(ItemStack stack) {
		return new Stack(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount());
	}

	private static BlockInfo block(BlockState state, Pos pos) {
		List<String> tags = state.tags().map(tag -> "#" + tag.location()).sorted().toList();
		Map<String, Object> properties = new HashMap<>();
		for (Property<?> property : state.getProperties()) {
			properties.put(property.getName(), value(state, property));
		}
		return new BlockInfo(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), pos, state.isAir(),
			!state.getFluidState().isEmpty(), tags, properties);
	}

	/** A block state property's value in a script: an int, a bool, or the name the game saves it by ({@code "north"}). */
	private static <T extends Comparable<T>> Object value(BlockState state, Property<T> property) {
		T value = state.getValue(property);
		return switch (value) {
			case Integer i -> (long) i;
			case Boolean b -> b;
			default -> property.getName(value);
		};
	}

	private static BlockPos blockPos(Pos pos) {
		return new BlockPos(clamp(pos.x()), clamp(pos.y()), clamp(pos.z()));
	}

	private static int clamp(long value) {
		return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
	}

	private static <T extends Entity> PyList near(AntBot bot, Class<T> type, Predicate<? super T> what, int radius) {
		double r2 = (double) radius * radius;
		List<T> found = new ArrayList<>(bot.level().getEntitiesOfClass(type, bot.getBoundingBox().inflate(radius),
			entity -> entity != bot && entity.isAlive() && entity.distanceToSqr(bot) <= r2 && what.test(entity)));
		found.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(bot)));
		PyList entities = new PyList();
		found.forEach(entity -> entities.items.add(new EntityInfo(entity)));
		return entities;
	}

	/**
	 * {@code scan} ({@code nearest} false: a list of positions) or {@code find_nearest} (a position or {@code None}):
	 * at once when the tick's {@link #SCAN_BUDGET} allows, otherwise as an action that goes on over the next ticks.
	 */
	private static Object search(ScriptRun run, NativeCall call, Object what, int radius, int limit, boolean nearest) {
		Search search = new Search(run, bot(run, call), radius, limit, Selector.parse(what).blocks(), nearest);
		if (search.tick()) {
			return search.result();
		}
		return run.start(search, call);
	}

	/** A block search, a tick's share of the server's scan budget at a time. */
	private static final class Search implements ScriptRun.Action {
		private final ScriptRun run;
		private final AntBot bot;
		private final Predicate<BlockState> blocks;
		private final boolean nearest;
		private final BlockScan scan;
		private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		/** Made anew each tick: it remembers chunks, which may unload between ticks. */
		private LoadedBlocks loaded;

		Search(ScriptRun run, AntBot bot, int radius, int limit, Predicate<BlockState> blocks, boolean nearest) {
			this.run = run;
			this.bot = bot;
			this.blocks = blocks;
			this.nearest = nearest;
			this.loaded = new LoadedBlocks(bot.level());
			this.scan = new BlockScan(EntityInfo.pos(bot.blockPosition()), radius, limit, this::matches);
		}

		private boolean matches(int x, int y, int z) {
			BlockState state = this.loaded.loaded(this.at.set(x, y, z));
			return state != null && this.blocks.test(state);
		}

		@Override
		public boolean tick() {
			long time = this.bot.level().getGameTime();
			this.loaded = new LoadedBlocks(this.bot.level());
			int before = this.scan.reads();
			boolean done = this.scan.step(SCAN_BUDGET.available(time));
			int reads = this.scan.reads() - before;
			SCAN_BUDGET.spend(time, reads);
			this.run.charge((reads + BLOCKS_PER_INSTRUCTION - 1) / BLOCKS_PER_INSTRUCTION);
			return done;
		}

		@Override
		public Object result() {
			if (this.nearest) {
				return this.scan.found().isEmpty() ? PyNone.NONE : this.scan.found().getFirst();
			}
			return new PyList(new ArrayList<>(this.scan.found()));
		}

		@Override
		public String describe() {
			return (this.nearest ? "find_nearest" : "scan") + " (" + this.scan.reads() + " blocks read)";
		}
	}
}
