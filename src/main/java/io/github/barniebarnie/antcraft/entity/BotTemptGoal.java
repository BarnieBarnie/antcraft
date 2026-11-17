package io.github.barniebarnie.antcraft.entity;

import java.util.Comparator;
import java.util.EnumSet;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.TemptGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.animal.Animal;
import org.jspecify.annotations.Nullable;

/**
 * An animal follows a bot that holds its food as it follows a player (docs/cookbook/ranch.md, The rounds; #843): vanilla's
 * {@link TemptGoal} looks only for players, so each animal that has one gets this beside it, at its priority and speed.
 * It comes closer than to a player, to {@link #STOP} blocks, so a bot standing in its pen draws it clear of the gate.
 */
public final class BotTemptGoal extends Goal {
	static final double STOP = 1.5;

	private final Animal animal;
	private final double speed;
	private @Nullable AntBot bot;

	private BotTemptGoal(Animal animal, double speed) {
		this.animal = animal;
		this.speed = speed;
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	/** Gives every animal that loads with a {@link TemptGoal} this goal too. */
	public static void register() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof Animal animal) {
				WrappedGoal tempt = animal.getGoalSelector().getAvailableGoals().stream()
					.filter(g -> g.getGoal() instanceof TemptGoal).findFirst().orElse(null);
				if (tempt != null && animal.getGoalSelector().getAvailableGoals().stream().noneMatch(g -> g.getGoal() instanceof BotTemptGoal)) {
					animal.getGoalSelector().addGoal(tempt.getPriority(), new BotTemptGoal(animal, ((TemptGoal) tempt.getGoal()).speedModifier));
				}
			}
		});
	}

	@Override
	public boolean canUse() {
		// ponytail: an entity query round each animal every other tick, as TemptGoal looks through the players; skip
		// animals far from any bot first if pens of hundreds show in the tick profile.
		double range = this.animal.getAttributeValue(Attributes.TEMPT_RANGE);
		this.bot = this.animal.level().getEntitiesOfClass(AntBot.class, this.animal.getBoundingBox().inflate(range),
				b -> b.isAlive() && this.animal.isFood(b.getMainHandItem())).stream()
			.min(Comparator.comparingDouble(this.animal::distanceToSqr)).orElse(null);
		return this.bot != null;
	}

	@Override
	public void stop() {
		this.bot = null;
		this.animal.getNavigation().stop();
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		AntBot bot = this.bot;
		if (bot == null) {
			return;
		}
		this.animal.getLookControl().setLookAt(bot, this.animal.getMaxHeadYRot() + 20, this.animal.getMaxHeadXRot());
		if (this.animal.distanceToSqr(bot) < STOP * STOP) {
			this.animal.getNavigation().stop();
		} else {
			this.animal.getNavigation().moveTo(bot, this.speed);
		}
	}
}
