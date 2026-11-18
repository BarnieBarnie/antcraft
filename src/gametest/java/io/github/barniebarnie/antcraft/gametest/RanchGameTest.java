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
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
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
		pen(helper);
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

	/** The pen's oak fence, with its gate at (3, 1, 3). */
	private static void pen(GameTestHelper helper) {
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
	 * Four adult cows, a cap of 2 and an axe in the chest: the two oldest (by Entity age, #871) are killed, whatever order
	 * entities() gives them in. Fails at once where a young one dies.
	 */
	// Measured: passes at tick 94 (red: a young cow was killed on tick 75); the cull test above's limit.
	@GameTest(maxTicks = 320)
	public void theOldestAdultsOverTheCapAreKilled(GameTestHelper helper) {
		List<Cow> old = new ArrayList<>();
		List<Cow> young = new ArrayList<>();
		for (int x = 1; x <= 4; x++) {
			Cow cow = animal(helper, EntityTypes.COW, x + 1, 6, false);
			if (x % 2 == 0) {
				cow.tickCount = 5000 + x;
				old.add(cow);
			} else {
				young.add(cow);
			}
		}
		ranch(helper, 2, new ItemStack(Items.STONE_AXE));
		helper.onEachTick(() -> {
			if (young.stream().anyMatch(c -> !c.isAlive())) {
				helper.fail("a young cow was killed");
			}
		});
		helper.succeedWhen(() -> helper.assertTrue(old.stream().noneMatch(Cow::isAlive), "an old cow lives"));
	}

	/**
	 * Four adult cows at a cap of 4, wheat and an axe in the chest: the ranch still breeds them, and once the calves are
	 * grown (aged up by the test 40 ticks after birth, as growing takes 20 minutes) it kills the adults past the cap and
	 * stores the beef (#864).
	 */
	// Measured: passes at tick 290 to 454 (red: 4 adult cows of 4, no calf; breed fed no pair at the cap, as it counted
	// the calves to come against it).
	// Under load (#882; 40 copies beside the gather, dig, mine, tree farm and storage tests, 8 runs): passes at up to tick
	// 619, and 2 of 320 failed at 900: there a cull's attack took 490 ticks, the bot not getting in reach of a cow in the
	// crowd, or ended unreachable, so the surplus waited for the next round. It passes only once a cull has left 4 adults
	// with the beef stored, before the next calf grows.
	@GameTest(maxTicks = 1250)
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
	// Full runs: passes at up to tick 98 (#861).
	@GameTest(maxTicks = 200)
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
	// Measured: passes at tick 272 to 309, 80 copies of 80 (one in 40 failed before #874, see the test below; red: the
	// cow stayed outside; without BotTemptGoal it wanders off, and led straight to the far corner it stood at the fence
	// nearest the bot, as an animal finds no way round a fence).
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
	 * A cow just outside the pen's south fence, two blocks from the bot inside, wheat in the chest: the bot still walks
	 * out to it and leads it round to the gate. Fails at once where the bot waits in its corner for the cow with the
	 * gate shut.
	 */
	// Measured: passes at tick 271 to 347, 80 copies of 80 (red: on tick 99 the bot waits with the cow outside:
	// goto(stray.pos, near=3) was done from inside the pen, through the fence, so the bot walked out through the gate
	// with the cow left behind, and the gate it had opened for that walk, still open as the bot checked it, was shut by
	// its walk to the corner. #874: one run in 40 of aCowOutsideThePenIsLuredIn, where the cow came to the fence by the
	// bot while it took the wheat.)
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 600)
	public void aCowAtTheFenceOutsideIsLedRoundToTheGate(GameTestHelper helper) {
		Cow cow = animal(helper, EntityTypes.COW, 3, 8, false);
		AntBot bot = ranch(helper, 4, new ItemStack(Items.WHEAT, 4));
		helper.onEachTick(() -> {
			String action = bot.getScript() == null ? null : bot.getScript().action();
			if ("wait(10)".equals(action) && inPen(helper, Cow.class).isEmpty()
				&& !helper.getBlockState(new BlockPos(3, 1, 3)).getValue(BlockStateProperties.OPEN)) {
				helper.fail("the bot waits for the cow with the gate shut, the cow at " + TestPositions.relative(helper, cow.position())
					+ ", the bot at " + TestPositions.relative(helper, bot.position()));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(inPen(helper, Cow.class).size() == 1, "the cow is not in the pen but at "
				+ TestPositions.relative(helper, cow.position()) + ", the bot at " + TestPositions.relative(helper, bot.position()));
			helper.assertTrue(!helper.getBlockState(new BlockPos(3, 1, 3)).getValue(BlockStateProperties.OPEN), "the gate is open");
		});
	}

	/**
	 * A cow outside the pen walled in with bedrock, which no walk gets through, wheat in the chest: the bot cannot get to
	 * it, so it does not lure it, neither opening the gate for it nor waiting in its corner, and says why once (#883).
	 * Fails at once where the bot waits for the cow.
	 */
	// Measured: passes at tick 400, its fixed end (red: on tick 228 the bot opened the gate with use for the cow it
	// never got to).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 600)
	public void anUnreachableStrayIsNotLured(GameTestHelper helper) {
		for (int x = 2; x <= 4; x++) {
			for (int z = 11; z <= 13; z++) {
				for (int y = 1; y <= 3; y++) {
					if (x != 3 || z != 12 || y == 3) {
						helper.setBlock(x, y, z, Blocks.BEDROCK);
					}
				}
			}
		}
		animal(helper, EntityTypes.COW, 3, 12, false);
		AntBot bot = ranch(helper, 4, new ItemStack(Items.WHEAT, 4));
		helper.onEachTick(() -> {
			String action = bot.getScript() == null ? null : bot.getScript().action();
			// The pen holds no animal, so the only use is the lure's of the gate; the walks open and shut it themselves.
			if (action != null && (action.equals("wait(10)") || action.startsWith("use("))) {
				helper.fail("the bot lures an unreachable cow, doing " + action + ", the gate "
					+ (helper.getBlockState(new BlockPos(3, 1, 3)).getValue(BlockStateProperties.OPEN) ? "open" : "shut"));
			}
		});
		helper.succeedWhen(() -> {
			List<String> said = BotScripts.log(bot, Output.Level.PRINT).stream().filter(s -> s.startsWith("lure")).toList();
			helper.assertTrue(helper.getTick() >= 400, "waiting for a few rounds");
			helper.assertTrue(said.size() == 1, "said " + said);
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
	 * gate. Fails at once where one is, or where one dies (#903).
	 */
	// Measured: passes at tick 262 to 289, the gate open some 53 ticks. Already green with #843's lure. With the chickens
	// crowded against the gate instead, one in two runs failed: the bot walking out through the gate shoved one out ahead
	// (#863, see aBotWalkingThroughACrowdedGateLetsNoAnimalOut).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 600)
	public void aLureLetsNoPennedAnimalOut(GameTestHelper helper) {
		List<Animal> penned = new ArrayList<>();
		for (int x = 1; x <= 6; x++) {
			penned.add(animal(helper, EntityTypes.CHICKEN, x, 6, false));
		}
		penned.add(animal(helper, EntityTypes.COW, 5, 5, false));
		Pig stray = animal(helper, EntityTypes.PIG, 3, 10, false);
		AntBot bot = ranch(helper, 8, new ItemStack(Items.CARROT, 4));
		failWhereOneGetsOut(helper, penned, bot);
		failWhereOneDies(helper, penned, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(inPen(helper, Pig.class).contains(stray), "the pig is not in the pen but at "
				+ TestPositions.relative(helper, stray.position()) + ", the bot at " + TestPositions.relative(helper, bot.position()));
			helper.assertTrue(!helper.getBlockState(new BlockPos(3, 1, 3)).getValue(BlockStateProperties.OPEN), "the gate is open");
		});
	}

	/**
	 * A cow in the pen and a bot outside it holding wheat, which opens the gate with {@code use} and stays there: the cow
	 * comes to the gate but does not follow the wheat out through it (#879). Fails at once where it gets out.
	 */
	// Measured: passes at tick 200, its fixed end, in three runs (red: on tick 20 the cow was out in the gate).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 300)
	public void aCowDoesNotFollowTheWheatOutThroughAUsedGate(GameTestHelper helper) {
		pen(helper);
		List<Cow> penned = List.of(animal(helper, EntityTypes.COW, 3, 5, false));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.WHEAT, 1));
		BlockPos gate = helper.absolutePos(new BlockPos(3, 1, 3));
		BotScripts.run(bot, """
			equip("minecraft:wheat")
			use(Pos(%d, %d, %d))
			wait(1000)
			""".formatted(gate.getX(), gate.getY(), gate.getZ()));
		BotScripts.failOnError(helper, bot);
		failWhereOneGetsOut(helper, penned, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(helper.getTick() >= 200, "waiting");
			helper.assertTrue(helper.getBlockState(new BlockPos(3, 1, 3)).getValue(BlockStateProperties.OPEN), "the gate is shut");
		});
	}

	/**
	 * A chicken in the pen driven north into its shut gate, as into a fence, does not jump at it (#896): vanilla's
	 * MoveControl jumps at a block with collision but not at a fence or door. Fails at once on a jump.
	 */
	@GameTest(maxTicks = 300)
	public void aChickenDrivenIntoAShutGateDoesNotJump(GameTestHelper helper) {
		pen(helper);
		Chicken chicken = animal(helper, EntityTypes.CHICKEN, 3, 5, false);
		Vec3 north = helper.absoluteVec(new Vec3(3.5, 1, 1.5));
		boolean[] wasOnGround = {false};
		helper.onEachTick(() -> {
			chicken.getMoveControl().setWantedPosition(north.x, north.y, north.z, 1.0);
			if (wasOnGround[0] && !chicken.onGround() && chicken.getDeltaMovement().y > 0.2) {
				helper.fail("the chicken jumped at " + TestPositions.relative(helper, chicken.position()));
			}
			wasOnGround[0] = chicken.onGround();
		});
		helper.succeedWhen(() -> helper.assertTrue(helper.getTick() >= 190, "waiting"));
	}


	/**
	 * failWhereOneGetsOut counts no animal that dies or is removed in the pen as out (#885): one cow killed and one
	 * discarded on tick 1, the test ending on tick 40, after the dead cow's body is gone too.
	 */
	// Measured: passes at tick 40, its fixed end (red: on tick 3 the dead cow in the pen counted as got out).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 80)
	public void aPennedAnimalThatDiesOrIsRemovedIsNotOut(GameTestHelper helper) {
		pen(helper);
		Cow killed = animal(helper, EntityTypes.COW, 2, 5, false);
		Cow removed = animal(helper, EntityTypes.COW, 5, 5, false);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 1);
		helper.runAfterDelay(1, () -> {
			killed.kill(helper.getLevel());
			removed.discard();
		});
		failWhereOneGetsOut(helper, List.of(killed, removed), bot);
		helper.succeedWhen(() -> helper.assertTrue(helper.getTick() >= 40, "waiting"));
	}

	/**
	 * A cow in the pen and a bot by it holding wheat, which opens the gate with {@code use} from inside, so it swings
	 * out, and walks out through it: the cow does not follow the wheat out (#884). Fails at once where it gets out.
	 */
	// Measured: passes at tick 200, its fixed end, in three runs (red: on tick 28 the cow was out in the gate).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 300)
	public void aCowDoesNotFollowTheWheatOutThroughAGateUsedFromInside(GameTestHelper helper) {
		pen(helper);
		leadOutFromInside(helper, EntityTypes.COW, Items.WHEAT);
	}

	/** aCowDoesNotFollowTheWheatOutThroughAGateUsedFromInside with a plank wall, two high, an oak door for the gate and a chicken following seeds (#884). */
	// Measured: passes at tick 200, its fixed end, in three runs (red: on tick 36 the chicken was out in the door; a cow
	// following wheat did not come through the door even before the fix).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 300)
	public void aChickenDoesNotFollowTheSeedsOutThroughADoorUsedFromInside(GameTestHelper helper) {
		pen(helper);
		for (int x = 0; x < 8; x++) {
			helper.setBlock(x, 1, 3, Blocks.OAK_PLANKS);
			helper.setBlock(x, 2, 3, Blocks.OAK_PLANKS);
		}
		BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
		helper.setBlock(new BlockPos(3, 1, 3), door);
		helper.setBlock(new BlockPos(3, 2, 3), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		leadOutFromInside(helper, EntityTypes.CHICKEN, Items.WHEAT_SEEDS);
	}

	/**
	 * A player in the pen opens its gate, so it swings out, and a bot outside holds wheat: the cow in the pen does not
	 * follow the wheat out (#905), as with a gate a bot opened from inside. Fails at once where it gets out.
	 */
	// Measured: passes at tick 200, its fixed end (red: on tick 20 the cow was out in the gate).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 300)
	public void aCowDoesNotFollowTheWheatOutThroughAGateAPlayerOpenedFromInside(GameTestHelper helper) {
		pen(helper);
		List<Cow> penned = List.of(animal(helper, EntityTypes.COW, 3, 5, false));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.WHEAT, 1));
		BotScripts.run(bot, """
			equip("minecraft:wheat")
			wait(1000)
			""");
		BotScripts.failOnError(helper, bot);
		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		Vec3 inside = helper.absoluteVec(new Vec3(3.5, 1, 4.5));
		BlockPos gate = helper.absolutePos(new BlockPos(3, 1, 3));
		Direction out = Direction.getApproximateNearest(Vec3.atLowerCornerOf(gate.subtract(helper.absolutePos(new BlockPos(3, 1, 4)))));
		player.snapTo(inside.x, inside.y, inside.z, out.toYRot(), 0);
		helper.useBlock(new BlockPos(3, 1, 3), player);
		helper.assertTrue(helper.getBlockState(new BlockPos(3, 1, 3)).getValue(BlockStateProperties.OPEN), "the player did not open the gate");
		failWhereOneGetsOut(helper, penned, bot);
		helper.succeedWhen(() -> helper.assertTrue(helper.getTick() >= 200, "waiting"));
	}

	/** A bot in the pen with food opens its gate or door at (3, 1, 3) with use and walks out; the animal of type in the pen stays in. */
	private static void leadOutFromInside(GameTestHelper helper, EntityType<? extends Animal> type, Item food) {
		List<Animal> penned = List.of(animal(helper, type, 3, 6, false));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(food, 1));
		BlockPos gate = helper.absolutePos(new BlockPos(3, 1, 3));
		BlockPos out = helper.absolutePos(new BlockPos(3, 1, 0));
		BotScripts.run(bot, """
			equip("%s")
			if not use(Pos(%d, %d, %d)) or not goto(Pos(%d, %d, %d)):
			    raise Exception("no way out")
			wait(1000)
			""".formatted(BuiltInRegistries.ITEM.getKey(food), gate.getX(), gate.getY(), gate.getZ(), out.getX(), out.getY(), out.getZ()));
		BotScripts.failOnError(helper, bot);
		failWhereOneGetsOut(helper, penned, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(helper.getTick() >= 200, "waiting");
			helper.assertTrue(helper.getBlockState(new BlockPos(3, 1, 3)).getValue(BlockStateProperties.OPEN), "the gate is shut");
		});
	}

	/**
	 * Fails at once where one of penned is beyond the middle of the pen's fence or gate, saying where it is dead. One
	 * removed from the world, such as a dead one whose body is gone or every entity once the test ends, is not out.
	 */
	private static void failWhereOneGetsOut(GameTestHelper helper, List<? extends Animal> penned, AntBot bot) {
		helper.onEachTick(() -> {
			for (Animal a : penned) {
				Vec3 at = TestPositions.relative(helper, a.position());
				if (!a.isRemoved() && (at.x <= 0.5 || at.x >= 7.5 || at.z <= 3.5 || at.z >= 7.5)) {
					helper.fail("a penned " + a.getType().getDescription().getString() + (a.isAlive() ? "" : ", dead,")
						+ " got out, at " + at + ", the bot at "
						+ TestPositions.relative(helper, bot.position()) + ", doing " + (bot.getScript() == null ? null : bot.getScript().action())
						+ ", the gate " + (helper.getBlockState(new BlockPos(3, 1, 3)).getValue(BlockStateProperties.OPEN) ? "open" : "shut"));
				}
			}
		});
	}

	/** Fails at once where one of penned dies, such as one the bot killed; failWhereOneGetsOut leaves dead ones out (#885). */
	private static void failWhereOneDies(GameTestHelper helper, List<? extends Animal> penned, AntBot bot) {
		helper.onEachTick(() -> {
			for (Animal a : penned) {
				if (a.getHealth() <= 0) {
					helper.fail("a penned " + a.getType().getDescription().getString() + " died at "
						+ TestPositions.relative(helper, a.position()) + ", the bot at " + TestPositions.relative(helper, bot.position())
						+ ", doing " + (bot.getScript() == null ? null : bot.getScript().action()));
				}
			}
		});
	}

	/**
	 * Six chickens crowded against the inside of the pen's gate, two on each block along it: the bot walks out through
	 * the gate and back in six times, and none of them gets out. Fails at once where one is beyond the fence's middle.
	 */
	// Measured: 20 trips passed at tick 1219 to 1261, so six some 380 (red: on tick 24 of the first trip a chicken the
	// bot shoved ahead was out; with bots not pushing animals, one in two runs one the crowd pressed in or that walked
	// in itself; with the gate shut to animals but only as its thin panel, one in four, as a chicken pressed into the
	// panel slipped through it, and another jumped clear of the gate's block over it).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 800)
	public void aBotWalkingThroughACrowdedGateLetsNoAnimalOut(GameTestHelper helper) {
		pen(helper);
		List<Chicken> penned = new ArrayList<>();
		for (int x = 2; x <= 4; x++) {
			penned.add(animal(helper, EntityTypes.CHICKEN, x, 4, false));
			penned.add(animal(helper, EntityTypes.CHICKEN, x, 4, false));
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 6);
		BlockPos out = helper.absolutePos(new BlockPos(3, 1, 0));
		BlockPos in = helper.absolutePos(new BlockPos(3, 1, 6));
		BotScripts.run(bot, """
			for i in range(6):
			    if not goto(Pos(%d, %d, %d)) or not goto(Pos(%d, %d, %d)):
			        raise Exception("no way through the gate")
			print("done")
			""".formatted(out.getX(), out.getY(), out.getZ(), in.getX(), in.getY(), in.getZ()));
		BotScripts.failOnError(helper, bot);
		failWhereOneGetsOut(helper, penned, bot);
		helper.succeedWhen(() -> helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).contains("done"), "the bot is still walking"));
	}

	/**
	 * aBotWalkingThroughACrowdedGateLetsNoAnimalOut with a plank wall, two high, for the gate's row and an oak door in it
	 * where the gate was (#878): the bot walks out through the door and back in six times, and none of the chickens
	 * crowded against it gets out.
	 */
	// Measured: passes at tick 377 to 393 in four runs; since #897 at 362 in 40 copies run together, before 387 to 642 and
	// one in many failing (red: on tick 37 a chicken was out in the open door; with the
	// gate's panel, 6 to 10 of the block, one was pressed into the space between it and the door's shut panel, at the
	// pen's side of the block, and was out once the door shut on tick 99).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 800)
	public void aBotWalkingThroughACrowdedDoorLetsNoAnimalOut(GameTestHelper helper) {
		pen(helper);
		for (int x = 0; x < 8; x++) {
			helper.setBlock(x, 1, 3, Blocks.OAK_PLANKS);
			helper.setBlock(x, 2, 3, Blocks.OAK_PLANKS);
		}
		BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
		helper.setBlock(new BlockPos(3, 1, 3), door);
		helper.setBlock(new BlockPos(3, 2, 3), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		List<Chicken> penned = new ArrayList<>();
		for (int x = 2; x <= 4; x++) {
			penned.add(animal(helper, EntityTypes.CHICKEN, x, 4, false));
			penned.add(animal(helper, EntityTypes.CHICKEN, x, 4, false));
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 6);
		BlockPos out = helper.absolutePos(new BlockPos(3, 1, 0));
		BlockPos in = helper.absolutePos(new BlockPos(3, 1, 6));
		BotScripts.run(bot, """
			for i in range(6):
			    if not goto(Pos(%d, %d, %d)) or not goto(Pos(%d, %d, %d)):
			        raise Exception("no way through the door")
			print("done")
			""".formatted(out.getX(), out.getY(), out.getZ(), in.getX(), in.getY(), in.getZ()));
		BotScripts.failOnError(helper, bot);
		failWhereOneGetsOut(helper, penned, bot);
		helper.succeedWhen(() -> helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).contains("done"), "the bot is still walking"));
	}

	/**
	 * The door of aBotWalkingThroughACrowdedDoorLetsNoAnimalOut at the end of a passage one wide and two long, plank
	 * walls on both sides, full of chickens: the bot walks out through them and the door (#897). In the crowded door
	 * test under load, the chickens the bot pressed against the door, shut to them, could go nowhere, and pushed it back
	 * until its goto gave up.
	 */
	// Measured: passes at tick 32 (red: still walking at tick 202, stuck in front of the open door).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 70)
	public void aBotWalksThroughChickensPressedAgainstADoor(GameTestHelper helper) {
		pen(helper);
		for (int x = 0; x < 8; x++) {
			helper.setBlock(x, 1, 3, Blocks.OAK_PLANKS);
			helper.setBlock(x, 2, 3, Blocks.OAK_PLANKS);
		}
		for (int z = 4; z <= 5; z++) {
			for (int y = 1; y <= 2; y++) {
				helper.setBlock(2, y, z, Blocks.OAK_PLANKS);
				helper.setBlock(4, y, z, Blocks.OAK_PLANKS);
			}
		}
		BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
		helper.setBlock(new BlockPos(3, 1, 3), door);
		helper.setBlock(new BlockPos(3, 2, 3), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		List<Chicken> penned = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			penned.add(animal(helper, EntityTypes.CHICKEN, 3, 4, false));
			penned.add(animal(helper, EntityTypes.CHICKEN, 3, 5, false));
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 6);
		BlockPos out = helper.absolutePos(new BlockPos(3, 1, 0));
		BotScripts.run(bot, """
			if not goto(Pos(%d, %d, %d)):
			    raise Exception("no way through the door")
			print("done")
			""".formatted(out.getX(), out.getY(), out.getZ()));
		BotScripts.failOnError(helper, bot);
		failWhereOneGetsOut(helper, penned, bot);
		helper.succeedWhen(() -> helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).contains("done"), "the bot is still walking"));
	}

	/**
	 * A cow at (1, 1, 5) behind still cows at x 1 to 2, z 4 to 6 of a pen with no gate, the bot outside the fence at
	 * (1, 1, 2): {@code attack} strikes it over the fence, as a player reaches 3 blocks from the eye (#902). With a mob's
	 * melee reach, 0.8 past the bot's box, the bot had to get beside the cow, and a cull in a crowded pen ended unreachable
	 * or took hundreds of ticks (#882).
	 */
	// Measured: passes at tick 16 (red: False unreachable on tick 42, the bot walking round the fence for a way in).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 40)
	public void aBotKillsACowBehindOtherCowsOverTheFence(GameTestHelper helper) {
		pen(helper);
		helper.setBlock(3, 1, 3, Blocks.OAK_FENCE);
		Cow target = animal(helper, EntityTypes.COW, 1, 5, false);
		target.setNoAi(true);
		for (int x = 1; x <= 2; x++) {
			for (int z = 4; z <= 6; z++) {
				if (x != 1 || z != 5) {
					animal(helper, EntityTypes.COW, x, z, false).setNoAi(true);
				}
			}
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 2);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
		BotScripts.run(bot, """
			for e in entities('cow', 8):
			    if e.id == '%s':
			        r = attack(e)
			        print(r.ok, r.reason)
			""".formatted(target.getUUID()));
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> {
			List<String> printed = BotScripts.log(bot, Output.Level.PRINT);
			helper.assertTrue(printed.equals(List.of("True None")), "printed " + printed + ", cow at " + target.getHealth() + " health");
		});
	}
}
