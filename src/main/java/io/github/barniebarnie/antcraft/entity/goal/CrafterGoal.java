package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.colony.BotActivation;
import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.TaskPlanner;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.registry.ModItems;
import io.github.barniebarnie.antcraft.work.Gatherables;
import io.github.barniebarnie.antcraft.work.GameRecipeBook;
import io.github.barniebarnie.antcraft.work.Storage;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Crafter work loop at the colony core: turn stored Bot Cores into new bots, and
 * work through colony orders one craft at a time, ingredients first. Smelting steps
 * run in a furnace next to the core; the crafter places one if there is none. A craft whose
 * result has no room in the core waits, and so does furnace output, while other crafts go on.
 */
public class CrafterGoal extends net.minecraft.world.entity.ai.goal.Goal {
	private static final double REACH = 4.0;
	private static final int CRAFT_TICKS = 10;
	private static final int IDLE_TICKS = 20;
	private static final int FURNACE_RANGE = Storage.FURNACE_RANGE;
	private static final int FURNACE_INPUT = Storage.FURNACE_INPUT;
	private static final int FURNACE_FUEL = Storage.FURNACE_FUEL;
	private static final int FURNACE_RESULT = Storage.FURNACE_RESULT;

	private final AntBot bot;
	private final PathFollower follower;
	private @Nullable Goal travelGoal;
	private int cooldown;

	public CrafterGoal(AntBot bot) {
		this.bot = bot;
		this.follower = new PathFollower(bot);
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		return this.bot.getRole() == BotRole.CRAFTER && this.bot.getWalkTarget() == null && this.bot.getColonyId() != null;
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void stop() {
		this.follower.stop();
		this.travelGoal = null;
	}

	@Override
	public void tick() {
		if (this.cooldown > 0) {
			this.cooldown--;
			return;
		}
		if (!(this.bot.level() instanceof ServerLevel level)) {
			return;
		}
		Optional<Colony> colony = this.colony(level);
		Container core = colony.map(c -> Storage.containerAt(level, c.core())).orElse(null);
		if (core == null) {
			this.cooldown = IDLE_TICKS;
			return;
		}

		Runnable work = this.nextWork(level, colony.get(), core);
		if (work == null) {
			this.cooldown = IDLE_TICKS;
			return;
		}
		BlockPos corePos = colony.get().core();
		Goal reach = new Goal.Reach(corePos, REACH);
		if (reach.isReached(this.bot.blockPosition())) {
			this.travelGoal = null;
			this.bot.getLookControl().setLookAt(corePos.getX() + 0.5, corePos.getY() + 0.5, corePos.getZ() + 0.5);
			this.bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			work.run();
			this.cooldown = CRAFT_TICKS;
		} else {
			if (!reach.equals(this.travelGoal)) {
				this.travelGoal = reach;
				this.follower.start(reach);
			}
			if (this.follower.tick() != PathFollower.Status.RUNNING) {
				this.travelGoal = null;
				this.cooldown = IDLE_TICKS;
			}
		}
	}

	/** The next thing to do at the core, or null if there is nothing. */
	private @Nullable Runnable nextWork(ServerLevel level, Colony colony, Container core) {
		if (core.countItem(ModItems.BOT_CORE) > 0 && BotActivation.canActivate(level)) {
			Optional<BlockPos> spot = BotActivation.freeSpotNear(level, colony.core().above());
			if (spot.isPresent()) {
				return () -> {
					if (BotActivation.activate(level, spot.get()) != null) {
						Storage.take(core, ModItems.BOT_CORE, 1);
					}
				};
			}
		}

		AbstractFurnaceBlockEntity furnace = Storage.colonyFurnace(level, colony.core());
		// Output the core has no room for waits in the furnace.
		if (furnace != null && !furnace.getItem(FURNACE_RESULT).isEmpty() && Storage.hasRoomFor(core, furnace.getItem(FURNACE_RESULT))) {
			return () -> collect(colony, core, furnace);
		}

		GameRecipeBook book = GameRecipeBook.of(level.getServer());
		for (Map.Entry<Item, Integer> order : colony.orders().entrySet()) {
			Map<Item, Integer> stock = Storage.stock(core);
			if (furnace != null) {
				// What is in the furnace is still the colony's, output waiting for room in the core too.
				for (int slot : new int[] {FURNACE_INPUT, FURNACE_FUEL, FURNACE_RESULT}) {
					ItemStack stack = furnace.getItem(slot);
					if (!stack.isEmpty()) {
						stock.merge(stack.getItem(), stack.getCount(), Integer::sum);
					}
				}
			}
			// An order asks for new items, so what is already stored does not count toward it.
			stock.remove(order.getKey());
			TaskPlanner.Plan<Item> plan = TaskPlanner.plan(order.getKey(), order.getValue(), stock, book, Gatherables::isGatherable);
			for (TaskPlanner.Craft<Item> craft : plan.crafts()) {
				if (craft.recipe().smelting()) {
					Runnable smelt = this.smeltWork(level, colony, core, furnace, craft, book);
					if (smelt != null) {
						return smelt;
					}
				} else if (Storage.canCraft(core, craft.recipe(), 1) && Storage.fits(core, craft.recipe())) {
					return () -> {
						if (Storage.craft(core, craft.recipe()) && craft.recipe().output() == order.getKey()) {
							colony.fulfil(order.getKey(), craft.recipe().count());
						}
					};
				}
			}
		}
		return null;
	}

	/** The next step of a smelting craft, or null if it has to wait. */
	private @Nullable Runnable smeltWork(ServerLevel level, Colony colony, Container core, @Nullable AbstractFurnaceBlockEntity furnace,
			TaskPlanner.Craft<Item> craft, GameRecipeBook book) {
		Set<Item> inputs = craft.recipe().ingredients().keySet().iterator().next();
		Item input = inputs.stream().filter(item -> core.countItem(item) > 0).findFirst().orElse(null);
		if (furnace == null) {
			return input == null ? null : this.furnaceWork(level, colony, core, book);
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
	private @Nullable Runnable furnaceWork(ServerLevel level, Colony colony, Container core, GameRecipeBook book) {
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

	/** The first fuel, in the book's order, that the core holds and the furnace's fuel slot can take. */
	private static TaskPlanner.@Nullable Fuel<Item> fuelFor(Container core, AbstractFurnaceBlockEntity furnace, GameRecipeBook book) {
		ItemStack inSlot = furnace.getItem(FURNACE_FUEL);
		for (TaskPlanner.Fuel<Item> fuel : book.fuels()) {
			for (Item item : fuel.items()) {
				if (core.countItem(item) > 0 && (inSlot.isEmpty() || inSlot.is(item))) {
					return new TaskPlanner.Fuel<>(Set.of(item), fuel.burnTicks());
				}
			}
		}
		return null;
	}

	/** Tops up the fuel slot from the core so it can smelt {@code smelts} items. */
	private static void refuel(Container core, AbstractFurnaceBlockEntity furnace, GameRecipeBook book, int smelts) {
		TaskPlanner.Fuel<Item> fuel = fuelFor(core, furnace, book);
		if (fuel == null) {
			return;
		}
		Item item = fuel.items().iterator().next();
		int inSlot = furnace.getItem(FURNACE_FUEL).getCount();
		int wanted = fuel.itemsFor(smelts) - inSlot;
		if (wanted > 0) {
			int taken = Storage.take(core, item, wanted);
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

	private Optional<Colony> colony(ServerLevel level) {
		UUID id = this.bot.getColonyId();
		return id == null ? Optional.empty() : ColonyManager.get(level).byId(id);
	}
}
