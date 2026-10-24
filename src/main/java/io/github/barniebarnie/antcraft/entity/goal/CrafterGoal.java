package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.colony.BotActivation;
import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.colony.TaskPlanner;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModItems;
import io.github.barniebarnie.antcraft.work.Crafting;
import io.github.barniebarnie.antcraft.work.GameRecipeBook;
import io.github.barniebarnie.antcraft.work.Gatherables;
import io.github.barniebarnie.antcraft.work.Storage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Crafting work, as the executor of craft and Bot Core jobs for {@link WorkerGoal}, so any colony bot crafts.
 * <p>
 * A craft job at the core works the steps of the colony's orders that make its item, from the core's stock and
 * into the core, ingredients first. The core serves as a crafting table. Smelting steps run in a furnace next
 * to the core, which the bot places if there is none, and it waits by the furnace for its item. A craft whose
 * result has no room in the core waits, and so does furnace output, while other crafts go on.
 * <p>
 * A craft job elsewhere is worked from the bot's own inventory, and the bot keeps what it makes unless the job's
 * target is a container to deliver to. A 3x3 recipe needs a crafting table standing there, or one the bot carries:
 * it places it next to itself, crafts and picks it back up. Smelting works the same way with a furnace, by which
 * the bot waits until its input is smelted.
 * <p>
 * A Bot Core job turns the Bot Cores stored in the core into new bots.
 */
public class CrafterGoal extends ColonyWorkGoal implements WorkerGoal.Executor {
	private static final int CRAFT_TICKS = 10;
	private static final int FURNACE_RANGE = Storage.FURNACE_RANGE;
	private static final int FURNACE_INPUT = Storage.FURNACE_INPUT;
	private static final int FURNACE_FUEL = Storage.FURNACE_FUEL;
	private static final int FURNACE_RESULT = Storage.FURNACE_RESULT;
	/** Ticks a bot waits by its furnace beyond the smelting time, in case the fire went out. */
	private static final int SMELT_SLACK_TICKS = 200;

	/** The job begun through {@link #begin}, until it is done. */
	private @Nullable Job job;
	private WorkerGoal.@Nullable Outcome outcome;
	/** The recipe a craft job away from the core works, chosen when it began. */
	private TaskPlanner.@Nullable Recipe<Item> recipe;
	/** The station the bot placed for the job, to pick back up. */
	private @Nullable BlockPos placed;
	/** Whether the bot loaded the furnace of a smelting job away from the core. */
	private boolean loaded;
	/** Game time until which the bot waits by that furnace. */
	private long smeltUntil;

	public CrafterGoal(AntBot bot) {
		super(bot);
	}

	@Override
	public boolean isReady(ServerLevel level, Colony colony, Job job) {
		return switch (job.type()) {
			case ACTIVATE_BOT -> this.activationSpot(level, colony).isPresent();
			case CRAFT -> this.isAtCore(colony, job) ? this.coreStep(level, colony, job) != null : this.fieldRecipe(level, job).isPresent();
			default -> false;
		};
	}

	@Override
	public boolean begin(ServerLevel level, Job job) {
		this.outcome = null;
		Optional<Colony> colony = this.colony(level);
		if (colony.isEmpty() || job.item().isEmpty() && job.type() == JobType.CRAFT) {
			return false;
		}
		boolean todo;
		if (job.type() == JobType.CRAFT && !this.isAtCore(colony.get(), job)) {
			this.recipe = this.fieldRecipe(level, job).orElse(null);
			todo = this.recipe != null;
		} else {
			todo = this.isReady(level, colony.get(), job) || job.type() == JobType.CRAFT && this.furnaceBusy(level, colony.get(), job.item().get());
		}
		if (!todo) {
			return false;
		}
		this.job = job;
		this.loaded = false;
		this.resetMovement();
		return true;
	}

	@Override
	public boolean work(ServerLevel level) {
		Optional<Colony> colony = this.colony(level);
		if (colony.isEmpty() || this.job == null) {
			return false;
		}
		this.bot.setWorking(true);
		if (this.cooldown > 0) {
			this.cooldown--;
			return true;
		}
		if (this.job.type() == JobType.ACTIVATE_BOT) {
			this.activate(level, colony.get());
		} else if (this.isAtCore(colony.get(), this.job)) {
			this.craftAtCore(level, colony.get(), this.job);
		} else {
			this.craftInField(level, this.job);
		}
		return true;
	}

