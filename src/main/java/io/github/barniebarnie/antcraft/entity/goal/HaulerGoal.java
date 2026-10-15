package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.work.Storage;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

/** Hauler work loop: pick up loose items around the colony and store them in the core. */
public class HaulerGoal extends ColonyWorkGoal {
	private static final int MIN_FREE_SLOTS = 1;

	private final Set<Integer> unreachableItems = new HashSet<>();
	private boolean depositing;

	public HaulerGoal(AntBot bot) {
		super(bot, BotRole.HAULER);
	}

	@Override
	protected void work(ServerLevel level, Colony colony) {
		if (this.depositing) {
			Container core = Storage.containerAt(level, colony.core());
			if (core == null) {
				this.depositing = false;
				this.cooldown = IDLE_TICKS;
			} else if (this.reach(colony.core())) {
				Storage.moveItems(this.bot.getInventory(), core, stack -> true);
				this.depositing = false;
				this.unreachableItems.clear();
			}
			return;
		}

		boolean carrying = !this.bot.getInventory().isEmpty();
		ItemEntity nearest = freeSlots(this.bot.getInventory()) < MIN_FREE_SLOTS ? null : level.getEntitiesOfClass(ItemEntity.class,
				new AABB(colony.core()).inflate(colony.radius()),
				item -> item.isAlive() && !this.unreachableItems.contains(item.getId()) && this.bot.wantsToPickUp(level, item.getItem()))
			.stream()
			.min(Comparator.comparingDouble(this.bot::distanceToSqr))
			.orElse(null);
		if (nearest == null) {
			if (carrying) {
				this.depositing = true;
			} else {
				this.cooldown = IDLE_TICKS;
			}
			return;
		}
		if (this.travel(new Goal.Near(nearest.blockPosition(), 0)) == PathFollower.Status.FAILED) {
			this.unreachableItems.add(nearest.getId());
		}
	}
}
