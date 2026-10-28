package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.profile.TickProfiler;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import java.lang.reflect.Method;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
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

	/**
	 * An operator's {@code /antcraft profile start} measures server ticks and the bots' parts of them until {@code stop};
	 * a player who is not an operator may not use it.
	 */
	@GameTest(maxTicks = 20)
	public void profileCommandMeasuresServerTicksForOperatorsOnly(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		CommandSourceStack operator = server.createCommandSourceStack().withLevel(helper.getLevel()).withSuppressedOutput();
		CommandSourceStack player = helper.makeMockServerPlayerInLevel().createCommandSourceStack().withSuppressedOutput();
		helper.assertFalse(server.getCommands().getDispatcher().getRoot().getChild("antcraft").getChild("profile").canUse(player),
			"non-operator player may use /antcraft profile");
		helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		server.getCommands().performPrefixedCommand(operator, "antcraft profile start");
		helper.assertTrue(TickProfiler.SERVER.running(), "profiler not started");
		helper.runAfterDelay(5, () -> {
			TickProfiler.Report report = TickProfiler.SERVER.report();
			helper.assertTrue(report.ticks() >= 4, "measured " + report.ticks() + " ticks");
			helper.assertTrue(report.parts().stream().anyMatch(part -> part.name().equals("bot.tick")), "no bot ticks in " + report.lines());
			server.getCommands().performPrefixedCommand(operator, "antcraft profile report");
			server.getCommands().performPrefixedCommand(operator, "antcraft profile stop");
			helper.assertFalse(TickProfiler.SERVER.running(), "profiler not stopped");
			helper.succeed();
		});
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
