package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.pathing.LevelWorldView;
import io.github.barniebarnie.antcraft.pathing.LoadedBlocks;
import io.github.barniebarnie.antcraft.pathing.PathFinder;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.GameEventListenerRegistry;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Bots following planned paths through terrain. Each test builds a room closed in
 * by bedrock so the bot cannot leave the test area.
 */
public class PathingGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** Bedrock walls on the border of the 8x8 area (y 0..7) and a floor at {@code floorY}. */
	private static void room(GameTestHelper helper, int floorY) {
		room(helper, floorY, 8);
	}

	/** Bedrock walls on the border of the 8x8 area, {@code height} blocks high, and a floor at {@code floorY}. */
	private static void room(GameTestHelper helper, int floorY, int height) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				boolean border = x == 0 || x == 7 || z == 0 || z == 7;
				for (int y = 0; y < height; y++) {
					if (border) {
						helper.setBlock(x, y, z, Blocks.BEDROCK);
					} else if (y <= floorY) {
						helper.setBlock(x, y, z, Blocks.BEDROCK);
					}
				}
			}
		}
	}

	private static void fill(GameTestHelper helper, int x1, int y1, int z1, int x2, int y2, int z2, Block block) {
		for (int x = x1; x <= x2; x++) {
			for (int y = y1; y <= y2; y++) {
				for (int z = z1; z <= z2; z++) {
					helper.setBlock(x, y, z, block);
				}
			}
		}
	}

	private static void succeedWhenAt(GameTestHelper helper, AntBot bot, BlockPos relativeTarget) {
		BlockPos target = helper.absolutePos(relativeTarget);
		helper.succeedWhen(() -> {
			helper.assertTrue(GameTests.walked(bot), "still walking, at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
			helper.assertTrue(bot.blockPosition().distManhattan(target) <= 1, "stopped at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
		});
	}

	/**
	 * Path searches and block scans read blocks through {@link LoadedBlocks}: the blocks of loaded chunks as the level
	 * has them, and nothing of a chunk that is not loaded, which stays unloaded, rather than holding up the tick loading it.
	 */
	@GameTest(maxTicks = 5)
	public void loadedBlocksReadsLoadedChunksOnlyAndLoadsNone(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		helper.setBlock(2, 1, 2, Blocks.OAK_LOG);
		LoadedBlocks blocks = new LoadedBlocks(level);
		for (BlockPos pos : List.of(new BlockPos(2, 1, 2), new BlockPos(2, 0, 2), new BlockPos(5, 3, 5))) {
			BlockPos at = helper.absolutePos(pos);
			helper.assertValueEqual(blocks.loaded(at), level.getBlockState(at), "state at " + pos.toShortString());
			helper.assertValueEqual(blocks.get(at), level.getBlockState(at), "state got at " + pos.toShortString());
		}
		BlockPos far = helper.absolutePos(new BlockPos(2, 1, 2)).offset(200_000, 0, 200_000);
		int x = SectionPos.blockToSectionCoord(far.getX());
		int z = SectionPos.blockToSectionCoord(far.getZ());
		helper.assertFalse(level.hasChunk(x, z), "the far chunk is loaded already");
		helper.assertTrue(blocks.loaded(far) == null, "a state for a chunk that is not loaded");
		helper.assertFalse(level.hasChunk(x, z), "the far chunk was loaded");
		helper.succeed();
	}

	/**
	 * A block a path breaks counts back as scaffold where its drop, broken with the bot's best tool, is one the bot
	 * places (#771): dirt and grass by hand, stone with a pickaxe only, not dirt the bot keeps.
	 */
	@GameTest(maxTicks = 5)
	public void aBlockDroppingAScaffoldBlockCountsBack(GameTestHelper helper) {
		helper.setBlock(2, 1, 2, Blocks.DIRT);
		helper.setBlock(3, 1, 2, Blocks.GRASS_BLOCK);
		helper.setBlock(4, 1, 2, Blocks.STONE);
		BlockPos dirt = helper.absolutePos(new BlockPos(2, 1, 2));
		BlockPos grass = helper.absolutePos(new BlockPos(3, 1, 2));
		BlockPos stone = helper.absolutePos(new BlockPos(4, 1, 2));
		Predicate<ItemStack> scaffold = stack -> stack.is(PathFollower.SCAFFOLD_BLOCKS);
		LevelWorldView hand = new LevelWorldView(helper.getLevel(), new SimpleContainer(9), ItemStack.EMPTY, Set.of()).scaffold(scaffold);
		helper.assertTrue(hand.dropsScaffold(dirt), "dirt does not count back");
		helper.assertTrue(hand.dropsScaffold(grass), "grass does not count back");
		helper.assertFalse(hand.dropsScaffold(stone), "stone counts back without a pickaxe");
		LevelWorldView pickaxe = new LevelWorldView(helper.getLevel(), new SimpleContainer(9), new ItemStack(Items.STONE_PICKAXE), Set.of()).scaffold(scaffold);
		helper.assertTrue(pickaxe.dropsScaffold(stone), "stone does not count back with a pickaxe");
		LevelWorldView keeping = new LevelWorldView(helper.getLevel(), new SimpleContainer(9), ItemStack.EMPTY, Set.of()).scaffold(scaffold.and(stack -> !stack.is(Items.DIRT)));
		helper.assertFalse(keeping.dropsScaffold(dirt), "kept dirt counts back");
		helper.succeed();
	}

	@GameTest(maxTicks = 100)
	public void digsThroughWall(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 4, 1, 1, 4, 6, 6, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 3);
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(6, 1, 3)));

		succeedWhenAt(helper, bot, new BlockPos(6, 1, 3));
	}

	/**
	 * A wall of oak fences, top to bottom, with a gap at the far end. A bot with a golden axe could chop through
	 * it sooner than walk round, but fences are what players build with, so it walks round.
	 */
	@GameTest(maxTicks = 150)
	public void walksRoundAFenceRatherThanThroughIt(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 4, 1, 1, 4, 7, 5, Blocks.OAK_FENCE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.GOLDEN_AXE));
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(6, 1, 1)));

		helper.onEachTick(() -> {
			for (int y = 1; y <= 7; y++) {
				for (int z = 1; z <= 5; z++) {
					if (!helper.getBlockState(new BlockPos(4, y, z)).is(Blocks.OAK_FENCE)) {
						helper.fail("broke the fence at " + new BlockPos(4, y, z).toShortString());
					}
				}
			}
		});
		succeedWhenAt(helper, bot, new BlockPos(6, 1, 1));
	}

	/** Like {@link #walksRoundAFenceRatherThanThroughIt}, with iron bars and a bot with a diamond pickaxe. */
	/** A cactus in the way: the bot walks round it a block off, never beside it, and the cactus never hurts it (#830). */
	// Measured: passes at tick 41 (red, without PathFinder.PRICKLY_COST: walked beside the cactus at 2, 1, 3 on tick 7).
	@GameTest(maxTicks = 80)
	public void walksRoundACactusWithoutBrushingIt(GameTestHelper helper) {
		room(helper, 0);
		helper.setBlock(3, 0, 3, Blocks.SAND);
		helper.setBlock(3, 1, 3, Blocks.CACTUS);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 3);
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(5, 1, 3)));

		helper.onEachTick(() -> {
			if (bot.getLastDamageSource() != null) {
				helper.fail("hurt by " + bot.getLastDamageSource().getMsgId() + " at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
			}
			BlockPos at = bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO));
			if (at.distManhattan(new BlockPos(3, 1, 3)) == 1) {
				helper.fail("walked beside the cactus at " + at.toShortString());
			}
		});
		succeedWhenAt(helper, bot, new BlockPos(5, 1, 3));
	}

	/**
	 * A walk that starts off the middle of its block, at its edge by a row of ripe sweet berry bushes, as a walk that ended
	 * on entering a block leaves the bot: it steps to the middle before it leaves, so its body never cuts into the bush at
	 * the corner it heads past (#845). Heading straight from the edge to the next block's middle, it swung into the bush.
	 */
	// Measured: passes at tick 15 (red: hurt by sweetBerryBush at 4.16, 1, 2.85 on tick 5).
	@GameTest(maxTicks = 40)
	public void leavesABlockByBushesFromItsMiddle(GameTestHelper helper) {
		room(helper, 0);
		for (int x = 1; x <= 3; x++) {
			helper.setBlock(x, 1, 3, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3));
		}
		fill(helper, 1, 1, 1, 6, 2, 1, Blocks.BEDROCK); // the lane at z 2, between the bushes and a wall
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(4.5, 1, 2.95));
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(1, 1, 2)));

		helper.onEachTick(() -> {
			if (bot.getLastDamageSource() != null) {
				helper.fail("hurt by " + bot.getLastDamageSource().getMsgId() + " at " + TestPositions.relative(helper, bot.position()));
			}
		});
		succeedWhenAt(helper, bot, new BlockPos(1, 1, 2));
	}

	/**
	 * A walk planned while the bot still moves on from its last one, which ended mid-step: it slides out of the block the
	 * path starts at into the one south of it. Followed from there, the path's first step east led it straight across the
	 * corner between the two, a sweet berry bush; it plans again from where it is instead (#848). So a berry farm's bot
	 * walked into a bush: pick_up's walk south ended as it took the berry, and take's walk east began.
	 */
	// Measured: passes at tick 20 (red: hurt by sweetBerryBush at (2.86, 1.0, 3.01) on tick 13).
	@GameTest(maxTicks = 40)
	public void aWalkFromTheBlockBesideWherePlannedPlansAgain(GameTestHelper helper) {
		room(helper, 0);
		helper.setBlock(3, 1, 3, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 2);
		ScriptRun run = BotScripts.run(bot, "wait(5)\ngoto((%d, %d, %d))\nwhile True:\n    wait(1000)\n".formatted(
			helper.absolutePos(new BlockPos(3, 1, 2)).getX(), helper.absolutePos(new BlockPos(3, 1, 2)).getY(), helper.absolutePos(new BlockPos(3, 1, 2)).getZ()));

		boolean[] pushed = new boolean[1];
		helper.onEachTick(() -> {
			// Entities tick before onEachTick: the walk planned from (2, 1, 2) this tick; set the bot moving south, over the
			// block's edge, as the walk before left it.
			if (!pushed[0] && run.walking()) {
				bot.setPos(bot.getX(), bot.getY(), helper.absoluteVec(new Vec3(2.5, 1, 2.95)).z);
				bot.setDeltaMovement(0, 0, 0.2);
				pushed[0] = true;
			}
			if (bot.getLastDamageSource() != null) {
				helper.fail("hurt by " + bot.getLastDamageSource().getMsgId() + " at " + TestPositions.relative(helper, bot.position()));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(pushed[0] && !run.walking(), "still walking, at " + TestPositions.relative(helper, bot.position()));
			helper.assertTrue(bot.blockPosition().equals(helper.absolutePos(new BlockPos(3, 1, 2))), "stopped at " + TestPositions.relative(helper, bot.position()));
		});
	}

	/**
	 * As {@link #aWalkFromTheBlockBesideWherePlannedPlansAgain}, but the path's first step is a step up east, onto a block
	 * with a sweet berry bush south of it: slid south off the step's start, the bot plans again rather than jump straight
	 * for the step's block across the bush (#860).
	 */
	@GameTest(maxTicks = 60)
	public void aStepUpFromTheBlockBesideWherePlannedPlansAgain(GameTestHelper helper) {
		room(helper, 0);
		helper.setBlock(3, 1, 2, Blocks.STONE);
		helper.setBlock(3, 1, 3, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 2);
		BlockPos goal = helper.absolutePos(new BlockPos(3, 2, 2));
		ScriptRun run = BotScripts.run(bot, "wait(5)\ngoto((%d, %d, %d))\nwhile True:\n    wait(1000)\n".formatted(goal.getX(), goal.getY(), goal.getZ()));

		boolean[] pushed = new boolean[1];
		int[] walking = new int[1];
		helper.onEachTick(() -> {
			if (!pushed[0] && run.walking() && ++walking[0] == 2) {
				bot.setPos(bot.getX(), bot.getY(), helper.absoluteVec(new Vec3(2.5, 1, 3.3)).z);
				bot.setDeltaMovement(0, 0, 0.2);
				pushed[0] = true;
			}
			if (bot.getLastDamageSource() != null) {
				helper.fail("hurt by " + bot.getLastDamageSource().getMsgId() + " at " + TestPositions.relative(helper, bot.position()));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(pushed[0] && !run.walking(), "still walking, at " + TestPositions.relative(helper, bot.position()));
			helper.assertTrue(bot.blockPosition().equals(goal), "stopped at " + TestPositions.relative(helper, bot.position()));
		});
	}

	/**
	 * A walk east along a lane that turns sharply south, with sweet berry bushes on the outside of the turn: turning, the bot
	 * keeps at least 0.1 block clear of them (#860). Turning from just short of the middle of the corner block, its way on
	 * east carried it within 0.08 of them.
	 */
	// Measured: passes at tick 41 (red: within 0.1 of the bushes at (4.62, 1, 1.89) on tick 21).
	@GameTest(maxTicks = 100)
	public void aSharpTurnBesideABushKeepsClearOfIt(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 1, 1, 2, 3, 2, 6, Blocks.BEDROCK); // the lane east at z 1, then south at x 4
		fill(helper, 5, 1, 3, 6, 2, 6, Blocks.BEDROCK);
		fill(helper, 6, 1, 1, 6, 2, 2, Blocks.BEDROCK);
		for (int z = 1; z <= 2; z++) {
			helper.setBlock(5, 0, z, Blocks.DIRT); // or the one bush set next to it drops the other off the stone
			helper.setBlock(5, 1, z, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3));
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(4, 1, 6)));

		AABB bushes = new AABB(helper.absolutePos(new BlockPos(5, 1, 1))).minmax(new AABB(helper.absolutePos(new BlockPos(5, 1, 2))));
		helper.onEachTick(() -> {
			if (bot.getBoundingBox().inflate(0.1).intersects(bushes) || bot.getLastDamageSource() != null) {
				helper.fail("within 0.1 of the bushes at " + TestPositions.relative(helper, bot.position()));
			}
		});
		succeedWhenAt(helper, bot, new BlockPos(4, 1, 6));
	}

	@GameTest(maxTicks = 150)
	public void walksRoundIronBarsRatherThanThroughThem(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 4, 1, 1, 4, 7, 5, Blocks.IRON_BARS);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.DIAMOND_PICKAXE));
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(6, 1, 1)));

		helper.onEachTick(() -> {
			for (int y = 1; y <= 7; y++) {
				for (int z = 1; z <= 5; z++) {
					if (!helper.getBlockState(new BlockPos(4, y, z)).is(Blocks.IRON_BARS)) {
						helper.fail("broke the bars at " + new BlockPos(4, y, z).toShortString());
					}
				}
			}
		});
		succeedWhenAt(helper, bot, new BlockPos(6, 1, 1));
	}

	/**
	 * A bedrock ceiling at y=4 with a hatch: a shut oak trapdoor over a ladder. A bot with no blocks climbs the ladder,
	 * opens the trapdoor, climbs out onto the ceiling and shuts the trapdoor behind it.
	 */
	@GameTest(maxTicks = 110)
	public void climbsALadderUpThroughAShutTrapdoorAndShutsIt(GameTestHelper helper) {
		BlockPos hatch = hatch(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 1);
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(3, 5, 1)));

		succeedWhenThroughTheHatch(helper, bot, new BlockPos(3, 5, 1), hatch);
	}

	/** Like {@link #climbsALadderUpThroughAShutTrapdoorAndShutsIt}, but down from the ceiling to the floor. */
	@GameTest(maxTicks = 110)
	public void climbsALadderDownThroughAShutTrapdoorAndShutsIt(GameTestHelper helper) {
		BlockPos hatch = hatch(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 5, 1);
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(3, 1, 1)));

		succeedWhenThroughTheHatch(helper, bot, new BlockPos(3, 1, 1), hatch);
	}

	/** A bedrock ceiling at y=4 over the room, with a shut oak trapdoor at (3, 4, 3) over a ladder facing north. */
	private static BlockPos hatch(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 1, 4, 1, 6, 4, 6, Blocks.BEDROCK);
		fill(helper, 3, 1, 4, 3, 3, 4, Blocks.BEDROCK);
		for (int y = 1; y <= 3; y++) {
			helper.setBlock(3, y, 3, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH));
		}
		BlockPos hatch = new BlockPos(3, 4, 3);
		helper.setBlock(hatch, Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, Direction.NORTH).setValue(TrapDoorBlock.HALF, Half.TOP));
		return hatch;
	}

	private static void succeedWhenThroughTheHatch(GameTestHelper helper, AntBot bot, BlockPos relativeTarget, BlockPos hatch) {
		BlockPos target = helper.absolutePos(relativeTarget);
		helper.succeedWhen(() -> {
			helper.assertTrue(GameTests.walked(bot) && bot.blockPosition().distManhattan(target) <= 1, "at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
			helper.assertTrue(helper.getBlockState(hatch).is(Blocks.OAK_TRAPDOOR), "the trapdoor is gone");
			helper.assertFalse(helper.getBlockState(hatch).getValue(TrapDoorBlock.OPEN), "left the trapdoor open");
			for (int y = 1; y <= 3; y++) {
				helper.assertTrue(helper.getBlockState(new BlockPos(3, y, 3)).is(Blocks.LADDER), "the ladder is gone at y " + y);
			}
		});
	}

	/** A free-standing column of scaffolding four high: a bot with no blocks climbs up inside it and stands on top. */
	@GameTest(maxTicks = 90)
	public void climbsAColumnOfScaffoldingToItsTop(GameTestHelper helper) {
		room(helper, 0);
		scaffoldingColumn(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 1);
		// Near the block above the top, so standing on the top is the only way to get there.
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(3, 6, 3)));

		succeedWhenAtWithTheColumnStanding(helper, bot, new BlockPos(3, 5, 3));
	}

	/** Like {@link #climbsAColumnOfScaffoldingToItsTop}, but down from its top to the floor: the bot sneaks to climb down inside it. */
	@GameTest(maxTicks = 70)
	public void climbsDownAColumnOfScaffoldingFromItsTop(GameTestHelper helper) {
		room(helper, 0);
		scaffoldingColumn(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 5, 3);
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(3, 1, 1)));

		succeedWhenAtWithTheColumnStanding(helper, bot, new BlockPos(3, 1, 2));
	}

	/** Scaffolding at (3, 1..4, 3), standing on the floor. */
	private static void scaffoldingColumn(GameTestHelper helper) {
		for (int y = 1; y <= 4; y++) {
			helper.setBlock(3, y, 3, Blocks.SCAFFOLDING.defaultBlockState().setValue(ScaffoldingBlock.DISTANCE, 0));
		}
	}

	/** Succeeds once the bot stopped on the ground at {@code relativeEnd}, not sneaking, with the column whole all the way. */
	private static void succeedWhenAtWithTheColumnStanding(GameTestHelper helper, AntBot bot, BlockPos relativeEnd) {
		BlockPos target = helper.absolutePos(relativeEnd);
		helper.onEachTick(() -> {
			for (int y = 1; y <= 4; y++) {
				if (!helper.getBlockState(new BlockPos(3, y, 3)).is(Blocks.SCAFFOLDING)) {
					helper.fail("the scaffolding is gone at y " + y);
				}
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(GameTests.walked(bot) && bot.blockPosition().equals(target) && bot.onGround(), "at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
			helper.assertFalse(bot.isShiftKeyDown(), "still sneaking");
		});
	}

	/**
	 * A bedrock wall three high across the room, with vines on its near side at (3, 1..3, 3): a bot with no blocks
	 * climbs the vines onto the wall.
	 */
	@GameTest(maxTicks = 110)
	public void climbsVinesUpAWall(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 1, 1, 4, 6, 3, 6, Blocks.BEDROCK);
		for (int y = 1; y <= 3; y++) {
			helper.setBlock(3, y, 3, Blocks.VINE.defaultBlockState().setValue(VineBlock.SOUTH, true));
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 1);
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(3, 4, 5)));

		BlockPos target = helper.absolutePos(new BlockPos(3, 4, 5));
		helper.succeedWhen(() -> {
			helper.assertTrue(GameTests.walked(bot) && bot.blockPosition().distManhattan(target) <= 1, "at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
			for (int y = 1; y <= 3; y++) {
				helper.assertTrue(helper.getBlockState(new BlockPos(3, y, 3)).is(Blocks.VINE), "the vine is gone at y " + y);
			}
		});
	}

	/**
	 * The vines up the bedrock wall of {@link #climbsVinesUpAWall}, with dirt two high on the wall's near edge, at
	 * (1..6, 4..5, 4): the bot climbs to the top of the vines and, hanging there, digs out the step onto the wall without
	 * sliding down. It let go while it dug, slid off the vines and planned again from the floor, which made the buried log
	 * of a giant jungle's crown take twice as long in some runs (#736).
	 */
	// Measured: passes at tick 92 (red: slid down the vines to y 2.3 at tick 39).
	@GameTest(maxTicks = 200)
	public void digsOffVinesWithoutSlidingDown(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 1, 1, 4, 6, 3, 6, Blocks.BEDROCK);
		fill(helper, 1, 4, 4, 6, 5, 4, Blocks.DIRT);
		for (int y = 1; y <= 3; y++) {
			helper.setBlock(3, y, 3, Blocks.VINE.defaultBlockState().setValue(VineBlock.SOUTH, true));
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 1);
		BlockPos target = helper.absolutePos(new BlockPos(3, 4, 5));
		GameTests.walkTo(bot, target, 0);
		BotScripts.failOnError(helper, bot);
		double floor = helper.absolutePos(BlockPos.ZERO).getY();
		boolean[] up = {false};
		helper.onEachTick(() -> {
			up[0] |= bot.getY() >= floor + 3;
			if (up[0] && bot.getY() < floor + 2.5) {
				helper.fail("slid down the vines to y " + (bot.getY() - floor));
			}
		});
		helper.succeedWhen(() -> helper.assertTrue(GameTests.walked(bot) && bot.blockPosition().equals(target) && bot.onGround(),
			"at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO))));
	}

	/**
	 * Bedrock from (1, 1, 1) to (6, 4, 3) but for a nook at (3, 3..4, 2), where the bot stands, and vines at (3, 2..4, 3)
	 * hanging on their south side from a bedrock block at (3, 4, 4): the bot drops onto the vines, then off them south,
	 * under that block, to the floor. Hanging on the vines most of a block over the one it drops off from, its head
	 * bumped the block as it moved off, and on vines a bump climbs: it rose, slid back and tried again until goto timed
	 * out, felling a mangrove's crown (#776).
	 */
	// Measured: passes at tick 34 (red: at (3, 3, 3) at its limit of 100, having climbed onto the block and dropped back).
	@GameTest(maxTicks = 70)
	public void dropsOffVinesUnderABlock(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 1, 1, 1, 6, 4, 3, Blocks.BEDROCK);
		fill(helper, 3, 3, 2, 3, 4, 2, Blocks.AIR);
		helper.setBlock(3, 4, 4, Blocks.BEDROCK);
		for (int y = 4; y >= 2; y--) {
			helper.setBlock(3, y, 3, Blocks.VINE.defaultBlockState().setValue(VineBlock.SOUTH, true));
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 3, 2);
		BlockPos target = helper.absolutePos(new BlockPos(3, 1, 5));
		GameTests.walkTo(bot, target, 0);
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> helper.assertTrue(GameTests.walked(bot) && bot.blockPosition().equals(target) && bot.onGround(),
			"at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO))));
	}

	/**
	 * A moss carpet at (3, 1, 3) with a vine over it at (3, 2, 3), hanging on its north side from bedrock at (3, 2, 2), and
	 * bedrock at (2, 1, 3) west of the carpet: the bot on the carpet walks west onto that block, nothing over the way out.
	 * A path counts the carpet a floor as high as a block, but its top is near a block under the bedrock's: the bot jumped
	 * into the vine and, over the level it walks off from, slid down to the carpet again before it moved off, and again, as
	 * in seed 13's mangrove crown at (8, 9, 7), where a moss carpet lies under a vine beside the dirt a pillar left: mine's
	 * walk to a branch log there gave up, and harvest ended out_of_reach (#784).
	 */
	// Measured: passes at tick 12 (red: at (3, 1, 3) at its limit of 100, jumping and sliding back).
	@GameTest(maxTicks = 40)
	public void walksOffAVineOverACarpetOntoABlock(GameTestHelper helper) {
		room(helper, 0);
		helper.setBlock(3, 1, 3, Blocks.MOSS_CARPET);
		helper.setBlock(3, 2, 2, Blocks.BEDROCK);
		helper.setBlock(3, 2, 3, Blocks.VINE.defaultBlockState().setValue(VineBlock.NORTH, true));
		helper.setBlock(2, 1, 3, Blocks.BEDROCK);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(3.5, 1 + 1 / 16.0, 3.5));
		BlockPos target = helper.absolutePos(new BlockPos(2, 2, 3));
		GameTests.walkTo(bot, target, 0);
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> helper.assertTrue(GameTests.walked(bot) && bot.blockPosition().equals(target) && bot.onGround(),
			"at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO))));
	}

	/**
	 * A bedrock shaft four high round (3, 1, 3): the bot with dirt pillars up out of it, and as it lands on its first block
	 * a vine grows in where it stands, on the south side. The search plans no pillar out of a vine, but vines grow after it
	 * planned, and on one a jump only climbs: the bot that jumped only from the ground never rose high enough to place its
	 * block, in some runs of the buried log of a giant jungle's crown (#736).
	 */
	// Measured: passes at tick 32 (red: still at y 2 at its limit of 100, the step timed out and no way planned on).
	@GameTest(maxTicks = 70)
	public void pillarsUpOutOfAVineGrownSincePlanned(GameTestHelper helper) {
		room(helper, 0);
		for (Direction side : Direction.Plane.HORIZONTAL) {
			BlockPos wall = new BlockPos(3, 1, 3).relative(side);
			fill(helper, wall.getX(), 1, wall.getZ(), wall.getX(), 4, wall.getZ(), Blocks.BEDROCK);
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 4));
		BlockPos target = helper.absolutePos(new BlockPos(3, 4, 3));
		GameTests.walkTo(bot, target, 0);
		BotScripts.failOnError(helper, bot);
		BlockPos vine = helper.absolutePos(new BlockPos(3, 2, 3));
		boolean[] grown = {false};
		helper.onEachTick(() -> {
			if (!grown[0] && bot.onGround() && bot.blockPosition().equals(vine)) {
				grown[0] = true;
				helper.setBlock(3, 2, 3, Blocks.VINE.defaultBlockState().setValue(VineBlock.SOUTH, true));
			}
		});
		helper.succeedWhen(() -> helper.assertTrue(grown[0] && GameTests.walked(bot) && bot.blockPosition().equals(target) && bot.onGround(),
			"at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO))));
	}

	/**
	 * A stone walkway three over the floor at z 3 from x 1 to 6, its block at x 3 jungle leaves with no log in range
	 * (distance 7), which decay at a random tick, and a way round them at z 4: the bot goes round and never stands on
	 * the leaves. Felling a giant jungle, it walked on leaves that decayed under it once the trunk was gone (#673).
	 */
	// Measured: passes at tick 36 (red: stood on the leaves at tick 12).
	@GameTest(maxTicks = 70)
	public void walksRoundLeavesThatDecay(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 1, 3, 3, 6, 3, 3, Blocks.STONE);
		fill(helper, 2, 3, 4, 4, 3, 4, Blocks.STONE);
		BlockPos leaves = new BlockPos(3, 3, 3);
		helper.setBlock(leaves, Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, LeavesBlock.DECAY_DISTANCE));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 4, 3);
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(6, 4, 3)));

		helper.onEachTick(() -> {
			if (bot.onGround() && bot.blockPosition().below().equals(helper.absolutePos(leaves))) {
				helper.fail("stood on the leaves");
			}
		});
		succeedWhenAt(helper, bot, new BlockPos(6, 4, 3));
	}

	/**
	 * A stone block four over the floor at (4, 4, 4), the bot over its west edge, its feet in the air beside it, as a bot
	 * lands that overshoots a drop, with dirt: to get a block higher, it plans from the block it rests on and pillars up
	 * there. Felling a giant jungle, a bot that landed so planned from the block its feet were in, a step up placing a
	 * block where its body was, and moved back to the middle of that block to make room, off the edge, and fell (#673).
	 */
	// Measured: passes at tick 12 (red: fell off at tick 11).
	@GameTest(maxTicks = 25)
	public void climbsFromOverTheEdgeOfTheBlockItRestsOn(GameTestHelper helper) {
		room(helper, 0);
		helper.setBlock(4, 4, 4, Blocks.STONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(3.8, 5, 4.5));
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 4));
		helper.runAfterDelay(3, () -> GameTests.walkTo(bot, helper.absolutePos(new BlockPos(4, 6, 4)), 0)); // landed

		helper.onEachTick(() -> {
			if (bot.getY() < helper.absolutePos(BlockPos.ZERO).getY() + 4.5) {
				helper.fail("fell off, at " + bot.position().subtract(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO))));
			}
		});
		succeedWhenAt(helper, bot, new BlockPos(4, 6, 4));
	}

	/** A bedrock wall across the room with a closed oak door in it: the bot opens the door, walks through and shuts it behind it. */
	@GameTest(maxTicks = 40)
	public void walksThroughAClosedDoorAndShutsIt(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 4, 1, 1, 4, 7, 6, Blocks.BEDROCK);
		BlockPos door = new BlockPos(4, 1, 3);
		BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
		helper.setBlock(door, lower);
		helper.setBlock(door.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 3);
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(6, 1, 3)));

		BlockPos target = helper.absolutePos(new BlockPos(6, 1, 3));
		helper.succeedWhen(() -> {
			helper.assertTrue(GameTests.walked(bot) && bot.blockPosition().distManhattan(target) <= 1, "at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
			helper.assertTrue(helper.getBlockState(door).is(Blocks.OAK_DOOR), "the door is gone");
			helper.assertFalse(helper.getBlockState(door).getValue(DoorBlock.OPEN), "left the door open");
		});
	}

	/**
	 * Like {@link #walksThroughAClosedDoorAndShutsIt}, but counts the door's state changes: open once, shut once, no
	 * flicker while the bot walks up to it. Both facings, so the shut panel is once on the near and once on the far side.
	 */
	@GameTest(maxTicks = 60)
	public void opensAndShutsADoorFacingEastOnceOnTheWayThrough(GameTestHelper helper) {
		opensAndShutsADoorOnce(helper, Direction.EAST);
	}

	@GameTest(maxTicks = 60)
	public void opensAndShutsADoorFacingWestOnceOnTheWayThrough(GameTestHelper helper) {
		opensAndShutsADoorOnce(helper, Direction.WEST);
	}

	private static void opensAndShutsADoorOnce(GameTestHelper helper, Direction facing) {
		room(helper, 0);
		fill(helper, 4, 1, 1, 4, 7, 6, Blocks.BEDROCK);
		BlockPos door = new BlockPos(4, 1, 3);
		BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, facing);
		helper.setBlock(door, lower);
		helper.setBlock(door.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 3);
		succeedWhenThroughTheDoorOnce(helper, bot, door, new BlockPos(6, 1, 3));
	}

	/**
	 * Like {@link #opensAndShutsADoorFacingEastOnceOnTheWayThrough}, but the doorway is on the edge of a ledge two
	 * blocks high: the bot walks into the doorway and drops out of it, and the door opens and shuts once.
	 */
	@GameTest(maxTicks = 60)
	public void opensAndShutsADoorOnceDroppingThroughIt(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 1, 1, 1, 4, 2, 6, Blocks.BEDROCK);
		fill(helper, 4, 3, 1, 4, 7, 6, Blocks.BEDROCK);
		BlockPos door = new BlockPos(4, 3, 3);
		BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
		helper.setBlock(door, lower);
		helper.setBlock(door.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 3, 3);
		succeedWhenThroughTheDoorOnce(helper, bot, door, new BlockPos(6, 1, 3));
	}

	/**
	 * Like {@link #opensAndShutsADoorOnceDroppingThroughIt}, but the other way: the doorway is on a step one block up,
	 * and the bot steps up into it from the floor, and the door opens and shuts once.
	 */
	@GameTest(maxTicks = 60)
	public void opensAndShutsADoorOnceSteppingUpThroughIt(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 4, 1, 1, 6, 1, 6, Blocks.BEDROCK);
		fill(helper, 4, 2, 1, 4, 7, 6, Blocks.BEDROCK);
		BlockPos door = new BlockPos(4, 2, 3);
		BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
		helper.setBlock(door, lower);
		helper.setBlock(door.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 3);
		succeedWhenThroughTheDoorOnce(helper, bot, door, new BlockPos(6, 2, 3));
	}

	/**
	 * Like {@link #opensAndShutsADoorOnceSteppingUpThroughIt}, but down: the bot is on a step one block up across the
	 * whole room, the doorway is on the floor with a hole above it, and the bot drops into it from the step, and the
	 * door opens and shuts once.
	 */
	@GameTest(maxTicks = 70)
	public void opensAndShutsADoorOnceDroppingIntoIt(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 1, 1, 1, 3, 1, 6, Blocks.BEDROCK);
		fill(helper, 4, 1, 1, 4, 7, 6, Blocks.BEDROCK);
		BlockPos door = new BlockPos(4, 1, 3);
		BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
		helper.setBlock(door, lower);
		helper.setBlock(door.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		helper.setBlock(door.above(2), Blocks.AIR);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 2, 3);
		succeedWhenThroughTheDoorOnce(helper, bot, door, new BlockPos(6, 1, 3));
	}

	/** Sends the bot to {@code relativeTarget} and succeeds once it is there and the door opened and shut once on the way. */
	private static void succeedWhenThroughTheDoorOnce(GameTestHelper helper, AntBot bot, BlockPos door, BlockPos relativeTarget) {
		BlockPos target = helper.absolutePos(relativeTarget);
		// Into the target block itself: stopping as soon as it is within a block of it can leave the bot in the swing of the door.
		GameTests.walkTo(bot, target, 0);

		// Counts every open and shut, also one undone in the same tick, which a once-a-tick look at the door would miss.
		BlockPos at = helper.absolutePos(door);
		int[] changes = {0};
		GameEventListener counter = new GameEventListener() {
			@Override
			public PositionSource getListenerSource() {
				return new BlockPositionSource(at);
			}

			@Override
			public int getListenerRadius() {
				return 1;
			}

			@Override
			public boolean handleGameEvent(ServerLevel level, Holder<GameEvent> event, GameEvent.Context context, Vec3 pos) {
				if (BlockPos.containing(pos).equals(at) && (event.is(GameEvent.BLOCK_OPEN) || event.is(GameEvent.BLOCK_CLOSE))) {
					changes[0]++;
				}
				return false;
			}
		};
		GameEventListenerRegistry listeners = helper.getLevel().getChunkAt(at).getListenerRegistry(SectionPos.blockToSectionCoord(at.getY()));
		listeners.register(counter);
		helper.onEachTick(() -> {
			if (changes[0] > 2) {
				listeners.unregister(counter);
				helper.fail("the door changed state " + changes[0] + " times");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(GameTests.walked(bot) && bot.blockPosition().distManhattan(target) <= 1, "at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
			helper.assertTrue(changes[0] == 2, "the door changed state " + changes[0] + " times, bot at " + bot.position().subtract(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO))));
			listeners.unregister(counter);
		});
	}

	/** A pen of oak fences across the room with a closed gate in it: the bot opens the gate to get in. */
	@GameTest(maxTicks = 40)
	public void walksThroughAClosedFenceGateIntoAPen(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 4, 1, 1, 4, 1, 6, Blocks.OAK_FENCE);
		BlockPos gate = new BlockPos(4, 1, 3);
		helper.setBlock(gate, Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.EAST));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 3);
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(6, 1, 3)));

		BlockPos target = helper.absolutePos(new BlockPos(6, 1, 3));
		helper.succeedWhen(() -> {
			helper.assertTrue(GameTests.walked(bot) && bot.blockPosition().distManhattan(target) <= 1, "at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
			helper.assertFalse(helper.getBlockState(gate).getValue(FenceGateBlock.OPEN), "left the gate open");
		});
	}

	/**
	 * Like {@link #walksThroughAClosedDoorAndShutsIt}, but the walk ends in the doorway, with the door still open
	 * around the bot. Something other than that walk then takes the bot away (here a teleport), and the door shuts.
	 */
	@GameTest(maxTicks = 50)
	public void shutsADoorItStoppedInOnceItLeaves(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 4, 1, 1, 4, 7, 6, Blocks.BEDROCK);
		BlockPos door = new BlockPos(4, 1, 3);
		BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
		helper.setBlock(door, lower);
		helper.setBlock(door.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 3);
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(5, 1, 3)));

		// Waits a few ticks in the doorway first, so the walk has wholly ended before the bot leaves.
		boolean[] left = {false};
		int[] waited = {0};
		helper.onEachTick(() -> {
			if (left[0] || !GameTests.walked(bot) || ++waited[0] < 5) {
				return;
			}
			helper.assertTrue(bot.blockPosition().equals(helper.absolutePos(door)), "stopped at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)) + ", not in the doorway");
			helper.assertTrue(helper.getBlockState(door).getValue(DoorBlock.OPEN), "shut the door on the bot");
			BlockPos away = helper.absolutePos(new BlockPos(2, 1, 3));
			bot.teleportTo(away.getX() + 0.5, away.getY(), away.getZ() + 0.5);
			left[0] = true;
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(left[0], "still walking");
			helper.assertFalse(helper.getBlockState(door).getValue(DoorBlock.OPEN), "left the door open");
		});
	}

	/**
	 * Like {@link #shutsADoorItStoppedInOnceItLeaves}, but the bot is saved and loaded again (as when its chunk unloads)
	 * before it leaves: the loaded bot still shuts the door it opened.
	 */
	@GameTest(maxTicks = 50)
	public void shutsADoorItOpenedBeforeItWasSavedAndLoaded(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 4, 1, 1, 4, 7, 6, Blocks.BEDROCK);
		BlockPos door = new BlockPos(4, 1, 3);
		BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
		helper.setBlock(door, lower);
		helper.setBlock(door.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 3);
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(5, 1, 3)));

		boolean[] left = {false};
		int[] waited = {0};
		helper.onEachTick(() -> {
			if (left[0] || !GameTests.walked(bot) || ++waited[0] < 5) {
				return;
			}
			helper.assertTrue(helper.getBlockState(door).getValue(DoorBlock.OPEN), "shut the door on the bot");
			TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
			bot.saveWithoutId(output);
			bot.discard();
			AntBot copy = helper.spawn(ModEntities.ANT_BOT, 2, 1, 3);
			copy.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), output.buildResult()));
			BlockPos away = helper.absolutePos(new BlockPos(2, 1, 3));
			copy.teleportTo(away.getX() + 0.5, away.getY(), away.getZ() + 0.5);
			left[0] = true;
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(left[0], "still walking");
			helper.assertFalse(helper.getBlockState(door).getValue(DoorBlock.OPEN), "left the door open");
		});
	}

	@GameTest(maxTicks = 50)
	public void bridgesGap(GameTestHelper helper) {
		room(helper, 3);
		// A pit too deep to drop into and climb out of.
		fill(helper, 3, 1, 1, 4, 3, 6, Blocks.AIR);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 4, 3);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 16));
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(6, 4, 3)));

		succeedWhenAt(helper, bot, new BlockPos(6, 4, 3));
	}

	@GameTest(maxTicks = 90)
	public void pillarsUpToLedge(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 5, 1, 1, 6, 3, 6, Blocks.BEDROCK);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 16));
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(6, 4, 3)));

		succeedWhenAt(helper, bot, new BlockPos(6, 4, 3));
	}

	/**
	 * A bot with no scaffold block at the bottom of a bedrock shaft three deep, its walls dirt at the bottom two blocks
	 * on the two sides away from its goal, so no path without placing comes nearer: it digs the dirt, which it picks
	 * up, and pillars out with it (#778).
	 */
	@GameTest(maxTicks = 160)
	public void digsDirtToPillarOutOfAPitWithNoScaffold(GameTestHelper helper) {
		fill(helper, 1, 1, 1, 6, 3, 6, Blocks.BEDROCK);
		fill(helper, 3, 1, 3, 3, 3, 3, Blocks.AIR);
		fill(helper, 2, 1, 3, 2, 2, 3, Blocks.DIRT);
		fill(helper, 3, 1, 2, 3, 2, 2, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		BotScripts.failOnError(helper, bot);
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(5, 4, 3)));

		succeedWhenAt(helper, bot, new BlockPos(5, 4, 3));
	}

	/**
	 * A bot pillars up a one-wide shaft from a spot holding a torch: the torch is broken with its drop before the
	 * scaffold goes there (#608), so it ends up in the bot's inventory or on the ground, not deleted.
	 */
	// Measured: passes at tick 22 (red: the torch gone without a drop).
	@GameTest(maxTicks = 50)
	public void pillaringBreaksATorchWithItsDrop(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 1, 1, 1, 6, 7, 6, Blocks.BEDROCK);
		fill(helper, 3, 2, 3, 3, 7, 3, Blocks.AIR);
		helper.setBlock(3, 1, 3, Blocks.TORCH);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(3, 4, 3)));
		helper.succeedWhen(() -> {
			helper.assertTrue(GameTests.walked(bot), "still walking, at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
			boolean dropped = !helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(8),
				item -> item.getItem().is(Items.TORCH)).isEmpty();
			helper.assertTrue(bot.getInventory().countItem(Items.TORCH) == 1 || dropped, "the torch vanished");
		});
	}

	/**
	 * A bot at the bottom of a two-wide shaft whose other half holds a sugar cane: pillaring up the cane's half is the
	 * cheapest way to the top there, but a path never puts scaffold where a plant stands (#865). It pillars up its own
	 * half and bridges over, and the cane stays, as a farm's crop, cane or bush would.
	 */
	// Measured: passes at tick 23 (red: the cane became dirt on tick 3).
	@GameTest(maxTicks = 50)
	public void pillaringGoesRoundACane(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 1, 1, 1, 6, 7, 6, Blocks.BEDROCK);
		fill(helper, 2, 1, 3, 3, 7, 3, Blocks.AIR);
		helper.setBlock(3, 0, 3, Blocks.SAND);
		helper.setBlock(4, 0, 3, Blocks.WATER);
		helper.setBlock(3, 1, 3, Blocks.SUGAR_CANE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 3);
		BotScripts.failOnError(helper, bot);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		helper.onEachTick(() -> {
			if (!helper.getBlockState(new BlockPos(3, 1, 3)).is(Blocks.SUGAR_CANE)) {
				helper.fail("the cane became " + helper.getBlockState(new BlockPos(3, 1, 3)));
			}
		});
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(3, 4, 3)));
		succeedWhenAt(helper, bot, new BlockPos(3, 4, 3));
	}

	/**
	 * A bot on a block, its body over the block's edge into the air beside it, steps up onto a block it bridges there
	 * (#656): it steps back off the edge to place it, as its own body was in the way, where it waited till it gave up
	 * unreachable, such as beside a felled poplar's trunk.
	 */
	// Measured: passes at tick 16, jumping straight onto the block it placed (#670: tick 96, as it jumped mid-turn and
	// fell off; #656 red: still walking at tick 300).
	@GameTest(maxTicks = 35)
	public void bridgesOutFromTheEdgeOfABlock(GameTestHelper helper) {
		helper.setBlock(3, 1, 3, Blocks.STONE);
		helper.setBlock(3, 2, 5, Blocks.STONE); // the face the bridged block goes against
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(3.5, 2, 3.78));
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(3, 3, 4)), 0);
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of("True"), "printed");
			helper.assertValueEqual(bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)), new BlockPos(3, 3, 4), "stopped at");
		});
	}

	/**
	 * Two bots at the bottom of a one-wide shaft, one carrying dirt to pillar up. It does not place its scaffold into the
	 * other bot, which would suffocate it, but waits; once the other bot is gone, it pillars up.
	 */
	@GameTest(maxTicks = 120)
	public void doesNotPlaceScaffoldIntoAnotherBot(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 1, 1, 1, 6, 7, 6, Blocks.BEDROCK);
		fill(helper, 3, 1, 3, 3, 7, 3, Blocks.AIR);
		AntBot other = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		AntBot climber = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		climber.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		GameTests.walkTo(climber, helper.absolutePos(new BlockPos(3, 4, 3)));
		BlockPos feet = helper.absolutePos(new BlockPos(3, 1, 3));
		helper.onEachTick(() -> {
			if (other.isAlive()) {
				for (BlockPos pos : List.of(feet, feet.above())) {
					if (!helper.getLevel().getBlockState(pos).isAir()) {
						helper.fail("placed " + helper.getLevel().getBlockState(pos) + " into the other bot at " + pos.subtract(helper.absolutePos(BlockPos.ZERO)));
					}
				}
				if (helper.getTick() >= 40) {
					helper.assertFalse(GameTests.walked(climber), "the climber gave up while the other bot was in the way");
					other.discard();
				}
			}
		});
		helper.succeedWhen(() -> {
			helper.assertFalse(other.isAlive(), "the other bot is still in the shaft");
			helper.assertTrue(GameTests.walked(climber), "still walking, at " + climber.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
			helper.assertTrue(climber.blockPosition().distManhattan(helper.absolutePos(new BlockPos(3, 4, 3))) <= 1,
				"stopped at " + climber.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
		});
	}

	/**
	 * A bot with no role pillars up in a one-wide corridor. Another bot without scaffold blocks
	 * then has to get past the pillar: it gives up instead of digging it out from under the first.
	 */
	@GameTest(maxTicks = 60)
	public void leavesPillarOfOtherBotAlone(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 1, 1, 1, 6, 7, 2, Blocks.BEDROCK);
		fill(helper, 1, 1, 4, 6, 7, 6, Blocks.BEDROCK);
		AntBot owner = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		owner.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		GameTests.walkTo(owner, helper.absolutePos(new BlockPos(3, 4, 3)));
		AntBot other = helper.spawn(ModEntities.ANT_BOT, 1, 1, 3);
		Set<BlockPos> pillar = new HashSet<>();
		helper.onEachTick(() -> {
			if (pillar.isEmpty() && GameTests.walked(owner)) {
				for (int y = 1; y <= 6; y++) {
					if (helper.getBlockState(new BlockPos(3, y, 3)).is(Blocks.DIRT)) {
						pillar.add(new BlockPos(3, y, 3));
					}
				}
				helper.assertFalse(pillar.isEmpty(), "the owner did not pillar");
				GameTests.walkTo(other, helper.absolutePos(new BlockPos(6, 1, 3)));
			}
			for (BlockPos pos : pillar) {
				if (!helper.getBlockState(pos).is(Blocks.DIRT)) {
					helper.fail("the other bot broke the pillar at " + pos.toShortString());
				}
			}
		});
		helper.succeedWhen(() -> {
			helper.assertFalse(pillar.isEmpty(), "the owner has not pillared yet");
			helper.assertTrue(GameTests.walked(other), "the other bot is still walking");
		});
	}

	/** A bot without scaffold dropped in the middle of a 5x5 pool, 2 deep, swims to the shore and climbs out. */
	@GameTest(maxTicks = 200)
	public void swimsOutOfADeepPool(GameTestHelper helper) {
		room(helper, 2);
		fill(helper, 1, 1, 1, 5, 2, 5, Blocks.WATER);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 2, 3);
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(6, 3, 6)));

		succeedWhenAt(helper, bot, new BlockPos(6, 3, 6));
	}

	/**
	 * Times a bot swimming across a moat 4 wide, 2 deep and as wide as the room, so there is no way round, and
	 * checks that {@link PathFinder#SWIM_COST} matches the ticks it takes per block. Only the middle of the moat
	 * counts, after the splash of jumping in has worn off and before the bot climbs out.
	 */
	@GameTest(maxTicks = 160)
	public void swimCostMatchesTheTimeASwimTakes(GameTestHelper helper) {
		room(helper, 2);
		fill(helper, 2, 1, 1, 5, 2, 6, Blocks.WATER);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 3, 3);
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(6, 3, 3)));
		double startX = 3.5;
		double endX = 5.0;
		long[] crossed = {-1, -1};
		helper.onEachTick(() -> {
			double x = bot.getX() - helper.absolutePos(BlockPos.ZERO).getX();
			if (crossed[0] < 0 && x >= startX) {
				crossed[0] = helper.getTick();
			}
			if (crossed[1] < 0 && x >= endX) {
				crossed[1] = helper.getTick();
			}
		});

		helper.succeedWhen(() -> {
			helper.assertTrue(crossed[1] >= 0, "not across yet, at " + TestPositions.relative(helper, bot.blockPosition()));
			double perBlock = (crossed[1] - crossed[0]) / (endX - startX);
			helper.assertTrue(Math.abs(perBlock - PathFinder.SWIM_COST) <= PathFinder.SWIM_COST / 5,
				"swimming took " + perBlock + " ticks per block, but SWIM_COST is " + PathFinder.SWIM_COST);
		});
	}

	/**
	 * A waterfall, falling water from a source on top of a cliff 4 high into a still pool a block deep, is the only
	 * way up. A bot without scaffold swims across the pool into the foot of the waterfall, up it and out on top.
	 */
	@GameTest(maxTicks = 560)
	public void swimsUpAWaterfall(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 4, 1, 1, 6, 4, 6, Blocks.BEDROCK);
		fill(helper, 1, 1, 1, 3, 1, 6, Blocks.WATER);
		// Walls either side of the waterfall at (3, 1..4, 3), so it is the only way up, and one beside its source.
		fill(helper, 2, 1, 2, 3, 4, 2, Blocks.BEDROCK);
		fill(helper, 2, 1, 4, 3, 4, 4, Blocks.BEDROCK);
		helper.setBlock(2, 4, 3, Blocks.BEDROCK);
		helper.setBlock(3, 4, 3, Blocks.WATER);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 5);
		// Let the waterfall run down before planning.
		helper.runAfterDelay(40, () -> GameTests.walkTo(bot, helper.absolutePos(new BlockPos(5, 5, 3))));

		succeedWhenAt(helper, bot, new BlockPos(5, 5, 3));
	}

	/**
	 * A waterfall 10 high, from a source on top of a cliff into a still pool, is the only way up, as in
	 * {@link #swimsUpAWaterfall}. Times the bot rising through the middle of it and checks that
	 * {@link PathFinder#SWIM_UP_COST} matches the ticks it takes per block.
	 */
	@GameTest(structure = "antcraft-gametest:tall", maxTicks = 440)
	public void swimUpCostMatchesTheTimeARiseTakes(GameTestHelper helper) {
		room(helper, 0, 16);
		fill(helper, 4, 1, 1, 6, 10, 6, Blocks.BEDROCK);
		fill(helper, 1, 1, 1, 3, 1, 6, Blocks.WATER);
		fill(helper, 2, 1, 2, 3, 10, 2, Blocks.BEDROCK);
		fill(helper, 2, 1, 4, 3, 10, 4, Blocks.BEDROCK);
		helper.setBlock(2, 10, 3, Blocks.BEDROCK);
		helper.setBlock(3, 10, 3, Blocks.WATER);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 5);
		helper.runAfterDelay(60, () -> GameTests.walkTo(bot, helper.absolutePos(new BlockPos(5, 11, 3))));
		double startY = 3.0;
		double endY = 8.0;
		long[] crossed = {-1, -1};
		// The rise speed on the last tick, and the first tick it dropped while rising from startY to endY.
		double[] lastRise = {Double.NaN};
		String[] slowed = {null};
		helper.onEachTick(() -> {
			double y = bot.getY() - helper.absolutePos(BlockPos.ZERO).getY();
			if (crossed[0] < 0 && y >= startY) {
				crossed[0] = helper.getTick();
			}
			if (crossed[1] < 0 && y >= endY) {
				crossed[1] = helper.getTick();
			}
			double rise = bot.getDeltaMovement().y;
			if (crossed[0] >= 0 && crossed[1] < 0 && slowed[0] == null && rise < lastRise[0] - 0.005) {
				slowed[0] = "at y " + y + " on tick " + helper.getTick() + ", from " + lastRise[0] + " to " + rise;
			}
			lastRise[0] = rise;
		});

		helper.succeedWhen(() -> {
			helper.assertTrue(crossed[1] >= 0, "not up yet, at " + TestPositions.relative(helper, bot.blockPosition()));
			// A stroke missed slows the rise, so the time it takes would vary from run to run.
			helper.assertTrue(slowed[0] == null, "the bot rose slower " + slowed[0]);
			double perBlock = (crossed[1] - crossed[0]) / (endY - startY);
			helper.assertTrue(Math.abs(perBlock - PathFinder.SWIM_UP_COST) <= PathFinder.SWIM_UP_COST / 4,
				"rising took " + perBlock + " ticks per block, but SWIM_UP_COST is " + PathFinder.SWIM_UP_COST);
		});
	}

	/**
	 * A channel a block wide with a water source at its upper end, so water flows down it the whole way. A bot at
	 * the lower end swims against the current to the bank beside the source.
	 */
	@GameTest(maxTicks = 420)
	public void swimsUpstreamThroughFlowingWater(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 1, 1, 2, 6, 2, 2, Blocks.BEDROCK);
		fill(helper, 1, 1, 4, 6, 2, 4, Blocks.BEDROCK);
		helper.setBlock(6, 1, 3, Blocks.BEDROCK);
		helper.setBlock(5, 1, 3, Blocks.WATER);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 3);
		// Let the water flow down the channel before planning.
		helper.runAfterDelay(40, () -> GameTests.walkTo(bot, helper.absolutePos(new BlockPos(6, 2, 3))));

		succeedWhenAt(helper, bot, new BlockPos(6, 2, 3));
	}

	/**
	 * The only way down from a ledge is a fall of {@link PathFinder#MAX_WATER_DROP} blocks, far more than
	 * {@code PathFollower}'s usual drop of 3, into water a block deep. Water breaks the fall: the bot drops in unhurt
	 * and climbs out on the far bank.
	 */
	@GameTest(structure = "antcraft-gametest:tall", maxTicks = 260)
	public void dropsFarIntoWaterUnhurt(GameTestHelper helper) {
		int fall = PathFinder.MAX_WATER_DROP;
		room(helper, 0, 16);
		fill(helper, 1, 1, 1, 2, fall, 6, Blocks.BEDROCK);
		fill(helper, 3, 1, 1, 5, 1, 6, Blocks.WATER);
		fill(helper, 6, 1, 1, 6, 1, 6, Blocks.BEDROCK);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, fall + 1, 3);
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(6, 2, 3)));
		helper.onEachTick(() -> helper.assertTrue(bot.getHealth() == bot.getMaxHealth(), "hurt by the fall"));

		succeedWhenAt(helper, bot, new BlockPos(6, 2, 3));
	}

	/**
	 * A bot up a dirt pillar 12 high, its home {@code homeOff} blocks east of the pillar's top, at {@code food} with
	 * {@code saturation} (a player starts at 20 with 5), walks to the floor beside the pillar: off the pillar, hurt, or
	 * digging down it (#682, #708).
	 */
	private static AntBot upAPillar(GameTestHelper helper, int homeOff, int food, float saturation) {
		room(helper, 0, 16);
		fill(helper, 3, 1, 3, 3, 12, 3, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 13, 3);
		bot.setHome(helper.absolutePos(new BlockPos(3 + homeOff, 13, 3)));
		bot.getFood().setFoodLevel(food);
		bot.getFood().setSaturation(saturation);
		BotScripts.failOnError(helper, bot);
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(5, 1, 3)));
		return bot;
	}

	/**
	 * With its home on the pillar, a bot jumps off it: of the 9 health the fall takes its food heals 3 quickly (10 ticks
	 * a point) and the rest costs no more than its share of a death (some 11 ticks a point, less than healing it slowly),
	 * some 100 ticks in all, which with the drop costs less than digging 12 dirt down (some 250) (#682, #708).
	 */
	@GameTest(structure = "antcraft-gametest:tall", maxTicks = 60)
	public void jumpsOffAPillarWhereThatCostsLessThanDiggingDown(GameTestHelper helper) {
		AntBot bot = upAPillar(helper, 0, 20, 5);
		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getHealth() < bot.getMaxHealth(), "not hurt: dug down? pillar top at " + topOf(helper));
			helper.assertTrue(helper.getBlockState(new BlockPos(3, 12, 3)).is(Blocks.DIRT), "dug the pillar");
			helper.assertTrue(GameTests.walked(bot) && bot.blockPosition().distManhattan(helper.absolutePos(new BlockPos(5, 1, 3))) <= 1,
				"at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
		});
	}

	/**
	 * With its home 100 blocks off but full with 20 saturation, the bot heals the 9 health in 90 ticks with 13.5 points of
	 * food (some 70 ticks, #730), which costs less than digging down, so it jumps off (#708).
	 */
	@GameTest(structure = "antcraft-gametest:tall", maxTicks = 60)
	public void aWellFedBotJumpsOffAPillarWithItsHomeFarOff(GameTestHelper helper) {
		AntBot bot = upAPillar(helper, 100, 20, 20);
		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getHealth() < bot.getMaxHealth(), "not hurt: dug down? pillar top at " + topOf(helper));
			helper.assertTrue(helper.getBlockState(new BlockPos(3, 12, 3)).is(Blocks.DIRT), "dug the pillar");
			helper.assertTrue(GameTests.walked(bot) && bot.blockPosition().distManhattan(helper.absolutePos(new BlockPos(5, 1, 3))) <= 1,
				"at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
		});
	}

	/**
	 * With its home 100 blocks off and too hungry to heal (17 food), the 9 health are 9/20 of a death, more than digging
	 * down costs, so the bot digs down unhurt (#682). A bot with a player's starting food heals some of it and jumps (#708).
	 */
	@GameTest(structure = "antcraft-gametest:tall", maxTicks = 400)
	public void digsDownAPillarWhereThatCostsLessThanJumpingOff(GameTestHelper helper) {
		AntBot bot = upAPillar(helper, 100, 17, 0);
		helper.onEachTick(() -> helper.assertTrue(bot.getHealth() == bot.getMaxHealth(), "hurt: jumped off"));
		succeedWhenAt(helper, bot, new BlockPos(5, 1, 3));
	}

	/**
	 * A bot up a bedrock pillar 12 high, which it cannot dig, its home in the Nether: the drop is its only way down, and it
	 * takes it, costed with a fixed walk back from the other dimension (#688). Before, it had no death to cost and stayed up.
	 */
	@GameTest(structure = "antcraft-gametest:tall", maxTicks = 60)
	public void dropsOffAPillarWithItsHomeInAnotherDimension(GameTestHelper helper) {
		room(helper, 0, 16);
		fill(helper, 3, 1, 3, 3, 12, 3, Blocks.BEDROCK);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 13, 3);
		bot.setHome(helper.absolutePos(new BlockPos(3, 13, 3)), Level.NETHER);
		BotScripts.failOnError(helper, bot);
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(5, 1, 3)));
		succeedWhenAt(helper, bot, new BlockPos(5, 1, 3));
	}

	/**
	 * Up through dirt as through a giant spruce's crown, a path stepped up breaking the block over its head, up again
	 * beside it, and back up onto the block it broke, and the bot fell into the gap, 2 blocks, and jumped there until
	 * the step's time ran out (#687). Now it places a block there, or goes another way, and never lands lower than it was.
	 */
	// Measured: passes at tick 32 (red: fell at tick 63).
	@GameTest(maxTicks = 80)
	public void stepsUpOntoNoBlockItsPathBroke(GameTestHelper helper) {
		helper.setBlock(2, 1, 3, Blocks.DIRT);
		helper.setBlock(2, 3, 3, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
		helper.setBlock(3, 2, 3, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 2);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 4));
		BotScripts.failOnError(helper, bot);
		double[] highest = {1};
		helper.onEachTick(() -> {
			if (bot.onGround()) {
				double y = bot.getY() - helper.absolutePos(BlockPos.ZERO).getY();
				if (y < highest[0] - 0.5) {
					helper.fail("fell from y " + highest[0] + " to " + y + " at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
				}
				highest[0] = Math.max(highest[0], y);
			}
		});
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(2, 4, 3)), 0);
		succeedWhenAt(helper, bot, new BlockPos(2, 4, 3));
	}

	/** The height of the top block of the pillar of {@link #upAPillar}. */
	private static int topOf(GameTestHelper helper) {
		int y = 12;
		while (y > 0 && !helper.getBlockState(new BlockPos(3, y, 3)).is(Blocks.DIRT)) {
			y--;
		}
		return y;
	}

	/**
	 * A band of blocks lower than a full one, from x 2 to 5 across the room on its floor (#642): the bot walks onto it,
	 * along it and off it to a goal past it, and breaks none of it.
	 */
	private static void walksAcross(GameTestHelper helper, BlockState band) {
		room(helper, 0);
		for (int x = 2; x <= 5; x++) {
			for (int z = 1; z <= 6; z++) {
				helper.setBlock(x, 1, z, band);
			}
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 3);
		BlockPos target = helper.absolutePos(new BlockPos(6, 1, 3));
		GameTests.walkTo(bot, target);
		helper.succeedWhen(() -> {
			for (int x = 2; x <= 5; x++) {
				for (int z = 1; z <= 6; z++) {
					helper.assertValueEqual(helper.getBlockState(new BlockPos(x, 1, z)), band, "block of the band at " + x + ", " + z);
				}
			}
			helper.assertTrue(GameTests.walked(bot), "still walking, at " + bot.position().subtract(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO))));
			helper.assertValueEqual(bot.blockPosition(), target, "where it stopped");
		});
	}

	// Measured: passes at tick 47.
	@GameTest(maxTicks = 100)
	public void walksAcrossSoulSand(GameTestHelper helper) {
		walksAcross(helper, Blocks.SOUL_SAND.defaultBlockState());
	}

	// Measured: passes at tick 33.
	@GameTest(maxTicks = 70)
	public void walksAcrossMud(GameTestHelper helper) {
		walksAcross(helper, Blocks.MUD.defaultBlockState());
	}

	// Measured: passes at tick 33 (red: stopped at x 1, a chest neither stood on nor broken).
	@GameTest(maxTicks = 70)
	public void walksAcrossChests(GameTestHelper helper) {
		walksAcross(helper, Blocks.CHEST.defaultBlockState());
	}

	/** Snow three layers deep: a quarter of a block high. */
	// Measured: passes at tick 27 (red: broke the snow; then, walked on, a jump at every block until tick 92).
	@GameTest(maxTicks = 70)
	public void walksAcrossThickSnow(GameTestHelper helper) {
		walksAcross(helper, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3));
	}

	// Measured: passes at tick 33 (red: broke the dirt path).
	@GameTest(maxTicks = 70)
	public void walksAcrossADirtPath(GameTestHelper helper) {
		walksAcross(helper, Blocks.DIRT_PATH.defaultBlockState());
	}

	// Measured: passes at tick 27 (red: broke the slab; then, walked on, still walking at tick 100).
	@GameTest(maxTicks = 70)
	public void walksAcrossBottomSlabs(GameTestHelper helper) {
		walksAcross(helper, Blocks.OAK_SLAB.defaultBlockState());
	}

	/**
	 * A bot on a bottom slab beside a block two high, in a room (#647): a jump from the slab's top falls short of the
	 * block's, a block and a half higher, so it steps down onto the floor and up a stone step beside it, rather than
	 * jumping at the block until it gives up.
	 */
	// Measured: passes at tick 53 (red: jumped at the block from the slab until it gave up, at tick 100).
	@GameTest(maxTicks = 100)
	public void stepsUpByWayOfTheFloorFromABottomSlab(GameTestHelper helper) {
		room(helper, 0);
		helper.setBlock(2, 1, 3, Blocks.OAK_SLAB);
		fill(helper, 3, 1, 3, 3, 2, 3, Blocks.STONE);
		helper.setBlock(4, 1, 3, Blocks.STONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(2.5, 1.5, 3.5));
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(3, 3, 3)), 0);
		helper.succeedWhen(() -> {
			helper.assertValueEqual(WorkGameTest.printed(bot), List.of("True"), "printed");
			helper.assertBlockPresent(Blocks.OAK_SLAB, 2, 1, 3);
		});
	}

	/**
	 * A bot with Jump Boost II on honey beside a block two high (#665): the boost lifts its jump from the honey over the
	 * block's top, so it steps straight up rather than down onto the floor and round by the stone step, as a bot without
	 * the boost does ({@link #stepsUpByWayOfTheFloorFromABottomSlab}).
	 */
	// Measured: passes at tick 13 (red: stepped down onto the floor at tick 15).
	@GameTest(maxTicks = 30)
	public void stepsUpFromHoneyWithJumpBoost(GameTestHelper helper) {
		room(helper, 0);
		helper.setBlock(2, 1, 3, Blocks.HONEY_BLOCK);
		fill(helper, 3, 1, 3, 3, 2, 3, Blocks.STONE);
		helper.setBlock(4, 1, 3, Blocks.STONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(2.5, 2, 3.5));
		bot.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, 1200, 1));
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(3, 3, 3)), 0);
		BlockPos origin = helper.absolutePos(BlockPos.ZERO);
		helper.onEachTick(() -> {
			if (bot.onGround() && bot.getY() < origin.getY() + 1.5) {
				helper.fail("stepped down onto the floor");
			}
		});
		helper.succeedWhen(() -> helper.assertValueEqual(WorkGameTest.printed(bot), List.of("True"), "printed"));
	}

	/**
	 * A bot in the air over a bottom slab, just spawned or mid-jump, is in the slab's block but its feet count in the
	 * block over it, so a path planned then starts there, not a block low (#659).
	 */
	@GameTest(maxTicks = 20)
	public void aPathPlannedInTheAirOverALowFloorStartsOverIt(GameTestHelper helper) {
		room(helper, 0);
		helper.setBlock(2, 1, 3, Blocks.OAK_SLAB);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(2.5, 1.8, 3.5));
		helper.assertFalse(bot.onGround(), "on the ground at spawn");
		PathFinder.Search search = new PathFollower(bot).plan(new Goal.Near(helper.absolutePos(new BlockPos(5, 1, 3)), 0));
		search.run(10_000);
		helper.assertValueEqual(search.path().orElseThrow().start(), helper.absolutePos(new BlockPos(2, 2, 3)), "path start");
		helper.succeed();
	}

	/**
	 * A bot not sneaking stands on scaffolding under its feet anywhere in a column, as vanilla gives it the full top from
	 * above (#666), so paths read scaffolding mid-column as a floor. These pin that down: a walk through the column between
	 * two ledges, a climb down it out onto a ledge, and a stop inside it.
	 */
	@GameTest(maxTicks = 50)
	public void walksThroughAColumnOfScaffoldingMidway(GameTestHelper helper) {
		room(helper, 0);
		scaffoldingColumn(helper);
		fill(helper, 3, 2, 1, 3, 2, 2, Blocks.BEDROCK);
		fill(helper, 3, 2, 4, 3, 2, 5, Blocks.BEDROCK);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 3, 1);
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(3, 3, 5)), 0);

		succeedWhenAtWithTheColumnStanding(helper, bot, new BlockPos(3, 3, 5));
	}

	/** Like {@link #walksThroughAColumnOfScaffoldingMidway}: down the column from its top and out onto a ledge midway. */
	@GameTest(maxTicks = 40)
	public void climbsDownAColumnOfScaffoldingOntoALedgeMidway(GameTestHelper helper) {
		room(helper, 0);
		scaffoldingColumn(helper);
		helper.setBlock(3, 2, 4, Blocks.BEDROCK);
		// No drop from the top onto the ledge: the way down is inside the column.
		helper.setBlock(3, 5, 4, Blocks.BEDROCK);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 5, 3);
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(3, 3, 4)), 0);

		succeedWhenAtWithTheColumnStanding(helper, bot, new BlockPos(3, 3, 4));
	}

	/** Like {@link #walksThroughAColumnOfScaffoldingMidway}: a walk that ends inside the column stays there, standing. */
	@GameTest(maxTicks = 60)
	public void stopsInsideAColumnOfScaffoldingMidway(GameTestHelper helper) {
		room(helper, 0);
		scaffoldingColumn(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 1);
		BlockPos target = helper.absolutePos(new BlockPos(3, 3, 3));
		GameTests.walkTo(bot, target, 0);
		int[] standing = {0};
		helper.onEachTick(() -> {
			if (GameTests.walked(bot)) {
				helper.assertTrue(bot.blockPosition().equals(target) && bot.onGround(), "at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)) + ", on the ground " + bot.onGround());
				if (++standing[0] >= 10) {
					helper.succeed();
				}
			}
		});
	}
}
