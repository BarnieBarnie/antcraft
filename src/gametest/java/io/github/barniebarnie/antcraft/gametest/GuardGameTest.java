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
import net.minecraft.world.level.block.Blocks;
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
	 * A guard with a sword and bread is hurt to 8 health (food 12) as it first strikes the near zombie: after killing it,
	 * it walks home before it eats, eats, heals to 18 and only then goes for the far zombie, killing it too. A wall with a gap at its end
	 * hides the far zombie from home and from the near one, as the bot's own goals would charge a mob in sight.
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 1400)
	public void aHurtGuardRetreatsEatsHealsAndFightsAgain(GameTestHelper helper) {
		Zombie near = zombie(helper, 9, 9);
		Zombie far = zombie(helper, 20, 20);
		for (int x = 0; x < 22; x++) {
			for (int y = 1; y <= 3; y++) {
				helper.setBlock(x, y, 14, Blocks.STONE);
			}
		}
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
		List<String> started = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run jobs.guard 32");
		helper.assertTrue(started.equals(List.of("commands.antcraft.bot.run[jobs.guard, 1]")), "run answered " + started);
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(near.isDeadOrDying() && far.isDeadOrDying(), "zombies alive: near " + near.getHealth() + ", far " + far.getHealth());
			helper.assertTrue(bot.getInventory().countItem(Items.BREAD) < 3, "ate no bread");
		});
	}
}
