package io.github.barniebarnie.antcraft.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.food.FoodConstants;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A bot's food level, saturation and exhaustion, with the rules of a player's {@code FoodData}, whose {@code tick} takes a
 * player only (#702, docs/features/bots.md, Hunger): every 4 exhaustion takes a point of saturation, or without any a point of
 * food (not on peaceful); full with saturation it heals every 10 ticks, at 18 food or more a point every 80, and at 0 food it
 * loses a point every 80 ticks down to the difficulty's floor. Saved under the names {@code FoodData} uses.
 */
public final class BotFood {
	private int food = FoodConstants.MAX_FOOD;
	private float saturation = FoodConstants.START_SATURATION;
	private float exhaustion;
	private int tickTimer;

	public int food() {
		return this.food;
	}

	public float saturation() {
		return this.saturation;
	}

	public float exhaustion() {
		return this.exhaustion;
	}

	public void setFoodLevel(int food) {
		this.food = food;
	}

	public void setSaturation(float saturation) {
		this.saturation = saturation;
	}

	/** Adds {@code amount} of exhaustion, as {@code Player.causeFoodExhaustion}; it stops adding up at 40. */
	public void exhaust(float amount) {
		this.exhaustion = Math.min(this.exhaustion + amount, 40);
	}

	/** Eats food of {@code nutrition} and {@code saturation} (FoodProperties' values): food up to 20, saturation up to the food. */
	public void eat(int nutrition, float saturation) {
		this.food = Mth.clamp(nutrition + this.food, 0, FoodConstants.MAX_FOOD);
		this.saturation = Mth.clamp(saturation + this.saturation, 0, this.food);
	}

	/** A tick of the Saturation effect at {@code amplifier}: {@code SaturationMobEffect}, which feeds only players (#730). */
	public void saturate(int amplifier) {
		this.eat(amplifier + 1, FoodConstants.saturationByModifier(amplifier + 1, 1));
	}

	/**
	 * {@code ServerPlayer.tickRegeneration}, on peaceful with natural regeneration on, for a bot {@code tickCount} ticks old
	 * at {@code health} of {@code maxHealth} (#730): every 20 ticks a point of health while hurt and a point of saturation
	 * under 20, every 10 ticks a point of food under 20. Gives the health to heal, or 0.
	 */
	public float peaceful(int tickCount, float health, float maxHealth) {
		float heal = 0;
		if (tickCount % 20 == 0) {
			heal = health < maxHealth ? 1 : 0;
			if (this.saturation < FoodConstants.MAX_SATURATION) {
				this.saturation++;
			}
		}
		if (tickCount % 10 == 0 && this.food < FoodConstants.MAX_FOOD) {
			this.food++;
		}
		return heal;
	}

	/**
	 * One tick of {@code FoodData.tick} for a bot at {@code health} of {@code maxHealth}: the health to heal, -1 for the point
	 * starving takes, or 0. {@code regeneration} is the {@code naturalHealthRegeneration} game rule.
	 */
	public float tick(float health, float maxHealth, boolean regeneration, Difficulty difficulty) {
		if (this.exhaustion > FoodConstants.EXHAUSTION_DROP) {
			this.exhaustion -= FoodConstants.EXHAUSTION_DROP;
			if (this.saturation > 0) {
				this.saturation = Math.max(this.saturation - 1, 0);
			} else if (difficulty != Difficulty.PEACEFUL) {
				this.food = Math.max(this.food - 1, 0);
			}
		}
		boolean hurt = health > 0 && health < maxHealth;
		if (regeneration && this.saturation > 0 && hurt && this.food >= FoodConstants.MAX_FOOD) {
			if (++this.tickTimer >= FoodConstants.HEALTH_TICK_COUNT_SATURATED) {
				float spent = Math.min(this.saturation, FoodConstants.EXHAUSTION_HEAL);
				this.exhaust(spent);
				this.tickTimer = 0;
				return spent / FoodConstants.EXHAUSTION_HEAL;
			}
		} else if (regeneration && this.food >= FoodConstants.HEAL_LEVEL && hurt) {
			if (++this.tickTimer >= FoodConstants.HEALTH_TICK_COUNT) {
				this.exhaust(FoodConstants.EXHAUSTION_HEAL);
				this.tickTimer = 0;
				return 1;
			}
		} else if (this.food <= FoodConstants.STARVE_LEVEL) {
			if (++this.tickTimer >= FoodConstants.HEALTH_TICK_COUNT) {
				this.tickTimer = 0;
				if (health > 10 || difficulty == Difficulty.HARD || health > 1 && difficulty == Difficulty.NORMAL) {
					return -1;
				}
			}
		} else {
			this.tickTimer = 0;
		}
		return 0;
	}

	/**
	 * The health the bot heals quickly, every 10 ticks, before it stops for want of food: while full with saturation, 6
	 * exhaustion a point, and every 4 exhaustion takes a point of saturation (#708).
	 */
	public float quickHeal() {
		if (this.food < FoodConstants.MAX_FOOD) {
			return 0;
		}
		return Math.max(0, Mth.ceil(this.saturation) * FoodConstants.EXHAUSTION_DROP - this.exhaustion) / FoodConstants.EXHAUSTION_HEAL;
	}

	/**
	 * The health the bot heals slowly, a point every 80 ticks, after {@link #quickHeal}: down to 18 food, its saturation
	 * first, 4 exhaustion a point of either.
	 */
	public float slowHeal() {
		int spare = this.food - FoodConstants.HEAL_LEVEL + 1;
		if (spare <= 0) {
			return 0;
		}
		float all = Math.max(0, (Mth.ceil(this.saturation) + spare) * FoodConstants.EXHAUSTION_DROP - this.exhaustion);
		return all / FoodConstants.EXHAUSTION_HEAL - this.quickHeal();
	}

	public void save(ValueOutput output) {
		output.putInt("foodLevel", this.food);
		output.putInt("foodTickTimer", this.tickTimer);
		output.putFloat("foodSaturationLevel", this.saturation);
		output.putFloat("foodExhaustionLevel", this.exhaustion);
	}

	/** Reads what {@link #save} wrote; a bot saved before it had food is full. */
	public void load(ValueInput input) {
		this.food = input.getIntOr("foodLevel", FoodConstants.MAX_FOOD);
		this.tickTimer = input.getIntOr("foodTickTimer", 0);
		this.saturation = input.getFloatOr("foodSaturationLevel", FoodConstants.START_SATURATION);
		this.exhaustion = input.getFloatOr("foodExhaustionLevel", 0);
	}
}
