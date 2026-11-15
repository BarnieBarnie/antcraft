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
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** A bot's hunger (#702, docs/features/bots.md, Hunger): food that work uses up, healing with it, starving, and {@code eat}. */
public class HungerGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** Runs {@code source} on {@code bot} and succeeds once it finished having printed exactly {@code expected}. */
	private static void expect(GameTestHelper helper, AntBot bot, String source, List<String> expected) {
		expect(helper, bot, source, expected, () -> {
		});
	}

	/** Like {@link #expect(GameTestHelper, AntBot, String, List)}, also checking {@code then}. */
	private static void expect(GameTestHelper helper, AntBot bot, String source, List<String> expected, Runnable then) {
		ScriptRun run = BotScripts.run(bot, source);
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> {
			List<String> printed = BotScripts.log(bot, Output.Level.PRINT);
			helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "script " + run.state() + ", printed " + printed);
			helper.assertTrue(printed.equals(expected), "printed " + printed + ", expected " + expected);
			then.run();
		});
	}

	private static String at(GameTestHelper helper, int x, int y, int z) {
		BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
		return "Pos(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
	}

	/** Mining a block adds a player's exhaustion for it, which with no saturation left takes a point of food at the end of the tick. */
	@GameTest(maxTicks = 40)
	public void miningUsesUpFood(GameTestHelper helper) {
		helper.setBlock(2, 1, 2, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getFood().setSaturation(0);
		bot.getFood().exhaust(3.999F);
		expect(helper, bot, "print(food(), saturation())\nmine(" + at(helper, 2, 1, 2) + ")\nwait(1)\nprint(food())\n", List.of("20 0.0", "19"));
	}

	/** Walking up steps jumps, and each jump adds a player's exhaustion for it. */
	@GameTest(maxTicks = 70)
	public void walkingUpStepsUsesUpFood(GameTestHelper helper) {
		helper.setBlock(3, 1, 3, Blocks.STONE);
		for (int y = 1; y <= 2; y++) {
			helper.setBlock(4, y, 3, Blocks.STONE);
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 3);
		GameTests.walkTo(bot, helper.absolutePos(new BlockPos(4, 3, 3)));
		helper.succeedWhen(() -> {
			helper.assertTrue(GameTests.walked(bot), "still walking");
			helper.assertTrue(bot.getFood().exhaustion() >= 0.1F - 1e-6, "exhaustion " + bot.getFood().exhaustion());
		});
	}

	/** A hurt bot at 18 food without saturation heals a point every 80 ticks, as a player does. */
	@GameTest(maxTicks = 120)
	public void aFedBotHeals(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.getFood().setFoodLevel(18);
		bot.getFood().setSaturation(0);
		bot.setHealth(10);
		helper.succeedWhen(() -> helper.assertTrue(bot.getHealth() == 11, "health " + bot.getHealth()));
	}

	/** The Saturation effect feeds a bot as it does a player, though vanilla's feeds only players (#730). */
	@GameTest(maxTicks = 20)
	public void theSaturationEffectFeedsABot(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.getFood().setFoodLevel(10);
		bot.getFood().setSaturation(0);
		bot.addEffect(new MobEffectInstance(MobEffects.SATURATION, 5));
		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getFood().food() >= 12, "food " + bot.getFood().food());
			helper.assertTrue(bot.getFood().saturation() >= 4, "saturation " + bot.getFood().saturation());
		});
	}

	/** A bot at 0 food starves: it loses a point every 80 ticks. */
	@GameTest(maxTicks = 120)
	public void aStarvingBotLosesHealth(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.getFood().setFoodLevel(0);
		bot.getFood().setSaturation(0);
		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getHealth() == 19, "health " + bot.getHealth());
			helper.assertTrue(bot.getLastDamageSource() != null && bot.getLastDamageSource().is(DamageTypes.STARVE),
				"hurt by " + bot.getLastDamageSource());
		});
	}

	/** {@code eat} takes the item's eat time, 32 ticks for a steak, then gives its nutrition and saturation and uses it up. */
	@GameTest(maxTicks = 70)
	public void eatGivesTheFoodsNutritionAfterItsEatTime(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.getFood().setFoodLevel(10);
		bot.getFood().setSaturation(0);
		bot.getInventory().addItem(new ItemStack(Items.COOKED_BEEF, 2));
		bot.getInventory().addItem(new ItemStack(Items.DIRT));
		expect(helper, bot, """
			t = game_time()
			r = eat('cooked_beef')
			print(r.ok, food(), saturation() > 12, count('cooked_beef'), game_time() - t >= 32)
			print(eat('dirt').reason, eat('bread').reason)
			""", List.of("True 18 True 1 True", "no_item no_item"));
	}

	/** A full bot cannot eat a steak; a golden apple it can, and it gets the apple's effects. */
	@GameTest(maxTicks = 70)
	public void aFullBotEatsOnlyWhatCanAlwaysBeEatenWithItsEffects(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.COOKED_BEEF));
		bot.getInventory().addItem(new ItemStack(Items.GOLDEN_APPLE));
		expect(helper, bot, "print(eat('cooked_beef').reason, eat('golden_apple').ok, count('cooked_beef'), count('golden_apple'))\n",
			List.of("full True 1 0"), () -> helper.assertTrue(bot.hasEffect(MobEffects.REGENERATION) && bot.hasEffect(MobEffects.ABSORPTION),
				"effects " + bot.getActiveEffects()));
	}

	/** The food state is saved and loaded with the bot. */
	@GameTest(maxTicks = 10)
	public void foodIsSavedWithTheBot(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.getFood().setFoodLevel(7);
		bot.getFood().setSaturation(2);
		bot.getFood().exhaust(1.5F);
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
		bot.saveWithoutId(output);
		bot.discard();
		AntBot copy = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		copy.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), output.buildResult()));
		helper.assertValueEqual(copy.getFood().food(), 7, "food");
		helper.assertValueEqual(copy.getFood().saturation(), 2F, "saturation");
		helper.assertValueEqual(copy.getFood().exhaustion(), 1.5F, "exhaustion");
		helper.succeed();
	}
}
