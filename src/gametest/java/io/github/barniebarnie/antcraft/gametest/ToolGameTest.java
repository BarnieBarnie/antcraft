package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.navigation.BlockBreaker;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import java.lang.reflect.Method;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

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

	/**
	 * A bot holding {@code tool} (none if null) breaks {@code block} twice: once collecting the drops
	 * and once dropping them in the world. Succeeds once both are gone and {@code check} passes.
	 */
	private static void breakTwice(GameTestHelper helper, @Nullable Item tool, Block block, java.util.function.Consumer<AntBot> check) {
		BlockPos collected = new BlockPos(3, 1, 3);
		BlockPos dropped = new BlockPos(5, 1, 3);
		helper.setBlock(collected, block);
		helper.setBlock(dropped, block);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 5);
		if (tool != null) {
			bot.getInventory().addItem(new ItemStack(tool));
		}
		BlockBreaker breaker = new BlockBreaker(bot);
		boolean[] done = new boolean[2];
		helper.onEachTick(() -> {
			if (!done[0]) {
				done[0] = breaker.tick(helper.getLevel(), helper.absolutePos(collected), true);
			} else if (!done[1]) {
				done[1] = breaker.tick(helper.getLevel(), helper.absolutePos(dropped), false);
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(done[0] && done[1], "still breaking");
			helper.assertBlockPresent(Blocks.AIR, collected);
			helper.assertBlockPresent(Blocks.AIR, dropped);
			check.accept(bot);
		});
	}

	private static int carried(AntBot bot, Item item) {
		return bot.getInventory().countItem(item) + (bot.getMainHandItem().is(item) ? bot.getMainHandItem().getCount() : 0);
	}

	private static int dropped(GameTestHelper helper, Item item) {
		AABB area = new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(16);
		return helper.getLevel().getEntitiesOfClass(ItemEntity.class, area, entity -> entity.getItem().is(item)).stream()
			.mapToInt(entity -> entity.getItem().getCount()).sum();
	}

	/** Like a player: iron ore needs a stone pickaxe or better to drop anything. */
	@GameTest(maxTicks = 400)
	public void woodenPickaxeGetsNoRawIron(GameTestHelper helper) {
		breakTwice(helper, Items.WOODEN_PICKAXE, Blocks.IRON_ORE, bot -> {
			helper.assertTrue(carried(bot, Items.RAW_IRON) == 0, "bot got raw iron with a wooden pickaxe");
			helper.assertTrue(dropped(helper, Items.RAW_IRON) == 0, "raw iron dropped for a wooden pickaxe");
		});
	}

	@GameTest(maxTicks = 200)
	public void stonePickaxeGetsRawIron(GameTestHelper helper) {
		breakTwice(helper, Items.STONE_PICKAXE, Blocks.IRON_ORE, bot -> {
			// The one dropped in the world may have been picked up since.
			helper.assertTrue(carried(bot, Items.RAW_IRON) >= 1, "bot did not collect raw iron");
			helper.assertTrue(carried(bot, Items.RAW_IRON) + dropped(helper, Items.RAW_IRON) == 2, "no raw iron dropped");
		});
	}

	/** Logs need no tool, so a lumberjack without an axe still gets them. */
	@GameTest(maxTicks = 300)
	public void logsDropWithoutATool(GameTestHelper helper) {
		breakTwice(helper, null, Blocks.OAK_LOG, bot -> {
			helper.assertTrue(carried(bot, Items.OAK_LOG) >= 1, "bot did not collect the log");
			helper.assertTrue(carried(bot, Items.OAK_LOG) + dropped(helper, Items.OAK_LOG) == 2, "no log dropped");
		});
	}
}
