package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRespawns;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.MinecraftServer;
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
		onEnd(helper, () -> forgetRespawns(helper.getLevel().getServer(), bounds));
	}

	/** Forgets every bot waiting to respawn at a home in {@code bounds}, in every level: what {@link #start} does at a test's end. */
	static void forgetRespawns(MinecraftServer server, AABB bounds) {
		server.getAllLevels().forEach(level -> BotRespawns.get(level).forget(server, home -> bounds.contains(Vec3.atCenterOf(home))));
	}

	/** The shortest tick {@link #waitAtMost} lets the server run: 5 ms. */
	private static final long PACED_TICK_NANOS = 5_000_000L;

	/** How long {@link #waitAtMost} lets a wait last: 15 s. */
	public static final long WAIT_SECONDS = 15;

	/** The ticks a wait of {@link #WAIT_SECONDS} lasts at most at the paced 5 ms a tick: a test's {@code maxTicks} adds its own to it. */
	public static final int PACED_WAIT_TICKS = 3000;

	/**
	 * Gives a wait on something that happens in the background, such as a chunk's entities loading from disk, a budget in
	 * wall-clock time rather than in ticks (#355, #356). Game tests run at a new spot each run, so their chunks are new, and
	 * the IO worker that loads their entities can take seconds while other tests' chunks are saved, as the idle server runs
	 * thousands of ticks. Paces the server to at least {@link #PACED_TICK_NANOS} a tick, and fails the test at once once
	 * {@code waiting} has held for {@link #WAIT_SECONDS} on end, with {@code failure}. So a test whose {@code maxTicks} is
	 * {@link #PACED_WAIT_TICKS} plus its own ticks never times out on the wait first. Registers an {@code onEachTick}
	 * listener: call it up front.
	 */
	public static void waitAtMost(GameTestHelper helper, BooleanSupplier waiting, Supplier<String> failure) {
		long[] lastTick = {System.nanoTime()};
		long[] since = {-1};
		helper.onEachTick(() -> {
			long wait = lastTick[0] + PACED_TICK_NANOS - System.nanoTime();
			if (wait > 0) {
				LockSupport.parkNanos(wait);
			}
			lastTick[0] = System.nanoTime();
			if (!waiting.getAsBoolean()) {
				since[0] = -1;
			} else if (since[0] < 0) {
				since[0] = lastTick[0];
			} else if (lastTick[0] - since[0] > WAIT_SECONDS * 1_000_000_000L) {
				helper.fail(failure.get() + " after " + WAIT_SECONDS + " s");
			}
		});
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
