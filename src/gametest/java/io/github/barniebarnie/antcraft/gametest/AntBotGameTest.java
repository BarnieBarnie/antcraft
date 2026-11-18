package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.command.AntCraftCommands;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.item.CoreScript;
import io.github.barniebarnie.antcraft.item.RememberedBot;
import io.github.barniebarnie.antcraft.network.BotNetworking;
import io.github.barniebarnie.antcraft.script.Memory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import org.jspecify.annotations.Nullable;
import io.github.barniebarnie.antcraft.profile.TickProfiler;
import io.github.barniebarnie.antcraft.registry.ModComponents;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.registry.ModItems;
import io.github.barniebarnie.antcraft.script.DefaultScripts;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Bot behaviour, tested on a real (headless) server. Each test gets an empty
 * 8x8x8 area; a stone floor is laid at y=0 so entities stand at y=1.
 */
public class AntBotGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		// Game tests run on a dedicated server, where bots are disabled unless opted in.
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	@GameTest(maxTicks = 240)
	public void killsHostileMob(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		// An unarmed bot and a husk are an even match; armed, the outcome does not depend on luck.
		bot.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		// Husks do not burn in daylight, so only the bot can kill it.
		Husk husk = helper.spawn(EntityTypes.HUSK, 6, 1, 6);

		helper.succeedWhen(() -> {
			helper.assertTrue(!husk.isAlive(), "husk still alive");
			helper.assertTrue(husk.getLastHurtByMob() == bot, "husk was not killed by the bot");
		});
	}

	@GameTest(maxTicks = 20)
	public void picksUpItemsIntoInventory(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		helper.spawnItem(Items.OAK_LOG, 3, 1, 3);

		helper.succeedWhen(() -> helper.assertTrue(GameTests.carried(bot, Items.OAK_LOG) == 1, "log not in inventory"));
	}

	@GameTest(maxTicks = 20)
	public void dropsInventoryOnDeath(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 5));
		bot.kill(helper.getLevel());

		helper.succeedWhen(() -> helper.assertItemEntityPresent(Items.COBBLESTONE, new BlockPos(3, 1, 3), 2.0));
	}

	/**
	 * An operator's {@code /antcraft profile start} measures server ticks and the bots' parts of them until {@code stop};
	 * a player who is not an operator may not use it.
	 */
	@GameTest(maxTicks = 20)
	public void profileCommandMeasuresServerTicksForOperatorsOnly(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		CommandSourceStack operator = server.createCommandSourceStack().withLevel(helper.getLevel()).withSuppressedOutput();
		CommandSourceStack player = helper.makeMockServerPlayerInLevel().createCommandSourceStack().withSuppressedOutput();
		helper.assertFalse(server.getCommands().getDispatcher().getRoot().getChild("antcraft").getChild("profile").canUse(player),
			"non-operator player may use /antcraft profile");
		helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		server.getCommands().performPrefixedCommand(operator, "antcraft profile start");
		helper.assertTrue(TickProfiler.SERVER.running(), "profiler not started");
		helper.runAfterDelay(5, () -> {
			TickProfiler.Report report = TickProfiler.SERVER.report();
			helper.assertTrue(report.ticks() >= 4, "measured " + report.ticks() + " ticks");
			helper.assertTrue(report.parts().stream().anyMatch(part -> part.name().equals("bot.tick")), "no bot ticks in " + report.lines());
			server.getCommands().performPrefixedCommand(operator, "antcraft profile report");
			server.getCommands().performPrefixedCommand(operator, "antcraft profile stop");
			helper.assertFalse(TickProfiler.SERVER.running(), "profiler not stopped");
			helper.succeed();
		});
	}

	/** A Bot Core that carries a script, used on a block, gives a bot that runs that script with its args, owned by the player. */
	@GameTest(maxTicks = 20)
	public void aBotCoreWithAScriptGivesABotRunningIt(GameTestHelper helper) {
		String name = BotScripts.libraryScript(helper, "core_", "print(' '.join(ARGS))\nwhile True:\n    wait(20)\n");
		ItemStack core = new ItemStack(ModItems.BOT_CORE);
		core.set(ModComponents.CORE_SCRIPT, new CoreScript(name, List.of("north", "3")));
		ServerPlayer player = useOnTheFloor(helper, core);
		AntBot bot = helper.findOneEntity(ModEntities.ANT_BOT);
		helper.assertValueEqual(bot.getOwner(), player.getUUID(), "owner");
		helper.succeedWhen(() -> {
			ScriptRun run = bot.getScript();
			helper.assertTrue(run != null && run.name().equals(name), "runs " + (run == null ? null : run.name()));
			helper.assertValueEqual(run.args(), List.of("north", "3"), "args");
			helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).contains("north 3"), "log " + BotScripts.log(bot, Output.Level.DEBUG));
		});
	}

	/**
	 * A player who already runs {@code maxScriptsPerPlayer} scripts and uses a Bot Core that carries one gets a bot that
	 * runs {@code idle.py}, and is told why (docs/features/scripts.md, Players' scripts).
	 */
	@GameTest(maxTicks = 5)
	public void aBotCoresScriptCountsAgainstThePlayersCap(GameTestHelper helper) {
		int max = AntCraftConfig.maxScriptsPerPlayer();
		helper.assertTrue(max > 0 && max <= 12, "the test needs a cap of 1 to 12 scripts, not " + max);
		String name = BotScripts.libraryScript(helper, "capped_", "while True:\n    wait(20)\n");
		List<AntBot> running = new java.util.ArrayList<>();
		BotScripts.RecordingPlayer player = null;
		for (int i = 0; i < max; i++) {
			AntBot bot = helper.spawn(ModEntities.ANT_BOT, i % 6, 1, 6 + i / 6);
			if (player == null) {
				player = BotScripts.owner(helper, bot);
			}
			bot.setOwner(player.getUUID());
			BotScripts.run(bot, "while True:\n    wait(20)\n");
			running.add(bot);
		}
		ItemStack core = new ItemStack(ModItems.BOT_CORE);
		core.set(ModComponents.CORE_SCRIPT, new CoreScript(name, List.of()));
		useOnTheFloor(helper, player, core);
		AntBot made = helper.getEntities(ModEntities.ANT_BOT).stream().filter(bot -> !running.contains(bot)).findFirst()
			.orElseThrow(() -> helper.assertionException("the core made no bot"));
		List<String> chat = player.messages().stream().map(BotScripts.Message::text).toList();
		helper.assertTrue(chat.equals(List.of("You may run at most " + max + " scripts at once")), "player was told " + chat);
		helper.succeedWhen(() -> {
			ScriptRun run = made.getScript();
			helper.assertTrue(run != null && run.name().equals(DefaultScripts.IDLE), "runs " + (run == null ? null : run.name()));
		});
	}

	/** A Bot Core without a script gives a bot that runs {@code idle.py}, as before. */
	@GameTest(maxTicks = 20)
	public void aBotCoreWithoutAScriptGivesAnIdleBot(GameTestHelper helper) {
		ServerPlayer player = useOnTheFloor(helper, new ItemStack(ModItems.BOT_CORE));
		AntBot bot = helper.findOneEntity(ModEntities.ANT_BOT);
		helper.assertValueEqual(bot.getOwner(), player.getUUID(), "owner");
		helper.succeedWhen(() -> {
			ScriptRun run = bot.getScript();
			helper.assertTrue(run != null && run.name().equals(DefaultScripts.IDLE), "runs " + (run == null ? null : run.name()));
		});
	}

	/**
	 * A Bot Core's script survives saving the stack, names itself and its args in the tooltip, and keeps cores with
	 * different scripts, or none, from stacking.
	 */
	@GameTest(maxTicks = 5)
	public void aBotCoresScriptIsKeptOnTheStack(GameTestHelper helper) {
		ItemStack core = new ItemStack(ModItems.BOT_CORE);
		CoreScript script = new CoreScript("miner", List.of("iron", "32"));
		core.set(ModComponents.CORE_SCRIPT, script);
		RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
		ItemStack back = ItemStack.CODEC.parse(ops, ItemStack.CODEC.encodeStart(ops, core).getOrThrow()).getOrThrow();
		helper.assertValueEqual(back.get(ModComponents.CORE_SCRIPT), script, "script after a round trip");
		ItemStack farmer = new ItemStack(ModItems.BOT_CORE);
		farmer.set(ModComponents.CORE_SCRIPT, new CoreScript("farmer", List.of()));
		helper.assertFalse(ItemStack.isSameItemSameComponents(core, farmer), "cores with different scripts stack");
		helper.assertFalse(ItemStack.isSameItemSameComponents(core, new ItemStack(ModItems.BOT_CORE)), "a core with a script stacks with one without");
		List<String> tooltip = core.getTooltipLines(Item.TooltipContext.of(helper.getLevel()), null, TooltipFlag.NORMAL).stream()
			.map(BotScripts::text).toList();
		helper.assertTrue(tooltip.contains("item.antcraft.bot_core.script[miner iron 32]"), "tooltip " + tooltip);
		helper.succeed();
	}

	/**
	 * {@code /antcraft core script} sets the script of the Bot Core in the player's main hand, refuses one that does not
	 * compile with its error, and {@code clear} takes it off again.
	 */
	@GameTest(maxTicks = 5)
	public void theCoreScriptCommandSetsAndClearsTheHeldCoresScript(GameTestHelper helper) {
		String good = BotScripts.libraryScript(helper, "core_good_", "wait(1)\n");
		String broken = BotScripts.libraryScript(helper, "core_broken_", "while\n");
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		ItemStack core = new ItemStack(ModItems.BOT_CORE);
		player.setItemInHand(InteractionHand.MAIN_HAND, core);

		List<String> refused = BotScripts.command(helper, player, "antcraft core script " + broken);
		// Failures come styled, so as the translated text.
		helper.assertTrue(refused.stream().anyMatch(line -> line.startsWith(broken + " does not compile: " + broken + ".py:1:")), "answered " + refused);
		helper.assertFalse(core.has(ModComponents.CORE_SCRIPT), "script set to a broken one");
		List<String> unknown = BotScripts.command(helper, player, "antcraft core script no_such_script");
		helper.assertTrue(unknown.stream().anyMatch(line -> line.startsWith("No script named no_such_script")), "answered " + unknown);

		BotScripts.command(helper, player, "antcraft core script " + good + " north 3");
		helper.assertValueEqual(core.get(ModComponents.CORE_SCRIPT), new CoreScript(good, List.of("north", "3")), "script set");
		BotScripts.command(helper, player, "antcraft core script clear");
		helper.assertFalse(core.has(ModComponents.CORE_SCRIPT), "script still set after clear");

		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
		List<String> noCore = BotScripts.command(helper, player, "antcraft core script " + good);
		helper.assertTrue(noCore.contains("Hold a Bot Core in your main hand"), "answered " + noCore);
		helper.succeed();
	}

	/**
	 * Sneak-using a bot with an empty hand (#916) picks it up for its owner: what it carries lies on the ground, the bot is
	 * gone, and the owner gets a Bot Core that remembers its name, script and memory. A player who may not control the bot
	 * cannot pick it up.
	 */
	@GameTest(maxTicks = 5)
	public void sneakUsingABotPicksItUpIntoACore(GameTestHelper helper) {
		AntBot bot = pickable(helper);
		BotScripts.RecordingPlayer stranger = BotScripts.owner(helper, bot);
		BotScripts.RecordingPlayer owner = BotScripts.owner(helper, bot);
		String name = bot.getName().getString();

		stranger.setShiftKeyDown(true);
		bot.interact(stranger, InteractionHand.MAIN_HAND, bot.position());
		helper.assertTrue(bot.isAlive() && !bot.isRemoved(), "a stranger picked the bot up");
		helper.assertTrue(core(stranger) == null, "the stranger got a core");

		owner.setShiftKeyDown(true);
		bot.interact(owner, InteractionHand.MAIN_HAND, bot.position());
		helper.assertTrue(bot.isRemoved(), "bot still there");
		helper.assertItemEntityPresent(Items.COBBLESTONE, new BlockPos(1, 1, 1), 2.0);
		helper.assertItemEntityPresent(Items.IRON_PICKAXE, new BlockPos(1, 1, 1), 2.0);
		assertRemembers(helper, core(owner), name, bot.getScript());
		helper.succeed();
	}

	/** A core a bot was picked up into (#916), placed again, brings that bot back: its name, its memory, running its script. */
	@GameTest(maxTicks = 5)
	public void aPickedUpBotsCoreBringsItBack(GameTestHelper helper) {
		AntBot bot = pickable(helper);
		BotScripts.RecordingPlayer owner = BotScripts.owner(helper, bot);
		String name = bot.getName().getString();
		ScriptRun was = bot.getScript();
		owner.setShiftKeyDown(true);
		bot.interact(owner, InteractionHand.MAIN_HAND, bot.position());
		ItemStack core = core(owner);
		helper.assertTrue(core != null, "no core");

		useOnTheFloor(helper, owner, core);
		AntBot back = helper.findOneEntity(ModEntities.ANT_BOT);
		helper.assertValueEqual(back.getName().getString(), name, "name");
		helper.assertValueEqual(back.getMemory().get("job"), "cane", "memory['job']");
		helper.succeedWhen(() -> {
			ScriptRun run = back.getScript();
			helper.assertTrue(run != null && run.name().equals(was.name()) && run.args().equals(was.args()),
				"runs " + (run == null ? null : run.name() + run.args()));
		});
	}

	/** {@code bot pickup} (#916) and the bots screen's Pick up button pick a bot up as sneak-using it does, for its owner only. */
	@GameTest(maxTicks = 5)
	public void thePickupCommandAndTheBotsScreenPickABotUp(GameTestHelper helper) {
		AntBot byCommand = pickable(helper);
		BotScripts.RecordingPlayer stranger = BotScripts.owner(helper, byCommand);
		BotScripts.RecordingPlayer owner = BotScripts.owner(helper, byCommand);
		String name = byCommand.getName().getString();
		List<String> refused = BotScripts.commandAsPlayer(helper, stranger, "antcraft bot " + byCommand.getStringUUID() + " pickup");
		helper.assertTrue(refused.size() == 1 && refused.getFirst().endsWith(" is not your bot"), "answer " + refused);
		helper.assertFalse(byCommand.isRemoved(), "a stranger picked the bot up");
		List<String> answer = BotScripts.commandAsPlayer(helper, owner, "antcraft bot " + byCommand.getStringUUID() + " pickup");
		helper.assertTrue(answer.equals(List.of("commands.antcraft.bot.pickup[1]")), "answer " + answer);
		helper.assertTrue(byCommand.isRemoved(), "bot still there");
		assertRemembers(helper, core(owner), name, byCommand.getScript());
		owner.getInventory().clearContent();

		AntBot byScreen = pickable(helper);
		byScreen.setOwner(owner.getUUID());
		String screenName = byScreen.getName().getString();
		BotNetworking.handle(owner, new BotNetworking.BotAction(byScreen.getUUID(), BotNetworking.Action.PICKUP, "", ""));
		helper.assertTrue(byScreen.isRemoved(), "bot still there after the screen's Pick up");
		assertRemembers(helper, core(owner), screenName, byScreen.getScript());
		helper.succeed();
	}

	/** A remembering core crafted alone (#916) gives a blank core. */
	@GameTest(maxTicks = 5)
	public void craftingARememberingCoreAloneGivesABlankCore(GameTestHelper helper) {
		ItemStack core = new ItemStack(ModItems.BOT_CORE);
		core.set(ModComponents.CORE_SCRIPT, new CoreScript("miner", List.of("iron")));
		core.set(ModComponents.REMEMBERED_BOT, new RememberedBot("Ant 1234", new CompoundTag()));
		CraftingInput input = CraftingInput.of(1, 1, List.of(core));
		ItemStack result = helper.getLevel().recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel())
			.map(recipe -> recipe.value().assemble(input)).orElse(ItemStack.EMPTY);
		helper.assertTrue(ItemStack.isSameItemSameComponents(result, new ItemStack(ModItems.BOT_CORE)),
			"crafted " + result + " " + result.getComponentsPatch());
		helper.succeed();
	}

	/** A bot with a name, a library script running with args, something in memory, and things to carry. */
	private static AntBot pickable(GameTestHelper helper) {
		String script = BotScripts.libraryScript(helper, "picked_", "while True:\n    wait(20)\n");
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.setCustomName(Component.literal("Ant " + bot.getUUID().toString().substring(0, 4)));
		bot.getMemory().put("job", "cane");
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 5));
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
		AntCraftCommands.runnable(script, failure -> helper.fail(failure.getString()))
			.ifPresent(found -> bot.runScript(found, List.of("north", "3")));
		return bot;
	}

	/** The Bot Core in {@code player}'s inventory, if any. */
	static @Nullable ItemStack core(ServerPlayer player) {
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			if (player.getInventory().getItem(i).is(ModItems.BOT_CORE)) {
				return player.getInventory().getItem(i);
			}
		}
		return null;
	}

	private static void assertRemembers(GameTestHelper helper, @Nullable ItemStack core, String name, ScriptRun script) {
		helper.assertTrue(core != null, "no core");
		RememberedBot remembered = core.get(ModComponents.REMEMBERED_BOT);
		helper.assertTrue(remembered != null && remembered.name().equals(name), "remembers " + remembered);
		helper.assertValueEqual(Memory.fromTag(remembered.memory()).get("job"), "cane", "remembered memory['job']");
		helper.assertValueEqual(core.get(ModComponents.CORE_SCRIPT), new CoreScript(script.name(), List.of("north", "3")), "script");
	}

	/** A player who uses {@code core} on the middle of the floor; the bot appears above it. */
	private static ServerPlayer useOnTheFloor(GameTestHelper helper, ItemStack core) {
		return useOnTheFloor(helper, helper.makeMockServerPlayerInLevel(), core);
	}

	private static ServerPlayer useOnTheFloor(GameTestHelper helper, ServerPlayer player, ItemStack core) {
		player.setItemInHand(InteractionHand.MAIN_HAND, core);
		BlockPos floor = helper.absolutePos(new BlockPos(3, 0, 3));
		core.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(floor), Direction.UP, floor, false)));
		return player;
	}

	@GameTest(maxTicks = 20)
	public void relativeInvertsAbsolutePos(GameTestHelper helper) {
		for (BlockPos pos : new BlockPos[] {BlockPos.ZERO, new BlockPos(1, 2, 5), new BlockPos(6, 1, 0), new BlockPos(-3, -2, 7)}) {
			BlockPos back = TestPositions.relative(helper, helper.absolutePos(pos));
			helper.assertTrue(back.equals(pos), pos.toShortString() + " came back as " + back.toShortString());
		}
		helper.succeed();
	}
}
