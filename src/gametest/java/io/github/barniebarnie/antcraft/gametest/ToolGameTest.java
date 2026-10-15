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
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class ToolGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				boolean border = x == 0 || x == 7 || z == 0 || z == 7;
				for (int y = 0; y < 8; y++) {
					if (border || y == 0) {
						helper.setBlock(x, y, z, Blocks.BEDROCK);
					}
				}
			}
		}
		method.invoke(this, helper);
	}

	/** Two stone blocks by hand take 300 ticks and drop nothing; with a stone pickaxe about 50 ticks. */
	@GameTest(maxTicks = 200)
	public void usesPickaxeFromInventoryForStone(GameTestHelper helper) {
		for (int y = 1; y <= 6; y++) {
			for (int z = 1; z <= 6; z++) {
				helper.setBlock(4, y, z, Blocks.STONE);
			}
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		bot.setWalkTarget(helper.absolutePos(new BlockPos(6, 1, 3)));

		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getWalkTarget() == null, "still walking");
			int cobble = bot.getInventory().countItem(Items.COBBLESTONE) + (bot.getMainHandItem().is(Items.COBBLESTONE) ? 1 : 0);
			helper.assertTrue(cobble > 0, "mined stone did not drop cobblestone");
		});
	}

	@GameTest(maxTicks = 200)
	public void wieldsSwordFromInventoryWhenFighting(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		Husk husk = helper.spawn(EntityTypes.HUSK, 5, 1, 3);

		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getMainHandItem().is(Items.IRON_SWORD), "not holding the sword");
			helper.assertTrue(!husk.isAlive(), "husk still alive");
		});
	}
}
