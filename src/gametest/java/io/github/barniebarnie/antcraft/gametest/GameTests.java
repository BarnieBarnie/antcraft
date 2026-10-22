package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.entity.AntBot;
import java.lang.reflect.Field;
import java.util.function.BooleanSupplier;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Helpers shared by the game tests: how long each one took, and failing early once a test cannot pass any more. */
public final class GameTests {
	private static final Logger LOGGER = LoggerFactory.getLogger("AntCraft game tests");

	private GameTests() {
	}

	/**
	 * Logs the tick each test passes or fails on, as {@code Game test <id> passed at tick <n> of <maxTicks>}, so
	 * {@code maxTicks} can be set from measured runs. Call it first in {@code invokeTestMethod}.
	 */
	public static void logTicks(GameTestHelper helper) {
		GameTestInfo info = info(helper);
		info.addListener(new GameTestListener() {
			@Override
			public void testStructureLoaded(GameTestInfo test) {
			}

			@Override
			public void testPassed(GameTestInfo test, GameTestRunner runner) {
				LOGGER.info("Game test {} passed at tick {} of {}", test.id(), test.getTick(), test.getTimeoutTicks());
			}

			@Override
			public void testFailed(GameTestInfo test, GameTestRunner runner) {
				LOGGER.info("Game test {} failed at tick {} of {}", test.id(), test.getTick(), test.getTimeoutTicks());
			}

			@Override
			public void testAddedForRerun(GameTestInfo old, GameTestInfo next, GameTestRunner runner) {
			}
		});
	}

	// ponytail: GameTestHelper has no getter for its test; reflection beats a mixin for test-only code.
	private static GameTestInfo info(GameTestHelper helper) {
		try {
			Field field = GameTestHelper.class.getDeclaredField("testInfo");
			field.setAccessible(true);
			return (GameTestInfo) field.get(helper);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Fails the test as soon as the colony gives up a job or reports a build step blocked, instead of waiting out
	 * {@code maxTicks}. For tests where nothing is out of reach.
	 */
	public static void failOnGiveUp(GameTestHelper helper, Colony colony) {
		helper.onEachTick(() -> {
			colony.board().jobs().stream().filter(job -> job.givenUp()).findFirst()
				.ifPresent(job -> helper.fail("gave up " + job.type() + " at " + TestPositions.relative(helper, job.target()).toShortString()));
			colony.blockedSteps().stream().findFirst()
				.ifPresent(pos -> helper.fail("build step blocked at " + TestPositions.relative(helper, pos).toShortString()));
		});
	}

	/**
	 * Fails the test once {@code bot} has had no work for {@code ticks} ticks in a row while {@code workLeft}, instead
	 * of waiting out {@code maxTicks}.
	 */
	public static void failIfIdle(GameTestHelper helper, AntBot bot, int ticks, BooleanSupplier workLeft) {
		helper.onEachTick(() -> {
			if (bot.getIdleTicks() >= ticks && workLeft.getAsBoolean()) {
				helper.fail("bot idle for " + ticks + " ticks with work left");
			}
		});
	}
}
