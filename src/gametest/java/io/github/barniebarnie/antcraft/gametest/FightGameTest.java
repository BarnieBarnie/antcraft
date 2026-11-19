package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotHands;
import io.github.barniebarnie.antcraft.entity.KiteGoal;
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
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enderman;
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
	 * {@code attack} on a mob standing still at the far edge of its block, the bot in the block beside it but out of melee
	 * reach, steps in to strike it (#825). A walk to within a block of the mob's ends where the bot stands, so each one
	 * counted an arrival at once and the attack gave {@code unreachable} after three, 7 ticks in, as it did now and then on
	 * a creeper that stopped 1.6 blocks off to light its fuse.
	 */
	// Measured: passes at tick 34.
	// Full runs: passes at up to tick 34 (#861).
	@GameTest(maxTicks = 70)
	public void attackStepsInToAMobAtTheFarEdgeOfItsBlock(GameTestHelper helper) {
		Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, new Vec3(6.95, 1, 3.5));
		zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
		zombie.setNoAi(true);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(5.2, 1, 3.5));
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
		ScriptRun run = run(helper, bot, attackThe("zombie"));
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
	// Measured: passes at tick 2, the first blow (tick 12 when the bot walked in from 3 blocks off, before #902).
	@GameTest(maxTicks = 25)
	public void aSwordAlsoHurtsTheMobBesideTheTarget(GameTestHelper helper) {
		List<Cow> cows = List.of(stillCow(helper, new Vec3(3, 1, 4.5)), stillCow(helper, new Vec3(4.2, 1, 4.5)));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
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
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
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
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
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
	// Full runs: passes at up to tick 23 (#861).
	@GameTest(maxTicks = 50)
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
	 * {@code attack} on a mob hovering out of melee reach, a blaze 5 blocks over the stone floor, walks only to the floor
	 * under it, not up to it, and gives up with {@code unreachable} (#799). With no blocks to pillar on, its walk to within
	 * 1 block of the blaze searched 528 nodes by tick 5 for a way up, and on.
	 */
	// Measured: passes at tick 25.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 50)
	public void attackOnAMobHoveringOutOfReachEndsWithUnreachableWithoutALongSearch(GameTestHelper helper) {
		Blaze blaze = helper.spawn(EntityTypes.BLAZE, 12, 6, 15);
		blaze.setNoAi(true);
		blaze.setNoGravity(true);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 12, 1, 10);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
		ScriptRun run = run(helper, bot, attackThe("blaze"));
		helper.onEachTick(() -> helper.assertTrue(PathCounts.nodes(bot) <= 300, PathCounts.nodes(bot) + " nodes searched"));
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
	 * {@code attack(husk, min_health=12)} ends with {@code hurt} once the bot's health drops under 12, above the flee
	 * reflex's 6 (#718), and the bot's own goals take the husk up neither then nor while the script runs on: the bot is
	 * hurt and runs a script. The husk has 200 health, so the bot's blows do not end the fight first.
	 */
	// Measured: passes at tick 50 to 60.
	@GameTest(maxTicks = 120)
	public void attackWithMinHealthEndsWithHurtWhenHealthDropsUnderIt(GameTestHelper helper) {
		Husk husk = helper.spawn(EntityTypes.HUSK, 5, 1, 5);
		husk.setNoAi(true);
		husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
		husk.setHealth(200);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		ScriptRun run = run(helper, bot, "r = attack(entities('husk', 8)[0], min_health=12)\nprint(r.ok, r.reason)\nwait(20)\n");
		boolean[] hurt = {false};
		helper.onEachTick(() -> {
			if (!hurt[0] && husk.getHealth() < 200) {
				hurt[0] = true;
				bot.setHealth(10);
			}
			// Only while the script runs: once it finished, the bot no longer waits on it and fights the husk of its own accord.
			if (run.state() == ScriptRun.State.RUNNING && !BotScripts.log(bot, Output.Level.PRINT).isEmpty() && bot.getTarget() != null) {
				helper.fail("the bot's own goals took up " + bot.getTarget());
			}
		});
		succeedWhenPrinted(helper, bot, run, List.of("False hurt"));
	}

	/** Whether {@code creeper}'s fuse is out: it went out, or the creeper exploded. */
	private static boolean out(Creeper creeper) {
		return creeper.isRemoved() || creeper.getSwelling(1) == 0;
	}

	/**
	 * Fails the test at once when one of {@code creepers} explodes, which discards it, or a blast hurts the bot (#706);
	 * with no creepers, only on a blast that hurts it (#781). Registered up front, as every listener.
	 */
	private static void failOnExplosion(GameTestHelper helper, AntBot bot, Creeper... creepers) {
		helper.onEachTick(() -> {
			for (Creeper creeper : creepers) {
				if (creeper.getRemovalReason() == Entity.RemovalReason.DISCARDED) {
					helper.fail("a creeper exploded " + String.format("%.1f", creeper.distanceTo(bot)) + " blocks from the bot, its health " + bot.getHealth());
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

	/**
	 * The kite goal sees no creeper beside the test's area (GameTests.hides, #899): of a lit creeper 2 blocks off just
	 * outside it and one 4 blocks off inside it, the bot keeps clear of the inside one.
	 */
	@GameTest(maxTicks = 10)
	public void theKiteGoalSeesNoCreeperBesideTheTestArea(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 6, 1, 3);
		Creeper inside = helper.spawnWithNoFreeWill(EntityTypes.CREEPER, 2, 1, 3);
		Creeper outside = helper.spawnWithNoFreeWill(EntityTypes.CREEPER, 8, 1, 3);
		inside.setSwellDir(1);
		outside.setSwellDir(1);
		GameTests.onEnd(helper, () -> {
			inside.discard();
			outside.discard();
		});
		helper.succeedWhen(() -> helper.assertTrue(KiteGoal.litNear(bot) == inside,
			"kites from " + (KiteGoal.litNear(bot) == outside ? "the creeper outside the area" : KiteGoal.litNear(bot))));
	}

	/**
	 * A bot with an iron sword fights two creepers and a husk that come at it together, and no blast hurts it (#706): it
	 * kills the husk and each creeper, or a creeper explodes with the bot out of its blast, 6 to 7 blocks off, where the
	 * fuse burns on (SwellGoal), as in about 1 run in 50 (#868). Only a blast that hurts the bot fails it, as in
	 * {@link #aBotBetweenTwoCreepersGetsOutOfBothBlastsNotCornered} (#781).
	 */
	// Measured: passes at tick 477 to 485 in 3 runs alone, 802 in a full run; 432 to 897 in 200 copies at once (#868).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 1600)
	public void aBotKillsTwoCreepersAndAHuskUnhurtByABlast(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 12, 1, 8);
		bot.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		// Well fed, as lib.food keeps a working bot: worn down to flee health by the husk, as in about 1 copy in 400, it
		// heals there rather than take on no creeper until the test runs out (#908).
		bot.getFood().setSaturation(20);
		Creeper[] creepers = {helper.spawn(EntityTypes.CREEPER, 9, 1, 14), helper.spawn(EntityTypes.CREEPER, 15, 1, 14)};
		// Husks do not burn in daylight, so only the bot can kill it.
		Husk husk = helper.spawn(EntityTypes.HUSK, 12, 1, 15);
		failOnExplosion(helper, bot);
		helper.succeedWhen(() -> {
			for (Creeper creeper : creepers) {
				helper.assertTrue(creeper.isRemoved() || creeper.isDeadOrDying(), "creeper alive with " + creeper.getHealth() + " health");
			}
			helper.assertTrue(husk.isDeadOrDying(), "husk alive with " + husk.getHealth() + " health");
			helper.assertTrue(bot.isAlive(), "bot dead");
		});
	}

	/**
	 * A bot at flee health takes on no mob, but heals and fights once over it again (#908). It took a creeper 10 blocks
	 * off for its target, walked at it, fled it at 8 blocks, and took it again past 8, over and over: worn down to 1.2
	 * health by the husk of {@link #aBotKillsTwoCreepersAndAHuskUnhurtByABlast}, as in about 1 copy in 400, it so went
	 * on until the test ran out with a creeper at 20 health, one that never hunted it, as creepers hunt only players. Well
	 * fed, it heals a point every 10 ticks.
	 */
	// Measured: passes at tick 249 alone (red: took the creeper for its target at 1.2 health on tick 8).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 600)
	public void aBotAtFleeHealthTakesOnNoMobButHealsAndThenFights(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(12.5, 1, 4.5));
		bot.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		bot.setHealth(1.2F);
		bot.getFood().setFoodLevel(20);
		bot.getFood().setSaturation(20);
		Creeper creeper = helper.spawn(EntityTypes.CREEPER, new Vec3(12.5, 1, 14.5));
		failOnExplosion(helper, bot);
		helper.onEachTick(() -> {
			if (bot.getTarget() != null && bot.getHealth() <= AntCraftConfig.botFleeHealth()) {
				helper.fail("took " + bot.getTarget().getName().getString() + " for its target at " + bot.getHealth() + " health");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(creeper.isRemoved() || creeper.isDeadOrDying(), "creeper alive with " + creeper.getHealth() + " health, bot at " + bot.getHealth());
			helper.assertTrue(bot.isAlive(), "bot dead");
		});
	}

	/**
	 * A bot kills two creepers that walk at it side by side (#898). Each kept the bot from stepping in at the other, as a
	 * mob beside it, so it backed off every time their fuses lit and struck neither again, as in about 1 of 200 copies of
	 * {@link #aBotKillsTwoCreepersAndAHuskUnhurtByABlast}, where it kited two such creepers, at 1 and 7 health, for 1300
	 * ticks without a blow. Their health as there: one blow kills the first, its sweep leaves the second at 6.
	 */
	// Measured: passes at tick 82 alone, 72 to 145 in 100 copies at once (red: times out with the first at 1 health).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 300)
	public void aBotKillsTwoCreepersWalkingAtItSideBySide(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(12.5, 1, 4.5));
		bot.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		Creeper[] creepers = {helper.spawn(EntityTypes.CREEPER, new Vec3(12, 1, 11.5)), helper.spawn(EntityTypes.CREEPER, new Vec3(13, 1, 11.5))};
		creepers[0].setHealth(1);
		creepers[1].setHealth(7);
		for (Creeper creeper : creepers) {
			creeper.setTarget(bot);
		}
		failOnExplosion(helper, bot);
		helper.succeedWhen(() -> {
			for (Creeper creeper : creepers) {
				helper.assertTrue(creeper.isRemoved() || creeper.isDeadOrDying(), "creeper alive with " + creeper.getHealth() + " health");
			}
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

	/**
	 * A bot between a creeper whose fuse lights at once and another 5 blocks off gets out of both blasts (#725). It ran
	 * straight away from the lit one, into the other, which lit in turn and cornered it at the edge of the area, where it
	 * stood until it exploded, as about 1 run in 52 of {@link #aBotKillsTwoCreepersAndAHuskUnhurtByABlast}: it now
	 * backs off from the nearest creeper of all, lit or not, to the side. A creeper may explode with the bot out of its
	 * blast, 6 to 7 blocks off, where the fuse burns on (SwellGoal): that passes, as only a blast that hurts the bot fails
	 * it (#781). It failed once so in a full run (#759: an explosion at tick 32, the bot at full health).
	 */
	// Measured: passes at tick 33 to 57 in 50 runs, 25 at once; 31 to 55 in 52 more (#733); 31 to 76 in 100, 25 at once (#781).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 120)
	public void aBotBetweenTwoCreepersGetsOutOfBothBlastsNotCornered(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(12.5, 1, 12.5));
		bot.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		Creeper lit = helper.spawn(EntityTypes.CREEPER, new Vec3(10.5, 1, 12.5));
		lit.setTarget(bot);
		Creeper other = helper.spawn(EntityTypes.CREEPER, new Vec3(17.5, 1, 12.5));
		other.setTarget(bot);
		failOnExplosion(helper, bot);
		boolean[] wasLit = {false};
		helper.onEachTick(() -> wasLit[0] |= lit.getSwelling(1) > 0);
		helper.succeedWhen(() -> {
			helper.assertTrue(wasLit[0], "the fuse was never lit");
			helper.assertTrue(KiteGoal.litNear(bot) == null && out(lit) && out(other), "a fuse burns");
		});
	}

	/**
	 * A bot near a corner of the area, a lit creeper on the open side and its weapon not ready, gets out of the blast
	 * rather than back into the corner (#868). Straight away gained most for each block walked, so it backed into the
	 * corner, 4 to 6 blocks from the creeper, and stood there until it exploded, as in 4 of 200 copies of
	 * {@link #aBotKillsTwoCreepersAndAHuskUnhurtByABlast}. Only a blast that hurts the bot fails it (#781).
	 */
	// Measured: the fuse lit at once (#915), passes at tick 48 alone and in 200 of 200 copies; before #868 the bot was
	// hurt in 100 of 100 copies.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 120)
	public void aBotBacksOffALitCreeperNearACornerUnhurt(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(2.5, 1, 2.5));
		bot.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		Creeper creeper = helper.spawn(EntityTypes.CREEPER, new Vec3(5, 1, 5));
		creeper.setTarget(bot);
		creeper.setSwellDir(1);
		// Struck it just now: the weapon is not ready, so the bot backs off rather than step in.
		bot.setLastHurtMob(creeper);
		failOnExplosion(helper, bot);
		boolean[] wasLit = {false};
		helper.onEachTick(() -> wasLit[0] |= creeper.getSwelling(1) > 0);
		helper.succeedWhen(() -> {
			helper.assertTrue(wasLit[0], "the fuse was never lit");
			helper.assertTrue(out(creeper), "fuse still lit, swell " + creeper.getSwelling(1));
		});
	}

	/**
	 * A bot at flee health with a creeper's fuse lit beside it kites, as at any health, rather than flee (#725): the flee
	 * reflex yields the bot's movement to the kite goal while the fuse burns, and plans no flight, which fought the goal
	 * for it.
	 */
	// Measured: passes at tick 46 to 50 in 50 runs, 25 at once.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 100)
	public void aFleeingBotKitesALitCreeperRatherThanFlee(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(12.5, 1, 8.5));
		bot.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		bot.setHealth(4);
		Creeper creeper = helper.spawn(EntityTypes.CREEPER, new Vec3(12.5, 1, 10.5));
		creeper.setTarget(bot);
		failOnExplosion(helper, bot, creeper);
		// The fuse lit at the end of the tick before, when the bot planned: it may plan before the creeper lights.
		int[] plans = {0};
		boolean[] wasLit = {false};
		helper.onEachTick(() -> {
			boolean lit = KiteGoal.litNear(bot) != null;
			if (bot.getFlightPlans() > plans[0] && lit && wasLit[0]) {
				helper.fail("planned a flight with the fuse lit, swell " + creeper.getSwelling(1));
			}
			plans[0] = bot.getFlightPlans();
			wasLit[0] = lit;
		});
		succeedWhenTheFuseWentOut(helper, creeper, 1F);
	}

	/**
	 * A hurt idle bot more than 8 blocks from a lit creeper, as after kiting there, out of reach of both the flee reflex
	 * and the kite goal, takes the creeper for no target while its fuse burns (#733): its melee goal would walk it back
	 * into the blast. The creeper stands still, its fuse lit by hand and then going out, so the bot stays out of range.
	 */
	// Measured: passes at tick 35 to 96 in 22 runs, 10 at once: the bot takes its target at random after the fuse is out.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 200)
	public void aHurtBotFarFromALitCreeperDoesNotTargetIt(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(12.5, 1, 2.5));
		bot.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		bot.setHealth(4);
		Creeper creeper = helper.spawn(EntityTypes.CREEPER, new Vec3(12.5, 1, 12.5));
		creeper.setNoAi(true);
		creeper.setSwellDir(1);
		failOnExplosion(helper, bot, creeper);
		helper.onEachTick(() -> {
			if (creeper.getSwelling(1) >= 0.6F) {
				creeper.setSwellDir(-1);
			}
			if (bot.getTarget() == creeper && KiteGoal.lit(creeper)) {
				helper.fail("targeted the creeper with its fuse lit, swell " + creeper.getSwelling(1));
			}
		});
		// Once the fuse is out, it does take the creeper on: so out of range it would have seen it all along.
		helper.succeedWhen(() -> helper.assertTrue(creeper.getSwellDir() < 0 && creeper.getSwelling(1) == 0 && bot.getTarget() == creeper,
			"swell " + creeper.getSwelling(1) + ", target " + bot.getTarget()));
	}

	/**
	 * A bot that has struck nothing yet, such as one just spawned, has its weapon ready: it steps in and strikes a creeper
	 * lit beside it at once, rather than wait out the 13 ticks of an iron sword, by when the fuse is too far gone to step in
	 * (#942). The kite goal took the weapon for ready only 13 ticks after the bot spawned, so where its melee goal walked
	 * it up to the creeper of {@link #aBotCorneredInADeadEndStrikesTheLitCreeperAndOutlivesItsBlast} in its first ticks,
	 * which lit the fuse, it never struck, in about 1 copy in 5.
	 */
	// Measured: passes at tick 6 (red: not struck, swell 0.5 on tick 14).
	@GameTest(maxTicks = 12)
	public void aBotThatStruckNothingYetStrikesALitCreeperBesideItAtOnce(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(2.5, 1, 2.5));
		bot.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		Creeper creeper = helper.spawn(EntityTypes.CREEPER, new Vec3(2.5, 1, 4.5));
		creeper.setTarget(bot);
		creeper.setSwellDir(1);
		GameTests.onEnd(helper, creeper::discard);
		float health = creeper.getHealth();
		helper.succeedWhen(() -> helper.assertTrue(creeper.getHealth() < health, "not struck, swell " + creeper.getSwelling(1)));
	}

	/**
	 * A bot that just struck another mob holds back from a creeper outside the 3 blocks where its fuse lights until its
	 * weapon is ready, then steps in and strikes (#949). The melee goal walked it up with the weapon still cooling, which
	 * lit the fuse with no blow ready. The creeper has no AI, so it neither walks nor lights: only the bot closes in.
	 */
	// Measured: passes at tick 22 (red: came 2.6 blocks near with the weapon not ready, tick 10).
	@GameTest(maxTicks = 45)
	public void aBotThatJustStruckAnotherMobHoldsBackFromACreeperUntilItsWeaponIsReady(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(2.5, 1, 1.5));
		bot.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		Pig pig = helper.spawn(EntityTypes.PIG, new Vec3(6.5, 1, 1.5));
		pig.setNoAi(true);
		Creeper creeper = helper.spawn(EntityTypes.CREEPER, new Vec3(2.5, 1, 6));
		creeper.setNoAi(true);
		bot.setLastHurtMob(pig);
		bot.setTarget(creeper);
		float health = creeper.getHealth();
		// An iron sword strikes every 13 ticks (BotHands.strikeTicks).
		helper.onEachTick(() -> {
			if (creeper.getHealth() == health && bot.distanceTo(creeper) < 3 && bot.tickCount - bot.getLastHurtMobTimestamp() < 13) {
				helper.fail("came " + bot.distanceTo(creeper) + " blocks near with the weapon not ready, tick " + helper.getTick());
			}
		});
		helper.succeedWhen(() -> helper.assertTrue(creeper.getHealth() < health, "not struck"));
	}

	/**
	 * A bot whose weapon cools backs away from a creeper walking at it, keeping outside the 3 blocks where its fuse lights,
	 * until the weapon is ready (#955). It stood still 4 blocks off, so the creeper walked up and lit its fuse. An iron axe
	 * strikes every 23 ticks (BotHands.strikeTicks), long enough for the creeper to close in. The bot waits without AI
	 * while the creeper sets off from 8 blocks, as a creeper takes some 20 ticks to start walking, and strikes the pig
	 * (so its axe starts cooling) and turns on once the creeper is 5 blocks off.
	 */
	// Measured: passes at tick 52, the bot kept 3.8 to 4.1 blocks off (red: came 2.9 blocks near, the fuse lit, tick 37).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 105)
	public void aBotWithItsWeaponCoolingBacksAwayFromACreeperWalkingAtIt(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(12.5, 1, 4.5));
		bot.getInventory().addItem(new ItemStack(Items.IRON_AXE));
		bot.setNoAi(true);
		Pig pig = helper.spawn(EntityTypes.PIG, new Vec3(16.5, 1, 4.5));
		pig.setNoAi(true);
		Creeper creeper = helper.spawn(EntityTypes.CREEPER, new Vec3(12.5, 1, 12.5));
		GameTests.onEnd(helper, creeper::discard);
		creeper.setTarget(bot);
		boolean[] struck = {false};
		helper.onEachTick(() -> {
			if (!struck[0] && bot.distanceTo(creeper) <= 5) {
				struck[0] = true;
				bot.setNoAi(false);
				bot.setLastHurtMob(pig);
				bot.setTarget(creeper);
			} else if (struck[0] && !BotHands.weaponReady(bot) && bot.distanceTo(creeper) <= 3) {
				helper.fail("came " + bot.distanceTo(creeper) + " blocks near with the weapon not ready, tick " + helper.getTick());
			}
		});
		helper.succeedWhen(() -> helper.assertTrue(struck[0] && BotHands.weaponReady(bot), "the weapon is not ready"));
	}

	/**
	 * A bot in a 1-wide dead end with a creeper lit in front of it is cornered: it goes at the creeper and strikes it, which
	 * knocks it back, rather than stand (#725). It cannot back off 7 blocks, and an iron sword does not kill the creeper
	 * within its 30-tick fuse, so the blast still catches it. It does not go at the creeper again with the fuse far gone
	 * (#793): where it struck a second time from 1.4 blocks the blast killed it at full health; staying 4.4 blocks off it
	 * keeps 12.7. A death would be accepted, as it costs only what the bot carries (docs/features/bots.md, Fighting
	 * creepers; #733), but this one was avoidable. Digging or pillaring out of the blast is not worth its code.
	 */
	// Measured: passes at tick 33 to 57 in 300 of 300 copies (#942; before, 239 of 300: a bot that walked up in its first
	// ticks, lighting the fuse, took its weapon for not ready until tick 13 and never struck); red, striking again: the bot
	// dead at tick 60, struck the creeper twice.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 100)
	public void aBotCorneredInADeadEndStrikesTheLitCreeperAndOutlivesItsBlast(GameTestHelper helper) {
		deadEnd(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(12.5, 1, 3.5));
		bot.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		Creeper creeper = helper.spawn(EntityTypes.CREEPER, new Vec3(12.5, 1, 6.5));
		creeper.setTarget(bot);
		int[] blows = {0};
		float[] health = {creeper.getHealth()};
		helper.onEachTick(() -> {
			if (creeper.getHealth() < health[0]) {
				blows[0]++;
			}
			health[0] = creeper.getHealth();
			if (!bot.isAlive()) {
				helper.fail("the blast killed the bot, struck the creeper " + blows[0] + " times");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(blows[0] >= 1, "never struck the creeper");
			helper.assertTrue(creeper.isRemoved(), "the creeper has not exploded yet");
		});
	}

	/**
	 * A bot cornered in the dead end of {@link #aBotCorneredInADeadEndStrikesTheLitCreeperAndOutlivesItsBlast} whose weapon
	 * is not ready plans its way off only every few ticks while it waits for the weapon, not each tick, 20 spots and a path,
	 * some 2 ms (#759).
	 */
	// Measured: passes at tick 18 (red: planned 4 ways off by tick 4).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 40)
	public void aCorneredBotWaitingForItsWeaponPlansNoWayOffEachTick(GameTestHelper helper) {
		deadEnd(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(12.5, 1, 3.5));
		bot.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		Creeper creeper = helper.spawn(EntityTypes.CREEPER, new Vec3(12.5, 1, 6.5));
		creeper.setTarget(bot);
		creeper.setSwellDir(1);
		bot.setLastHurtMob(creeper);
		float health = creeper.getHealth();
		helper.onEachTick(() -> {
			if (bot.getKitePlans() > 2 + helper.getTick() / 3) {
				helper.fail("planned " + bot.getKitePlans() + " ways off by tick " + helper.getTick());
			}
		});
		helper.succeedWhen(() -> helper.assertTrue(creeper.getHealth() < health, "not struck"));
	}

	/**
	 * A bot at the end of a 1-wide dead end, a creeper lit for another 7.5 blocks off past its mouth, stays put out of the
	 * blast (#785). In the benchmark creepers case bots died two ways here: a way off that ends further from the creeper
	 * but runs past it, and cornered by a creeper 8 blocks off, which it walked up to. The creeper stands still, its fuse
	 * lit by hand and put out before it explodes.
	 */
	// Measured: passes at tick 52 (red: came 5.8 blocks near at tick 9).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 120)
	public void aBotInADeadEndStaysOutOfAFarLitCreepersBlast(GameTestHelper helper) {
		for (int z = 2; z <= 8; z++) {
			for (int y = 1; y <= 2; y++) {
				helper.setBlock(11, y, z, Blocks.STONE);
				helper.setBlock(13, y, z, Blocks.STONE);
				if (z == 2) {
					helper.setBlock(12, y, z, Blocks.STONE);
				}
			}
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(12.5, 1, 3.5));
		bot.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		Creeper creeper = helper.spawn(EntityTypes.CREEPER, new Vec3(12.5, 1, 11));
		creeper.setNoAi(true);
		creeper.setSwellDir(1);
		helper.onEachTick(() -> {
			if (creeper.getSwelling(1) >= 0.9F) {
				creeper.setSwellDir(-1);
			}
			if (bot.distanceTo(creeper) < 6.5) {
				helper.fail("came " + String.format("%.1f", bot.distanceTo(creeper)) + " blocks near the lit creeper, swell " + creeper.getSwelling(1));
			}
		});
		succeedWhenTheFuseWentOut(helper, creeper, 1F);
	}

	/**
	 * A bot at the end of a 1-wide dead end, a creeper lit 5.5 blocks off at its mouth, whose weapon is ready only once
	 * nearly half the fuse has burnt, stays at the edge of the blast rather than go at the creeper (#793). In the benchmark
	 * creepers case a bot cornered so at 5.4 blocks with the fuse at 0.82 walked to 2.9 blocks, where the blast killed it at
	 * full health: it could not get within striking reach before the fuse ran out, and at the edge the blast hurts little.
	 * The creeper stands still, its fuse lit by hand and put out before it explodes.
	 */
	// Measured: passes at tick 54 (red: came 4.6 blocks near at tick 15, swell 0.54).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 110)
	public void aCorneredBotDoesNotGoAtACreeperWhoseFuseIsTooFarGone(GameTestHelper helper) {
		for (int z = 2; z <= 8; z++) {
			for (int y = 1; y <= 2; y++) {
				helper.setBlock(11, y, z, Blocks.STONE);
				helper.setBlock(13, y, z, Blocks.STONE);
				if (z == 2) {
					helper.setBlock(12, y, z, Blocks.STONE);
				}
			}
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(12.5, 1, 3.5));
		bot.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		Creeper creeper = helper.spawn(EntityTypes.CREEPER, new Vec3(12.5, 1, 9));
		creeper.setNoAi(true);
		creeper.setSwellDir(1);
		// Struck it just now: the weapon is ready only after the 13 ticks of an iron sword, nearly half the fuse (#942).
		bot.setLastHurtMob(creeper);
		helper.onEachTick(() -> {
			if (creeper.getSwelling(1) >= 0.95F) {
				creeper.setSwellDir(-1);
			}
			// Only while the fuse burns: put out by hand 5.5 blocks off, as a real one never is, it may go at the creeper.
			if (creeper.getSwellDir() > 0 && bot.distanceTo(creeper) < 5) {
				helper.fail("came " + String.format("%.1f", bot.distanceTo(creeper)) + " blocks near the lit creeper, swell " + creeper.getSwelling(1));
			}
		});
		succeedWhenTheFuseWentOut(helper, creeper, 1F);
	}

	/**
	 * A bot that backs off from a lit creeper, as a husk beside it keeps it from stepping in, does not turn back to step in
	 * when the husk is gone a few ticks later, with the fuse still short (#793). In the benchmark creepers case bots flipped
	 * so between stepping in and planning a way off every few ticks, as other creepers came and went within 3 blocks. The
	 * creeper and the husk stand still and come once the bot's weapon is ready; the fuse is put out before it explodes.
	 */
	// Measured: passes at tick 67 (red: struck the lit creeper at tick 26, swell 0.39).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 130)
	public void aBotBackingOffFromACrowdDoesNotTurnBackWhenTheCrowdGoes(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(12.5, 1, 8.5));
		bot.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		Creeper[] creeper = {null};
		Husk[] husk = {null};
		helper.onEachTick(() -> {
			long tick = helper.getTick();
			if (tick == 15) {
				husk[0] = helper.spawn(EntityTypes.HUSK, new Vec3(11.0, 1, 8.5));
				husk[0].setNoAi(true);
				creeper[0] = helper.spawn(EntityTypes.CREEPER, new Vec3(12.5, 1, 10.5));
				creeper[0].setNoAi(true);
				creeper[0].setSwellDir(1);
			} else if (tick == 18) {
				husk[0].discard();
			}
			if (creeper[0] == null) {
				return;
			}
			if (creeper[0].getSwelling(1) >= 0.9F) {
				creeper[0].setSwellDir(-1);
			}
			if (KiteGoal.lit(creeper[0]) && creeper[0].getHealth() < creeper[0].getMaxHealth()) {
				helper.fail("stepped back in and struck the lit creeper, swell " + creeper[0].getSwelling(1));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(creeper[0] != null && creeper[0].getSwellDir() < 0 && creeper[0].getSwelling(1) == 0, "fuse not lit and out yet");
		});
	}

	/** A closed stone tunnel along z at x = 12, two high, from z = 3 to z = 13. */
	private static void deadEnd(GameTestHelper helper) {
		for (int z = 2; z <= 14; z++) {
			for (int y = 1; y <= 3; y++) {
				for (int x = 11; x <= 13; x++) {
					if (x != 12 || y == 3 || z == 2 || z == 14) {
						helper.setBlock(x, y, z, Blocks.STONE);
					}
				}
			}
		}
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
	 * An idle bot with a bow and arrows that a creeper comes at shoots it of its own accord rather than kite it (#724):
	 * only arrows hurt it, and it neither explodes nor hurts the bot.
	 */
	// Measured: passes at tick 68 to 79.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 150)
	public void anIdleBotWithABowShootsACreeperThatComesAtIt(GameTestHelper helper) {
		AntBot bot = archer(helper, 12, 3, 16);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
		Creeper creeper = helper.spawn(EntityTypes.CREEPER, 12, 1, 18);
		creeper.setTarget(bot);
		failOnExplosion(helper, bot, creeper);
		failOnAnythingButArrows(helper, creeper);
		helper.succeedWhen(() -> helper.assertTrue(creeper.isDeadOrDying(), "creeper alive with " + creeper.getHealth() + " health"));
	}

	/**
	 * An idle bot with a bow and arrows that a zombie comes at shoots it of its own accord rather than strike it with the
	 * sword in its hand (#734): only arrows hurt it. The zombie wears a helmet, so the sun does not kill it instead.
	 */
	// Measured: passes at tick 72 to 112 in 4 runs.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 220)
	public void anIdleBotWithABowShootsAZombieThatComesAtIt(GameTestHelper helper) {
		AntBot bot = archer(helper, 12, 3, 16);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
		Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, 12, 1, 18);
		zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
		zombie.setTarget(bot);
		failOnAnythingButArrows(helper, zombie);
		helper.succeedWhen(() -> helper.assertTrue(zombie.isDeadOrDying(), "zombie alive with " + zombie.getHealth() + " health"));
	}

	/**
	 * A hurt idle bot with a bow keeps its distance from a mob out of its range rather than walk closer (#734), as
	 * {@code attack(min_health=...)} lets go once hurt: it still targets the still zombie 20 blocks off, but stays put.
	 */
	// Passes at tick 60, where it checks.
	// Full runs: passes at up to tick 60 (#861), the fixed wait where it checks.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 120)
	public void aHurtBotWithABowDoesNotCloseInOnAMobOutOfRange(GameTestHelper helper) {
		AntBot bot = archer(helper, 12, 1, 16);
		bot.setHealth(10);
		Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, 12, 1, 21);
		zombie.setNoAi(true);
		zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
		double z = bot.getZ();
		// Kept at 10, under ShootGoal's 12 (#750): a fed bot heals a health in 10 ticks.
		helper.onEachTick(() -> bot.setHealth(Math.min(bot.getHealth(), 10)));
		helper.onEachTick(() -> helper.assertTrue(Math.abs(bot.getZ() - z) < 1, "the bot walked " + (bot.getZ() - z) + " blocks"));
		helper.runAtTickTime(60, () -> {
			helper.assertTrue(bot.getTarget() == zombie, "target " + bot.getTarget());
			helper.succeed();
		});
	}

	/**
	 * A bot with a bow that cannot get nearer to a mob out of its range does not plan a way to it every tick (#792): it
	 * stands in a ring of fences it sees over but cannot cross, the still zombie 18 blocks off. Its vanilla navigation
	 * searched each tick it was done, as a way to the zombie never got it anywhere.
	 */
	// Passes at tick 60, where it checks; it planned 58 times before.
	// Full runs: passes at up to tick 60 (#861), the fixed wait where it checks.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 120)
	public void aBotWithABowPlansAWayToAMobItCannotGetNearerToOnlyNowAndThen(GameTestHelper helper) {
		for (int x = 11; x <= 13; x++) {
			for (int z = 2; z <= 4; z++) {
				if (x != 12 || z != 3) {
					helper.setBlock(x, 1, z, Blocks.OAK_FENCE);
				}
			}
		}
		AntBot bot = archer(helper, 12, 3, 16);
		Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, 12, 1, 21);
		zombie.setNoAi(true);
		zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
		helper.runAtTickTime(60, () -> {
			helper.assertTrue(bot.getTarget() == zombie, "target " + bot.getTarget());
			int plans = PathCounts.plans(bot);
			helper.assertTrue(plans <= 8, plans + " plans in 60 ticks");
			helper.succeed();
		});
	}

	/**
	 * {@code shoot} plans a walk only to where it sees the mob within its bow's range, not up to it (#792): the blaze
	 * hovers 11 blocks over the stone floor, 19 off, where no walk gets near it. Its walk to within 1 block of the blaze
	 * searched 1152 nodes for a way up; the walk into sight, 50.
	 */
	// Measured: passes at tick 91.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 200)
	public void shootWalksOnlyIntoRangeOfAMobItCannotGetTo(GameTestHelper helper) {
		AntBot bot = archer(helper, 12, 2, 16);
		Blaze blaze = helper.spawn(EntityTypes.BLAZE, 12, 12, 21);
		blaze.setNoAi(true);
		blaze.setNoGravity(true);
		// Without AI the blaze keeps the knockback of a hit as its movement for ever, and the bot would aim ahead of it.
		helper.onEachTick(() -> blaze.setDeltaMovement(Vec3.ZERO));
		ScriptRun run = run(helper, bot, "r = shoot(entities('blaze', 32)[0])\nprint(r.ok, r.reason)\n");
		helper.onEachTick(() -> helper.assertTrue(PathCounts.nodes(bot) <= 300, PathCounts.nodes(bot) + " nodes searched"));
		succeedWhenPrinted(helper, bot, run, List.of("True None"));
	}

	/**
	 * A bot with a scratch, kept at 18 health, and a bow still closes in on a mob out of its range (#750): only a real low
	 * health, under the guard's RETREAT of 12, keeps it waiting. The still zombie stands 20 blocks off.
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 60)
	public void aScratchedBotWithABowClosesInOnAMobOutOfRange(GameTestHelper helper) {
		AntBot bot = archer(helper, 12, 1, 16);
		bot.setHealth(18);
		Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, 12, 1, 21);
		zombie.setNoAi(true);
		zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
		double z = bot.getZ();
		// Kept at 18: a fed bot heals to full in a few ticks, and closes in then anyway.
		helper.onEachTick(() -> bot.setHealth(Math.min(bot.getHealth(), 18)));
		helper.succeedWhen(() -> helper.assertTrue(bot.getZ() - z > 2, "the bot walked " + (bot.getZ() - z) + " blocks"));
	}

	/**
	 * An idle bot leaves an enderman near it alone, as arrows do not hit it and a blow turns it on the bot (#750); once the
	 * enderman hurts it, it fights back (HurtByTargetGoal). The enderman has no AI, so it neither wanders nor teleports.
	 */
	// Full runs: passes at up to tick 42 (#861).
	@GameTest(maxTicks = 90)
	public void anIdleBotLeavesAnEndermanAloneUntilHurtByIt(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		Enderman enderman = helper.spawn(EntityTypes.ENDERMAN, 5, 1, 5);
		enderman.setNoAi(true);
		helper.onEachTick(() -> {
			if (helper.getTick() < 40) {
				helper.assertTrue(bot.getTarget() != enderman, "targets the enderman unprovoked");
			}
		});
		helper.runAtTickTime(40, () -> bot.hurtServer(helper.getLevel(), helper.getLevel().damageSources().mobAttack(enderman), 1));
		helper.succeedWhen(() -> {
			helper.assertTrue(helper.getTick() > 40, "not hurt yet");
			helper.assertTrue(bot.getTarget() == enderman, "target " + bot.getTarget());
		});
	}

	/**
	 * The kite goal strikes a lit creeper with the sword the bot carries, not the bow in its hand (#734): the bot has no
	 * arrows, so it does not shoot. The creeper stands still, lit by hand, beside the bot. The bow struck first, a blow of 1.
	 */
	// Measured: passes at tick 1 in 4 runs.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 10)
	public void aKiteStrikeUsesTheSwordNotTheBow(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 12, 1, 8);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
		bot.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		// Long enough alive that a sword is ready at once: the bot strikes rather than back off first.
		bot.tickCount = 100;
		Creeper creeper = helper.spawn(EntityTypes.CREEPER, new Vec3(12.5, 1, 9.7));
		creeper.setNoAi(true);
		creeper.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
		creeper.setHealth(200);
		creeper.setSwellDir(1);
		helper.succeedWhen(() -> {
			helper.assertTrue(creeper.getHealth() < 200, "creeper not struck");
			helper.assertTrue(bot.getMainHandItem().is(Items.IRON_SWORD), "struck with " + bot.getMainHandItem());
			helper.assertTrue(200 - creeper.getHealth() >= 6, "a blow of " + (200 - creeper.getHealth()));
		});
	}

	/**
	 * An arrow the bot shot lies on the ground for {@code pick_up} to take back, as a player's (#720): its one arrow bounces
	 * off an invulnerable husk, so {@code shoot} ends with {@code no_item}, and {@code pick_up} returns it.
	 */
	// Measured: passes at tick 78.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 160)
	public void anArrowTheBotShotCanBePickedUp(GameTestHelper helper) {
		Husk husk = helper.spawn(EntityTypes.HUSK, 12, 1, 18);
		husk.setNoAi(true);
		husk.setPermanentlyInvulnerable(true);
		AntBot bot = archer(helper, 12, 10, 1);
		ScriptRun run = run(helper, bot, """
			r = shoot(entities('husk', 16)[0])
			print(r.reason, count('arrow'))
			wait(30)
			p = pick_up('arrow', 16)
			print(p.ok, p.reason, count('arrow'))
			""");
		succeedWhenPrinted(helper, bot, run, List.of("no_item 0", "True None 1"));
	}

	/**
	 * A bot's arrow passes through another bot (#775): {@code shoot} kills a husk behind a bot that stands in the line of
	 * fire, and that bot is never hurt. The bot in front has no AI, so it stays put and leaves the husk alone.
	 */
	// Measured: passes at tick 70.
	// Full runs: passes at up to tick 91 (#861).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 190)
	public void aBotsArrowPassesThroughABotInTheLineOfFire(GameTestHelper helper) {
		Husk husk = helper.spawn(EntityTypes.HUSK, 12, 1, 18);
		husk.setNoAi(true);
		AntBot front = helper.spawn(ModEntities.ANT_BOT, 12, 1, 10);
		front.setNoAi(true);
		helper.onEachTick(() -> {
			if (front.getLastDamageSource() != null) {
				helper.fail("the bot in front was hurt by " + front.getLastDamageSource().getMsgId());
			}
		});
		AntBot bot = archer(helper, 12, 3, 16);
		ScriptRun run = run(helper, bot, "r = shoot(entities('husk', 20)[0])\nprint(r.ok, r.reason)\n");
		succeedWhenPrinted(helper, bot, run, List.of("True None"));
	}

	/** {@code shoot(entity, min_health=...)} ends with {@code hurt} once the bot's health is under it, as {@code attack} does (#724). */
	// Measured: passes at tick 47.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 100)
	public void shootWithMinHealthEndsWithHurtWhenHealthDropsUnderIt(GameTestHelper helper) {
		Husk husk = helper.spawn(EntityTypes.HUSK, 12, 1, 14);
		husk.setNoAi(true);
		husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
		husk.setHealth(200);
		AntBot bot = archer(helper, 12, 3, 16);
		ScriptRun run = run(helper, bot, "r = shoot(entities('husk', 16)[0], min_health=10)\nprint(r.ok, r.reason)\nwait(20)\n");
		boolean[] hurt = {false};
		helper.onEachTick(() -> {
			if (!hurt[0] && husk.getHealth() < 200) {
				hurt[0] = true;
				bot.setHealth(9);
			}
			// Only while the script runs: once it finished, the bot no longer waits on it and fights the husk of its own accord.
			if (run.state() == ScriptRun.State.RUNNING && !BotScripts.log(bot, Output.Level.PRINT).isEmpty() && bot.getTarget() != null) {
				helper.fail("the bot's own goals took up " + bot.getTarget());
			}
		});
		succeedWhenPrinted(helper, bot, run, List.of("False hurt"));
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

	/**
	 * A bot spawned in the air with a creeper's fuse lit beside it plans its way off while it falls, from the block it
	 * lands on, rather than lose its first ticks to no path: vanilla navigation plans only on the ground, and the goal
	 * then waited 4 ticks to plan again, of the fuse's 30 (#901).
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 10)
	public void aBotSpawnedInTheAirBacksOffALitCreeperWhileItFalls(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(12.5, 1.5, 12.5));
		bot.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		Creeper creeper = helper.spawn(EntityTypes.CREEPER, new Vec3(14.5, 1, 12.5));
		creeper.setTarget(bot);
		creeper.setSwellDir(1);
		// Struck it just now: the weapon is not ready, so the bot backs off rather than step in.
		bot.setLastHurtMob(creeper);
		boolean[] landed = {false};
		helper.onEachTick(() -> {
			if (bot.getNavigation().getPath() == null && landed[0]) {
				helper.fail("no path on tick " + helper.getTick() + ", landed the tick before");
			}
			landed[0] |= bot.onGround();
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(!bot.onGround(), "landed with no path yet");
			helper.assertTrue(bot.getNavigation().getPath() != null, "no path yet, at y " + (bot.getY() - helper.absoluteVec(Vec3.ZERO).y));
		});
	}
}
