package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import java.lang.reflect.Method;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** The guard job (#705, docs/cookbook/guard.md): fights the hostile mobs near home, retreats home to eat and heal. */
public class GuardGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** A still zombie in a helmet, so the sun does not burn it. */
	private static Zombie zombie(GameTestHelper helper, int x, int z) {
		Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, x, 1, z);
		zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
		zombie.setNoAi(true);
		return zombie;
	}

	/**
	 * A guard with a sword and bread is hurt to 8 health (food 12) as it first strikes the near zombie: its attack ends
	 * with hurt there (#718), it walks home before it eats, eats, heals to 18 and only then goes for the far zombie, killing
	 * both. The far zombie
	 * is in sight all along: the bot's own goals take no new target while it is hurt and runs a script (#718).
	 */
	// Measured: passes at tick 599 to 611.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 1200)
	public void aHurtGuardRetreatsEatsHealsAndFightsAgain(GameTestHelper helper) {
		Zombie near = zombie(helper, 9, 9);
		Zombie far = zombie(helper, 20, 20);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.DIAMOND_SWORD));
		bot.getInventory().addItem(new ItemStack(Items.BREAD, 3));
		Vec3 home = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 1, 3)));
		boolean[] hurt = {false};
		boolean[] retreated = {false};
		helper.onEachTick(() -> {
			if (!hurt[0] && near.getHealth() < near.getMaxHealth()) {
				hurt[0] = true;
				bot.getFood().setFoodLevel(12);
				bot.getFood().setSaturation(0);
				bot.setHealth(8);
			}
			if (hurt[0] && bot.getInventory().countItem(Items.BREAD) == 3 && bot.position().distanceTo(home) < 1.5) {
				retreated[0] = true;
			}
			if (far.getHealth() < far.getMaxHealth() && !(retreated[0] && bot.getHealth() >= 18)) {
				helper.fail("struck the far zombie with " + bot.getHealth() + " health, retreated " + retreated[0]);
			}
		});
		guard(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(near.isDeadOrDying() && far.isDeadOrDying(), "zombies alive: near " + near.getHealth() + ", far " + far.getHealth());
			helper.assertTrue(bot.getInventory().countItem(Items.BREAD) < 3, "ate no bread");
		});
	}

	/** Runs {@code jobs.guard 32} on {@code bot}, failing the test as soon as the script fails. */
	private static void guard(GameTestHelper helper, AntBot bot) {
		List<String> started = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run jobs.guard 32");
		helper.assertTrue(started.equals(List.of("commands.antcraft.bot.run[jobs.guard, 1]")), "run answered " + started);
		BotScripts.failOnError(helper, bot);
	}

	/**
	 * A guard brought to 4 health, at the flee reflex's 6 or under, as it first strikes the near zombie: the reflex ends its
	 * attack with interrupted and runs it home, the job's retreat follows (#725): it eats there, heals to 18 and only then
	 * kills both zombies. The near zombie is more than 8 blocks (HOSTILE_RANGE) from home, so the bot stops fleeing there.
	 */
	// Measured: passes at tick 839 to 845.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 1600)
	public void aGuardTheFleeReflexRunsHomeEatsHealsAndFightsAgain(GameTestHelper helper) {
		Zombie near = zombie(helper, 12, 12);
		Zombie far = zombie(helper, 20, 20);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.DIAMOND_SWORD));
		bot.getInventory().addItem(new ItemStack(Items.BREAD, 4));
		Vec3 home = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 1, 3)));
		boolean[] hurt = {false};
		boolean[] fledHome = {false};
		float[] firstBlow = {0};
		helper.onEachTick(() -> {
			if (!hurt[0] && near.getHealth() < near.getMaxHealth()) {
				hurt[0] = true;
				firstBlow[0] = near.getHealth();
				bot.getFood().setFoodLevel(12);
				bot.getFood().setSaturation(0);
				bot.setHealth(4);
			}
			if (hurt[0] && bot.getFlightPlans() > 0 && bot.getInventory().countItem(Items.BREAD) == 4 && bot.position().distanceTo(home) < 1.5) {
				fledHome[0] = true;
			}
			boolean struck = near.getHealth() < firstBlow[0] || far.getHealth() < far.getMaxHealth();
			if (hurt[0] && struck && !(fledHome[0] && bot.getHealth() >= 18)) {
				helper.fail("struck again with " + bot.getHealth() + " health, fled home " + fledHome[0]);
			}
		});
		guard(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(near.isDeadOrDying() && far.isDeadOrDying(), "zombies alive: near " + near.getHealth() + ", far " + far.getHealth());
			helper.assertTrue(bot.getInventory().countItem(Items.BREAD) < 4, "ate no bread");
		});
	}
}
