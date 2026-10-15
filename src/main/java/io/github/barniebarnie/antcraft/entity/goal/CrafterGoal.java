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
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import org.jspecify.annotations.Nullable;

/**
 * Crafter work loop at the colony core: turn stored Bot Cores into new bots, and
 * work through colony orders one craft at a time, ingredients first.
 */
public class CrafterGoal extends net.minecraft.world.entity.ai.goal.Goal {
	private static final double REACH = 4.0;
	private static final int CRAFT_TICKS = 10;
	private static final int IDLE_TICKS = 20;

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

		GameRecipeBook book = GameRecipeBook.of(level.getServer());
		for (Map.Entry<Item, Integer> order : colony.orders().entrySet()) {
			Map<Item, Integer> stock = Storage.stock(core);
			// An order asks for new items, so what is already stored does not count toward it.
			stock.remove(order.getKey());
			TaskPlanner.Plan<Item> plan = TaskPlanner.plan(order.getKey(), order.getValue(), stock, book, Gatherables::isGatherable);
			for (TaskPlanner.Craft<Item> craft : plan.crafts()) {
				if (Storage.canCraft(core, craft.recipe(), 1)) {
					return () -> {
						ItemStack leftover = Storage.craft(core, craft.recipe());
						if (craft.recipe().output() == order.getKey()) {
							colony.fulfil(order.getKey(), craft.recipe().count());
						}
						if (!leftover.isEmpty()) {
							BlockPos above = colony.core().above();
							Containers.dropItemStack(level, above.getX() + 0.5, above.getY(), above.getZ() + 0.5, leftover);
						}
					};
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
