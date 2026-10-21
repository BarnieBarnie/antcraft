package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.JobType;
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
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Work between jobs goes on while the job board always has a job the bot can take. */
public class UpkeepGameTest implements CustomTestMethodInvoker {
	private static final BlockPos CORE = new BlockPos(1, 1, 1);
	/** Logs that grow back and get a job again once chopped, so the board is never without a tree job. */
	private static final List<BlockPos> LOGS = List.of(new BlockPos(6, 1, 1), new BlockPos(6, 1, 3));

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	private static Colony colony(GameTestHelper helper) {
		helper.setBlock(CORE, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(CORE)).orElseThrow();
		colony.setRadius(6);
		colony.setStockTargets(List.of());
		return colony;
	}

	private static Container core(GameTestHelper helper) {
		return (Container) helper.getBlockEntity(CORE, BlockEntity.class);
	}

	/** Keeps a log job on the board at each of {@link #LOGS}, putting the log back once its job is gone. */
	private static void keepBoardBusy(GameTestHelper helper, Colony colony) {
		helper.onEachTick(() -> {
			for (BlockPos log : LOGS) {
				BlockPos target = helper.absolutePos(log);
				if (colony.board().jobs().stream().noneMatch(job -> job.target().equals(target))) {
					helper.setBlock(log, Blocks.OAK_LOG);
					colony.board().post(JobType.CHOP_LOG, target);
				}
			}
		});
	}

	private static AntBot member(GameTestHelper helper, Colony colony) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		return bot;
	}

	@GameTest(maxTicks = 1200)
	public void shearsASheepWhileTheBoardIsBusy(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.SHEARS));
		Sheep sheep = helper.spawn(EntityTypes.SHEEP, 2, 1, 6);
		sheep.setNoAi(true);
		keepBoardBusy(helper, colony);
		member(helper, colony);

		helper.succeedWhen(() -> helper.assertTrue(sheep.isSheared(), "sheep not sheared"));
	}

	@GameTest(maxTicks = 1200)
	public void storesLooseItemsWhileTheBoardIsBusy(GameTestHelper helper) {
		Colony colony = colony(helper);
		ItemEntity loose = helper.spawnItem(Items.COBBLESTONE, new BlockPos(2, 1, 6));
		loose.getItem().setCount(16);
		keepBoardBusy(helper, colony);
		member(helper, colony);

		helper.succeedWhen(() -> helper.assertTrue(core(helper).countItem(Items.COBBLESTONE) == 16,
			"core has " + core(helper).countItem(Items.COBBLESTONE) + " cobblestone"));
	}
}
