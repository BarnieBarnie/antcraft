package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
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
		method.invoke(this, helper);
	}

	/** Runs {@code source} on {@code bot}, failing the test as soon as the script fails. */
	private static ScriptRun run(GameTestHelper helper, AntBot bot, String source) {
		ScriptRun run = BotScripts.run(bot, source);
		BotScripts.failOnError(helper, bot);
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
	@GameTest(maxTicks = 160)
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

	/**
	 * A bot that takes out its sword as it picks a hostile mob to fight (AntBot.setTarget) strikes with it at once (#427):
	 * its own melee goal can strike on the tick it takes the target, and that blow does the sword's 7, not a bare hand's 1.
	 * Six bots, each beside a still, armourless, 200-health husk of its own, so that some bot likely strikes on that tick;
	 * every blow does 7, so a husk's lost health is always a multiple of 7. Passes at tick 30 if any bot struck: a bot
	 * that does not strike on the tick it takes its target strikes 20 ticks later.
	 */
	@GameTest(maxTicks = 60)
	public void aBotsOwnFightStrikesWithTheSwordItJustTookOut(GameTestHelper helper) {
		List<Husk> husks = new ArrayList<>();
		for (int x : new int[] {1, 5}) {
			for (int z : new int[] {1, 4, 7}) {
				Husk husk = helper.spawn(EntityTypes.HUSK, x + 1, 1, z);
				husk.setNoAi(true);
				husk.getAttribute(Attributes.ARMOR).setBaseValue(0);
				husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
				husk.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
				husk.setHealth(200);
				husks.add(husk);
				AntBot bot = helper.spawn(ModEntities.ANT_BOT, x, 1, z);
				bot.getInventory().addItem(new ItemStack(Items.DIAMOND_SWORD));
			}
		}
		helper.onEachTick(() -> {
			for (Husk husk : husks) {
				float lost = 200 - husk.getHealth();
				helper.assertTrue(lost % 7 == 0, "a husk lost " + lost + " health");
			}
		});
		helper.runAtTickTime(30, () -> {
			helper.assertTrue(husks.stream().anyMatch(husk -> husk.getHealth() < 200), "no bot struck its husk");
			helper.succeed();
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
	@GameTest(maxTicks = 70)
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
	@GameTest(maxTicks = 60)
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
		helper.runAfterDelay(3, () -> GameTests.hurt(bot, 4));
		succeedWhenPrinted(helper, bot, run, List.of("False interrupted"));
	}

	/**
	 * Fails the test at once when one of {@code creepers} explodes, which discards it, or a blast hurts the bot (#706).
	 * Registered up front, as every listener.
	 */
	private static void failOnExplosion(GameTestHelper helper, AntBot bot, Creeper... creepers) {
		helper.onEachTick(() -> {
			for (Creeper creeper : creepers) {
				if (creeper.getRemovalReason() == Entity.RemovalReason.DISCARDED) {
					helper.fail("a creeper exploded, bot health " + bot.getHealth());
				}
			}
			DamageSource hurt = bot.getLastDamageSource();
			if (hurt != null && hurt.is(DamageTypeTags.IS_EXPLOSION)) {
				helper.fail("a blast hurt the bot, health " + bot.getHealth());
			}
		});
	}

	/**
	 * A bot with an iron sword fights a creeper of its own accord and kills it without it exploding (#706): it strikes,
	 * backs off out of the blast while the creeper's fuse is lit and strikes again once it went out. Wide, for room to back off.
	 */
	// Measured: passes at tick 145 to 177 in 6 runs.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 360)
	public void aBotKillsACreeperWithoutItExploding(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 12, 1, 8);
		bot.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		Creeper creeper = helper.spawn(EntityTypes.CREEPER, 12, 1, 13);
		failOnExplosion(helper, bot, creeper);
		helper.succeedWhen(() -> {
			helper.assertTrue(creeper.isDeadOrDying(), "creeper alive with " + creeper.getHealth() + " health");
			helper.assertTrue(bot.isAlive(), "bot dead");
		});
	}

	/** {@code attack(creeper)} kites as the bot's own fight does: the creeper dies without exploding, and the attack is ok (#706). */
	// Measured: passes at tick 129 to 165 in 6 runs.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 340)
	public void attackKillsACreeperWithoutItExploding(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 12, 1, 8);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
		Creeper creeper = helper.spawn(EntityTypes.CREEPER, 12, 1, 13);
		failOnExplosion(helper, bot, creeper);
		ScriptRun run = run(helper, bot, attackThe("creeper"));
		succeedWhenPrinted(helper, bot, run, List.of("True None"));
	}

	/** A bot with an iron sword kills two creepers and a husk that come at it together, and neither creeper explodes (#706). */
	// Measured: passes at tick 477 to 485 in 3 runs alone, 802 in a full run.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 1600)
	public void aBotKillsTwoCreepersAndAHuskWithoutAnExplosion(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 12, 1, 8);
		bot.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		Creeper[] creepers = {helper.spawn(EntityTypes.CREEPER, 9, 1, 14), helper.spawn(EntityTypes.CREEPER, 15, 1, 14)};
		// Husks do not burn in daylight, so only the bot can kill it.
		Husk husk = helper.spawn(EntityTypes.HUSK, 12, 1, 15);
		failOnExplosion(helper, bot, creepers);
		helper.succeedWhen(() -> {
			for (Creeper creeper : creepers) {
				helper.assertTrue(creeper.isDeadOrDying(), "creeper alive with " + creeper.getHealth() + " health");
			}
			helper.assertTrue(husk.isDeadOrDying(), "husk alive with " + husk.getHealth() + " health");
			helper.assertTrue(bot.isAlive(), "bot dead");
		});
	}

	/**
	 * Succeeds once {@code creeper}'s fuse, lit at some point, has gone out again, the bot having backed out of its reach;
	 * fails as soon as the fuse burns past {@code most}, as it does where the bot wastes its time.
	 */
	private static void succeedWhenTheFuseWentOut(GameTestHelper helper, Creeper creeper, float most) {
		boolean[] lit = {false};
		helper.onEachTick(() -> {
			lit[0] |= creeper.getSwelling(1) > 0;
			if (creeper.getSwelling(1) > most) {
				helper.fail("the fuse burnt to " + creeper.getSwelling(1));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(lit[0], "the creeper's fuse was never lit");
			helper.assertTrue(creeper.getSwellDir() < 0 && creeper.getSwelling(1) == 0, "fuse still lit, swell " + creeper.getSwelling(1));
		});
	}

	/**
	 * A bot does not step in to strike a lit creeper with another hostile mob beside it, which stands in its way there and
	 * back (#717): it backs off at once, out of reach before three quarters of the fuse have burnt (0.5 measured). Stepping
	 * in, it got nowhere past the husk and was out of reach only as the fuse burnt to 0.96.
	 */
	// Measured: passes at tick 31 to 35.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 80)
	public void aBotDoesNotStepInAtALitCreeperPastAnotherMob(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(12.5, 1, 8.5));
		bot.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		// Stands still in the bot's way, and neither walks nor strikes.
		Husk husk = helper.spawn(EntityTypes.HUSK, new Vec3(12.5, 1, 9.6));
		husk.setNoAi(true);
		// Near enough to light at once, before the bot's own fight strikes the husk.
		Creeper creeper = helper.spawn(EntityTypes.CREEPER, new Vec3(12.5, 1, 10.9));
		creeper.setTarget(bot);
		failOnExplosion(helper, bot, creeper);
		succeedWhenTheFuseWentOut(helper, creeper, 0.75F);
	}

	/** A bot with a bow and {@code arrows} arrows, at {@code x, z} in the test area. */
	private static AntBot archer(GameTestHelper helper, int x, int z, int arrows) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, x, 1, z);
		bot.getInventory().addItem(new ItemStack(Items.BOW));
		if (arrows > 0) {
			bot.getInventory().addItem(new ItemStack(Items.ARROW, arrows));
		}
		return bot;
	}

	/** Fails the test at once when {@code target} is hurt other than by an arrow, such as by the bot's blow. */
	private static void failOnAnythingButArrows(GameTestHelper helper, LivingEntity target) {
		helper.onEachTick(() -> {
			DamageSource hurt = target.getLastDamageSource();
			if (hurt != null && !hurt.is(DamageTypes.ARROW)) {
				helper.fail(target.getType().toShortString() + " hurt by " + hurt.getMsgId());
			}
		});
	}

	/**
	 * {@code shoot(creeper)} kills a creeper 15 blocks off with arrows before it gets near enough to explode (#707): only
	 * arrows hurt it, and it neither explodes nor hurts the bot. Wide, for the range.
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 150)
	public void shootKillsACreeperAtRangeWithoutItExploding(GameTestHelper helper) {
		AntBot bot = archer(helper, 12, 3, 16);
		Creeper creeper = helper.spawn(EntityTypes.CREEPER, 12, 1, 18);
		failOnExplosion(helper, bot, creeper);
		failOnAnythingButArrows(helper, creeper);
		ScriptRun run = run(helper, bot, "r = shoot(entities('creeper', 20)[0])\nprint(r.ok, r.reason)\n");
		succeedWhenPrinted(helper, bot, run, List.of("True None"));
	}

	/**
	 * {@code shoot(zombie)} kills a zombie that walks at the bot once hit, with arrows only, the bot leading it as it comes
	 * (#707). The zombie wears a helmet, so the sun does not kill it instead.
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 150)
	public void shootKillsAZombieAtRange(GameTestHelper helper) {
		AntBot bot = archer(helper, 12, 3, 16);
		Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, 12, 1, 18);
		zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
		failOnAnythingButArrows(helper, zombie);
		ScriptRun run = run(helper, bot, "r = shoot(entities('zombie', 20)[0])\nprint(r.ok, r.reason)\n");
		succeedWhenPrinted(helper, bot, run, List.of("True None"));
	}

	/**
	 * {@code attack(creeper)} by a bot with a sword, a bow and arrows shoots it, as {@code shoot} does, rather than kite it
	 * with the sword (#707): only arrows hurt it.
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 150)
	public void attackShootsACreeperWithTheBowItCarries(GameTestHelper helper) {
		AntBot bot = archer(helper, 12, 3, 16);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
		Creeper creeper = helper.spawn(EntityTypes.CREEPER, 12, 1, 18);
		failOnExplosion(helper, bot, creeper);
		failOnAnythingButArrows(helper, creeper);
		ScriptRun run = run(helper, bot, "r = attack(entities('creeper', 20)[0])\nprint(r.ok, r.reason)\n");
		succeedWhenPrinted(helper, bot, run, List.of("True None"));
	}

	/**
	 * {@code shoot} with no bow or crossbow fails with {@code no_tool}, with a bow but no arrows with {@code no_item}, and
	 * a target it may not attack is {@code protected} (#707).
	 */
	@GameTest(maxTicks = 15)
	public void shootWithoutABowOrArrowsFails(GameTestHelper helper) {
		Husk husk = helper.spawn(EntityTypes.HUSK, 4, 1, 6);
		husk.setNoAi(true);
		Cow named = helper.spawn(EntityTypes.COW, 6, 1, 6);
		named.setNoAi(true);
		named.setCustomName(Component.literal("Daisy"));
		AntBot unarmed = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		AntBot noArrows = archer(helper, 6, 1, 0);
		AntBot armed = archer(helper, 3, 1, 4);
		ScriptRun[] runs = {
			run(helper, unarmed, "print(shoot(entities('husk', 8)[0]).reason)\n"),
			run(helper, noArrows, "print(shoot(entities('husk', 8)[0]).reason)\n"),
			run(helper, armed, "print(shoot(entities('cow', 8)[0]).reason)\n")};
		AntBot[] bots = {unarmed, noArrows, armed};
		List<String> expected = List.of("no_tool", "no_item", "protected");
		helper.succeedWhen(() -> {
			for (int i = 0; i < 3; i++) {
				List<String> printed = BotScripts.log(bots[i], Output.Level.PRINT);
				helper.assertTrue(runs[i].state() == ScriptRun.State.FINISHED && printed.equals(List.of(expected.get(i))),
					"bot " + i + ": script " + runs[i].state() + ", printed " + printed);
			}
			helper.assertTrue(named.getHealth() == named.getMaxHealth(), "the named cow was hurt");
		});
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
