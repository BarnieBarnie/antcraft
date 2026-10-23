package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.pathing.PathFinder;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.Terraform;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
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
			helper.assertTrue(bot.getWalkTarget() == null, "still walking, at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
			helper.assertTrue(bot.blockPosition().distManhattan(target) <= 1, "stopped at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
		});
	}

	@GameTest(maxTicks = 100)
	public void digsThroughWall(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 4, 1, 1, 4, 6, 6, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 3);
		bot.setWalkTarget(helper.absolutePos(new BlockPos(6, 1, 3)));

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
		bot.setWalkTarget(helper.absolutePos(new BlockPos(6, 1, 1)));

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
	@GameTest(maxTicks = 150)
	public void walksRoundIronBarsRatherThanThroughThem(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 4, 1, 1, 4, 7, 5, Blocks.IRON_BARS);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.DIAMOND_PICKAXE));
		bot.setWalkTarget(helper.absolutePos(new BlockPos(6, 1, 1)));

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
		bot.setWalkTarget(helper.absolutePos(new BlockPos(3, 5, 1)));

		succeedWhenThroughTheHatch(helper, bot, new BlockPos(3, 5, 1), hatch);
	}

	/** Like {@link #climbsALadderUpThroughAShutTrapdoorAndShutsIt}, but down from the ceiling to the floor. */
	@GameTest(maxTicks = 110)
	public void climbsALadderDownThroughAShutTrapdoorAndShutsIt(GameTestHelper helper) {
		BlockPos hatch = hatch(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 5, 1);
		bot.setWalkTarget(helper.absolutePos(new BlockPos(3, 1, 1)));

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
			helper.assertTrue(bot.getWalkTarget() == null && bot.blockPosition().distManhattan(target) <= 1, "at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
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
		bot.setWalkTarget(helper.absolutePos(new BlockPos(3, 6, 3)));

		succeedWhenAtWithTheColumnStanding(helper, bot, new BlockPos(3, 5, 3));
	}

	/** Like {@link #climbsAColumnOfScaffoldingToItsTop}, but down from its top to the floor: the bot sneaks to climb down inside it. */
	@GameTest(maxTicks = 70)
	public void climbsDownAColumnOfScaffoldingFromItsTop(GameTestHelper helper) {
		room(helper, 0);
		scaffoldingColumn(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 5, 3);
		bot.setWalkTarget(helper.absolutePos(new BlockPos(3, 1, 1)));

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
			helper.assertTrue(bot.getWalkTarget() == null && bot.blockPosition().equals(target) && bot.onGround(), "at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
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
		bot.setWalkTarget(helper.absolutePos(new BlockPos(3, 4, 5)));

		BlockPos target = helper.absolutePos(new BlockPos(3, 4, 5));
		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getWalkTarget() == null && bot.blockPosition().distManhattan(target) <= 1, "at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
			for (int y = 1; y <= 3; y++) {
				helper.assertTrue(helper.getBlockState(new BlockPos(3, y, 3)).is(Blocks.VINE), "the vine is gone at y " + y);
			}
		});
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
		bot.setWalkTarget(helper.absolutePos(new BlockPos(6, 1, 3)));

		BlockPos target = helper.absolutePos(new BlockPos(6, 1, 3));
		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getWalkTarget() == null && bot.blockPosition().distManhattan(target) <= 1, "at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
			helper.assertTrue(helper.getBlockState(door).is(Blocks.OAK_DOOR), "the door is gone");
			helper.assertFalse(helper.getBlockState(door).getValue(DoorBlock.OPEN), "left the door open");
		});
	}

	/**
	 * Like {@link #walksThroughAClosedDoorAndShutsIt}, but counts the door's state changes: open once, shut once, no
	 * flicker while the bot walks up to it. Both facings, so the shut panel is once on the near and once on the far side.
	 */
	@GameTest(maxTicks = 40)
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
	@GameTest(maxTicks = 40)
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
		bot.setWalkTarget(target);

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
			helper.assertTrue(bot.getWalkTarget() == null && bot.blockPosition().distManhattan(target) <= 1, "at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
			helper.assertTrue(changes[0] == 2, "the door changed state " + changes[0] + " times");
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
		bot.setWalkTarget(helper.absolutePos(new BlockPos(6, 1, 3)));

		BlockPos target = helper.absolutePos(new BlockPos(6, 1, 3));
		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getWalkTarget() == null && bot.blockPosition().distManhattan(target) <= 1, "at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
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
		bot.setWalkTarget(helper.absolutePos(new BlockPos(5, 1, 3)));

		// Waits a few ticks in the doorway first, so the walk has wholly ended before the bot leaves.
		boolean[] left = {false};
		int[] waited = {0};
		helper.onEachTick(() -> {
			if (left[0] || bot.getWalkTarget() != null || ++waited[0] < 5) {
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
		bot.setWalkTarget(helper.absolutePos(new BlockPos(5, 1, 3)));

		boolean[] left = {false};
		int[] waited = {0};
		helper.onEachTick(() -> {
			if (left[0] || bot.getWalkTarget() != null || ++waited[0] < 5) {
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
		bot.setWalkTarget(helper.absolutePos(new BlockPos(6, 4, 3)));

		succeedWhenAt(helper, bot, new BlockPos(6, 4, 3));
	}

	@GameTest(maxTicks = 90)
	public void pillarsUpToLedge(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 5, 1, 1, 6, 3, 6, Blocks.BEDROCK);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 16));
		bot.setWalkTarget(helper.absolutePos(new BlockPos(6, 4, 3)));

		succeedWhenAt(helper, bot, new BlockPos(6, 4, 3));
	}

	/**
	 * As {@link #pillarsUpToLedge}, but a colony's mineshaft hall runs along z=3 at the bot's feet and a branch
	 * leaves it at x=4, right where the bot would pillar. It gets onto the ledge without placing a block in them.
	 */
	@GameTest(maxTicks = 120)
	public void pillarsAroundMineshaftHallsAndBranches(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 5, 1, 1, 6, 3, 6, Blocks.BEDROCK);
		helper.setBlock(6, 1, 6, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(new BlockPos(6, 1, 6))).orElseThrow();
		colony.setStockTargets(List.of());
		// An old shaft whose level at y=1 lands at (7, 1, 3), with its hall running west along z=3.
		int level = helper.absolutePos(new BlockPos(0, 1, 0)).getY();
		Mineshaft shaft = new Mineshaft(helper.absolutePos(new BlockPos(7, 5, 3)), List.of(level), Mineshaft.DEFAULT_BRANCHES_PER_SIDE,
			Mineshaft.DEFAULT_BRANCH_LENGTH, Mineshaft.DEFAULT_TORCH_SPACING, Mineshaft.DEFAULT_ROUNDS, false);
		colony.setMineshaft(shaft);
		colony.removeBuild(shaft.plan());
		Set<BlockPos> open = new HashSet<>();
		shaft.hall(level).forEach(cell -> open.addAll(List.of(cell, cell.above())));
		shaft.branches(level).forEach(branch -> open.addAll(branch.cells()));
		helper.assertTrue(open.contains(helper.absolutePos(new BlockPos(4, 1, 3))), "the hall does not run through the pillar spot");
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 16));
		bot.setWalkTarget(helper.absolutePos(new BlockPos(6, 4, 3)));

		helper.onEachTick(() -> {
			for (BlockPos pos : open) {
				if (helper.getLevel().getBlockState(pos).is(Blocks.COBBLESTONE)) {
					helper.fail("placed a block in the mineshaft at " + TestPositions.relative(helper, pos).toShortString());
				}
			}
		});
		succeedWhenAt(helper, bot, new BlockPos(6, 4, 3));
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
		owner.setWalkTarget(helper.absolutePos(new BlockPos(3, 4, 3)));
		AntBot other = helper.spawn(ModEntities.ANT_BOT, 1, 1, 3);
		Set<BlockPos> pillar = new HashSet<>();
		helper.onEachTick(() -> {
			if (pillar.isEmpty() && owner.getWalkTarget() == null) {
				for (int y = 1; y <= 6; y++) {
					if (helper.getBlockState(new BlockPos(3, y, 3)).is(Blocks.DIRT)) {
						pillar.add(new BlockPos(3, y, 3));
					}
				}
				helper.assertFalse(pillar.isEmpty(), "the owner did not pillar");
				other.setWalkTarget(helper.absolutePos(new BlockPos(6, 1, 3)));
			}
			for (BlockPos pos : pillar) {
				if (!helper.getBlockState(pos).is(Blocks.DIRT)) {
					helper.fail("the other bot broke the pillar at " + pos.toShortString());
				}
			}
		});
		helper.succeedWhen(() -> {
			helper.assertFalse(pillar.isEmpty(), "the owner has not pillared yet");
			helper.assertTrue(other.getWalkTarget() == null, "the other bot is still walking");
		});
	}

	/** A bot without scaffold dropped in the middle of a 5x5 pool, 2 deep, swims to the shore and climbs out. */
	@GameTest(maxTicks = 200)
	public void swimsOutOfADeepPool(GameTestHelper helper) {
		room(helper, 2);
		fill(helper, 1, 1, 1, 5, 2, 5, Blocks.WATER);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 2, 3);
		bot.setWalkTarget(helper.absolutePos(new BlockPos(6, 3, 6)));

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
		bot.setWalkTarget(helper.absolutePos(new BlockPos(6, 3, 3)));
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
		helper.runAfterDelay(40, () -> bot.setWalkTarget(helper.absolutePos(new BlockPos(5, 5, 3))));

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
		helper.runAfterDelay(60, () -> bot.setWalkTarget(helper.absolutePos(new BlockPos(5, 11, 3))));
		double startY = 3.0;
		double endY = 8.0;
		long[] crossed = {-1, -1};
		helper.onEachTick(() -> {
			double y = bot.getY() - helper.absolutePos(BlockPos.ZERO).getY();
			if (crossed[0] < 0 && y >= startY) {
				crossed[0] = helper.getTick();
			}
			if (crossed[1] < 0 && y >= endY) {
				crossed[1] = helper.getTick();
			}
		});

		helper.succeedWhen(() -> {
			helper.assertTrue(crossed[1] >= 0, "not up yet, at " + TestPositions.relative(helper, bot.blockPosition()));
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
		helper.runAfterDelay(40, () -> bot.setWalkTarget(helper.absolutePos(new BlockPos(6, 2, 3))));

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
		bot.setWalkTarget(helper.absolutePos(new BlockPos(6, 2, 3)));
		helper.onEachTick(() -> helper.assertTrue(bot.getHealth() == bot.getMaxHealth(), "hurt by the fall"));

		succeedWhenAt(helper, bot, new BlockPos(6, 2, 3));
	}

	/**
	 * Ground at y=1..2 that a terraform levels at y=2, with a hill left on it, and a bedrock wall across it but for a gap
	 * at the far end. Returns the colony that levels it.
	 */
	private static Colony levelledGroundBehindAWall(GameTestHelper helper) {
		fill(helper, 0, 0, 0, 15, 0, 15, Blocks.STONE);
		fill(helper, 0, 1, 0, 15, 2, 15, Blocks.DIRT);
		fill(helper, 8, 3, 0, 8, 5, 14, Blocks.BEDROCK);
		helper.setBlock(14, 3, 14, Blocks.DIRT);
		helper.setBlock(15, 3, 15, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(new BlockPos(15, 3, 15))).orElseThrow();
		colony.setRadius(16);
		colony.setStockTargets(List.of());
		int height = helper.absolutePos(new BlockPos(0, 2, 0)).getY();
		colony.addBuild(Terraform.plan(helper.getLevel()::getBlockState, helper.absolutePos(new BlockPos(0, 1, 0)),
			helper.absolutePos(new BlockPos(15, 4, 15)), height).orElseThrow());
		return colony;
	}

	/**
	 * A bot with a golden shovel walks past the wall of {@link #levelledGroundBehindAWall}. Digging under the wall is
	 * quicker than walking round, but the ground is as the terraform wants it and no step of it, so nothing would fill
	 * the holes: the bot walks round.
	 */
	private static void walksRoundTheWall(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 6, 3, 0);
		bot.getInventory().addItem(new ItemStack(Items.GOLDEN_SHOVEL));
		bot.setWalkTarget(helper.absolutePos(new BlockPos(10, 3, 0)));

		helper.onEachTick(() -> {
			for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 15, 2, 15)) {
				if (!helper.getBlockState(pos).is(Blocks.DIRT)) {
					helper.fail("dug a hole in the levelled ground at " + pos.toShortString());
				}
			}
		});
		succeedWhenAt(helper, bot, new BlockPos(10, 3, 0));
	}

	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 350)
	public void walksRoundRatherThanDigIntoLevelledGround(GameTestHelper helper) {
		levelledGroundBehindAWall(helper);
		walksRoundTheWall(helper);
	}

	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 350)
	public void walksRoundRatherThanDigIntoGroundLevelledBefore(GameTestHelper helper) {
		Colony colony = levelledGroundBehindAWall(helper);
		// The hill dug, and every batch of the terraform done.
		helper.setBlock(14, 3, 14, Blocks.AIR);
		colony.postBuildJobs();
		for (Job job : colony.board().jobs().stream().filter(job -> job.type() == JobType.BUILD || job.type() == JobType.CLEAR_AREA).toList()) {
			colony.finishBatch(job.id());
			colony.board().complete(job.id());
		}
		helper.assertTrue(colony.builds().isEmpty(), "the terraform is not done: " + colony.builds());
		walksRoundTheWall(helper);
	}

	/**
	 * A bot with no blocks to pillar on at the bottom of a pit 3 deep in ground a terraform levels: it cannot get out
	 * without digging into that ground, so it may, from inside it.
	 */
	@GameTest(maxTicks = 300)
	public void getsOutOfAPitInLevelledGround(GameTestHelper helper) {
		fill(helper, 0, 1, 0, 7, 3, 7, Blocks.DIRT);
		fill(helper, 3, 1, 3, 3, 3, 3, Blocks.AIR);
		helper.setBlock(6, 4, 6, Blocks.DIRT);
		helper.setBlock(7, 4, 7, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(new BlockPos(7, 4, 7))).orElseThrow();
		colony.setStockTargets(List.of());
		int height = helper.absolutePos(new BlockPos(0, 3, 0)).getY();
		colony.addBuild(Terraform.plan(helper.getLevel()::getBlockState, helper.absolutePos(new BlockPos(0, 1, 0)),
			helper.absolutePos(new BlockPos(6, 5, 6)), height).orElseThrow());
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.GOLDEN_SHOVEL));
		bot.setWalkTarget(helper.absolutePos(new BlockPos(0, 4, 0)));

		succeedWhenAt(helper, bot, new BlockPos(0, 4, 0));
	}

	/**
	 * A bot with no blocks at the bottom of a pit 2 deep in the levelled ground of {@link #levelledGroundBehindAWall},
	 * beside the wall. Tunnelling under the wall is quicker than walking round, but from the pit the bot may break only
	 * the ground it needs to climb out, beside the pit: it climbs out and walks round.
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 350)
	public void leavesAPitInLevelledGroundWithoutDiggingOtherHoles(GameTestHelper helper) {
		Colony colony = levelledGroundBehindAWall(helper);
		helper.setBlock(14, 3, 14, Blocks.AIR);
		colony.postBuildJobs();
		for (Job job : colony.board().jobs().stream().filter(job -> job.type() == JobType.BUILD || job.type() == JobType.CLEAR_AREA).toList()) {
			colony.finishBatch(job.id());
			colony.board().complete(job.id());
		}
		helper.assertTrue(colony.builds().isEmpty(), "the terraform is not done: " + colony.builds());
		fill(helper, 6, 1, 1, 6, 2, 1, Blocks.AIR);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 6, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.GOLDEN_SHOVEL));
		bot.setWalkTarget(helper.absolutePos(new BlockPos(10, 3, 0)));

		helper.onEachTick(() -> {
			for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 15, 2, 15)) {
				boolean besidePit = Math.abs(pos.getX() - 6) <= 1 && Math.abs(pos.getZ() - 1) <= 1;
				if (!besidePit && !helper.getBlockState(pos).is(Blocks.DIRT)) {
					helper.fail("dug a hole in the levelled ground at " + pos.toShortString());
				}
			}
		});
		succeedWhenAt(helper, bot, new BlockPos(10, 3, 0));
	}
}
