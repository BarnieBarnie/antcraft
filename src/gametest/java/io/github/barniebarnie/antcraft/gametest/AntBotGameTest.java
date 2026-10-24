package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import java.lang.reflect.Method;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Bot behaviour, tested on a real (headless) server. Each test gets an empty
 * 8x8x8 area; a stone floor is laid at y=0 so entities stand at y=1.
 */
public class AntBotGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		// Game tests run on a dedicated server, where bots are disabled unless opted in.
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
			}
		}
		method.invoke(this, helper);
	}

	/**
	 * Game tests keep each colony inside its own test area, so it cannot reach into the tests beside it. A colony with
	 * the default radius of 32 leaves an item lying two blocks outside its area alone, on ground the bot can walk, and
	 * stores the one inside. Without the limit the bot fetches both before it goes to the core.
	 */
	@GameTest(maxTicks = 40)
	public void colonyBotLeavesAnItemJustOutsideItsTestAreaAlone(GameTestHelper helper) {
		helper.setBlock(1, 1, 1, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(new BlockPos(1, 1, 1))).orElseThrow();
		colony.setStockTargets(List.of());
		for (int x = 8; x <= 10; x++) {
			helper.getLevel().setBlockAndUpdate(helper.absolutePos(new BlockPos(x, 0, 4)), Blocks.STONE.defaultBlockState());
			for (int y = 1; y <= 3; y++) {
				helper.getLevel().setBlockAndUpdate(helper.absolutePos(new BlockPos(x, y, 4)), Blocks.AIR.defaultBlockState());
			}
		}
		helper.spawnItem(Items.COBBLESTONE, new BlockPos(6, 1, 4));
		GameTests.spawnOutside(helper, Items.COBBLESTONE, new BlockPos(10, 1, 4));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		Container core = (Container) helper.getBlockEntity(new BlockPos(1, 1, 1), BlockEntity.class);
		GameTests.failIfTakenFromOutside(helper, bot, core);

		helper.succeedWhen(() -> {
			helper.assertTrue(core.countItem(Items.COBBLESTONE) == 1, "core holds " + core.countItem(Items.COBBLESTONE) + " cobblestone");
		});
	}

	@GameTest(maxTicks = 100)
	public void walksToTargetAndClearsIt(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		BlockPos target = helper.absolutePos(new BlockPos(6, 1, 6));
		bot.setWalkTarget(target);

		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getWalkTarget() == null, "walk target not cleared yet");
			helper.assertTrue(bot.blockPosition().distManhattan(target) <= 2, "bot not at target");
		});
	}

	@GameTest(maxTicks = 240)
	public void killsHostileMob(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		// An unarmed bot and a husk are an even match; armed, the outcome does not depend on luck.
		bot.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		// Husks do not burn in daylight, so only the bot can kill it.
		Husk husk = helper.spawn(EntityTypes.HUSK, 6, 1, 6);

		helper.succeedWhen(() -> {
			helper.assertTrue(!husk.isAlive(), "husk still alive");
			helper.assertTrue(husk.getLastHurtByMob() == bot, "husk was not killed by the bot");
		});
	}

	@GameTest(maxTicks = 100)
	public void ignoresCreepers(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		Creeper creeper = helper.spawn(EntityTypes.CREEPER, 6, 1, 6);
		creeper.setNoAi(true);

		helper.runAfterDelay(60, () -> {
			helper.assertTrue(bot.getTarget() == null, "bot targeted a creeper");
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 20)
	public void picksUpItemsIntoInventory(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		helper.spawnItem(Items.OAK_LOG, 3, 1, 3);

		helper.succeedWhen(() -> helper.assertTrue(bot.getInventory().countItem(Items.OAK_LOG) == 1, "log not in inventory"));
	}

	@GameTest(maxTicks = 20)
	public void dropsInventoryOnDeath(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 5));
		bot.kill(helper.getLevel());

		helper.succeedWhen(() -> helper.assertItemEntityPresent(Items.COBBLESTONE, new BlockPos(3, 1, 3), 2.0));
	}

	@GameTest(maxTicks = 20)
	public void relativeInvertsAbsolutePos(GameTestHelper helper) {
		for (BlockPos pos : new BlockPos[] {BlockPos.ZERO, new BlockPos(1, 2, 5), new BlockPos(6, 1, 0), new BlockPos(-3, -2, 7)}) {
			BlockPos back = TestPositions.relative(helper, helper.absolutePos(pos));
			helper.assertTrue(back.equals(pos), pos.toShortString() + " came back as " + back.toShortString());
		}
		helper.succeed();
	}
}
