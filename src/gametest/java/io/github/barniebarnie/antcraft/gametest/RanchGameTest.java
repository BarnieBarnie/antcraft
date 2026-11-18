package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * {@code jobs/ranch.py} (#837; docs/cookbook/ranch.md): a pen of oak fence round x 0 to 7, z 3 to 7 with a gate at
 * (3, 1, 3), so its inside is x 1 to 6, z 4 to 6; the bot starts in it, a supply chest at (6, 1, 1) and the depot's
 * first chest goes at (1, 1, 1), three blocks north of the pen's low corner.
 */
public class RanchGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** The pen, a supply chest of supplies and 2 chests, the bot in the pen and jobs.ranch run with cap. */
	private static AntBot ranch(GameTestHelper helper, int cap, ItemStack... supplies) {
		return ranch(helper, cap, "", supplies);
	}

	/** As above, with prelude run first in the bot's script, such as to give it a depot in its memory. */
	private static AntBot ranch(GameTestHelper helper, int cap, String prelude, ItemStack... supplies) {
		for (int x = 0; x < 8; x++) {
			for (int z = 3; z < 8; z++) {
				if (x == 0 || x == 7 || z == 3 || z == 7) {
					helper.setBlock(x, 1, z, x == 3 && z == 3 ? Blocks.OAK_FENCE_GATE : Blocks.OAK_FENCE);
				}
			}
		}
		// Each fence joins the ones beside it, which were placed after it or before.
		for (int x = 0; x < 8; x++) {
			for (int z = 3; z < 8; z++) {
				BlockPos at = helper.absolutePos(new BlockPos(x, 1, z));
				BlockState state = helper.getLevel().getBlockState(at);
				helper.getLevel().setBlockAndUpdate(at, Block.updateFromNeighbourShapes(state, helper.getLevel(), at));
			}
		}
		helper.setBlock(6, 1, 1, Blocks.CHEST);
		ChestBlockEntity chest = helper.getBlockEntity(new BlockPos(6, 1, 1), ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.CHEST, 2));
		for (int i = 0; i < supplies.length; i++) {
			chest.setItem(i + 1, supplies[i]);
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 5);
		BlockPos low = helper.absolutePos(new BlockPos(1, 1, 4));
		BlockPos high = helper.absolutePos(new BlockPos(6, 1, 6));
		BotScripts.run(bot, prelude + """
			import jobs.ranch
			jobs.ranch.run(Pos(%d, %d, %d), Pos(%d, %d, %d), %d, 8)
			""".formatted(low.getX(), low.getY(), low.getZ(), high.getX(), high.getY(), high.getZ(), cap));
		BotScripts.failOnError(helper, bot);
		return bot;
	}

	private static <T extends Animal> T animal(GameTestHelper helper, EntityType<T> type, int x, int z, boolean baby) {
		T animal = helper.spawn(type, x, 1, z);
		if (baby) {
			animal.setAge(-24000);
		}
		return animal;
	}

	private static <T extends Animal> List<T> inPen(GameTestHelper helper, Class<T> type) {
		AABB pen = AABB.encapsulatingFullBlocks(helper.absolutePos(new BlockPos(1, 0, 4)), helper.absolutePos(new BlockPos(6, 3, 6)));
		return helper.getLevel().getEntitiesOfClass(type, pen, Animal::isAlive);
	}

	/** How many of item the depot's chests hold, along its row east of (1, 1, 1). */
	private static int stored(GameTestHelper helper, Item item) {
		int n = 0;
		for (int x = 1; x < 6; x++) {
			if (helper.getBlockState(new BlockPos(x, 1, 1)).is(Blocks.CHEST)) {
				n += helper.getBlockEntity(new BlockPos(x, 1, 1), ChestBlockEntity.class).countItem(item);
			}
		}
		return n;
	}

	/** Two cows in the pen, wheat in the chest: the bot feeds both and a calf appears. */
	// Measured: passes at tick 100 to 115 (red: No module named 'jobs.ranch'); the cows wander, so a wide margin.
	@GameTest(maxTicks = 300)
	public void twoCowsAreBredWithWheat(GameTestHelper helper) {
		animal(helper, EntityTypes.COW, 2, 6, false);
		animal(helper, EntityTypes.COW, 5, 6, false);
		ranch(helper, 4, new ItemStack(Items.WHEAT, 8));
		helper.succeedWhen(() -> {
			List<Cow> cows = inPen(helper, Cow.class);
			helper.assertTrue(cows.stream().anyMatch(Cow::isBaby), "no calf among " + cows.size() + " cows");
		});
	}

	/**
	 * Four adult cows and two calves, a cap of 2 and an axe in the chest: two adults are killed, both calves live, and
	 * the beef (1 to 3 a cow) is stored. Fails at once where a calf dies.
	 */
	// Measured: passes at tick 118 to 154.
	@GameTest(maxTicks = 320)
	public void adultsOverTheCapAreKilledBabiesNeverAndTheMeatStored(GameTestHelper helper) {
		for (int x = 1; x <= 4; x++) {
			animal(helper, EntityTypes.COW, x + 1, 6, false);
		}
		Cow[] calves = {animal(helper, EntityTypes.COW, 2, 4, true), animal(helper, EntityTypes.COW, 5, 4, true)};
		ranch(helper, 2, new ItemStack(Items.STONE_AXE));
		helper.onEachTick(() -> {
			for (Cow calf : calves) {
				if (!calf.isAlive()) {
					helper.fail("a calf was killed");
				}
			}
		});
		helper.succeedWhen(() -> {
			long adults = inPen(helper, Cow.class).stream().filter(c -> !c.isBaby()).count();
			helper.assertTrue(adults == 2, adults + " adult cows left");
			int beef = stored(helper, Items.BEEF);
			helper.assertTrue(beef >= 2, beef + " beef stored");
		});
	}

	/**
	 * Four adult cows at a cap of 4, wheat and an axe in the chest: the ranch still breeds them, and once the calves are
	 * grown (aged up by the test 40 ticks after birth, as growing takes 20 minutes) it kills the adults past the cap and
	 * stores the beef (#864).
	 */
	// Measured: passes at tick 290 to 454 (red: 4 adult cows of 4, no calf; breed fed no pair at the cap, as it counted
	// the calves to come against it).
	@GameTest(maxTicks = 900)
	public void atTheCapTheRanchBreedsAndCullsTheGrownSurplus(GameTestHelper helper) {
		for (int x = 1; x <= 4; x++) {
			animal(helper, EntityTypes.COW, x + 1, 6, false);
		}
		ranch(helper, 4, new ItemStack(Items.WHEAT, 16), new ItemStack(Items.STONE_AXE));
		helper.onEachTick(() -> {
			for (Cow calf : inPen(helper, Cow.class)) {
				if (calf.isBaby() && calf.getAge() >= -24000 + 40) {
					calf.setAge(0);
				}
			}
		});
		helper.succeedWhen(() -> {
			List<Cow> cows = inPen(helper, Cow.class);
			long adults = cows.stream().filter(c -> !c.isBaby()).count();
			int beef = stored(helper, Items.BEEF);
			helper.assertTrue(adults == 4 && beef >= 1, adults + " adult cows of " + cows.size() + ", " + beef + " beef stored");
		});
	}

	/** A sheep in the pen, shears in the chest: it is sheared and its wool (1 to 3) stored. */
	// Measured: passes at tick 47 to 48.
	@GameTest(maxTicks = 120)
	public void aSheepIsShearedAndTheWoolStored(GameTestHelper helper) {
		animal(helper, EntityTypes.SHEEP, 5, 5, false);
		ranch(helper, 4, new ItemStack(Items.SHEARS));
		helper.succeedWhen(() -> {
			int wool = stored(helper, Items.WOOL.white());
			helper.assertTrue(wool >= 1, wool + " wool stored");
		});
	}

	/** Two pigs in the pen, carrots in the chest: the bot feeds both and a piglet appears. */
	// Measured: passes at tick 100 (it bred already with #837's job; #843 adds the test).
	@GameTest(maxTicks = 300)
	public void twoPigsAreBredWithCarrots(GameTestHelper helper) {
		animal(helper, EntityTypes.PIG, 2, 6, false);
		animal(helper, EntityTypes.PIG, 5, 6, false);
		ranch(helper, 4, new ItemStack(Items.CARROT, 8));
		helper.succeedWhen(() -> {
			List<Pig> pigs = inPen(helper, Pig.class);
			helper.assertTrue(pigs.stream().anyMatch(Pig::isBaby), "no piglet among " + pigs.size() + " pigs");
		});
	}

	/** Two chickens in the pen, seeds in the chest: the bot feeds both and a chick appears. */
	// Measured: passes at tick 88 to 105 (as for pigs).
	@GameTest(maxTicks = 300)
	public void twoChickensAreBredWithSeeds(GameTestHelper helper) {
		animal(helper, EntityTypes.CHICKEN, 2, 6, false);
		animal(helper, EntityTypes.CHICKEN, 5, 6, false);
		ranch(helper, 4, new ItemStack(Items.WHEAT_SEEDS, 8));
		helper.succeedWhen(() -> {
			List<Chicken> chickens = inPen(helper, Chicken.class);
			helper.assertTrue(chickens.stream().anyMatch(Chicken::isBaby), "no chick among " + chickens.size() + " chickens");
		});
	}

	/** A chicken about to lay in the pen: the bot picks up its egg and stores it. */
	// Measured: passes at tick 145 (red: 0 eggs stored; the egg rolled into the fence's block at z 7.1, so the pen counts
	// its fence for what lies there).
	@GameTest(maxTicks = 300)
	public void anEggLaidInThePenIsStored(GameTestHelper helper) {
		Chicken chicken = animal(helper, EntityTypes.CHICKEN, 5, 6, false);
		chicken.eggTime = 40;
		AntBot bot = ranch(helper, 4);
		helper.succeedWhen(() -> {
			int eggs = stored(helper, Items.EGG);
			List<ItemEntity> laid = helper.getLevel().getEntitiesOfClass(ItemEntity.class, bot.getBoundingBox().inflate(16),
				i -> i.getItem().is(Items.EGG));
			helper.assertTrue(eggs >= 1, eggs + " eggs stored, eggs lying at "
				+ laid.stream().map(i -> TestPositions.relative(helper, i.position())).toList() + ", the bot at "
				+ TestPositions.relative(helper, bot.position()) + ", " + BotScripts.carries(bot));
		});
	}

	/** A cow in the pen, a bucket in the chest: it is milked and the milk stored. */
	// Measured: passes at tick 31 (red: 0 milk stored).
	@GameTest(maxTicks = 100)
	public void aCowIsMilkedAndTheMilkStored(GameTestHelper helper) {
		animal(helper, EntityTypes.COW, 5, 5, false);
		ranch(helper, 4, new ItemStack(Items.BUCKET));
		helper.succeedWhen(() -> {
			int milk = stored(helper, Items.MILK_BUCKET);
			helper.assertTrue(milk >= 1, milk + " milk stored");
		});
	}

	/**
	 * A cow outside the pen, south of its fence, wheat in the chest: the bot lures it in with the wheat, through the gate
	 * on the north side, and shuts the gate behind it.
	 */
	// Measured: passes at tick 277 (red: the cow stayed outside; without BotTemptGoal it wanders off, and led straight to
	// the far corner it stood at the fence nearest the bot, as an animal finds no way round a fence).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 600)
	public void aCowOutsideThePenIsLuredIn(GameTestHelper helper) {
		Cow cow = animal(helper, EntityTypes.COW, 3, 10, false);
		AntBot bot = ranch(helper, 4, new ItemStack(Items.WHEAT, 4));
		helper.succeedWhen(() -> {
			helper.assertTrue(inPen(helper, Cow.class).size() == 1, "the cow is not in the pen but at "
				+ TestPositions.relative(helper, cow.position()) + ", the bot at " + TestPositions.relative(helper, bot.position()) + ", "
				+ BotScripts.carries(bot) + ", " + BotScripts.log(bot, Output.Level.DEBUG));
			helper.assertTrue(!helper.getBlockState(new BlockPos(3, 1, 3)).getValue(BlockStateProperties.OPEN), "the gate is open");
		});
	}

	/**
	 * Two cows in the pen and wheat only in a chest of a crop farm's depot in the bot's memory, too far off for the
	 * ranch's chest radius (8): the bot fetches the wheat from it and a calf appears.
	 */
	// Measured: passes at tick 386 to 390 (red: no calf; the wheat chest is out of the scan radius), the walk there and
	// back being most of it.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 800)
	public void theRanchFeedsFromACropFarmsDepot(GameTestHelper helper) {
		animal(helper, EntityTypes.COW, 2, 6, false);
		animal(helper, EntityTypes.COW, 5, 6, false);
		helper.setBlock(18, 1, 18, Blocks.CHEST);
		helper.getBlockEntity(new BlockPos(18, 1, 18), ChestBlockEntity.class).setItem(0, new ItemStack(Items.WHEAT, 8));
		BlockPos depot = helper.absolutePos(new BlockPos(18, 1, 18));
		ranch(helper, 4, """
			import lib.depot
			lib.depot.depot("crop_farm", Pos(%1$d, %2$d, %3$d), "east", {"crops": ["minecraft:wheat"]})["chests"].append(["crops", Pos(%1$d, %2$d, %3$d)])
			""".formatted(depot.getX(), depot.getY(), depot.getZ()));
		helper.succeedWhen(() -> {
			List<Cow> cows = inPen(helper, Cow.class);
			helper.assertTrue(cows.stream().anyMatch(Cow::isBaby), "no calf among " + cows.size() + " cows");
		});
	}

	/**
	 * A pig outside the pen, south of its fence, carrots in the chest, and animals in the pen that do not follow carrots:
	 * a chicken on each block of the row along the far fence and a cow. The bot lures the pig in, and none of the
	 * penned animals gets past the fence, not even while the gate stands open: none is beyond the middle of the fence or
	 * gate. Fails at once where one is.
	 */
	// Measured: passes at tick 262 to 289, the gate open some 53 ticks. Already green with #843's lure. With the chickens
	// crowded against the gate instead, one in two runs failed: the bot walking out through the gate shoved one out ahead.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 600)
	public void aLureLetsNoPennedAnimalOut(GameTestHelper helper) {
		List<Animal> penned = new ArrayList<>();
		for (int x = 1; x <= 6; x++) {
			penned.add(animal(helper, EntityTypes.CHICKEN, x, 6, false));
		}
		penned.add(animal(helper, EntityTypes.COW, 5, 5, false));
		Pig stray = animal(helper, EntityTypes.PIG, 3, 10, false);
		AntBot bot = ranch(helper, 8, new ItemStack(Items.CARROT, 4));
		helper.onEachTick(() -> {
			for (Animal a : penned) {
				Vec3 at = TestPositions.relative(helper, a.position());
				if (!a.isAlive() || at.x <= 0.5 || at.x >= 7.5 || at.z <= 3.5 || at.z >= 7.5) {
					helper.fail("a penned " + a.getType().getDescription().getString() + " got out, at " + at + ", the bot at "
						+ TestPositions.relative(helper, bot.position()) + ", doing " + (bot.getScript() == null ? null : bot.getScript().action())
						+ ", the gate " + (helper.getBlockState(new BlockPos(3, 1, 3)).getValue(BlockStateProperties.OPEN) ? "open" : "shut"));
				}
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(inPen(helper, Pig.class).contains(stray), "the pig is not in the pen but at "
				+ TestPositions.relative(helper, stray.position()) + ", the bot at " + TestPositions.relative(helper, bot.position()));
			helper.assertTrue(!helper.getBlockState(new BlockPos(3, 1, 3)).getValue(BlockStateProperties.OPEN), "the gate is open");
		});
	}
}
