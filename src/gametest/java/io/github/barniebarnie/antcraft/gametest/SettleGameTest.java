package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.blueprint.Blueprint;
import io.github.barniebarnie.antcraft.blueprint.BlueprintSummary;
import io.github.barniebarnie.antcraft.blueprint.Blueprints;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Output;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.RegionPlan;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** The job {@code jobs/settle.py} (#574; docs/cookbook/settle.md): a whole base over {@code lib.project}. */
public class SettleGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/**
	 * An empty bot by a chest with stone tools, a crafting table, a chest, dirt, seeds and the starter house's materials
	 * settles at (4, 1, 4): a dirt bump at the plot's corner is dug, the house built, the chest and crafting table placed, the
	 * 3 by 3 dirt patch planted with wheat, home set inside the house and the borrowed tools put back. Stopped as it
	 * starts the house and run again, it does not do the kit and the flattening again.
	 */
	// Measured: passes at tick 953 to 1017, the starter house most of it.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 2000)
	public void settlesABaseAndResumesAfterAStop(GameTestHelper helper) {
		helper.setBlock(12, 1, 9, Blocks.DIRT);
		helper.setBlock(12, 2, 9, Blocks.DIRT);
		for (int x = 10; x <= 12; x++) {
			for (int z = 6; z <= 8; z++) {
				helper.setBlock(x, 0, z, Blocks.DIRT);
			}
		}
		helper.setBlock(17, 1, 4, Blocks.CHEST);
		Container chest = helper.getBlockEntity(new BlockPos(17, 1, 4), ChestBlockEntity.class);
		List.of(Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SHOVEL, Items.STONE_HOE, Items.CRAFTING_TABLE, Items.CHEST)
			.forEach(item -> add(chest, new ItemStack(item)));
		add(chest, new ItemStack(Items.DIRT, 16));
		add(chest, new ItemStack(Items.WHEAT_SEEDS, 9));
		ServerLevel level = helper.getLevel();
		Identifier id = AntCraft.id("starter_house");
		Blueprint blueprint = Blueprints.get(id).orElseThrow();
		Optional<StructureTemplate> template = level.getServer().getStructureTemplateManager().get(blueprint.structure());
		BlueprintSummary.of(id, blueprint, template).materials().forEach((item, count) -> {
			for (int i = 0; i < count; i++) {
				add(chest, new ItemStack(item));
			}
		});
		BlockPos at = helper.absolutePos(new BlockPos(4, 1, 4));
		BuildPlan plan = RegionPlan.load(template.orElseThrow(), at, Mirror.NONE, Rotation.NONE, blueprint.palette()).orElseThrow();
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 16, 1, 6);
		String run = "antcraft bot " + bot.getStringUUID() + " run jobs.settle " + at.getX() + " " + at.getY() + " " + at.getZ() + " 10";
		BotScripts.command(helper, run);
		boolean[] rerun = new boolean[1];
		helper.onEachTick(() -> {
			ScriptRun script = bot.getScript();
			if (script != null && script.state() == ScriptRun.State.FAILED 
				|| !BotScripts.warnings(bot).isEmpty()) {
				helper.fail("failed or warned: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
			if (!rerun[0] && BotScripts.log(bot, Output.Level.PRINT).contains("settle 3/7: hut")) {
				rerun[0] = true;
				bot.stopScript();
				BotScripts.command(helper, run);
			}
		});
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of("settle 1/7: kit", "settle 2/7: flatten",
				"settle 3/7: hut", "settle 3/7: hut", "settle 4/7: furnish", "settle 5/7: field", "settle 6/7: store", "settle 7/7: home",
				"settled"), "said");
			helper.assertTrue(plan.nextStep(level::getBlockState).isEmpty(), "the house is not built: " + plan.nextStep(level::getBlockState));
			helper.assertTrue(helper.getBlockState(new BlockPos(12, 1, 9)).isAir() && helper.getBlockState(new BlockPos(12, 2, 9)).isAir(), "the bump is left");
			helper.assertBlockPresent(Blocks.CHEST, 10, 1, 4);
			helper.assertBlockPresent(Blocks.CRAFTING_TABLE, 10, 1, 5);
			for (int x = 10; x <= 12; x++) {
				for (int z = 6; z <= 8; z++) {
					helper.assertBlockPresent(Blocks.FARMLAND, x, 0, z);
					helper.assertBlockPresent(Blocks.WHEAT, x, 1, z);
				}
			}
			helper.assertValueEqual(bot.getHome(), at.offset(2, 1, 1), "home");
			helper.assertTrue(chest.countItem(Items.STONE_PICKAXE) == 1 && bot.getInventory().countItem(Items.STONE_PICKAXE) == 0,
				"the borrowed pickaxe is not back");
		});
	}

	private static void add(Container chest, ItemStack stack) {
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack in = chest.getItem(i);
			if (in.isEmpty()) {
				chest.setItem(i, stack);
				return;
			}
			if (ItemStack.isSameItemSameComponents(in, stack) && in.getCount() + stack.getCount() <= in.getMaxStackSize()) {
				in.grow(stack.getCount());
				return;
			}
		}
		throw new IllegalStateException("chest full");
	}
}
