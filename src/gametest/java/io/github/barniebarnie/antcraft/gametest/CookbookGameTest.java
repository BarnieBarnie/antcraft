package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.ServerScripts;
import io.github.barniebarnie.antcraft.script.lang.Compiler;
import io.github.barniebarnie.antcraft.script.lang.Output;
import io.github.barniebarnie.antcraft.script.lang.Parser;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * The recipes of docs/cookbook run as they are (DocExampleTest keeps each copy under scripts/ equal to its page):
 * farm.md, recover-items.md and memory.md. stock-chest.md runs in CraftGameTest; mining-loop.md is compile-only (see
 * docs/cookbook/index.md).
 */
public class CookbookGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
			}
		}
		method.invoke(this, helper);
	}

	/** The copy of a recipe in the game test resources. */
	private static String recipe(String name) {
		try (InputStream in = CookbookGameTest.class.getResourceAsStream("/antcraft-gametest/scripts/" + name + ".py")) {
			return new String(Objects.requireNonNull(in, name + ".py").readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void runRecipe(AntBot bot, String name) {
		bot.runScript(name, Compiler.compile(Parser.parse(name + ".py", recipe(name))), List.of());
	}

	/** The live bot that has {@code id} in the test's level now, or null (dead, or not respawned yet). */
	private static AntBot bot(GameTestHelper helper, UUID id) {
		Entity entity = helper.getLevel().getEntity(id);
		return entity instanceof AntBot bot && bot.isAlive() ? bot : null;
	}

	/** Why the script of {@code bot} can no longer pass (it failed, ended or is gone), or null while it runs. */
	private static String ended(AntBot bot) {
		ScriptRun run = bot.getScript();
		return run == null || run.state() == ScriptRun.State.FAILED || run.state() == ScriptRun.State.FINISHED
			? "script " + (run == null ? "gone" : run.state()) + ", log " + BotScripts.log(bot, Output.Level.DEBUG)
			: null;
	}

	private static int countIn(Container container, Item item) {
		int n = 0;
		for (int i = 0; i < container.getContainerSize(); i++) {
			n += container.getItem(i).is(item) ? container.getItem(i).getCount() : 0;
		}
		return n;
	}

	/**
	 * docs/cookbook/farm.md: by a chest with a hoe and seeds and a 3x3 field around water, the farmer harvests the ripe
	 * wheat, tills a spot that turned back to dirt after it picked the field, plants seeds on every spot and stores the
	 * wheat in the chest.
	 */
	@GameTest(maxTicks = 60)
	public void theFarmRecipeHarvestsTillsReplantsAndStores(GameTestHelper helper) {
		BlockPos chestAt = new BlockPos(1, 1, 1);
		helper.setBlock(chestAt, Blocks.CHEST);
		Container chest = (Container) Objects.requireNonNull(helper.getBlockEntity(chestAt, ChestBlockEntity.class));
		chest.setItem(0, new ItemStack(Items.WOODEN_HOE));
		chest.setItem(1, new ItemStack(Items.WHEAT_SEEDS, 16));
		List<BlockPos> field = new ArrayList<>();
		for (int x = 3; x <= 5; x++) {
			for (int z = 3; z <= 5; z++) {
				if (x == 4 && z == 4) {
					helper.setBlock(x, 0, z, Blocks.WATER);
				} else {
					helper.setBlock(x, 0, z, Blocks.FARMLAND);
					field.add(new BlockPos(x, 0, z));
				}
			}
		}
		helper.setBlock(3, 1, 3, ((CropBlock) Blocks.WHEAT).getStateForAge(CropBlock.MAX_AGE));
		BlockPos trampled = new BlockPos(5, 0, 5);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 1);
		runRecipe(bot, "farm");
		boolean[] turned = {false};
		helper.onEachTick(() -> {
			String ended = ended(bot);
			if (ended != null) {
				helper.fail(ended);
			}
			// Once the farmer has picked its field, a spot of it is trampled back to dirt.
			if (!turned[0] && bot.getMemory().get("field") != null) {
				helper.setBlock(trampled, Blocks.DIRT);
				turned[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(turned[0], "field not picked");
			for (BlockPos soil : field) {
				helper.assertBlockPresent(Blocks.FARMLAND, soil);
				helper.assertBlockPresent(Blocks.WHEAT, soil.above());
			}
			helper.assertTrue(countIn(chest, Items.WHEAT) == 1, "chest holds " + countIn(chest, Items.WHEAT) + " wheat");
			helper.assertTrue(!bot.getInventory().hasAnyOf(Set.of(Items.WHEAT)), "wheat not stored");
		});
	}

	/**
	 * docs/cookbook/recover-items.md: the lumberjack killed away from its home respawns there, walks to last_death(),
	 * picks up what it dropped and says so.
	 */
	@GameTest(maxTicks = 520)
	public void theRecoverItemsRecipeFetchesWhatTheBotDropped(GameTestHelper helper) {
		helper.setBlock(7, 1, 7, Blocks.CHEST);
		AntBot first = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		UUID id = first.getUUID();
		first.setHome(helper.absolutePos(new BlockPos(6, 1, 6)));
		first.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 5));
		runRecipe(first, "recover_items");
		boolean[] killed = {false};
		helper.onEachTick(() -> {
			AntBot bot = bot(helper, id);
			if (bot == null) {
				return;
			}
			String ended = ended(bot);
			if (ended != null) {
				helper.fail(ended);
			}
			// Killed once its first run has noted the deaths so far.
			if (!killed[0] && bot.getMemory().get("deaths") != null) {
				bot.kill(helper.getLevel());
				killed[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			AntBot bot = bot(helper, id);
			helper.assertTrue(bot != null && bot != first, "not respawned");
			List<String> printed = BotScripts.log(Objects.requireNonNull(bot), Output.Level.PRINT);
			helper.assertTrue(printed.contains("picked up 5 items where I died"), "printed " + printed);
			helper.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE) == 5, "carries " + bot.getInventory().getItems());
		});
	}

	/**
	 * docs/cookbook/memory.md: the tunnel digger, started on a 5-block tunnel through dirt, is run again from the top part
	 * way by an edit of its script and a reload; it goes on from memory["tunnel"] instead of starting a new tunnel, so the
	 * dirt past the tunnel's end stays.
	 */
	@GameTest(maxTicks = 330)
	public void theMemoryRecipeGoesOnWithItsTunnelAfterAnEditAndReload(GameTestHelper helper) {
		for (int x = 2; x <= 7; x++) {
			for (int y = 1; y <= 3; y++) {
				for (int z = 2; z <= 4; z++) {
					helper.setBlock(x, y, z, Blocks.DIRT);
				}
			}
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 3);
		String source = recipe("memory");
		String name = BotScripts.libraryScript(helper, "memory_recipe_", source);
		List<String> started = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + name + " 5");
		helper.assertTrue(started.equals(List.of("commands.antcraft.bot.run[" + name + ", 1]")), "run answered " + started);
		boolean[] edited = {false};
		helper.onEachTick(() -> {
			ScriptRun run = bot.getScript();
			if (run != null && run.state() == ScriptRun.State.FAILED) {
				helper.fail("script failed: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
			// Once two blocks of the tunnel are dug, the script is edited and reloaded: it starts again from the top.
			if (!edited[0] && helper.getBlockState(new BlockPos(3, 1, 3)).isAir() && helper.getBlockState(new BlockPos(3, 2, 3)).isAir()) {
				try {
					Files.writeString(ServerScripts.worldFolder(helper.getLevel().getServer()).resolve(name + ".py"), "# edited\n" + source);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
				BotScripts.command(helper, "antcraft script reload");
				edited[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			List<String> printed = BotScripts.log(bot, Output.Level.PRINT);
			helper.assertTrue(edited[0], "not edited");
			helper.assertTrue(printed.stream().anyMatch(line -> line.matches("back to block [12] of my tunnel")), "printed " + printed);
			helper.assertTrue(printed.contains("the tunnel is 5 blocks long"), "printed " + printed);
			for (int x = 2; x <= 6; x++) {
				helper.assertBlockPresent(Blocks.AIR, new BlockPos(x, 1, 3));
				helper.assertBlockPresent(Blocks.AIR, new BlockPos(x, 2, 3));
			}
			helper.assertBlockPresent(Blocks.DIRT, new BlockPos(7, 1, 3));
			helper.assertTrue(bot.getMemory().get("tunnel") == null, "memory still has the tunnel");
		});
	}
}
