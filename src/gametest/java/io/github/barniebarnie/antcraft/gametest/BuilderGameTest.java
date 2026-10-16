package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.pathing.LevelWorldView;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.Mining;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

public class BuilderGameTest implements CustomTestMethodInvoker {
	private static final BlockPos CORE = new BlockPos(1, 1, 1);
	private static final BlockState COBBLE = Blocks.COBBLESTONE.defaultBlockState();
	private static final BlockState STAIRS_EAST = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
			}
		}
		method.invoke(this, helper);
	}

	private static Colony colony(GameTestHelper helper) {
		helper.setBlock(CORE, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(CORE)).orElseThrow();
		colony.setRadius(6);
		// No default stock targets, so only the builder's orders show up.
		colony.setStockTargets(List.of());
		return colony;
	}

	private static Container core(GameTestHelper helper) {
		return (Container) helper.getBlockEntity(CORE, BlockEntity.class);
	}

	private static AntBot member(GameTestHelper helper, Colony colony, BotRole role, int x, int z) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, x, 1, z);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		bot.setRole(role);
		return bot;
	}

	private static BuildPlan.Step step(GameTestHelper helper, int x, int y, int z, BlockState state) {
		return new BuildPlan.Step(helper.absolutePos(new BlockPos(x, y, z)), state);
	}

	private static void assertState(GameTestHelper helper, int x, int y, int z, BlockState state) {
		BlockState actual = helper.getBlockState(new BlockPos(x, y, z));
		helper.assertTrue(actual.equals(state), "expected " + state + " at " + x + "," + y + "," + z + " but found " + actual);
	}

	@GameTest(maxTicks = 600)
	public void buildsPlanWithMaterialsFromCore(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 2));
		core(helper).setItem(1, new ItemStack(Items.OAK_STAIRS, 1));
		helper.setBlock(4, 1, 4, Blocks.DIRT);
		colony.addBuild(new BuildPlan(List.of(
			BuildPlan.Step.clear(helper.absolutePos(new BlockPos(4, 1, 4))),
			step(helper, 5, 1, 5, COBBLE),
			step(helper, 5, 2, 5, COBBLE),
			step(helper, 3, 1, 5, STAIRS_EAST))));
		member(helper, colony, BotRole.BUILDER, 1, 4);

		helper.succeedWhen(() -> {
			helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(4, 1, 4));
			assertState(helper, 5, 1, 5, COBBLE);
			assertState(helper, 5, 2, 5, COBBLE);
			assertState(helper, 3, 1, 5, STAIRS_EAST);
			helper.assertTrue(colony.builds().isEmpty(), "finished plan still listed");
			helper.assertTrue(core(helper).countItem(Items.COBBLESTONE) == 0, "cobblestone not taken from the core");
		});
	}

	@GameTest(maxTicks = 600)
	public void ordersMissingMaterialsAndBuildsOnceStocked(GameTestHelper helper) {
		Colony colony = colony(helper);
		colony.addBuild(new BuildPlan(List.of(step(helper, 4, 1, 4, STAIRS_EAST))));
		member(helper, colony, BotRole.BUILDER, 1, 4);

		boolean[] stocked = {false};
		helper.onEachTick(() -> {
			if (!stocked[0] && colony.orders().containsKey(Items.OAK_STAIRS)) {
				// What a crafter would bring.
				core(helper).setItem(0, new ItemStack(Items.OAK_STAIRS, 1));
				stocked[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(stocked[0], "no order for stairs: " + colony.orders());
			assertState(helper, 4, 1, 4, STAIRS_EAST);
		});
	}

	/** Saves the bot and loads it into a new bot, as when its chunk unloads and loads again. */
	private static AntBot reload(GameTestHelper helper, AntBot bot) {
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
		bot.saveWithoutId(output);
		bot.discard();
		AntBot copy = helper.spawn(ModEntities.ANT_BOT, 1, 1, 5);
		copy.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), output.buildResult()));
		return copy;
	}

	private static long placed(GameTestHelper helper) {
		return Stream.of(3, 4, 5, 6).filter(x -> helper.getBlockState(new BlockPos(x, 1, 6)).is(Blocks.COBBLESTONE)).count();
	}

	@GameTest(maxTicks = 800)
	public void resumesHalfDonePlanAfterSaveAndLoad(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 10));
		// Built before: the builder must skip it.
		helper.setBlock(3, 1, 6, Blocks.COBBLESTONE);
		colony.addBuild(new BuildPlan(List.of(step(helper, 3, 1, 6, COBBLE), step(helper, 4, 1, 6, COBBLE),
			step(helper, 5, 1, 6, COBBLE), step(helper, 6, 1, 6, COBBLE))));
		AntBot builder = member(helper, colony, BotRole.BUILDER, 1, 4);

		AntBot[] copy = {null};
		helper.onEachTick(() -> {
			if (copy[0] == null && placed(helper) == 2) {
				copy[0] = reload(helper, builder);
				Colony loaded = Colony.CODEC.parse(NbtOps.INSTANCE, Colony.CODEC.encodeStart(NbtOps.INSTANCE, colony).getOrThrow()).getOrThrow();
				helper.assertTrue(loaded.builds().equals(colony.builds()), "plans not saved: " + loaded.builds());
				helper.assertTrue(loaded.isProtected(helper.absolutePos(new BlockPos(4, 1, 6))), "placed block not saved as protected");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(copy[0] != null && copy[0].isAlive(), "bot not reloaded");
			helper.assertTrue(placed(helper) == 4, "plan not finished");
			// Three placed by the builder; the block that was already there took nothing.
			int left = core(helper).countItem(Items.COBBLESTONE) + copy[0].getInventory().countItem(Items.COBBLESTONE);
			helper.assertTrue(left == 7, "cobblestone left: " + left);
		});
	}

	@GameTest(maxTicks = 600)
	public void otherBotsDoNotBreakPlacedBlocks(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.OAK_PLANKS, 1));
		BlockPos planks = new BlockPos(4, 1, 4);
		colony.addBuild(new BuildPlan(List.of(step(helper, 4, 1, 4, Blocks.OAK_PLANKS.defaultBlockState()))));
		member(helper, colony, BotRole.BUILDER, 1, 4);

		LevelWorldView view = new LevelWorldView(helper.getLevel(), new SimpleContainer(1), ItemStack.EMPTY, Set.of());
		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.OAK_PLANKS, planks);
			helper.assertTrue(view.breakTicks(helper.absolutePos(planks)) < 0, "pathing may break the placed planks");
			helper.assertFalse(Mining.isMinable(helper.getLevel(), helper.absolutePos(planks)), "miners may break the placed planks");
		});
	}

	/** Every block in the area that is not air. */
	private static Set<BlockPos> solid(GameTestHelper helper) {
		Set<BlockPos> solid = new HashSet<>();
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 7, 7, 7)) {
			if (!helper.getBlockState(pos).isAir()) {
				solid.add(pos.immutable());
			}
		}
		return solid;
	}

	@GameTest(maxTicks = 2400)
	public void digsMineshaftAndWalksBackUp(GameTestHelper helper) {
		Mineshaft shaft = MinerGameTest.mine(helper);
		helper.setBlock(MinerGameTest.MINE_CORE, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(MinerGameTest.MINE_CORE)).orElseThrow();
		colony.setStockTargets(List.of());
		Container core = (Container) helper.getBlockEntity(MinerGameTest.MINE_CORE, BlockEntity.class);
		core.setItem(0, new ItemStack(Items.TORCH, 8));
		colony.setMineshaft(shaft);
		AntBot builder = helper.spawn(ModEntities.ANT_BOT, 1, 5, 1);
		ColonyManager.get(helper.getLevel()).join(builder, colony);
		builder.setRole(BotRole.BUILDER);
		builder.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));

		BlockPos home = new BlockPos(1, 5, 1);
		Set<?>[] dug = {null};
		helper.onEachTick(() -> {
			if (dug[0] == null && colony.builds().isEmpty()) {
				helper.assertTrue(shaft.plan().nextStep(helper.getLevel()::getBlockState).isEmpty(), "plan dropped before it was done");
				helper.assertTrue(helper.relativePos(builder.blockPosition()).getY() < 3, "builder not down the shaft");
				dug[0] = solid(helper);
				builder.setWalkTarget(helper.absolutePos(home));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(dug[0] != null, "shaft not dug, next step " + shaft.plan().nextStep(helper.getLevel()::getBlockState));
			helper.assertTrue(builder.blockPosition().distManhattan(helper.absolutePos(home)) <= 2, "builder at " + helper.relativePos(builder.blockPosition()));
			// It walked up the stairs as they are, without digging or building its own way.
			helper.assertTrue(solid(helper).equals(dug[0]), "blocks changed on the way up");
			MinerGameTest.assertLit(helper, MinerGameTest.dugPath(shaft, false));
		});
	}
}
