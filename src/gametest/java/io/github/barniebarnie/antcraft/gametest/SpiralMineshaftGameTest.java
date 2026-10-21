package io.github.barniebarnie.antcraft.gametest;

import static io.github.barniebarnie.antcraft.gametest.TestPositions.relative;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** The 2-wide spiral mineshaft of new colonies: dug with crafted stair blocks, and walked by several bots at once. */
public class SpiralMineshaftGameTest implements CustomTestMethodInvoker {
	/** Beside the shaft, on its stone. */
	private static final BlockPos CORE = new BlockPos(0, 5, 0);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		// Stone up to y=4, the ground the shaft is dug into; the test area ends at y=7, 3 above the top step.
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 7, 4, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		method.invoke(this, helper);
	}

	private static Colony colony(GameTestHelper helper) {
		helper.setBlock(CORE, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(CORE)).orElseThrow();
		colony.setStockTargets(List.of());
		return colony;
	}

	private static Container core(GameTestHelper helper) {
		return (Container) helper.getBlockEntity(CORE, BlockEntity.class);
	}

	/** A spiral from the ground at y=5 down to a landing at {@code level}, filling the test area's 6x6 middle. */
	private static Mineshaft shaft(GameTestHelper helper, int level) {
		int y = helper.absolutePos(new BlockPos(0, level, 0)).getY();
		return new Mineshaft(helper.absolutePos(new BlockPos(1, 5, 1)), List.of(y));
	}

	private static AntBot member(GameTestHelper helper, Colony colony, BlockPos at) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, at.getX(), at.getY(), at.getZ());
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		return bot;
	}

	private static int stairBlocks(GameTestHelper helper) {
		int found = 0;
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 7, 7, 7)) {
			if (helper.getBlockState(pos).is(Blocks.COBBLESTONE_STAIRS)) {
				found++;
			}
		}
		return found;
	}

	/**
	 * Two bots dig the top of a new shaft. The colony has no stair blocks: the bots make them from the
	 * cobblestone they dig, with the crafting table one carries, and put one on every step.
	 */
	@GameTest(maxTicks = 3000)
	public void botsDigTheTopOfTheShaftAndPlaceCraftedCobblestoneStairs(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.TORCH, 8));
		core(helper).setItem(1, new ItemStack(Items.CHEST, 1));
		Mineshaft shaft = shaft(helper, 3);
		colony.setMineshaft(shaft);
		for (BlockPos at : List.of(new BlockPos(7, 5, 0), new BlockPos(7, 5, 7))) {
			AntBot bot = member(helper, colony, at);
			bot.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
			if (at.getZ() == 0) {
				bot.getInventory().addItem(new ItemStack(Items.CRAFTING_TABLE));
			}
		}

		helper.succeedWhen(() -> {
			helper.assertTrue(stairBlocks(helper) == 4, "stair blocks placed: " + stairBlocks(helper));
			for (BuildPlan.Step step : shaft.plan().steps()) {
				BlockState state = helper.getLevel().getBlockState(step.pos());
				helper.assertTrue(step.isDone(state), "not done at " + relative(helper, step.pos()) + ": " + state);
			}
			helper.assertTrue(colony.builds().isEmpty(), "shaft not finished");
		});
	}

	/** Every block of the test area that is not air. */
	private static Set<BlockPos> solid(GameTestHelper helper) {
		Set<BlockPos> solid = new HashSet<>();
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 7, 7, 7)) {
			if (!helper.getBlockState(pos).isAir()) {
				solid.add(pos.immutable());
			}
		}
		return solid;
	}

	/** On the 2-wide stairs, a bot on its way up and one on its way down get past each other, without digging a way round. */
	@GameTest(maxTicks = 1200)
	public void twoBotsPassEachOtherOnTheStairs(GameTestHelper helper) {
		Colony colony = colony(helper);
		Mineshaft shaft = shaft(helper, 1);
		colony.setMineshaft(shaft);
		for (BuildPlan.Step step : shaft.plan().steps()) {
			if (!step.isDone(helper.getLevel().getBlockState(step.pos()))) {
				helper.getLevel().setBlockAndUpdate(step.pos(), step.state());
			}
		}
		colony.removeBuild(shaft.plan());
		Set<BlockPos> dug = solid(helper);
		BlockPos top = shaft.top();
		BlockPos bottom = shaft.landing(shaft.levels().getLast());
		AntBot up = member(helper, colony, relative(helper, bottom));
		AntBot down = member(helper, colony, relative(helper, top));
		up.setWalkTarget(top);
		down.setWalkTarget(bottom);

		helper.succeedWhen(() -> {
			// A walk ends next to its target.
			helper.assertTrue(up.getWalkTarget() == null && up.blockPosition().distManhattan(top) <= 1,
				"the bot going up is at " + relative(helper, up.blockPosition()));
			helper.assertTrue(down.getWalkTarget() == null && down.blockPosition().distManhattan(bottom) <= 1,
				"the bot going down is at " + relative(helper, down.blockPosition()));
			helper.assertTrue(solid(helper).equals(dug), "blocks changed on the way");
		});
	}
}
