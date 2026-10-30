package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotHands;
import io.github.barniebarnie.antcraft.script.lang.Args;
import io.github.barniebarnie.antcraft.script.lang.NativeCall;
import io.github.barniebarnie.antcraft.script.lang.Natives;
import io.github.barniebarnie.antcraft.script.lang.PyDict;
import io.github.barniebarnie.antcraft.script.lang.PyList;
import io.github.barniebarnie.antcraft.script.lang.PyTuple;
import io.github.barniebarnie.antcraft.script.lang.ScriptError;
import io.github.barniebarnie.antcraft.script.lang.Values;
import io.github.barniebarnie.antcraft.work.GameRecipeBook;
import io.github.barniebarnie.antcraft.work.Storage;
import io.github.barniebarnie.antcraft.work.TaskPlanner;
import java.util.Comparator;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The actions of the bot API that use containers and stations (docs/reference/index.md, Containers and crafting): {@code put}, {@code take},
 * {@code craft} and {@code smelt}, and the sense {@code needs}. Those that work at a block walk into reach of it first, as the block actions of
 * {@link WorkApi} do; each returns a {@link Result}.
 */
public final class CraftApi {
	/** How many ticks {@code smelt} waits without the furnace's output growing before it gives up with {@code timeout}. */
	static final int SMELT_TIMEOUT = 1200;
	/** The most {@code needs} plans for, so the planner's counts stay far from overflowing. */
	static final long MAX_NEEDS = 1_000_000;
	/**
	 * What {@code needs} gathers rather than makes: the item tag {@code antcraft:gatherable}. One object for every call, as
	 * {@link TaskPlanner#plan} keeps plans by its identity.
	 */
	private static final TagKey<Item> GATHERABLE_TAG = TagKey.create(Registries.ITEM, AntCraft.id("gatherable"));
	private static final Predicate<Item> GATHERABLE = item -> item.builtInRegistryHolder().is(GATHERABLE_TAG);
	/** Instructions a {@code needs} call costs besides the call, from its time in the benchmark (#441). */
	static final long NEEDS_COST = 3000;

	private CraftApi() {
	}

	static void register(Natives natives, ScriptRun run) {
		natives.register("put", call -> {
			Object[] args = Args.bind("put", call, 2, "container", "item", "n");
			Pos target = Pos.of(args[0]);
			Selector item = Selector.parse(args[1]);
			return run.start(new Put(run, WorkApi.bot(run, call), target, item, count("put", args[2])), call, target);
		});
		natives.register("take", call -> {
			Object[] args = Args.bind("take", call, 2, "container", "item", "n");
			Pos target = Pos.of(args[0]);
			Selector item = Selector.parse(args[1]);
			return run.start(new Take(run, WorkApi.bot(run, call), target, item, count("take", args[2])), call, target);
		});
		natives.register("craft", 40, call -> {
			Object[] args = Args.bind("craft", call, 1, "item", "n", "table");
			Item item = oneItem("craft", args[0]);
			long n = Args.absent(args[1]) ? 1 : Args.integer(args[1]);
			if (n < 1) {
				throw new ScriptError("craft() n must be at least 1, not " + n);
			}
			Pos table = Args.absent(args[2]) ? null : Pos.of(args[2]);
			return craft(run, WorkApi.bot(run, call), item, n, table, call);
		});
		natives.register("needs", NEEDS_COST, call -> {
			Object[] args = Args.bind("needs", call, 1, "item", "n", "also");
			Item item = oneItem("needs", args[0]);
			long n = Args.absent(args[1]) ? 1 : Args.integer(args[1]);
			List<Item> also = Args.absent(args[2]) ? List.of() : Values.elements(args[2]).stream().map(one -> oneItem("needs", one)).toList();
			AntBot bot = WorkApi.bot(run, call);
			return needs(item, n, Storage.stock(BotHands.carried(bot)), also, GameRecipeBook.of(((ServerLevel) bot.level()).getServer()), GATHERABLE);
		});
		natives.register("smelt", 60, call -> {
			Object[] args = Args.bind("smelt", call, 1, "item", "n", "furnace", "fuel");
			Selector item = Selector.parse(args[0]);
			long n = Args.absent(args[1]) ? 1 : Args.integer(args[1]);
			if (n < 1) {
				throw new ScriptError("smelt() n must be at least 1, not " + n);
			}
			Pos furnace = Args.absent(args[2]) ? null : Pos.of(args[2]);
			Selector fuel = Args.absent(args[3]) ? null : Selector.parse(args[3]);
			return smelt(run, WorkApi.bot(run, call), item, n, furnace, fuel, call);
		});
	}

