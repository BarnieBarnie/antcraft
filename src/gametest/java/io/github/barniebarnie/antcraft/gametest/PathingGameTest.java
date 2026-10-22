package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.pathing.PathFinder;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

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
	@GameTest(maxTicks = 400)
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
}
