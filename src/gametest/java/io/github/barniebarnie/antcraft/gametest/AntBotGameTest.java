package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import java.lang.reflect.Method;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Bot behaviour, tested on a real (headless) server. Each test gets an empty
 * 8x8x8 area; a stone floor is laid at y=0 so entities stand at y=1.
 */
public class AntBotGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		// Game tests run on a dedicated server, where bots are disabled unless opted in.
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
			}
		}
		method.invoke(this, helper);
	}

	@GameTest(maxTicks = 200)
	public void walksToTargetAndClearsIt(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		BlockPos target = helper.absolutePos(new BlockPos(6, 1, 6));
		bot.setWalkTarget(target);

		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getWalkTarget() == null, "walk target not cleared yet");
			helper.assertTrue(bot.blockPosition().distManhattan(target) <= 2, "bot not at target");
		});
	}

	@GameTest(maxTicks = 600)
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

	@GameTest(maxTicks = 100)
	public void picksUpItemsIntoInventory(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		helper.spawnItem(Items.OAK_LOG, 3, 1, 3);

		helper.succeedWhen(() -> helper.assertTrue(bot.getInventory().countItem(Items.OAK_LOG) == 1, "log not in inventory"));
	}

	@GameTest(maxTicks = 100)
	public void dropsInventoryOnDeath(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 5));
		bot.kill(helper.getLevel());

		helper.succeedWhen(() -> helper.assertItemEntityPresent(Items.COBBLESTONE, new BlockPos(3, 1, 3), 2.0));
	}
}
