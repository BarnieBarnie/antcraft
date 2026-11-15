package io.github.barniebarnie.antcraft.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.Difficulty;
import org.junit.jupiter.api.Test;

/** The bot's food rules, those of a player's FoodData (#702, docs/features/bots.md, Hunger). */
class BotFoodTest {
	/** Ticks {@code food} {@code ticks} times at {@code health} of 20, natural regeneration on; the health change of the last tick. */
	private static float tick(BotFood food, int ticks, float health, Difficulty difficulty) {
		float change = 0;
		for (int i = 0; i < ticks; i++) {
			change = food.tick(health, 20, true, difficulty);
		}
		return change;
	}

	@Test
	void startsFullWithAPlayersSaturation() {
		BotFood food = new BotFood();
		assertEquals(20, food.food());
		assertEquals(5, food.saturation());
		assertEquals(0, food.exhaustion());
	}

	@Test
	void eachFourExhaustionTakesSaturationFirstThenFood() {
		BotFood food = new BotFood();
		food.setSaturation(1);
		food.exhaust(4.5F);
		tick(food, 1, 20, Difficulty.NORMAL);
		assertEquals(0, food.saturation());
		assertEquals(20, food.food());
		assertEquals(0.5F, food.exhaustion(), 1e-6);
		food.exhaust(4);
		tick(food, 1, 20, Difficulty.NORMAL);
		assertEquals(19, food.food());
		// Peaceful takes saturation but no food.
		food.exhaust(4);
		tick(food, 1, 20, Difficulty.PEACEFUL);
		assertEquals(19, food.food());
		// Exhaustion stops adding up at 40.
		food.exhaust(100);
		assertEquals(40, food.exhaustion());
	}

	@Test
	void fullWithSaturationItHealsEveryTenTicksAtTheCostOfTheSaturation() {
		BotFood food = new BotFood();
		assertEquals(0, tick(food, 9, 10, Difficulty.NORMAL));
		assertEquals(5 / 6F, tick(food, 1, 10, Difficulty.NORMAL), 1e-6);
		assertEquals(5, food.exhaustion(), 1e-6);
	}

	@Test
	void atEighteenItHealsAPointEveryEightyTicks() {
		BotFood food = new BotFood();
		food.setFoodLevel(18);
		food.setSaturation(0);
		assertEquals(0, tick(food, 79, 10, Difficulty.NORMAL));
		assertEquals(1, tick(food, 1, 10, Difficulty.NORMAL));
		assertEquals(6, food.exhaustion(), 1e-6);
	}

	@Test
	void itDoesNotHealBelowEighteenAtFullHealthOrWithoutNaturalRegeneration() {
		BotFood food = new BotFood();
		food.setFoodLevel(17);
		assertEquals(0, tick(food, 200, 10, Difficulty.NORMAL));
		food = new BotFood();
		assertEquals(0, tick(food, 200, 20, Difficulty.NORMAL));
		for (int i = 0; i < 200; i++) {
			assertEquals(0, food.tick(10, 20, false, Difficulty.NORMAL));
		}
	}

	@Test
	void starvingItLosesAPointEveryEightyTicksDownToTheDifficultysFloor() {
		BotFood food = new BotFood();
		food.setFoodLevel(0);
		food.setSaturation(0);
		assertEquals(0, tick(food, 79, 20, Difficulty.NORMAL));
		assertEquals(-1, tick(food, 1, 20, Difficulty.NORMAL));
		assertEquals(-1, tick(food, 80, 11, Difficulty.EASY));
		assertEquals(0, tick(food, 80, 10, Difficulty.EASY));
		assertEquals(-1, tick(food, 80, 2, Difficulty.NORMAL));
		assertEquals(0, tick(food, 80, 1, Difficulty.NORMAL));
		assertEquals(-1, tick(food, 80, 1, Difficulty.HARD));
	}

	@Test
	void eatingAddsNutritionUpToTwentyAndSaturationUpToTheFood() {
		BotFood food = new BotFood();
		food.setFoodLevel(10);
		food.setSaturation(0);
		food.eat(8, 12.8F); // a steak
		assertEquals(18, food.food());
		assertEquals(12.8F, food.saturation(), 1e-6);
		food.eat(8, 12.8F);
		assertEquals(20, food.food());
		assertEquals(20, food.saturation());
	}
}