	@Override
	public Optional<WorkerGoal.Outcome> takeOutcome() {
		Optional<WorkerGoal.Outcome> taken = Optional.ofNullable(this.outcome);
		this.outcome = null;
		return taken;
	}

	@Override
	public void reset() {
		if (this.placed != null && this.bot.level() instanceof ServerLevel level) {
			this.takeBack(level);
		}
		this.job = null;
		this.outcome = null;
		this.recipe = null;
		this.loaded = false;
		this.resetMovement();
	}

	private void endJob(WorkerGoal.Outcome how) {
		this.outcome = how;
		this.job = null;
		this.recipe = null;
		this.bot.setWorking(false);
	}

	private boolean isAtCore(Colony colony, Job job) {
		return job.target().equals(colony.core());
	}

	private Optional<Colony> colony(ServerLevel level) {
		return Optional.ofNullable(this.bot.getColonyId()).flatMap(id -> ColonyManager.get(level).byId(id));
	}

	private void swingAt(BlockPos at) {
		this.bot.getLookControl().setLookAt(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
		this.bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		this.cooldown = CRAFT_TICKS;
	}

	// Bot Cores

	private Optional<BlockPos> activationSpot(ServerLevel level, Colony colony) {
		Container core = Storage.containerAt(level, colony.core());
		if (core == null || core.countItem(ModItems.BOT_CORE) == 0 || !BotActivation.canActivate(level)) {
			return Optional.empty();
		}
		return BotActivation.freeSpotNear(level, colony.core().above());
	}

	/** Turns the stored Bot Cores into bots, one at a time. */
	private void activate(ServerLevel level, Colony colony) {
		Optional<BlockPos> spot = this.activationSpot(level, colony);
		if (spot.isEmpty()) {
			this.endJob(WorkerGoal.Outcome.COMPLETE);
			return;
		}
		if (this.reach(colony.core())) {
			this.swingAt(colony.core());
			Container core = Storage.containerAt(level, colony.core());
			if (core != null && BotActivation.activate(level, spot.get()) != null) {
				Storage.take(core, ModItems.BOT_CORE, 1);
			}
		}
	}

	// Crafting at the core

	/**
	 * The plan of each open order against the core's stock, and what waits in the colony furnace, which is
	 * still the colony's. An order asks for new items, so what is stored of the ordered item does not count toward it.
	 */
	public static Map<Item, TaskPlanner.Plan<Item>> orderPlans(ServerLevel level, Colony colony, Container core) {
		GameRecipeBook book = GameRecipeBook.of(level.getServer());
		AbstractFurnaceBlockEntity furnace = Storage.colonyFurnace(level, colony.core());
		Map<Item, Integer> stock = Storage.stock(core);
		if (furnace != null) {
			for (int slot : new int[] {FURNACE_INPUT, FURNACE_FUEL, FURNACE_RESULT}) {
				ItemStack stack = furnace.getItem(slot);
				if (!stack.isEmpty()) {
					stock.merge(stack.getItem(), stack.getCount(), Integer::sum);
				}
			}
		}
		return colony.orderPlans(stock, book);
	}

	/**
	 * What the colony crafts at its core: each item a step of an open order's plan makes, and the colony
	 * furnace's output waiting to be taken into the core. One craft job is posted for each.
	 */
	public static Set<Item> coreCrafts(ServerLevel level, Colony colony, Container core) {
		Set<Item> crafts = new LinkedHashSet<>();
		orderPlans(level, colony, core).values().forEach(plan -> plan.crafts().forEach(craft -> crafts.add(craft.recipe().output())));
		AbstractFurnaceBlockEntity furnace = Storage.colonyFurnace(level, colony.core());
		if (furnace != null && !furnace.getItem(FURNACE_RESULT).isEmpty()) {
			crafts.add(furnace.getItem(FURNACE_RESULT).getItem());
		}
		return crafts;
	}

	private void craftAtCore(ServerLevel level, Colony colony, Job job) {
		Item item = job.item().orElseThrow();
		if (!this.reach(colony.core())) {
			return;
		}
		Runnable step = this.coreStep(level, colony, job);
		if (step != null) {
			this.swingAt(colony.core());
			step.run();
		} else if (this.furnaceBusy(level, colony, item)) {
			// Waits by the core for the furnace to smelt its item.
			this.cooldown = IDLE_TICKS;
		} else {
			this.endJob(WorkerGoal.Outcome.COMPLETE);
		}
	}

	/**
	 * The next thing to do at the core for the job's item, or null if there is nothing now. That is taking the
	 * item out of the colony furnace, or the first step of an order's plan that can be worked now, when it makes
	 * the item: steps go in the plan's order, ingredients first.
	 */
	private @Nullable Runnable coreStep(ServerLevel level, Colony colony, Job job) {
		Container core = Storage.containerAt(level, colony.core());
		Item item = job.item().orElse(null);
		if (core == null || item == null) {
			return null;
		}
		AbstractFurnaceBlockEntity furnace = Storage.colonyFurnace(level, colony.core());
		// Output the core has no room for waits in the furnace.
		if (furnace != null && furnace.getItem(FURNACE_RESULT).is(item) && Storage.hasRoomFor(core, furnace.getItem(FURNACE_RESULT))) {
			return () -> collect(colony, core, furnace);
		}
		GameRecipeBook book = GameRecipeBook.of(level.getServer());
		for (Map.Entry<Item, TaskPlanner.Plan<Item>> order : orderPlans(level, colony, core).entrySet()) {
			for (TaskPlanner.Craft<Item> craft : order.getValue().crafts()) {
				Runnable step = null;
				if (craft.recipe().smelting()) {
					step = this.smeltWork(level, colony, core, furnace, craft, book);
				} else if (Storage.canCraft(core, craft.recipe(), 1) && Storage.fits(core, craft.recipe())) {
					step = () -> {
						if (Storage.craft(core, craft.recipe()) && craft.recipe().output() == order.getKey()) {
							colony.fulfil(order.getKey(), craft.recipe().count());
						}
					};
				}
				if (step != null) {
					if (craft.recipe().output() == item) {
						return step;
					}
					// An earlier step of this order comes first.
					break;
				}
			}
		}
		return null;
	}

	/** Whether the colony furnace is smelting something into {@code item}. */
	private boolean furnaceBusy(ServerLevel level, Colony colony, Item item) {
		AbstractFurnaceBlockEntity furnace = Storage.colonyFurnace(level, colony.core());
		if (furnace == null) {
			return false;
		}
		ItemStack input = furnace.getItem(FURNACE_INPUT);
		return !input.isEmpty() && (isLit(furnace) || !furnace.getItem(FURNACE_FUEL).isEmpty())
			&& GameRecipeBook.of(level.getServer()).recipesFor(item).stream()
				.anyMatch(recipe -> recipe.smelting() && recipe.ingredients().keySet().stream().anyMatch(options -> options.contains(input.getItem())));
	}

	/** The next step of a smelting craft, or null if it has to wait. */
	private @Nullable Runnable smeltWork(ServerLevel level, Colony colony, Container core, @Nullable AbstractFurnaceBlockEntity furnace,
			TaskPlanner.Craft<Item> craft, GameRecipeBook book) {
		Set<Item> inputs = craft.recipe().ingredients().keySet().iterator().next();
		Item input = inputs.stream().filter(item -> core.countItem(item) > 0).findFirst().orElse(null);
		if (furnace == null) {
			return input == null ? null : furnaceWork(level, colony, core, book);
		}

		ItemStack loaded = furnace.getItem(FURNACE_INPUT);
		if (!loaded.isEmpty()) {
			if (!inputs.contains(loaded.getItem()) || isLit(furnace) || !furnace.getItem(FURNACE_FUEL).isEmpty()) {
				// Busy with this or something else.
				return null;
			}
			// The fire went out before the input was done.
			int smelts = loaded.getCount();
			return fuelFor(core, furnace, book) == null ? null : () -> refuel(core, furnace, book, smelts);
		}
		if (input == null) {
			return null;
		}
		int smelts = Math.min(Math.min(core.countItem(input), craft.times() * craft.recipe().count()), input.getDefaultMaxStackSize());
		if (!isLit(furnace) && furnace.getItem(FURNACE_FUEL).isEmpty() && fuelFor(core, furnace, book) == null) {
			return null;
		}
		return () -> {
			int taken = Storage.take(core, input, smelts);
			furnace.setItem(FURNACE_INPUT, new ItemStack(input, taken));
			refuel(core, furnace, book, taken);
		};
	}

	/** Places a stored furnace next to the core, or takes the next step toward crafting one. */
	private static @Nullable Runnable furnaceWork(ServerLevel level, Colony colony, Container core, GameRecipeBook book) {
		if (core.countItem(Items.FURNACE) > 0) {
			BlockPos spot = furnaceSpot(level, colony.core());
			if (spot == null) {
				return null;
			}
			return () -> {
				if (level.getBlockState(spot).isAir() && Storage.take(core, Items.FURNACE, 1) == 1) {
					Direction away = Direction.getApproximateNearest(spot.getX() - colony.core().getX(), 0, spot.getZ() - colony.core().getZ());
					level.setBlockAndUpdate(spot, Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, away));
				}
			};
		}
		TaskPlanner.Plan<Item> plan = TaskPlanner.plan(Items.FURNACE, 1, Storage.stock(core), book, Gatherables::isGatherable);
		for (TaskPlanner.Craft<Item> craft : plan.crafts()) {
			if (!craft.recipe().smelting() && Storage.canCraft(core, craft.recipe(), 1) && Storage.fits(core, craft.recipe())) {
				return () -> Storage.craft(core, craft.recipe());
			}
		}
		return null;
	}

