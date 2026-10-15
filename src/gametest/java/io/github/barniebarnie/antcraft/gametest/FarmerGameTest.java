package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import java.lang.reflect.Method;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class FarmerGameTest implements CustomTestMethodInvoker {
	private static final BlockPos CORE = new BlockPos(1, 1, 1);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.DIRT);
			}
		}
		method.invoke(this, helper);
	}

	private static Colony colony(GameTestHelper helper) {
		helper.setBlock(CORE, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(CORE)).orElseThrow();
		colony.setRadius(6);
		return colony;
	}

	private static Container core(GameTestHelper helper) {
		return (Container) helper.getBlockEntity(CORE, BlockEntity.class);
	}

	/** A row of wheat on farmland at x = 3..5, z = 5. */
	private static void wheatRow(GameTestHelper helper, int age) {
		for (int x = 3; x <= 5; x++) {
			helper.setBlock(x, 0, 5, Blocks.FARMLAND);
			helper.setBlock(x, 1, 5, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, age));
		}
	}

	private static AntBot farmer(GameTestHelper helper, Colony colony) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 3);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		bot.setRole(BotRole.FARMER);
		return bot;
	}

	@GameTest(maxTicks = 800)
	public void harvestsRipeWheatReplantsAndStoresIt(GameTestHelper helper) {
		Colony colony = colony(helper);
		wheatRow(helper, 7);
		farmer(helper, colony);

		helper.succeedWhen(() -> {
			helper.assertTrue(core(helper).countItem(Items.WHEAT) == 3, "core has " + core(helper).countItem(Items.WHEAT) + " wheat");
			for (int x = 3; x <= 5; x++) {
				BlockState crop = helper.getBlockState(new BlockPos(x, 1, 5));
				helper.assertTrue(crop.is(Blocks.WHEAT) && crop.getValue(CropBlock.AGE) < 7, "not replanted at x=" + x);
			}
		});
	}

	@GameTest(maxTicks = 200)
	public void leavesUnripeCropsAlone(GameTestHelper helper) {
		Colony colony = colony(helper);
		wheatRow(helper, 3);
		farmer(helper, colony);

		helper.runAfterDelay(150, () -> {
			for (int x = 3; x <= 5; x++) {
				helper.assertBlockPresent(Blocks.WHEAT, new BlockPos(x, 1, 5));
			}
			helper.assertTrue(core(helper).countItem(Items.WHEAT) == 0, "harvested unripe wheat");
			helper.succeed();
		});
	}
}