	/**
	 * {@code needs(item, n, also)} (docs/reference/crafting/needs.md): what to gather and craft to make one of each of
	 * {@code also} and then {@code n} of {@code item} from {@code stock}.
	 */
	static PyDict needs(Item item, long n, Map<Item, Integer> stock, List<Item> also, TaskPlanner.RecipeBook<Item> book, Predicate<Item> gatherable) {
		if (n < 1 || n > MAX_NEEDS) {
			throw new ScriptError("needs() n must be at least 1" + (n < 1 ? "" : " and at most " + MAX_NEEDS) + ", not " + n);
		}
		return needs(TaskPlanner.plan(item, (int) n, also, stock, book, gatherable), i -> BuiltInRegistries.ITEM.getKey(i).toString());
	}

	/**
	 * A plan as {@code needs} returns it: {@code gather}, what the plan gathers or misses, by id, and {@code crafts}, its
	 * steps as {@code (item, n, how)} tuples, and {@code table}, whether a crafting step needs a crafting table's 3x3 grid.
	 */
	static <I> PyDict needs(TaskPlanner.Plan<I> plan, Function<I, String> id) {
		Map<String, Long> gather = new LinkedHashMap<>();
		plan.toGather().forEach((item, count) -> gather.merge(id.apply(item), (long) count, Long::sum));
		plan.missing().forEach((item, count) -> gather.merge(id.apply(item), (long) count, Long::sum));
		PyDict gatherDict = new PyDict();
		gather.forEach(gatherDict::put);
		List<Object> crafts = new ArrayList<>();
		PyDict inputs = new PyDict();
		boolean table = false;
		for (TaskPlanner.Craft<I> craft : plan.crafts()) {
			TaskPlanner.Recipe<I> recipe = craft.recipe();
			table |= !recipe.smelting() && recipe.large();
			crafts.add(PyTuple.of(id.apply(recipe.output()), (long) craft.times() * recipe.count(), recipe.smelting() ? "smelt" : "craft"));
			if (recipe.smelting()) {
				// What smelt() takes: the input, any of the recipe's one ingredient.
				List<Object> options = new ArrayList<>();
				recipe.ingredients().keySet().forEach(set -> set.forEach(item -> options.add(id.apply(item))));
				inputs.put(id.apply(recipe.output()), new PyList(options));
			}
		}
		PyDict needs = new PyDict();
		needs.put("gather", gatherDict);
		needs.put("crafts", new PyList(crafts));
		needs.put("inputs", inputs);
		needs.put("table", table);
		return needs;
	}

	/** The {@code n} of {@code put} and {@code take}: every matching item when absent, else at least 1. */
	private static long count(String function, Object n) {
		if (Args.absent(n)) {
			return Long.MAX_VALUE;
		}
		long count = Args.integer(n);
		if (count < 1) {
			throw new ScriptError(function + "() n must be at least 1, not " + count);
		}
		return count;
	}

	/** The one item a selector names, such as {@code "oak_planks"}; a tag or a list fails the script. */
	private static Item oneItem(String function, Object value) {
		Selector selector = Selector.parse(value);
		if (!selector.tags().isEmpty() || selector.ids().size() != 1) {
			throw new ScriptError(function + "() needs one item, not " + selector);
		}
		selector.items(); // fails on an unknown item
		return BuiltInRegistries.ITEM.getValue(Identifier.parse(selector.ids().getFirst()));
	}

