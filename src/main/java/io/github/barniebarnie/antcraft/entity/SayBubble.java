package io.github.barniebarnie.antcraft.entity;

import com.mojang.math.Transformation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * What a bot says ({@code say(text)} in a script), shown above it for {@link #TICKS} ticks: a text display riding the bot,
 * so it goes where the bot goes. A new {@code say} replaces the text and starts the time over. The display is saved with
 * the bot as its passenger, but this is not: a bot that loads with one takes it away on its next tick.
 */
public final class SayBubble {
	/** How long the text stays above the bot: five seconds. */
	public static final int TICKS = 100;
	/** How far above the bot's head the text floats, clear of its name tag. */
	private static final float RAISE = 0.6F;

	private final Entity bot;
	private Display.@Nullable TextDisplay display;
	private int ticksLeft;

	SayBubble(Entity bot) {
		this.bot = bot;
	}

	/** Shows {@code text} above the bot, in place of what it said before. */
	void show(String text) {
		if (this.display == null || this.display.isRemoved() || this.display.getVehicle() != this.bot) {
			this.remove();
			Display.TextDisplay created = new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, this.bot.level());
			created.setBillboardConstraints(Display.BillboardConstraints.CENTER);
			created.setTransformation(new Transformation(new Vector3f(0, RAISE, 0), new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf()));
			created.snapTo(this.bot.getX(), this.bot.getY() + this.bot.getBbHeight(), this.bot.getZ());
			if (!this.bot.level().addFreshEntity(created)) {
				return;
			}
			created.startRiding(this.bot, true, false);
			this.display = created;
		}
		this.display.setText(Component.literal(text));
		this.ticksLeft = TICKS;
	}

	/**
	 * Counts down the time the text has left, and takes it away once it is up or it no longer rides the bot. Also takes
	 * away any other text display riding the bot, such as one it was saved with.
	 */
	void tick() {
		if (this.display != null && (--this.ticksLeft <= 0 || this.display.isRemoved() || this.display.getVehicle() != this.bot)) {
			this.remove();
		}
		if (this.bot.isVehicle()) {
			for (Entity passenger : this.bot.getPassengers()) {
				if (passenger instanceof Display.TextDisplay && passenger != this.display) {
					passenger.discard();
				}
			}
		}
	}

	/** Takes the text away now, as when the bot is gone. */
	void remove() {
		if (this.display != null) {
			this.display.discard();
			this.display = null;
		}
	}
}
