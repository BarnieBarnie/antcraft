package io.github.barniebarnie.antcraft.entity;

import com.mojang.math.Transformation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * What a bot says ({@code say(text)} in a script), shown above it for {@link #TICKS} ticks: a text display that the bot
 * moves along with it every tick. It does not ride the bot, as a bot with a passenger is a vehicle, which nothing
 * pushes. A new {@code say} replaces the text and starts the time over. The display is never saved, so it goes when its
 * chunk unloads; a bot saved by an older version with one as its passenger takes that away on its next tick.
 */
public final class SayBubble {
	/** How long the text stays above the bot: five seconds. */
	public static final int TICKS = 100;
	/** How far above the bot's head the text floats, clear of its name tag. */
	private static final float RAISE = 0.6F;
	/** Ticks the client takes to glide the text to where the bot moved it, so it follows smoothly. */
	private static final int GLIDE_TICKS = 1;

	private final Entity bot;
	private @Nullable Bubble display;
	private int ticksLeft;

	SayBubble(Entity bot) {
		this.bot = bot;
	}

	/** Shows {@code text} above the bot, in place of what it said before. */
	void show(String text) {
		if (this.display == null || this.display.isRemoved()) {
			this.remove();
			Bubble created = new Bubble(EntityTypes.TEXT_DISPLAY, this.bot.level());
			created.setBillboardConstraints(Display.BillboardConstraints.CENTER);
			created.setTransformation(new Transformation(new Vector3f(0, RAISE, 0), new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf()));
			created.setPosRotInterpolationDuration(GLIDE_TICKS);
			created.snapTo(this.bot.getX(), this.top(), this.bot.getZ());
			if (!this.bot.level().addFreshEntity(created)) {
				return;
			}
			this.display = created;
		}
		this.display.setText(Component.literal(text));
		this.ticksLeft = TICKS;
	}

	/**
	 * Counts down the time the text has left, and takes it away once it is up or the display is gone. Also takes away any
	 * text display riding the bot, such as one an older version saved with it.
	 */
	void tick() {
		if (this.display != null && (--this.ticksLeft <= 0 || this.display.isRemoved())) {
			this.remove();
		}
		if (this.bot.isVehicle()) {
			for (Entity passenger : this.bot.getPassengers()) {
				if (passenger instanceof Display.TextDisplay) {
					passenger.discard();
				}
			}
		}
	}

	/** Moves the text to above where the bot is now; called after the bot moved this tick. */
	void follow() {
		if (this.display != null && !this.display.isRemoved()) {
			this.display.setPos(this.bot.getX(), this.top(), this.bot.getZ());
		}
	}

	/** Takes the text away now, as when the bot is gone. */
	void remove() {
		if (this.display != null) {
			this.display.discard();
			this.display = null;
		}
	}

	private double top() {
		return this.bot.getY() + this.bot.getBbHeight();
	}

	/** A text display that is never saved: it lives only as long as the bot shows it. */
	private static final class Bubble extends Display.TextDisplay {
		Bubble(EntityType<?> type, Level level) {
			super(type, level);
		}

		@Override
		public boolean shouldBeSaved() {
			return false;
		}
	}
}
