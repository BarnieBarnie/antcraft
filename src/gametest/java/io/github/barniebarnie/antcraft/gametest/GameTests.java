package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.entity.AntBot;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Helpers shared by the game tests: keeping tests apart, how long each one took, and failing early once a test cannot pass any more. */
public final class GameTests {
	private static final Logger LOGGER = LoggerFactory.getLogger("AntCraft game tests");
	/**
	 * The area of every test started, which a colony founded in it is clipped to. Tests run side by side 5 or 6 blocks
	 * apart, closer than a colony's radius, so without this a colony would pick up items, flowers and work in other
	 * tests, and protect and keep open its mineshaft there.
	 */
	// ponytail: never cleared and scanned in full; a test area is never reused and there are a few hundred at most.
	private static final List<BoundingBox> AREAS = new ArrayList<>();

	static {
		ColonyManager.setTestArea(core -> AREAS.stream().filter(area -> area.isInside(core)).findFirst().orElse(null));
	}

	private GameTests() {
	}

	/**
	 * Call first in {@code invokeTestMethod}. Keeps the colonies founded in the test inside its area (see
	 * {@link #AREAS}), and logs the tick the test passes or fails on, as
	 * {@code Game test <id> passed at tick <n> of <maxTicks>}, so {@code maxTicks} can be set from measured runs.
	 */
	public static void start(GameTestHelper helper) {
		AABB bounds = helper.getBounds();
		AREAS.add(BoundingBox.fromCorners(BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ),
			BlockPos.containing(bounds.maxX - 1, bounds.maxY - 1, bounds.maxZ - 1)));
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
