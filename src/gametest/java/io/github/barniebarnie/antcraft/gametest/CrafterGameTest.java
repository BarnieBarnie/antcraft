package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.registry.ModItems;
import java.lang.reflect.Method;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

public class CrafterGameTest implements CustomTestMethodInvoker {
	private static final BlockPos CORE = new BlockPos(1, 1, 1);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
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

	private static void store(GameTestHelper helper, ItemStack... stacks) {
		Container core = core(helper);
		for (int i = 0; i < stacks.length; i++) {
			core.setItem(i, stacks[i]);
		}
	}

	private static AntBot crafter(GameTestHelper helper, Colony colony) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		bot.setRole(BotRole.CRAFTER);
		return bot;
	}

	@GameTest(maxTicks = 400)
	public void craftsOrderFromLogsInCore(GameTestHelper helper) {
		Colony colony = colony(helper);
		store(helper, new ItemStack(Items.OAK_LOG, 2));
		colony.addOrder(Items.STICK, 4);
		crafter(helper, colony);

		helper.succeedWhen(() -> {
			helper.assertTrue(core(helper).countItem(Items.STICK) >= 4, "core has " + core(helper).countItem(Items.STICK) + " sticks");
			helper.assertTrue(colony.orders().isEmpty(), "order not cleared: " + colony.orders());
		});
	}

	@GameTest(maxTicks = 400)
	public void activatesBotCoresFromStorage(GameTestHelper helper) {
		Colony colony = colony(helper);
		store(helper, new ItemStack(ModItems.BOT_CORE));
		crafter(helper, colony);

		helper.succeedWhen(() -> {
			helper.assertTrue(colony.members().size() == 2, "members: " + colony.members().size());
			helper.assertTrue(core(helper).countItem(ModItems.BOT_CORE) == 0, "bot core still in storage");
		});
	}

	@GameTest(maxTicks = 600)
	public void buildsANewBotFromRawMaterials(GameTestHelper helper) {
		Colony colony = colony(helper);
		store(helper,
			new ItemStack(Items.CARVED_PUMPKIN),
			new ItemStack(Items.IRON_INGOT, 3),
			new ItemStack(Items.REDSTONE, 9));
		colony.addOrder(ModItems.BOT_CORE, 1);
		crafter(helper, colony);

		helper.succeedWhen(() -> helper.assertTrue(colony.members().size() == 2, "members: " + colony.members().size()));
	}
}
