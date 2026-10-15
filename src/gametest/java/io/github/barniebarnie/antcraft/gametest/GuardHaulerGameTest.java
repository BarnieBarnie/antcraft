package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import java.lang.reflect.Method;
import java.util.HashSet;
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

public class GuardHaulerGameTest implements CustomTestMethodInvoker {
	private static final BlockPos CORE = new BlockPos(1, 1, 1);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		// Bedrock walls keep patrolling guards inside the test area.
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				boolean border = x == 0 || x == 7 || z == 0 || z == 7;
				for (int y = 0; y < 4; y++) {
					if (y == 0 || border) {
						helper.setBlock(x, y, z, Blocks.BEDROCK);
					}
				}
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

	private static AntBot member(GameTestHelper helper, Colony colony, BotRole role) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		bot.setRole(role);
		return bot;
	}

	@GameTest(maxTicks = 300)
	public void guardTakesWeaponFromCore(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.IRON_SWORD));
		AntBot guard = member(helper, colony, BotRole.GUARD);

		helper.succeedWhen(() -> {
			boolean armed = guard.getMainHandItem().is(Items.IRON_SWORD) || guard.getInventory().countItem(Items.IRON_SWORD) == 1;
			helper.assertTrue(armed, "guard has no sword");
			helper.assertTrue(core(helper).countItem(Items.IRON_SWORD) == 0, "sword still in core");
		});
	}

	@GameTest(maxTicks = 600)
	public void guardPatrolsAroundCore(GameTestHelper helper) {
		Colony colony = colony(helper);
		AntBot guard = member(helper, colony, BotRole.GUARD);
		Set<BlockPos> visited = new HashSet<>();

		helper.onEachTick(() -> visited.add(guard.blockPosition()));
		helper.succeedWhen(() -> {
			helper.assertTrue(visited.size() >= 6, "guard visited only " + visited.size() + " spots");
			helper.assertTrue(guard.blockPosition().closerThan(helper.absolutePos(CORE), colony.radius() + 1), "guard wandered off");
		});
	}

	@GameTest(maxTicks = 600)
	public void haulerStoresLooseItemsInCore(GameTestHelper helper) {
		Colony colony = colony(helper);
		helper.spawnItem(Items.OAK_SAPLING, 5, 1, 5);
		helper.spawnItem(Items.STICK, 2, 1, 5);
		helper.spawnItem(Items.APPLE, 5, 1, 2);
		member(helper, colony, BotRole.HAULER);

		helper.succeedWhen(() -> {
			Container core = core(helper);
			helper.assertTrue(core.countItem(Items.OAK_SAPLING) == 1 && core.countItem(Items.STICK) == 1 && core.countItem(Items.APPLE) == 1,
				"core does not hold all three items yet");
		});
	}
}
