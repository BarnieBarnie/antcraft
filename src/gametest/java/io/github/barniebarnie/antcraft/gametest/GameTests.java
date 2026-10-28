package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRespawns;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Helpers shared by the game tests: how long each one took, cleaning up after one, and sending a bot somewhere. */
public final class GameTests {
	private static final Logger LOGGER = LoggerFactory.getLogger("AntCraft game tests");

	private GameTests() {
	}

	/**
	 * Call first in {@code invokeTestMethod}. Logs the tick the test passes or fails on, as
	 * {@code Game test <id> passed at tick <n> of <maxTicks>}, so {@code maxTicks} can be set from measured runs.
	 */
	public static void start(GameTestHelper helper) {
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
		// A bot that died in the test would otherwise come back at its home in the test's area after the test, into
		// whatever test runs there next. Every level: a bot waits in the level of its home, not of where it died.
		AABB bounds = helper.getBounds();
		onEnd(helper, () -> helper.getLevel().getServer().getAllLevels()
			.forEach(level -> BotRespawns.get(level).forget(home -> bounds.contains(Vec3.atCenterOf(home)))));
	}

	/** Runs {@code cleanup} once the test passed or failed, such as to remove a player or file it added. */
	public static void onEnd(GameTestHelper helper, Runnable cleanup) {
		info(helper).addListener(new GameTestListener() {
			@Override
			public void testStructureLoaded(GameTestInfo test) {
			}

			@Override
			public void testPassed(GameTestInfo test, GameTestRunner runner) {
				cleanup.run();
			}

			@Override
			public void testFailed(GameTestInfo test, GameTestRunner runner) {
				cleanup.run();
			}

			@Override
			public void testAddedForRerun(GameTestInfo old, GameTestInfo next, GameTestRunner runner) {
			}
		});
	}

	/** The blocks, in absolute positions, that are under spawn protection (see SpawnProtectionMixin). */
	private static final Set<BlockPos> SPAWN_PROTECTED = ConcurrentHashMap.newKeySet();

	/**
	 * Puts the block at {@code pos} of the test area under the server's spawn protection until the test ends, for every
	 * player but an operator, as a dedicated server with {@code spawn-protection} does near spawn. The game test server
	 * protects nothing by itself.
	 */
	public static void protect(GameTestHelper helper, BlockPos pos) {
		BlockPos absolute = helper.absolutePos(pos);
		SPAWN_PROTECTED.add(absolute);
		onEnd(helper, () -> SPAWN_PROTECTED.remove(absolute));
	}

	/** Whether {@link #protect} put the block at the absolute {@code pos} under spawn protection. */
	public static boolean isSpawnProtected(BlockPos pos) {
		return SPAWN_PROTECTED.contains(pos);
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
	 * Sends {@code bot} to within a block of {@code target}, as {@code goto(target, near=1)} in a script that then waits
	 * there, so it stays rather than going home; {@link #walked} tells when the walk ended. Starts on the bot's next tick.
	 */
	public static void walkTo(AntBot bot, BlockPos target) {
		walkTo(bot, target, 1);
	}

	/** Like {@link #walkTo(AntBot, BlockPos)}, to within {@code near} blocks of {@code target}. */
	public static void walkTo(AntBot bot, BlockPos target, int near) {
		BotScripts.run(bot, "print(goto((" + target.getX() + ", " + target.getY() + ", " + target.getZ() + "), near=" + near + ").ok)\n"
			+ "while True:\n    wait(1000)\n");
	}

	/** Whether the walk {@link #walkTo} started has ended, there or given up. */
	public static boolean walked(AntBot bot) {
		return !BotScripts.log(bot, Output.Level.PRINT).isEmpty();
	}
}