	/** The container at {@code pos}, a double chest as a whole; null if there is none. */
	private static @Nullable Container containerAt(ServerLevel level, BlockPos pos) {
		return HopperBlockEntity.getContainerAt(level, pos);
	}

	/** The nearest block {@code what} matches that the bot sees within reach where it stands; null if there is none. */
	private static @Nullable Pos inReach(ScriptRun run, AntBot bot, Predicate<BlockState> what) {
		Vec3 eyes = bot.getEyePosition();
		BlockPos center = BlockPos.containing(eyes);
		int r = (int) Math.ceil(MoveApi.REACH);
		if (!mayHave(bot.level(), center.offset(-r, -r, -r), center.offset(r, r, r), what)) {
			return null;
		}
		List<BlockPos> found = StreamSupport.stream(BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r)).spliterator(), false)
			.filter(pos -> what.test(bot.level().getBlockState(pos)))
			.map(BlockPos::immutable)
			.sorted(Comparator.comparingDouble((BlockPos pos) -> Vec3.atCenterOf(pos).distanceToSqr(eyes)))
			.toList();
		for (BlockPos pos : found) {
			Pos at = EntityInfo.pos(pos);
			if (run.host().body().inReach(at)) {
				return at;
			}
		}
		return null;
	}

	/**
	 * Whether a block {@code what} matches may be in the box from {@code min} to {@code max}: false if the palette of no
	 * chunk section the box touches has such a block, which spares reading its blocks one by one (#347).
	 */
	private static boolean mayHave(Level level, BlockPos min, BlockPos max, Predicate<BlockState> what) {
		for (int x = SectionPos.blockToSectionCoord(min.getX()); x <= SectionPos.blockToSectionCoord(max.getX()); x++) {
			for (int z = SectionPos.blockToSectionCoord(min.getZ()); z <= SectionPos.blockToSectionCoord(max.getZ()); z++) {
				ChunkAccess chunk = level.getChunk(x, z);
				for (int y = SectionPos.blockToSectionCoord(min.getY()); y <= SectionPos.blockToSectionCoord(max.getY()); y++) {
					int index = chunk.getSectionIndexFromSectionY(y);
					if (index >= 0 && index < chunk.getSectionsCount() && chunk.getSection(index).maybeHas(what)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static void swing(AntBot bot, BlockPos at) {
		bot.getLookControl().setLookAt(Vec3.atCenterOf(at));
		bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
	}

	// put and take

	/** {@code put(container, item, n=None)}: moves up to {@code n} of the items {@code item} selects into the container. */
	private static final class Put extends WorkApi.OnBlock {
		private final Selector selector;
		private final Predicate<ItemStack> item;
		private final long n;

		Put(ScriptRun run, AntBot bot, Pos target, Selector selector, long n) {
			super(run, bot, target);
			this.selector = selector;
			this.item = selector.items();
			this.n = n;
		}

		@Override
		@Nullable Result before() {
			return BotHands.find(this.bot, this.item) == null ? Result.fail(Result.NO_ITEM) : super.before();
		}

		@Override
		@Nullable Result check(BlockState state) {
			return containerAt(this.level, this.pos) == null ? Result.fail(Result.TARGET_GONE) : null;
		}

		@Override
		Result work(BlockState state) {
			Container to = containerAt(this.level, this.pos);
			int wanted = (int) Math.min(this.n, BotHands.count(this.bot, this.item));
			int moved = Storage.moveItems(BotHands.carried(this.bot), to, this.item, wanted);
			swing(this.bot, this.pos);
			return moved < wanted ? Result.fail(Result.NO_SPACE, (long) moved) : Result.ok((long) moved);
		}

		@Override
		public String describe() {
			return "put(" + this.target + ", " + this.selector + (this.n == Long.MAX_VALUE ? "" : ", " + this.n) + ")";
		}
	}

	/** {@code take(container, item, n=None)}: moves up to {@code n} of the items {@code item} selects out of the container. */
	private static final class Take extends WorkApi.OnBlock {
		private final Selector selector;
		private final Predicate<ItemStack> item;
		private final long n;

		Take(ScriptRun run, AntBot bot, Pos target, Selector selector, long n) {
			super(run, bot, target);
			this.selector = selector;
			this.item = selector.items();
			this.n = n;
		}

		@Override
		@Nullable Result check(BlockState state) {
			return containerAt(this.level, this.pos) == null ? Result.fail(Result.TARGET_GONE) : null;
		}

		@Override
		Result work(BlockState state) {
			Container from = containerAt(this.level, this.pos);
			long stored = 0;
			for (int i = 0; i < from.getContainerSize(); i++) {
				ItemStack stack = from.getItem(i);
				if (!stack.isEmpty() && this.item.test(stack)) {
					stored += stack.getCount();
				}
			}
			if (stored == 0) {
				return Result.fail(Result.NO_ITEM);
			}
			int wanted = (int) Math.min(this.n, stored);
			int moved = Storage.moveItems(from, BotHands.carried(this.bot), this.item, wanted);
			swing(this.bot, this.pos);
			return moved < wanted ? Result.fail(Result.NO_SPACE, (long) moved) : Result.ok((long) moved);
		}

		@Override
		public String describe() {
			return "take(" + this.target + ", " + this.selector + (this.n == Long.MAX_VALUE ? "" : ", " + this.n) + ")";
		}
	}

	// craft

	/**
	 * {@code craft(item, n=1, table=None)}: works one recipe for {@code item} from carried items until it made at least
	 * {@code n}, the first recipe whose ingredients the bot carries. A 2x2 recipe is worked at once; a 3x3 one at the
	 * crafting table at {@code table}, walked to, or at one within reach.
	 */
	private static Object craft(ScriptRun run, AntBot bot, Item item, long n, @Nullable Pos table, NativeCall call) {
		String describe = "craft(" + Values.repr(BuiltInRegistries.ITEM.getKey(item).toString()) + ", " + n + (table == null ? "" : ", table=" + table) + ")";
		if (run.elsewhere(table)) {
			return run.done(describe, Result.fail(Result.WRONG_DIMENSION));
		}
		List<TaskPlanner.Recipe<Item>> recipes = GameRecipeBook.of(((ServerLevel) bot.level()).getServer()).recipesFor(item).stream()
			.filter(recipe -> !recipe.smelting()).toList();
		if (recipes.isEmpty()) {
			return run.done(describe, Result.fail(Result.NO_RECIPE));
		}
		Container carried = BotHands.carried(bot);
		Optional<TaskPlanner.Recipe<Item>> recipe = recipes.stream().filter(candidate -> Storage.canCraft(carried, candidate, 1)).findFirst();
		if (recipe.isEmpty()) {
			return run.done(describe, Result.fail(Result.MISSING_INGREDIENTS));
		}
		if (!recipe.get().large()) {
			return run.done(describe, craftTimes(bot, recipe.get(), n));
		}
		Pos at = table != null ? table : inReach(run, bot, state -> state.is(Blocks.CRAFTING_TABLE));
		if (at == null) {
			return run.done(describe, Result.fail(Result.NO_TABLE));
		}
		return run.start(new AtTable(run, bot, at, recipe.get(), n, describe), call);
	}

	/** Works {@code recipe} until it made at least {@code n} items; its value is how many it made. */
	private static Result craftTimes(AntBot bot, TaskPlanner.Recipe<Item> recipe, long n) {
		Container carried = BotHands.carried(bot);
		long made = 0;
		while (made < n) {
			if (!Storage.canCraft(carried, recipe, 1)) {
				return Result.fail(Result.MISSING_INGREDIENTS, made);
			}
			if (!Storage.craft(carried, recipe)) {
				return Result.fail(Result.NO_SPACE, made);
			}
			made += recipe.count();
		}
		return Result.ok(made);
	}

	/** A 3x3 recipe at a crafting table. */
	private static final class AtTable extends WorkApi.OnBlock {
		private final TaskPlanner.Recipe<Item> recipe;
		private final long n;
		private final String describe;

		AtTable(ScriptRun run, AntBot bot, Pos table, TaskPlanner.Recipe<Item> recipe, long n, String describe) {
			super(run, bot, table);
			this.recipe = recipe;
			this.n = n;
			this.describe = describe;
		}

		@Override
		@Nullable Result check(BlockState state) {
			return state.is(Blocks.CRAFTING_TABLE) ? null : Result.fail(Result.NO_TABLE);
		}

		@Override
		Result work(BlockState state) {
			swing(this.bot, this.pos);
			return craftTimes(this.bot, this.recipe, this.n);
		}

		@Override
		public String describe() {
			return this.describe;
		}
	}

	// smelt

	/**
	 * {@code smelt(item, n=1, furnace=None, fuel=None)}: loads up to {@code n} of a carried item {@code item} selects into
	 * the furnace, blast furnace or smoker at {@code furnace}, walked to, or one within reach with a recipe for it; burns
	 * the fuel {@code fuel} selects, or else the best carried fuel (coal, charcoal, then logs), adding one at a time as
	 * the fire goes out; waits by it until the input is smelted and takes the output. Its value is how many items it took
	 * out.
	 */
	private static Object smelt(ScriptRun run, AntBot bot, Selector selector, long n, @Nullable Pos furnace, @Nullable Selector fuelSelector,
			NativeCall call) {
		String describe = "smelt(" + selector + ", " + n + (furnace == null ? "" : ", furnace=" + furnace) + (fuelSelector == null ? "" : ", fuel=" + fuelSelector) + ")";
		if (run.elsewhere(furnace)) {
			return run.done(describe, Result.fail(Result.WRONG_DIMENSION));
		}
		ServerLevel level = (ServerLevel) bot.level();
		Predicate<ItemStack> wanted = selector.items();
		Predicate<ItemStack> given = fuelSelector == null ? null : fuelSelector.items(); // fails at once on an unknown item
		Container carried = BotHands.carried(bot);
		boolean any = false;
		boolean smeltable = false;
		Set<Item> tried = new HashSet<>();
		// With furnace, items the station there cannot cook are skipped too, if its chunk is loaded to tell.
		BlockPos station = furnace == null ? null : WorkApi.blockPos(furnace);
		RecipeType<? extends AbstractCookingRecipe> stationType = station != null && level.isLoaded(station) ? cooking(level.getBlockState(station)) : null;
		for (int i = 0; i < carried.getContainerSize(); i++) {
			ItemStack stack = carried.getItem(i);
			if (stack.isEmpty() || !wanted.test(stack)) {
				continue;
			}
			any = true;
			Item input = stack.getItem();
			if (!tried.add(input) || smelted(level, stack).isEmpty()) {
				continue;
			}
			smeltable = true;
			if (stationType != null && cooked(level, stationType, new ItemStack(input)).isEmpty()) {
				continue;
			}
			// Without furnace, each item the selector takes gets its turn: the station in reach may cook only some.
			Pos at = furnace != null ? furnace : inReach(run, bot, state -> {
				RecipeType<? extends AbstractCookingRecipe> type = cooking(state);
				return type != null && !cooked(level, type, new ItemStack(input)).isEmpty();
			});
			if (at != null) {
				// Only now: bestFuel reads the inventory once for each fuel item (#347).
				Predicate<ItemStack> fuel = given == null ? bestFuel(level, bot) : given;
				return run.start(new Smelt(run, bot, at, input, (int) Math.min(n, input.getDefaultMaxStackSize()),
					fuel.and(burnable -> burnable.has(DataComponents.COOKING_FUEL)), describe), call);
			}
		}
		if (smeltable) {
			return run.done(describe, Result.fail(stationType != null ? Result.NO_RECIPE : Result.NO_FURNACE));
		}
		boolean recipe = any || !selector.tags().isEmpty()
			|| selector.ids().stream().anyMatch(id -> !smelted(level, new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)))).isEmpty());
		return run.done(describe, Result.fail(recipe && !any ? Result.MISSING_INGREDIENTS : Result.NO_RECIPE));
	}

	/** What the furnace, blast furnace or smoker {@code state} is cooks: its recipe type; null for any other block. */
	private static @Nullable RecipeType<? extends AbstractCookingRecipe> cooking(BlockState state) {
		if (state.is(Blocks.FURNACE)) {
			return RecipeType.SMELTING;
		}
		if (state.is(Blocks.BLAST_FURNACE)) {
			return RecipeType.BLASTING;
		}
		return state.is(Blocks.SMOKER) ? RecipeType.SMOKING : null;
	}

	/** What the furnace, blast furnace or smoker makes of {@code stack}; empty if none does. */
	private static ItemStack smelted(ServerLevel level, ItemStack stack) {
		// Not a constant: the unit tests load this class without the game's registries.
		for (RecipeType<? extends AbstractCookingRecipe> type : List.of(RecipeType.SMELTING, RecipeType.BLASTING, RecipeType.SMOKING)) {
			ItemStack output = cooked(level, type, stack);
			if (!output.isEmpty()) {
				return output;
			}
		}
		return ItemStack.EMPTY;
	}

	/** What a furnace cooking recipes of {@code type} makes of {@code stack}; empty if nothing. */
	private static ItemStack cooked(ServerLevel level, RecipeType<? extends AbstractCookingRecipe> type, ItemStack stack) {
		SingleRecipeInput input = new SingleRecipeInput(stack);
		return level.getServer().getRecipeManager().getRecipeFor(type, input, level)
			.map(recipe -> recipe.value().assemble(input)).orElse(ItemStack.EMPTY);
	}

	/** The best fuel the bot carries, in the order of {@link GameRecipeBook#fuels}; one that matches nothing if it carries none. */
	private static Predicate<ItemStack> bestFuel(ServerLevel level, AntBot bot) {
		for (TaskPlanner.Fuel<Item> fuel : GameRecipeBook.of(level.getServer()).fuels()) {
			for (Item item : fuel.items()) {
				if (BotHands.count(bot, stack -> stack.is(item)) > 0) {
					return stack -> stack.is(item);
				}
			}
		}
		return stack -> false;
	}

	/** Loading a furnace, waiting by it and taking the output out. */
	private static final class Smelt extends WorkApi.OnBlock {
		private static final int INPUT = Storage.FURNACE_INPUT;
		private static final int FUEL = Storage.FURNACE_FUEL;
		private static final int RESULT = Storage.FURNACE_RESULT;

		private final Item input;
		/** What this furnace makes of the input, known once the bot is at it. */
		private Item output = Items.AIR;
		private final int n;
		private final Predicate<ItemStack> fuel;
		private final String describe;
		private boolean loaded;
		private int outputSeen;
		private int idle;

		Smelt(ScriptRun run, AntBot bot, Pos furnace, Item input, int n, Predicate<ItemStack> fuel, String describe) {
			super(run, bot, furnace);
			this.input = input;
			this.n = n;
			this.fuel = fuel;
			this.describe = describe;
		}

		/** A furnace, blast furnace or smoker, which has a recipe for the input: a smoker smelts no ore. */
		@Override
		@Nullable Result check(BlockState state) {
			RecipeType<? extends AbstractCookingRecipe> type = cooking(state);
			if (type == null || !(this.level.getBlockEntity(this.pos) instanceof AbstractFurnaceBlockEntity)) {
				return Result.fail(Result.NO_FURNACE);
			}
			return cooked(this.level, type, new ItemStack(this.input)).isEmpty() ? Result.fail(Result.NO_RECIPE) : null;
		}

		@Override
		@Nullable Result work(BlockState state) {
			AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) this.level.getBlockEntity(this.pos);
			if (!this.loaded) {
				this.output = cooked(this.level, Objects.requireNonNull(cooking(state)), new ItemStack(this.input)).getItem();
			}
			return this.loaded ? this.watch(furnace) : this.load(furnace);
		}

		private @Nullable Result load(AbstractFurnaceBlockEntity furnace) {
			ItemStack in = furnace.getItem(INPUT);
			ItemStack out = furnace.getItem(RESULT);
			if (!in.isEmpty() && !in.is(this.input) || !out.isEmpty() && !out.is(this.output)) {
				return Result.fail(Result.BLOCKED);
			}
			Container carried = BotHands.carried(this.bot);
			int amount = (int) Math.min(this.n, Math.min(carried.countItem(this.input), this.input.getDefaultMaxStackSize() - in.getCount()));
			if (carried.countItem(this.input) == 0) {
				return Result.fail(Result.MISSING_INGREDIENTS);
			}
			if (amount <= 0) {
				return Result.fail(Result.BLOCKED);
			}
			if (!isLit(furnace) && furnace.getItem(FUEL).isEmpty() && BotHands.find(this.bot, this.fuel) == null) {
				return Result.fail(Result.MISSING_INGREDIENTS);
			}
			int taken = Storage.take(carried, this.input, amount);
			furnace.setItem(INPUT, new ItemStack(this.input, in.getCount() + taken));
			this.refuel(furnace);
			furnace.setChanged();
			this.loaded = true;
			this.outputSeen = out.getCount();
			swing(this.bot, this.pos);
			return null;
		}

		private @Nullable Result watch(AbstractFurnaceBlockEntity furnace) {
			if (!furnace.getItem(INPUT).is(this.input)) {
				return this.finish(furnace, null);
			}
			if (!this.refuel(furnace)) {
				return this.finish(furnace, Result.MISSING_INGREDIENTS);
			}
			int out = furnace.getItem(RESULT).getCount();
			if (out != this.outputSeen) {
				this.outputSeen = out;
				this.idle = 0;
			} else if (++this.idle > SMELT_TIMEOUT) {
				return this.finish(furnace, Result.TIMEOUT);
			}
			return null;
		}

		/** Puts one more fuel item in when the fire is out and the fuel slot empty; false when the bot has none left. */
		private boolean refuel(AbstractFurnaceBlockEntity furnace) {
			if (isLit(furnace) || !furnace.getItem(FUEL).isEmpty()) {
				return true;
			}
			Container carried = BotHands.carried(this.bot);
			for (int i = 0; i < carried.getContainerSize(); i++) {
				ItemStack stack = carried.getItem(i);
				if (!stack.isEmpty() && this.fuel.test(stack)) {
					furnace.setItem(FUEL, carried.removeItem(i, 1));
					furnace.setChanged();
					return true;
				}
			}
			return false;
		}

		/** Takes the output out, and on a failure what is left of the input; the result fails with {@code reason} if given. */
		private Result finish(AbstractFurnaceBlockEntity furnace, @Nullable String reason) {
			long taken = 0;
			boolean full = false;
			ItemStack out = furnace.getItem(RESULT);
			if (out.is(this.output)) {
				int before = out.getCount();
				ItemStack rest = HopperBlockEntity.addItem(null, BotHands.carried(this.bot), out.copy(), null);
				furnace.setItem(RESULT, rest);
				taken = before - rest.getCount();
				full = !rest.isEmpty();
			}
			if (reason != null && furnace.getItem(INPUT).is(this.input)) {
				furnace.setItem(INPUT, HopperBlockEntity.addItem(null, BotHands.carried(this.bot), furnace.getItem(INPUT).copy(), null));
			}
			furnace.setChanged();
			swing(this.bot, this.pos);
			if (full) {
				return Result.fail(Result.NO_SPACE, taken);
			}
			return reason == null ? Result.ok(taken) : Result.fail(reason, taken);
		}

		private static boolean isLit(AbstractFurnaceBlockEntity furnace) {
			return furnace.getBlockState().getValue(AbstractFurnaceBlock.LIT);
		}

		@Override
		public String describe() {
			return this.describe;
		}
	}
}
