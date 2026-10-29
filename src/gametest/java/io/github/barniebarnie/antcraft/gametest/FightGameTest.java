package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The bot's health and fighting (#415): {@code health()}, {@code max_health()} and {@code attack(entity)}, each run as a
 * short script on a bot in the test area. Only the default targets: which ones the config allows is a unit test
 * ({@code AttackTargetTest}), as tests in a batch share the config.
 */
public class FightGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		// The whole floor: the flee test runs in a wider area.
		AABB bounds = helper.getBounds();
		for (int x = 0; x < (int) bounds.getXsize(); x++) {
			for (int z = 0; z < (int) bounds.getZsize(); z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
			}
		}
		method.invoke(this, helper);
	}

	/** Runs {@code source} on {@code bot}, failing the test as soon as the script fails. */
	private static ScriptRun run(GameTestHelper helper, AntBot bot, String source) {
		ScriptRun run = BotScripts.run(bot, source);
		helper.onEachTick(() -> {
			if (run.state() == ScriptRun.State.FAILED) {
				helper.fail("script failed: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
		return run;
	}

	/** Succeeds once the script finished having printed exactly {@code expected}. */
	private static void succeedWhenPrinted(GameTestHelper helper, AntBot bot, ScriptRun run, List<String> expected) {
		helper.succeedWhen(() -> {
			List<String> printed = BotScripts.log(bot, Output.Level.PRINT);
			helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "script " + run.state() + ", printed " + printed);
			helper.assertTrue(printed.equals(expected), "printed " + printed + ", expected " + expected);
		});
	}

	/** Attacks the one entity of type {@code type} within 8 blocks and prints the result. */
	private static String attackThe(String type) {
		return "r = attack(entities('" + type + "', 8)[0])\nprint(r.ok, r.reason)\n";
	}

	@GameTest(maxTicks = 15)
	public void healthAndMaxHealthReadTheBotsHealth(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		ScriptRun run = run(helper, bot, "print(health(), max_health())\nwait(5)\nprint(health(), max_health())\n");
		helper.runAfterDelay(3, () -> bot.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 5));
		succeedWhenPrinted(helper, bot, run, List.of("20.0 20.0", "15.0 20.0"));
	}

	/**
	 * A bot with a diamond sword walks to a zombie and kills it. The zombie wears a helmet, so the sun does not kill it
	 * instead, and fails the test if it burns all the same.
	 * The bot's own goals fight monsters too, so {@link #attackKillsACow} is the test that only {@code attack} can pass.
	 */
	@GameTest(maxTicks = 130)
	public void attackKillsAZombie(GameTestHelper helper) {
		Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, 6, 1, 3);
		zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
		zombie.setNoAi(true);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 3);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
		ScriptRun run = run(helper, bot, attackThe("zombie"));
		helper.onEachTick(() -> {
			if (zombie.isOnFire()) {
				helper.fail("the zombie burns");
			}
		});
		succeedWhenPrinted(helper, bot, run, List.of("True None"));
	}

	/**
	 * A bot with bare hands walks to a cow and kills it: the bot's own goals target only monsters, so only {@code attack}
	 * can kill it.
	 */
	// Measured: passes at tick 113, ten 1-damage blows (#422) spaced by the cow's invulnerability after each.
	@GameTest(maxTicks = 230)
	public void attackKillsACow(GameTestHelper helper) {
		Cow cow = helper.spawn(EntityTypes.COW, 6, 1, 3);
		cow.setNoAi(true);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 3);
		ScriptRun run = run(helper, bot, attackThe("cow"));
		helper.succeedWhen(() -> {
			helper.assertTrue(cow.isDeadOrDying(), "cow alive with " + cow.getHealth() + " health");
			List<String> printed = BotScripts.log(bot, Output.Level.PRINT);
			helper.assertTrue(run.state() == ScriptRun.State.FINISHED && printed.equals(List.of("True None")), "script " + run.state() + ", printed " + printed);
		});
	}

	/**
	 * A bot with a wooden sword strikes more often than one with a wooden axe (#417): every 13 ticks against every 25, as a
	 * player's attack cooldown. Each beats a cow of its own, given the health to outlast the span and made to stand still,
	 * not knocked back; a blow is a drop in its health.
	 */
	@GameTest(maxTicks = 90)
	public void aSwordStrikesMoreOftenThanAnAxe(GameTestHelper helper) {
		Cow[] cows = {helper.spawn(EntityTypes.COW, 1, 1, 4), helper.spawn(EntityTypes.COW, 6, 1, 4)};
		ItemStack[] weapons = {new ItemStack(Items.WOODEN_SWORD), new ItemStack(Items.WOODEN_AXE)};
		for (int i = 0; i < 2; i++) {
			cows[i].setNoAi(true);
			cows[i].getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
			cows[i].getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
			cows[i].setHealth(200);
			AntBot bot = helper.spawn(ModEntities.ANT_BOT, i == 0 ? 1 : 6, 1, 1);
			bot.setItemSlot(EquipmentSlot.MAINHAND, weapons[i]);
			run(helper, bot, "attack(entities('cow', 4)[0])\n");
		}
		int[] blows = new int[2];
		float[] health = {cows[0].getHealth(), cows[1].getHealth()};
		helper.onEachTick(() -> {
			for (int i = 0; i < 2; i++) {
				if (cows[i].getHealth() < health[i]) {
					blows[i]++;
				}
				health[i] = cows[i].getHealth();
			}
		});
		helper.runAtTickTime(80, () -> {
			helper.assertTrue(blows[1] > 0 && blows[0] > blows[1], "sword " + blows[0] + " blows, axe " + blows[1]);
			helper.succeed();
		});
	}

	/**
	 * A blow does a player's damage with the same item (#422): 1 bare-handed, 7 with a diamond sword. Each bot beats a
	 * still, 200-health cow of its own; the first drop in its health is the first blow.
	 */
	@GameTest(maxTicks = 30)
	public void aBlowDoesAPlayersDamage(GameTestHelper helper) {
		Cow[] cows = {helper.spawn(EntityTypes.COW, 1, 1, 4), helper.spawn(EntityTypes.COW, 6, 1, 4)};
		ItemStack[] weapons = {ItemStack.EMPTY, new ItemStack(Items.DIAMOND_SWORD)};
		for (int i = 0; i < 2; i++) {
			cows[i].setNoAi(true);
			cows[i].getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
			cows[i].getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
			cows[i].setHealth(200);
			AntBot bot = helper.spawn(ModEntities.ANT_BOT, i == 0 ? 1 : 6, 1, 1);
			bot.setItemSlot(EquipmentSlot.MAINHAND, weapons[i]);
			run(helper, bot, "attack(entities('cow', 4)[0])\n");
		}
		float[] firstBlow = {0, 0};
		helper.onEachTick(() -> {
			for (int i = 0; i < 2; i++) {
				if (firstBlow[i] == 0 && cows[i].getHealth() < 200) {
					firstBlow[i] = 200 - cows[i].getHealth();
				}
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(firstBlow[0] == 1 && firstBlow[1] == 7, "bare hand took " + firstBlow[0] + ", diamond sword " + firstBlow[1]);
		});
	}

	/** A still cow of 200 health, not knocked back, at {@code at} in the test area. */
	private static Cow stillCow(GameTestHelper helper, Vec3 at) {
		Cow cow = helper.spawn(EntityTypes.COW, at);
		cow.setNoAi(true);
		cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
		cow.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
		cow.setHealth(200);
		return cow;
	}

	/**
	 * A sword's blow sweeps (#423), as a player's: a bot with a sword attacking one cow also hurts the cow beside it. Which
	 * of the two it attacks does not matter.
	 */
	// Measured: passes at tick 12, the first blow.
	@GameTest(maxTicks = 25)
	public void aSwordAlsoHurtsTheMobBesideTheTarget(GameTestHelper helper) {
		List<Cow> cows = List.of(stillCow(helper, new Vec3(3, 1, 4.5)), stillCow(helper, new Vec3(4.2, 1, 4.5)));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 1);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
		run(helper, bot, "attack(entities('cow', 5)[0])\n");
		helper.succeedWhen(() -> {
			for (Cow cow : cows) {
				helper.assertTrue(cow.getHealth() < 200, "a cow is unhurt");
			}
		});
	}

	/** An axe does not sweep (#423): a bot with one hurts only the cow it attacks, not the one beside it. */
	@GameTest(maxTicks = 45)
	public void anAxeHurtsOnlyTheTarget(GameTestHelper helper) {
		List<Cow> cows = List.of(stillCow(helper, new Vec3(3, 1, 4.5)), stillCow(helper, new Vec3(4.2, 1, 4.5)));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 1);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_AXE));
		run(helper, bot, "attack(entities('cow', 5)[0])\n");
		// The first blow lands at about tick 12, the second 20 ticks later.
		helper.runAtTickTime(35, () -> {
			long hurt = cows.stream().filter(cow -> cow.getHealth() < 200).count();
			helper.assertTrue(hurt == 1, hurt + " cows hurt");
			helper.succeed();
		});
	}

	/**
	 * A sweep never hurts what {@code attack} may not (#423): a bot with a sword attacking a pig hurts the cow behind it,
	 * but not the named cow and the other bot beside it.
	 */
	@GameTest(maxTicks = 40)
	public void aSweepSparesProtectedEntities(GameTestHelper helper) {
		Pig pig = helper.spawn(EntityTypes.PIG, new Vec3(3.5, 1, 4.5));
		pig.setNoAi(true);
		pig.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
		pig.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
		pig.setHealth(200);
		Cow cow = stillCow(helper, new Vec3(3.5, 1, 5.6));
		Cow named = stillCow(helper, new Vec3(2.4, 1, 4.5));
		named.setCustomName(Component.literal("Daisy"));
		AntBot other = helper.spawn(ModEntities.ANT_BOT, new Vec3(4.5, 1, 4.5));
		other.setNoAi(true);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 1);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
		run(helper, bot, "attack(entities('pig', 8)[0])\n");
		helper.onEachTick(() -> {
			if (named.getHealth() < 200 || other.getHealth() < other.getMaxHealth()) {
				helper.fail("the sweep hurt the named cow (" + named.getHealth() + ") or the other bot (" + other.getHealth() + ")");
			}
		});
		// By then the bot struck the pig twice, at about tick 12 and 13 ticks later. Red without the mayAttack check in the
		// sweep: the named cow and the other bot are hurt on tick 13.
		helper.runAtTickTime(30, () -> {
			helper.assertTrue(cow.getHealth() < 200, "the sweep never hurt the cow behind the pig");
			helper.succeed();
		});
	}

	/** A husk that is gone (here: discarded) while the bot walks to it ends the attack with {@code target_gone}. */
	@GameTest(maxTicks = 15)
	public void attackOnATargetThatDisappearsEndsWithTargetGone(GameTestHelper helper) {
		Husk husk = helper.spawn(EntityTypes.HUSK, 6, 1, 6);
		husk.setNoAi(true);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		ScriptRun run = run(helper, bot, attackThe("husk"));
		helper.runAfterDelay(4, () -> husk.discard());
		succeedWhenPrinted(helper, bot, run, List.of("False target_gone"));
	}

	/**
	 * A cow teleported 70 blocks straight up once the bot struck it ends the attack with {@code target_gone} (#418): it is
	 * out of {@code SenseApi.MAX_ENTITY_RADIUS}, though alive and in its own, loaded chunk, so the range check is what ends
	 * it. Up, not sideways, so it does not land in another test's area; without gravity, so it stays there. Once the script
	 * ends the test notes how the cow was and discards it.
	 */
	@GameTest(maxTicks = 40)
	public void attackOnATargetBeyondRangeEndsWithTargetGone(GameTestHelper helper) {
		Cow cow = helper.spawn(EntityTypes.COW, 6, 1, 3);
		cow.setNoAi(true);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 3);
		ScriptRun run = run(helper, bot, attackThe("cow"));
		BlockPos far = helper.absolutePos(new BlockPos(6, 71, 3));
		helper.assertTrue(helper.getLevel().isInWorldBounds(far) && helper.getLevel().isLoaded(far), "far " + far + " not in the world");
		boolean[] teleported = {false};
		String[] cowAtEnd = {null};
		helper.onEachTick(() -> {
			if (!teleported[0] && cow.getHealth() < cow.getMaxHealth()) {
				cow.setNoGravity(true);
				cow.teleportTo(far.getX() + 0.5, far.getY(), far.getZ() + 0.5);
				teleported[0] = true;
			}
			if (cowAtEnd[0] == null && (run.state() == ScriptRun.State.FINISHED || helper.getTick() >= 39)) {
				cowAtEnd[0] = "alive " + cow.isAlive() + ", removed " + cow.isRemoved() + ", " + (cow.distanceTo(bot) > 64 ? "over" : "within")
					+ " 64 blocks";
				cow.discard();
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(teleported[0], "the bot never struck the cow");
			helper.assertTrue("alive true, removed false, over 64 blocks".equals(cowAtEnd[0]), "cow " + cowAtEnd[0]);
			List<String> printed = BotScripts.log(bot, Output.Level.PRINT);
			helper.assertTrue(run.state() == ScriptRun.State.FINISHED && printed.equals(List.of("False target_gone")),
				"script " + run.state() + ", printed " + printed);
		});
	}

	/** A husk shut in bedrock, which the bot cannot dig through, is {@code unreachable}. */
	@GameTest(maxTicks = 70)
	public void attackOnAShutInTargetEndsWithUnreachable(GameTestHelper helper) {
		for (int x = 4; x <= 6; x++) {
			for (int z = 4; z <= 6; z++) {
				for (int y = 0; y <= 3; y++) {
					if (x != 5 || z != 5 || y == 0 || y == 3) {
						helper.setBlock(x, y, z, Blocks.BEDROCK);
					}
				}
			}
		}
		Husk husk = helper.spawn(EntityTypes.HUSK, 5, 1, 5);
		husk.setNoAi(true);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		ScriptRun run = run(helper, bot, attackThe("husk"));
		succeedWhenPrinted(helper, bot, run, List.of("False unreachable"));
	}

	/**
	 * A bot brought to flee health while it fights a husk flees home: the flee reflex ends the attack with
	 * {@code interrupted}. In a wide area, as the flee tests of {@code ReflexGameTest}, so the bot has room to flee.
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 80)
	public void theFleeReflexInterruptsAnAttack(GameTestHelper helper) {
		Husk husk = helper.spawn(EntityTypes.HUSK, 15, 1, 15);
		husk.setNoAi(true);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 13, 1, 13);
		bot.setHome(helper.absolutePos(new BlockPos(8, 1, 8)));
		ScriptRun run = run(helper, bot, attackThe("husk"));
		// Once the bot attacks.
		helper.runAfterDelay(3, () -> bot.setHealth(4));
		succeedWhenPrinted(helper, bot, run, List.of("False interrupted"));
	}

	/** By default the bot refuses to attack a player, another bot, a named animal or a tamed one, and leaves them unhurt. */
	@GameTest(maxTicks = 10)
	public void attackRefusesPlayersBotsAndNamedOrTamedAnimals(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		BotScripts.RecordingPlayer player = BotScripts.owner(helper, bot);
		BlockPos at = helper.absolutePos(new BlockPos(1, 1, 3));
		player.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
		AntBot other = helper.spawn(ModEntities.ANT_BOT, 5, 1, 3);
		Cow cow = helper.spawn(EntityTypes.COW, 3, 1, 5);
		cow.setCustomName(Component.literal("Daisy"));
		Wolf wolf = helper.spawn(EntityTypes.WOLF, 3, 1, 1);
		wolf.setOwner(player);
		List<LivingEntity> targets = List.of(player, other, cow, wolf);
		cow.setNoAi(true);
		wolf.setNoAi(true);
		other.setNoAi(true);
		ScriptRun run = run(helper, bot, """
			lines = []
			for e in entities(radius=3):
			    lines.append(e.type + ' ' + attack(e).reason)
			for line in sorted(lines):
			    print(line)
			""");
		helper.onEachTick(() -> {
			for (LivingEntity target : targets) {
				if (target.getHealth() < target.getMaxHealth()) {
					helper.fail(target + " hurt");
				}
			}
		});
		succeedWhenPrinted(helper, bot, run, List.of("antcraft:ant_bot protected", "minecraft:cow protected", "minecraft:player protected",
			"minecraft:wolf protected"));
	}
}
