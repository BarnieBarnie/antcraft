package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.colony.Territory;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import java.lang.reflect.Method;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Colony bots outside the colony radius head home once they have nothing to do. */
public class ReturnHomeGameTest implements CustomTestMethodInvoker {
	private static final BlockPos CORE = new BlockPos(1, 1, 1);
	/** Covers x and z from -1 to 3, so the far side of the test area is outside. */
	private static final int RADIUS = 2;

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.DIRT);
			}
		}
		helper.setBlock(CORE, ModBlocks.COLONY_CORE);
		method.invoke(this, helper);
	}

	private static Colony colony(GameTestHelper helper) {
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(CORE)).orElseThrow(() -> new AssertionError("no colony founded"));
		colony.setRadius(RADIUS);
		// No default stock targets, so only the test's own orders give the colony work.
		colony.setStockTargets(List.of());
		return colony;
	}

	private static Territory territory(Colony colony) {
		return new Territory(colony.core(), colony.radius());
	}

	/** A member with a role whose work is not available here, so it has nothing to do unless told. */
	private static AntBot member(GameTestHelper helper, Colony colony, BotRole role, int x, int z) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, x, 1, z);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		bot.setRole(role);
		return bot;
	}

	@GameTest(maxTicks = 200)
	public void idleMemberWalksBackIntoTerritory(GameTestHelper helper) {
		Colony colony = colony(helper);
		// No quarry, so the miner has no work.
		AntBot bot = member(helper, colony, BotRole.MINER, 6, 6);

		helper.succeedWhen(() -> helper.assertTrue(territory(colony).contains(bot.blockPosition()), "bot still at " + bot.blockPosition().toShortString()));
	}

	@GameTest(maxTicks = 1200)
	public void workingMemberIsNotPulledHome(GameTestHelper helper) {
		Colony colony = colony(helper);
		BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				helper.setBlock(6 + dx, 4, 6 + dz, leaves);
			}
		}
		for (int y = 1; y <= 3; y++) {
			helper.setBlock(6, y, 6, Blocks.OAK_LOG);
		}
		// The tree is outside the colony radius, so the survey would not post it.
		colony.board().post(JobType.CHOP_TREE, helper.absolutePos(new BlockPos(6, 1, 6)));
		AntBot bot = member(helper, colony, BotRole.LUMBERJACK, 6, 4);

		helper.onEachTick(() -> {
			boolean logsLeft = helper.getBlockState(new BlockPos(6, 1, 6)).is(Blocks.OAK_LOG) || helper.getBlockState(new BlockPos(6, 3, 6)).is(Blocks.OAK_LOG);
			if (logsLeft && territory(colony).contains(bot.blockPosition())) {
				helper.fail("lumberjack went home before finishing its tree");
			}
		});
		helper.succeedWhen(() -> {
			for (int y = 1; y <= 3; y++) {
				helper.assertBlockNotPresent(Blocks.OAK_LOG, 6, y, 6);
			}
		});
	}

	@GameTest(maxTicks = 600)
	public void finishesFightOutsideTerritoryThenReturns(GameTestHelper helper) {
		Colony colony = colony(helper);
		AntBot bot = member(helper, colony, BotRole.MINER, 6, 6);
		bot.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		Husk husk = helper.spawn(EntityTypes.HUSK, 6, 1, 4);
		bot.setTarget(husk);

		helper.succeedWhen(() -> {
			helper.assertTrue(!husk.isAlive(), "husk still alive");
			helper.assertTrue(husk.getLastHurtByMob() == bot, "husk was not killed by the bot");
			helper.assertTrue(territory(colony).contains(bot.blockPosition()), "bot still at " + bot.blockPosition().toShortString());
		});
	}

	@GameTest(maxTicks = 100)
	public void ignoresMobsOutsideTerritory(GameTestHelper helper) {
		Colony colony = colony(helper);
		AntBot bot = member(helper, colony, BotRole.MINER, 2, 2);
		Husk husk = helper.spawn(EntityTypes.HUSK, 6, 1, 6);
		husk.setNoAi(true);

		helper.runAfterDelay(60, () -> {
			helper.assertTrue(bot.getTarget() == null, "bot targeted a mob outside the colony");
			helper.succeed();
		});
	}
}
