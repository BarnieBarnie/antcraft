package io.github.barniebarnie.antcraft.entity;

import java.util.EnumSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The bot's own fight with a hostile mob it targets, where it carries a bow or crossbow and arrows (docs/features/bots.md,
 * Fighting targets): it shoots it from between {@link Bow#NEAR} and {@link Bow#RANGE} blocks, as {@code shoot} does,
 * rather than close in with the melee goal, which fights on once the bow or the arrows are gone (#724), with its best
 * weapon in hand again. Not a skeleton, which outshoots it and is better closed in on, nor an enderman, which arrows do
 * not hit (#734). A bot under {@link #LOW_HEALTH} does not walk closer to a mob out of range, as {@code
 * attack(min_health=...)} lets go once hurt: it waits for it to come. A scratch does not stop it (#750). While a fuse is lit near, the kite goal before it backs the bot off.
 */
final class ShootGoal extends Goal {
	/** How fast the bot walks closer, as a factor of its speed: as the melee goal does. */
	private static final double SPEED = 1.2;
	/** Health under which the bot waits for a mob out of range rather than close in: jobs.guard's RETREAT (#750). */
	static final float LOW_HEALTH = 12;

	private final AntBot bot;
	private @Nullable Vec3 away;

	ShootGoal(AntBot bot) {
		this.bot = bot;
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		return shootable(this.bot.getTarget()) && Bow.armed(this.bot);
	}

	/** Whether the bot shoots {@code target} rather than strike it. */
	private static boolean shootable(@Nullable LivingEntity target) {
		return target instanceof Enemy && target.isAlive() && !(target instanceof AbstractSkeleton) && !(target instanceof Enderman);
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
		Tools.equipBestWeapon(this.bot);
	}

	@Override
	public void tick() {
		LivingEntity target = this.bot.getTarget();
		if (!shootable(target) || !BotHands.equip(this.bot, Bow.WEAPON)) {
			return;
		}
		double distance = this.bot.distanceTo(target);
		boolean inRange = distance <= Bow.RANGE && this.bot.getSensing().hasLineOfSight(target);
		if (!inRange) {
			this.away = null;
			if (this.bot.getHealth() < LOW_HEALTH) {
				this.bot.getNavigation().stop();
			} else if (this.bot.getNavigation().isDone()) {
				this.bot.getNavigation().moveTo(target, SPEED);
			}
		} else if (distance < Bow.NEAR) {
			this.away = Bow.backOff(this.bot, target, this.away);
		} else {
			this.away = null;
			this.bot.getNavigation().stop();
		}
		Bow.draw(this.bot, target, inRange);
	}
}
