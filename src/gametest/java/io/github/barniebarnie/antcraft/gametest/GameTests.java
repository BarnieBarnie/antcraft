package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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

	/** How far sideways a mob picks up items beyond its own box ({@code Mob.ITEM_PICKUP_REACH}). */
	private static final double PICKUP_REACH = 1;
	/** The name {@link #spawnOutside} marks its items with. */
	private static final Component OUTSIDE = Component.literal("outside the test area");

	/**
	 * Spawns {@code item} at {@code pos}, just outside the test area, marked so the test can tell it from items of its
	 * own. Just outside one test area is inside or beside the next, whose bots may take it, so tests check with
	 * {@link #failIfTakenFromOutside} that their own bot did not, rather than that the item still lies there.
	 * <p>
	 * Fails the test if the item lies within the game's own pickup reach of a bot standing in the area (the area grown
	 * by {@link #PICKUP_REACH} sideways): a bot fetching an item at the edge would then sweep it up by chance, and the
	 * test fail without the bot going for it. {@link GameTestHelper#spawnItem(Item, BlockPos)} puts the item on the
	 * corner of {@code pos}, so on the east side x=9 is too near and x=10 the nearest that is safe.
	 */
	public static void spawnOutside(GameTestHelper helper, Item item, BlockPos pos) {
		ItemEntity entity = helper.spawnItem(item, pos);
		if (entity.getBoundingBox().intersects(helper.getBounds().inflate(PICKUP_REACH, 0, PICKUP_REACH))) {
			entity.discard();
			helper.fail("item outside the test area at " + pos.toShortString() + " lies within pickup reach of a bot in it");
		}
		ItemStack stack = entity.getItem().copy();
		stack.set(DataComponents.CUSTOM_NAME, OUTSIDE);
		entity.setItem(stack);
	}

	/** Fails the test as soon as {@code bot} carries, or {@code store} holds, an item marked by {@link #spawnOutside}. */
	public static void failIfTakenFromOutside(GameTestHelper helper, AntBot bot, Container store) {
		helper.onEachTick(() -> {
			Stream<ItemStack> stacks = Stream.concat(Stream.of(bot.getMainHandItem()),
				Stream.of(bot.getInventory(), store).flatMap(container -> IntStream.range(0, container.getContainerSize()).mapToObj(container::getItem)));
			stacks.filter(stack -> OUTSIDE.equals(stack.get(DataComponents.CUSTOM_NAME))).findFirst()
				.ifPresent(stack -> helper.fail("picked up the " + stack.getItem() + " outside the test area"));
		});
	}

	/**
	 * Stands every build plan of {@code colony}, such as its placed blueprints with their levelling, in the world at once,
	 * protecting every block placed, as builders would, and finishes them: for tests about a built building, without
	 * waiting for bots to build it.
	 */
	public static void buildNow(GameTestHelper helper, Colony colony) {
		for (int round = 0; round < 10 && !colony.builds().isEmpty(); round++) {
			for (BuildPlan plan : colony.builds()) {
				for (BuildPlan.Step step : plan.steps()) {
					helper.getLevel().setBlockAndUpdate(step.pos(), step.state());
					if (!step.state().isAir()) {
						colony.protect(step.pos(), step.state().getBlock());
					}
				}
			}
			colony.postBuildJobs();
			colony.board().jobs().stream().filter(job -> job.isOpen() && colony.build(job.id()).isPresent()).toList().forEach(job -> {
				colony.finishBatch(job.id());
				colony.board().complete(job.id());
			});
		}
		helper.assertTrue(colony.blueprints().stream().allMatch(placed -> placed.finished()), "blueprints not built: " + colony.blueprints());
	}
}
