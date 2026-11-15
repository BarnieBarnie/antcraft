package io.github.barniebarnie.antcraft.entity;

import java.util.EnumSet;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The bot's own fight with a creeper it targets, where it carries a bow or crossbow and arrows (docs/features/bots.md,
 * Fighting creepers): it shoots it from between {@link Bow#NEAR} and {@link Bow#RANGE} blocks, as {@code shoot} does,
 * rather than close in with the melee goal, which fights on once the bow or the arrows are gone (#724). While a fuse is
 * lit near, the kite goal before it backs the bot off.
 */
final class ShootGoal extends Goal {
	/** How fast the bot walks closer, as a factor of its speed: as the melee goal does. */
	private static final double SPEED = 1.2;

	private final AntBot bot;
	private @Nullable Vec3 away;

	ShootGoal(AntBot bot) {
		this.bot = bot;
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		return this.bot.getTarget() instanceof Creeper creeper && creeper.isAlive() && Bow.armed(this.bot);
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void stop() {
		this.away = null;
		this.bot.getNavigation().stop();
		this.bot.stopUsingItem();
	}

	@Override
	public void tick() {
		if (!(this.bot.getTarget() instanceof Creeper creeper) || !BotHands.equip(this.bot, Bow.WEAPON)) {
			return;
		}
		double distance = this.bot.distanceTo(creeper);
		boolean inRange = distance <= Bow.RANGE && this.bot.getSensing().hasLineOfSight(creeper);
		if (!inRange) {
			this.away = null;
			if (this.bot.getNavigation().isDone()) {
				this.bot.getNavigation().moveTo(creeper, SPEED);
			}
		} else if (distance < Bow.NEAR) {
			this.away = Bow.backOff(this.bot, creeper, this.away);
		} else {
			this.away = null;
			this.bot.getNavigation().stop();
		}
		Bow.draw(this.bot, creeper, inRange);
	}
}