	/** Moves the furnace's output into the core; ordered items count toward their order. */
	private static void collect(Colony colony, Container core, AbstractFurnaceBlockEntity furnace) {
		ItemStack result = furnace.getItem(FURNACE_RESULT);
		Item item = result.getItem();
		int before = result.getCount();
		ItemStack rest = HopperBlockEntity.addItem(null, core, result.copy(), null);
		furnace.setItem(FURNACE_RESULT, rest);
		furnace.setChanged();
		int moved = before - rest.getCount();
		if (moved > 0) {
			colony.fulfil(item, moved);
		}
	}

	/** The first fuel, in the book's order, that {@code source} holds and the furnace's fuel slot can take. */
	private static TaskPlanner.@Nullable Fuel<Item> fuelFor(Container source, AbstractFurnaceBlockEntity furnace, GameRecipeBook book) {
		ItemStack inSlot = furnace.getItem(FURNACE_FUEL);
		for (TaskPlanner.Fuel<Item> fuel : book.fuels()) {
			for (Item item : fuel.items()) {
				if (source.countItem(item) > 0 && (inSlot.isEmpty() || inSlot.is(item))) {
					return new TaskPlanner.Fuel<>(Set.of(item), fuel.burnTicks());
				}
			}
		}
		return null;
	}

