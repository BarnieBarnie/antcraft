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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

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
		BotScripts.run(bot, """
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
}
