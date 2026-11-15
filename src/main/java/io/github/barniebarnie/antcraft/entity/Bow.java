package io.github.barniebarnie.antcraft.entity;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.phys.Vec3;

/**
 * A bot's bow or crossbow (docs/reference/combat/shoot.md): how long it draws, and a shot of a real arrow from the
 * bot's own, aimed with lead and drop ({@link Aim}) and fired as the item fires a player's, with its enchantments.
 */
public final class Bow {
	/** A bow or crossbow. */
	public static final Predicate<ItemStack> WEAPON = stack -> stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem;
	/** What it shoots: arrows, tipped and spectral ones too, not a crossbow's fireworks. */
	public static final Predicate<ItemStack> ARROWS = ProjectileWeaponItem.ARROW_ONLY;

	private Bow() {
	}

	/** Whether {@code bot} carries a bow or crossbow and arrows for it. */
	public static boolean armed(AntBot bot) {
		return BotHands.find(bot, WEAPON) != null && BotHands.find(bot, ARROWS) != null;
	}

	/** How many ticks {@code bot} draws {@code weapon} for a full-power shot: 20 for a bow, a crossbow's charge time. */
	public static int drawTicks(AntBot bot, ItemStack weapon) {
		return weapon.getItem() instanceof CrossbowItem ? CrossbowItem.getChargeDuration(weapon, bot) : BowItem.MAX_DRAW_DURATION;
	}

	/**
	 * Shoots an arrow the bot carries at {@code target} with the drawn bow or crossbow in its hand: turns to the shot that
	 * comes onto where the target will be, led by how it moves relative to the bot, and fires as the item would for a
	 * player at full draw. False, firing nothing, if the target is beyond reach or the bot has no arrow.
	 */
	public static boolean fire(AntBot bot, ServerLevel level, LivingEntity target) {
		ItemStack weapon = bot.getMainHandItem();
		ItemStack ammo = BotHands.find(bot, ARROWS);
		if (!(weapon.getItem() instanceof ProjectileWeaponItem item) || ammo == null) {
			return false;
		}
		boolean bow = item instanceof BowItem;
		// A player's full-draw arrow speed: 3 for a bow (power 1), 3.15 for a crossbow.
		float speed = bow ? 3.0F : 3.15F;
		Vec3 from = new Vec3(bot.getX(), bot.getEyeY() - 0.1, bot.getZ());
		Vec3 moving = target.getDeltaMovement().subtract(bot.getKnownMovement()).multiply(1, 0, 1);
		Vec3 at = new Vec3(target.getX(), target.getY(0.5), target.getZ());
		// Where the target is once the arrow got there, from the flight to where it was, twice over.
		Vec3 to = at;
		Aim.Shot shot = null;
		for (int i = 0; i < 3; i++) {
			if (shot != null) {
				to = at.add(moving.scale(shot.ticks()));
			}
			shot = Aim.at(to.subtract(from).horizontalDistance(), to.y - from.y, speed);
			if (shot == null) {
				return false;
			}
		}
		float yaw = (float) Math.toDegrees(Math.atan2(-(to.x - from.x), to.z - from.z));
		bot.setYRot(yaw);
		bot.setYHeadRot(yaw);
		bot.setXRot((float) -Math.toDegrees(shot.pitch()));
		List<ItemStack> arrows = ProjectileWeaponItem.draw(weapon, ammo, bot);
		if (arrows.isEmpty()) {
			return false;
		}
		item.shoot(level, bot, InteractionHand.MAIN_HAND, weapon, arrows, speed, 1.0F, bow, null);
		if (bow) {
			level.playSound(null, bot.getX(), bot.getY(), bot.getZ(), SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL, 1.0F,
				1.0F / (bot.getRandom().nextFloat() * 0.4F + 1.2F) + 0.5F);
		}
		return true;
	}
}