	/** Tops up the fuel slot from {@code source} so it can smelt {@code smelts} items. */
	private static void refuel(Container source, AbstractFurnaceBlockEntity furnace, GameRecipeBook book, int smelts) {
		TaskPlanner.Fuel<Item> fuel = fuelFor(source, furnace, book);
		if (fuel == null) {
			return;
		}
		Item item = fuel.items().iterator().next();
		int inSlot = furnace.getItem(FURNACE_FUEL).getCount();
		int wanted = fuel.itemsFor(smelts) - inSlot;
		if (wanted > 0) {
			int taken = Storage.take(source, item, wanted);
			furnace.setItem(FURNACE_FUEL, new ItemStack(item, inSlot + taken));
		}
		furnace.setChanged();
	}

	private static boolean isLit(AbstractFurnaceBlockEntity furnace) {
		return furnace.getBlockState().getValue(AbstractFurnaceBlock.LIT);
	}

	/** A free spot next to the core, on solid ground and with nobody standing in it. */
	private static @Nullable BlockPos furnaceSpot(ServerLevel level, BlockPos core) {
		for (int range = 1; range <= FURNACE_RANGE; range++) {
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos pos = core.relative(direction, range);
				if (level.getBlockState(pos).isAir()
						&& level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
						&& level.getEntities(null, new AABB(pos)).isEmpty()) {
					return pos;
				}
			}
		}
		return null;
	}

	// Crafting away from the core

	private List<Item> carried() {
		List<Item> carried = new ArrayList<>();
		Stream.concat(this.bot.getInventory().getItems().stream(), Stream.of(this.bot.getMainHandItem()))
			.filter(stack -> !stack.isEmpty()).forEach(stack -> carried.add(stack.getItem()));
		return carried;
	}

	/**
	 * The recipe for the job's item the bot can work from its own inventory: smelting for a job whose station is
	 * a furnace, crafting otherwise. Empty if it lacks the ingredients, or the station the recipe needs (see
	 * {@link Crafting#canClaim}).
	 */
	private Optional<TaskPlanner.Recipe<Item>> fieldRecipe(ServerLevel level, Job job) {
		if (job.item().isEmpty()) {
			return Optional.empty();
		}
		boolean smelting = job.tool().filter(Items.FURNACE::equals).isPresent();
		SimpleContainer inventory = this.bot.getInventory();
		List<Item> carried = this.carried();
		return GameRecipeBook.of(level.getServer()).recipesFor(job.item().get()).stream()
			.filter(recipe -> recipe.smelting() == smelting)
			.filter(recipe -> smelting ? smeltInput(recipe, inventory) != null : Storage.canCraft(inventory, recipe, 1) && Storage.fits(inventory, recipe))
			.filter(recipe -> Crafting.canClaim(recipe, carried, standingStation(level, Crafting.station(recipe), job.target()) != null))
			.findFirst();
	}

	private static @Nullable Item smeltInput(TaskPlanner.Recipe<Item> recipe, Container inventory) {
		return recipe.ingredients().keySet().iterator().next().stream().filter(item -> inventory.countItem(item) > 0).findFirst().orElse(null);
	}

	/** A station of the kind standing within reach of {@code near}: a crafting table or the colony core for a table, a furnace for smelting. */
	private static @Nullable BlockPos standingStation(ServerLevel level, Crafting.Station station, BlockPos near) {
		if (station == Crafting.Station.HAND) {
			return near;
		}
		int r = (int) REACH;
		for (BlockPos pos : BlockPos.betweenClosed(near.offset(-r, -r, -r), near.offset(r, r, r))) {
			if (!pos.closerThan(near, REACH)) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			boolean fits = station == Crafting.Station.TABLE ? state.is(Blocks.CRAFTING_TABLE) || state.is(ModBlocks.COLONY_CORE) : state.is(Blocks.FURNACE);
			if (fits) {
				return pos.immutable();
			}
		}
		return null;
	}

	private void craftInField(ServerLevel level, Job job) {
		TaskPlanner.Recipe<Item> recipe = this.recipe;
		if (recipe == null) {
			this.endJob(WorkerGoal.Outcome.COMPLETE);
			return;
		}
		if (this.placed == null && !this.loaded && !this.near(job.target())) {
			return;
		}
		Crafting.Station station = Crafting.station(recipe);
		BlockPos at = this.placed != null ? this.placed : standingStation(level, station, this.bot.blockPosition());
		if (at == null) {
			this.placeStation(level, station);
			return;
		}
		if (station == Crafting.Station.FURNACE) {
			this.smeltInField(level, job, recipe, at);
			return;
		}
		this.swingAt(at);
		boolean crafted = Storage.craft(this.bot.getInventory(), recipe);
		this.finish(level, job, crafted);
	}

	/** Places the station the bot carries next to it, or gives the job back if there is no room or it has none. */
	private void placeStation(ServerLevel level, Crafting.Station station) {
		Item item = station.item().orElseThrow();
		Block block = item == Items.FURNACE ? Blocks.FURNACE : Blocks.CRAFTING_TABLE;
		int slot = findSlot(this.bot.getInventory(), stack -> stack.is(item));
		BlockPos feet = this.bot.blockPosition();
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos spot = feet.relative(direction);
			BlockState state = block.defaultBlockState();
			if (block == Blocks.FURNACE) {
				state = state.setValue(AbstractFurnaceBlock.FACING, direction.getOpposite());
			}
			if (slot >= 0 && level.getBlockState(spot).canBeReplaced() && level.getBlockState(spot).getFluidState().isEmpty()
					&& level.getBlockState(spot.below()).isFaceSturdy(level, spot.below(), Direction.UP)
					&& level.isUnobstructed(state, spot, net.minecraft.world.phys.shapes.CollisionContext.empty())) {
				this.bot.getInventory().getItem(slot).shrink(1);
				level.setBlockAndUpdate(spot, state);
				this.placed = spot.immutable();
				this.swingAt(spot);
				return;
			}
		}
		this.endJob(WorkerGoal.Outcome.RELEASE);
	}

	/** Loads the furnace with the input the bot carries and fuel for it, waits, and takes the output. */
	private void smeltInField(ServerLevel level, Job job, TaskPlanner.Recipe<Item> recipe, BlockPos at) {
		if (!(level.getBlockEntity(at) instanceof AbstractFurnaceBlockEntity furnace)) {
			this.placed = null;
			this.endJob(WorkerGoal.Outcome.RELEASE);
			return;
		}
		SimpleContainer inventory = this.bot.getInventory();
		if (!this.loaded) {
			Item input = smeltInput(recipe, inventory);
			if (input == null || !furnace.getItem(FURNACE_INPUT).isEmpty()) {
				// Its input is gone, or someone else's furnace is busy.
				this.finish(level, job, false);
				return;
			}
			int smelts = Storage.take(inventory, input, input.getDefaultMaxStackSize());
			furnace.setItem(FURNACE_INPUT, new ItemStack(input, smelts));
			refuel(inventory, furnace, GameRecipeBook.of(level.getServer()), smelts);
			this.loaded = true;
			this.smeltUntil = level.getGameTime() + (long) smelts * TaskPlanner.SMELT_TICKS + SMELT_SLACK_TICKS;
			this.swingAt(at);
			return;
		}
		boolean stalled = !isLit(furnace) && furnace.getItem(FURNACE_FUEL).isEmpty();
		if (!furnace.getItem(FURNACE_INPUT).isEmpty() && !stalled && level.getGameTime() < this.smeltUntil) {
			this.cooldown = IDLE_TICKS;
			return;
		}
		this.swingAt(at);
		boolean smelted = !furnace.getItem(FURNACE_RESULT).isEmpty();
		for (int slot : new int[] {FURNACE_RESULT, FURNACE_INPUT, FURNACE_FUEL}) {
			this.keep(level, furnace.removeItemNoUpdate(slot));
		}
		furnace.setChanged();
		this.finish(level, job, smelted);
	}

	/** Picks up the station it placed, delivers what it made if the job's target is a container, and ends the job. */
	private void finish(ServerLevel level, Job job, boolean made) {
		this.takeBack(level);
		Container to = Storage.containerAt(level, job.target());
		Item product = job.item().orElse(null);
		if (made && to != null && product != null) {
			Storage.moveItems(this.bot.getInventory(), to, stack -> stack.is(product));
		}
		this.loaded = false;
		this.endJob(made ? WorkerGoal.Outcome.COMPLETE : WorkerGoal.Outcome.RELEASE);
	}

	/** Breaks the station the bot placed and puts it back in its inventory, with anything left in it. */
	private void takeBack(ServerLevel level) {
		BlockPos at = this.placed;
		this.placed = null;
		if (at == null) {
			return;
		}
		BlockState state = level.getBlockState(at);
		Item item = state.is(Blocks.FURNACE) ? Items.FURNACE : state.is(Blocks.CRAFTING_TABLE) ? Items.CRAFTING_TABLE : null;
		if (item == null) {
			return;
		}
		if (level.getBlockEntity(at) instanceof Container container) {
			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				this.keep(level, container.removeItemNoUpdate(slot));
			}
		}
		level.removeBlock(at, false);
		this.keep(level, new ItemStack(item));
	}

	/** Puts {@code stack} in the bot's inventory, dropping what does not fit. */
	private void keep(ServerLevel level, ItemStack stack) {
		if (stack.isEmpty()) {
			return;
		}
		ItemStack rest = this.bot.getInventory().addItem(stack);
		if (!rest.isEmpty()) {
			this.bot.spawnAtLocation(level, rest);
		}
	}
}
