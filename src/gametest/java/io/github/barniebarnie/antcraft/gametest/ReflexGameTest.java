package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * A bot's reflexes (docs/scripting.md, Reflexes): whatever its script does, it gets out of fire, swims up before it
 * drowns, flees a hostile mob at low health and steps from under falling sand. Each bot runs a script that waits, and the
 * wait ends with {@code interrupted} once a reflex takes over.
 */
public class ReflexGameTest implements CustomTestMethodInvoker {
	/** Waits, and prints how the wait ended; then waits on, so the bot stays where its reflex left it. */
	private static final String WAIT = "r = wait(1000)\nprint(r.ok, r.reason)\nwait(1000)\n";

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
			}
		}
		method.invoke(this, helper);
	}

	private static List<String> printed(AntBot bot) {
		return BotScripts.log(bot, Output.Level.PRINT);
	}

	/** Fails at once when the bot takes {@code what} damage: its reflex came too late, or not at all. */
	private static void failIfHurt(GameTestHelper helper, AntBot bot, String what) {
		helper.onEachTick(() -> {
			if (bot.getHealth() < bot.getMaxHealth() && bot.getLastDamageSource() != null) {
				helper.fail("bot hurt by " + what + " (" + bot.getLastDamageSource().getMsgId() + "), at "
					+ TestPositions.relative(helper, bot.blockPosition()).toShortString());
			}
		});
	}

	/** A bot standing in fire on netherrack, which burns for good, steps out of it. */
	@GameTest(maxTicks = 30)
	public void getsOutOfFire(GameTestHelper helper) {
		helper.setBlock(3, 0, 3, Blocks.NETHERRACK);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		BotScripts.run(bot, WAIT);
		// Once the bot waits.
		helper.runAfterDelay(2, () -> helper.setBlock(3, 1, 3, Blocks.FIRE));

		helper.succeedWhen(() -> {
			helper.assertTrue(printed(bot).equals(List.of("False interrupted")), "printed " + printed(bot));
			helper.assertFalse(helper.getLevel().getBlockState(bot.blockPosition()).is(BlockTags.FIRE), "bot still in fire");
		});
	}

	/**
	 * A bot under water in a tunnel with a stone ceiling, short of air: it swims along to where the water is open above
	 * and comes up to breathe, rather than drowning against the ceiling where it is.
	 */
	@GameTest(maxTicks = 80)
	public void swimsUpBeforeDrowning(GameTestHelper helper) {
		for (int x = 0; x <= 6; x++) {
			for (int z = 2; z <= 4; z++) {
				for (int y = 1; y <= 3; y++) {
					helper.setBlock(x, y, z, Blocks.STONE);
				}
			}
		}
		for (int x = 1; x <= 5; x++) {
			helper.setBlock(x, 1, 3, Blocks.WATER);
			helper.setBlock(x, 2, 3, Blocks.WATER);
		}
		helper.setBlock(5, 3, 3, Blocks.AIR);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 3);
		// Once the bot waits.
		helper.runAfterDelay(2, () -> bot.setAirSupply(90));
		BotScripts.run(bot, WAIT);
		failIfHurt(helper, bot, "drowning");

		helper.succeedWhen(() -> {
			helper.assertTrue(printed(bot).equals(List.of("False interrupted")), "printed " + printed(bot));
			helper.assertFalse(bot.isUnderWater(), "bot under water at " + TestPositions.relative(helper, bot.blockPosition()).toShortString());
		});
	}

	/**
	 * A bot low on health with a husk next to it runs home, out of the husk's reach, instead of fighting it. The husk
	 * stands still, so only the bot can end the fight.
	 */
	@GameTest(maxTicks = 60)
	public void fleesAHostileMobAtLowHealth(GameTestHelper helper) {
		Husk husk = helper.spawn(EntityTypes.HUSK, 7, 1, 7);
		husk.setNoAi(true);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		// Once the bot waits.
		helper.runAfterDelay(2, () -> bot.setHealth(4));
		bot.setHome(helper.absolutePos(new BlockPos(0, 1, 0)));
		BotScripts.run(bot, WAIT);
		helper.onEachTick(() -> {
			if (husk.getHealth() < husk.getMaxHealth()) {
				helper.fail("bot fought the husk");
			}
		});

		helper.succeedWhen(() -> {
			helper.assertTrue(printed(bot).equals(List.of("False interrupted")), "printed " + printed(bot));
			helper.assertTrue(bot.distanceTo(husk) > 8, "bot " + bot.distanceTo(husk) + " blocks from the husk");
		});
	}

	/** Two blocks of sand fall onto the bot: it steps from under them rather than be buried. */
	@GameTest(maxTicks = 40)
	public void stepsAwayFromFallingSand(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		BotScripts.run(bot, WAIT);
		helper.runAfterDelay(2, () -> {
			helper.setBlock(3, 6, 3, Blocks.SAND);
			helper.setBlock(3, 7, 3, Blocks.SAND);
		});
		failIfHurt(helper, bot, "the sand");

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.SAND, 3, 1, 3);
			helper.assertBlockPresent(Blocks.SAND, 3, 2, 3);
			helper.assertTrue(printed(bot).equals(List.of("False interrupted")), "printed " + printed(bot));
			helper.assertFalse(bot.getBoundingBox().intersects(new AABB(helper.absolutePos(new BlockPos(3, 1, 3))).expandTowards(0, 1, 0)),
				"bot inside the sand");
		});
	}
}
