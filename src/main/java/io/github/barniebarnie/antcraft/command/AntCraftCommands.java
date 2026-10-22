package io.github.barniebarnie.antcraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.datafixers.util.Either;
import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyActions;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.ColonyStatus;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.colony.Project;
import io.github.barniebarnie.antcraft.colony.StockPlanner;
import io.github.barniebarnie.antcraft.colony.StockTarget;
import io.github.barniebarnie.antcraft.colony.Territory;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.RegionPlan;
import io.github.barniebarnie.antcraft.work.Terraform;
import io.github.barniebarnie.antcraft.work.Storage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.commands.arguments.TemplateMirrorArgument;
import net.minecraft.commands.arguments.TemplateRotationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;

/**
 * /antcraft status
 * /antcraft goto &lt;pos&gt; [bots]   (defaults to the nearest bot)
 * /antcraft stop [bots]
 * /antcraft colony info|order &lt;item&gt; &lt;count&gt;|cancel &lt;item&gt;|mineshaft &lt;pos&gt;|radius &lt;blocks&gt;   (nearest colony)
 * /antcraft colony stock [set &lt;item|#tag&gt; &lt;count&gt;|remove &lt;item|#tag&gt;]
 * /antcraft colony blocked clear
 * /antcraft colony build list|copy &lt;from&gt; &lt;to&gt; &lt;at&gt;|load &lt;structure&gt; &lt;at&gt; [rotation] [mirror] [palette]|cancel &lt;n&gt;
 * /antcraft colony project list|cancel &lt;n&gt;|priority &lt;n&gt; &lt;priority&gt;
 * /antcraft colony terraform &lt;from&gt; &lt;to&gt; &lt;y&gt;
 */
public final class AntCraftCommands {
	private static final double NEAREST_RANGE = 64.0;
	public static final int COLONY_RANGE = 64;
	/** Largest region {@code colony build copy} takes, the same as vanilla /clone. */
	private static final int MAX_COPY_VOLUME = 32768;
	/** Highest priority a project may get, and lowest below zero. */
	private static final int MAX_PRIORITY = 100;
	private static final DynamicCommandExceptionType UNKNOWN_ITEM = new DynamicCommandExceptionType(
		name -> Component.translatableEscape("commands.antcraft.colony.stock.unknown", name));

	private AntCraftCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> register(dispatcher, buildContext));
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		dispatcher.register(Commands.literal("antcraft")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("status").executes(AntCraftCommands::status))
			.then(Commands.literal("goto")
				.then(Commands.argument("pos", BlockPosArgument.blockPos())
					.executes(ctx -> walkTo(ctx, nearestBot(ctx.getSource())))
					.then(Commands.argument("bots", EntityArgument.entities())
						.executes(ctx -> walkTo(ctx, bots(ctx))))))
			.then(Commands.literal("stop")
				.executes(ctx -> stop(ctx.getSource(), nearestBot(ctx.getSource())))
				.then(Commands.argument("bots", EntityArgument.entities())
					.executes(ctx -> stop(ctx.getSource(), bots(ctx)))))
			.then(Commands.literal("colony")
				.then(Commands.literal("info").executes(ctx -> colonyInfo(ctx.getSource())))
				.then(Commands.literal("blocked")
					.then(Commands.literal("clear").executes(ctx -> clearBlocked(ctx.getSource()))))
				.then(Commands.literal("order")
					.then(Commands.argument("item", ItemArgument.item(buildContext))
						.then(Commands.argument("count", IntegerArgumentType.integer(1, ColonyActions.MAX_COUNT))
							.executes(ctx -> order(ctx.getSource(), ItemArgument.getItem(ctx, "item").item().value(), IntegerArgumentType.getInteger(ctx, "count"))))))
				.then(Commands.literal("mineshaft")
					.then(Commands.argument("pos", BlockPosArgument.blockPos())
						.executes(ctx -> setMineshaft(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "pos")))))
				.then(Commands.literal("radius")
					.then(Commands.argument("blocks", IntegerArgumentType.integer(ColonyActions.MIN_RADIUS, ColonyActions.MAX_RADIUS))
						.executes(ctx -> setRadius(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "blocks")))))
				.then(Commands.literal("stock")
					.executes(ctx -> listStock(ctx.getSource()))
					.then(Commands.literal("set")
						.then(Commands.argument("match", ResourceOrTagKeyArgument.resourceOrTagKey(Registries.ITEM))
							.then(Commands.argument("count", IntegerArgumentType.integer(1, ColonyActions.MAX_COUNT))
								.executes(ctx -> setStock(ctx.getSource(), stockMatch(ctx), IntegerArgumentType.getInteger(ctx, "count"))))))
					.then(Commands.literal("remove")
						.then(Commands.argument("match", ResourceOrTagKeyArgument.resourceOrTagKey(Registries.ITEM))
							.executes(ctx -> removeStock(ctx.getSource(), stockMatch(ctx))))))
				.then(Commands.literal("build")
					.then(Commands.literal("list").executes(ctx -> listBuilds(ctx.getSource())))
					.then(Commands.literal("copy")
						.then(Commands.argument("from", BlockPosArgument.blockPos())
							.then(Commands.argument("to", BlockPosArgument.blockPos())
								.then(Commands.argument("at", BlockPosArgument.blockPos())
									.executes(ctx -> copyBuild(ctx.getSource(), BlockPosArgument.getLoadedBlockPos(ctx, "from"),
										BlockPosArgument.getLoadedBlockPos(ctx, "to"), BlockPosArgument.getBlockPos(ctx, "at")))))))
					.then(Commands.literal("load")
						.then(Commands.argument("structure", IdentifierArgument.id())
							.suggests((ctx, builder) -> SharedSuggestionProvider.suggestResource(
								ctx.getSource().getServer().getStructureTemplateManager().listTemplates(), builder))
							.then(Commands.argument("at", BlockPosArgument.blockPos())
								.executes(ctx -> loadBuild(ctx.getSource(), IdentifierArgument.getId(ctx, "structure"),
									BlockPosArgument.getBlockPos(ctx, "at"), Rotation.NONE, Mirror.NONE, 0))
								.then(Commands.argument("rotation", TemplateRotationArgument.templateRotation())
									.executes(ctx -> loadBuild(ctx.getSource(), IdentifierArgument.getId(ctx, "structure"),
										BlockPosArgument.getBlockPos(ctx, "at"), TemplateRotationArgument.getRotation(ctx, "rotation"), Mirror.NONE, 0))
									.then(Commands.argument("mirror", TemplateMirrorArgument.templateMirror())
										.executes(ctx -> loadBuild(ctx.getSource(), IdentifierArgument.getId(ctx, "structure"),
											BlockPosArgument.getBlockPos(ctx, "at"), TemplateRotationArgument.getRotation(ctx, "rotation"),
											TemplateMirrorArgument.getMirror(ctx, "mirror"), 0))
										.then(Commands.argument("palette", IntegerArgumentType.integer(0))
											.executes(ctx -> loadBuild(ctx.getSource(), IdentifierArgument.getId(ctx, "structure"),
												BlockPosArgument.getBlockPos(ctx, "at"), TemplateRotationArgument.getRotation(ctx, "rotation"),
												TemplateMirrorArgument.getMirror(ctx, "mirror"), IntegerArgumentType.getInteger(ctx, "palette")))))))))
					.then(Commands.literal("cancel")
						.then(Commands.argument("n", IntegerArgumentType.integer(1))
							.executes(ctx -> cancelBuild(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "n"))))))
				.then(Commands.literal("project")
					.then(Commands.literal("list").executes(ctx -> listProjects(ctx.getSource())))
					.then(Commands.literal("cancel")
						.then(Commands.argument("n", IntegerArgumentType.integer(1))
							.executes(ctx -> cancelProject(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "n")))))
					.then(Commands.literal("priority")
						.then(Commands.argument("n", IntegerArgumentType.integer(1))
							.then(Commands.argument("priority", IntegerArgumentType.integer(-MAX_PRIORITY, MAX_PRIORITY))
								.executes(ctx -> setProjectPriority(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "n"),
									IntegerArgumentType.getInteger(ctx, "priority")))))))
				.then(Commands.literal("terraform")
					.then(Commands.argument("from", BlockPosArgument.blockPos())
						.then(Commands.argument("to", BlockPosArgument.blockPos())
							.then(Commands.argument("y", IntegerArgumentType.integer())
								.executes(ctx -> terraform(ctx.getSource(), BlockPosArgument.getLoadedBlockPos(ctx, "from"),
									BlockPosArgument.getLoadedBlockPos(ctx, "to"), IntegerArgumentType.getInteger(ctx, "y")))))))
				.then(Commands.literal("cancel")
					.then(Commands.argument("item", ItemArgument.item(buildContext))
						.executes(ctx -> cancel(ctx.getSource(), ItemArgument.getItem(ctx, "item").item().value()))))));
	}

	private static Optional<Colony> nearestColony(CommandSourceStack source) {
		Optional<Colony> colony = ColonyManager.get(source.getLevel()).nearest(BlockPos.containing(source.getPosition()), COLONY_RANGE);
		if (colony.isEmpty()) {
			source.sendFailure(Component.translatable("commands.antcraft.colony.none", COLONY_RANGE));
		}
		return colony;
	}

	private static int colonyInfo(CommandSourceStack source) {
		Optional<Colony> found = nearestColony(source);
		if (found.isEmpty()) {
			return 0;
		}
		ColonyStatus status = ColonyStatus.of(source.getLevel(), found.get());
		source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.info",
			status.core().toShortString(), status.members(), status.jobs(), status.claimed(), status.radius(), status.idle()), false);
		status.bots().values().forEach(bot -> source.sendSuccess(() -> botLine(bot), false));

		List<String> low = status.stock().stream().filter(ColonyStatus.Stock::isShort)
			.map(stock -> stock.have() + "/" + stock.want() + " " + stock.name()).toList();
		if (!low.isEmpty()) {
			source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.stock.low", String.join(", ", low)), false);
		}
		for (ColonyStatus.Order order : status.orders()) {
			source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.order_line",
				order.count(), itemName(order.item()), names(order.toGather()), names(order.missing())), false);
		}
		if (!status.blocked().isEmpty()) {
			String blocked = String.join(", ", status.blocked().stream().map(BlockPos::toShortString).toList());
			source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.blocked", blocked), false);
		}
		for (JobType type : List.of(JobType.CHOP_TREE, JobType.CHOP_LOG, JobType.MINE_BRANCH, JobType.MINE_VEIN)) {
			List<BlockPos> givenUp = status.givenUp(type);
			if (!givenUp.isEmpty()) {
				String list = String.join(", ", givenUp.stream().map(BlockPos::toShortString).toList());
				source.sendSuccess(() -> Component.translatable(ColonyStatus.givenUpKey(type), list), false);
			}
		}
		if (status.room().coreFull()) {
			source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.core_full", Component.translatable(status.room().storage().key())), false);
		}
		return status.members();
	}

	private static Either<TagKey<Item>, Item> stockMatch(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Either<ResourceKey<Item>, TagKey<Item>> match = ResourceOrTagKeyArgument.getResourceOrTagKey(ctx, "match", Registries.ITEM, UNKNOWN_ITEM).unwrap();
		Optional<TagKey<Item>> tag = match.right();
		if (tag.isPresent()) {
			return Either.left(tag.get());
		}
		ResourceKey<Item> key = match.left().orElseThrow();
		Item item = BuiltInRegistries.ITEM.getOptional(key).orElseThrow(() -> UNKNOWN_ITEM.create(key.identifier().toString()));
		return Either.right(item);
	}

	private static int listStock(CommandSourceStack source) {
		Optional<Colony> found = nearestColony(source);
		if (found.isEmpty()) {
			return 0;
		}
		Colony colony = found.get();
		Container core = Storage.containerAt(source.getLevel(), colony.core());
		// What the colony holds, its bots' packs included, as stock targets count it.
		Map<Item, Integer> stock = core == null ? Map.of() : Storage.held(source.getLevel(), colony);
		if (colony.stockTargets().isEmpty()) {
			source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.stock.empty"), false);
		}
		for (StockTarget target : colony.stockTargets()) {
			int have = StockPlanner.have(target.toPlannerTarget(), stock);
			source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.stock.line", target.name(), have, target.count()), false);
		}
		return colony.stockTargets().size();
	}

	private static int setStock(CommandSourceStack source, Either<TagKey<Item>, Item> match, int count) {
		if (!checkMayEdit(source, ColonyActions.Edit.SET_STOCK)) {
			return 0;
		}
		Optional<Colony> colony = nearestColony(source);
		colony.ifPresent(c -> {
			StockTarget target = ColonyActions.setStock(c, match, count);
			source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.stock.set", target.name(), count), false);
		});
		return colony.isPresent() ? 1 : 0;
	}

	private static int removeStock(CommandSourceStack source, Either<TagKey<Item>, Item> match) {
		if (!checkMayEdit(source, ColonyActions.Edit.REMOVE_STOCK)) {
			return 0;
		}
		Optional<Colony> colony = nearestColony(source);
		if (colony.isEmpty()) {
			return 0;
		}
		if (!colony.get().removeStockTarget(match)) {
			source.sendFailure(Component.translatable("commands.antcraft.colony.stock.not_found", StockTarget.name(match)));
			return 0;
		}
		source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.stock.removed", StockTarget.name(match)), false);
		return 1;
	}

	private static String itemName(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).getPath();
	}

	private static String names(Map<Item, Integer> items) {
		if (items.isEmpty()) {
			return "-";
		}
		StringBuilder text = new StringBuilder();
		items.forEach((item, count) -> text.append(text.isEmpty() ? "" : ", ").append(count).append(' ').append(itemName(item)));
		return text.toString();
	}

	private static int order(CommandSourceStack source, Item item, int count) {
		if (!checkMayEdit(source, ColonyActions.Edit.ORDER)) {
			return 0;
		}
		Optional<Colony> colony = nearestColony(source);
		colony.ifPresent(c -> {
			c.addOrder(item, count);
			source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.ordered", count, itemName(item)), false);
		});
		return colony.isPresent() ? 1 : 0;
	}

	private static int setMineshaft(CommandSourceStack source, BlockPos top) {
		if (!checkMayEdit(source, ColonyActions.Edit.MINESHAFT)) {
			return 0;
		}
		Optional<Colony> colony = nearestColony(source);
		colony.ifPresent(c -> {
			c.setMineshaft(new Mineshaft(top, AntCraftConfig.mineshaftLevels()));
			source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.mineshaft", top.toShortString()), false);
		});
		return colony.isPresent() ? 1 : 0;
	}

	private static int setRadius(CommandSourceStack source, int radius) {
		if (!checkMayEdit(source, ColonyActions.Edit.RADIUS)) {
			return 0;
		}
		Optional<Colony> colony = nearestColony(source);
		colony.ifPresent(c -> {
			c.setRadius(radius);
			source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.radius", radius), false);
		});
		return colony.isPresent() ? 1 : 0;
	}

	private static int cancel(CommandSourceStack source, Item item) {
		if (!checkMayEdit(source, ColonyActions.Edit.CANCEL)) {
			return 0;
		}
		Optional<Colony> colony = nearestColony(source);
		colony.ifPresent(c -> {
			ColonyActions.cancelOrder(c, item);
			source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.cancelled", itemName(item)), false);
		});
		return colony.isPresent() ? 1 : 0;
	}

	private static boolean isMineshaft(Colony colony, BuildPlan plan) {
		return colony.mineshaft().map(shaft -> shaft.plan().equals(plan)).orElse(false);
	}

	private static int listBuilds(CommandSourceStack source) {
		Optional<Colony> found = nearestColony(source);
		if (found.isEmpty()) {
			return 0;
		}
		Colony colony = found.get();
		List<BuildPlan> builds = colony.builds();
		if (builds.isEmpty()) {
			source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.build.empty"), false);
		}
		for (int i = 0; i < builds.size(); i++) {
			BuildPlan plan = builds.get(i);
			long left = plan.steps().stream().filter(step -> !step.isDone(source.getLevel().getBlockState(step.pos()))).count();
			String key = isMineshaft(colony, plan) ? "commands.antcraft.colony.build.line_mineshaft"
				: plan.kind() == BuildPlan.Kind.TERRAFORM ? "commands.antcraft.colony.build.line_terraform" : "commands.antcraft.colony.build.line";
			int n = i + 1;
			source.sendSuccess(() -> Component.translatable(key, n, plan.target().toShortString(), left, plan.steps().size()), false);
		}
		return builds.size();
	}

	private static int copyBuild(CommandSourceStack source, BlockPos from, BlockPos to, BlockPos at) {
		if (!checkMayEdit(source, ColonyActions.Edit.BUILD_COPY)) {
			return 0;
		}
		BlockPos size = BlockPos.max(from, to).subtract(BlockPos.min(from, to)).offset(1, 1, 1);
		long volume = (long) size.getX() * size.getY() * size.getZ();
		if (volume > MAX_COPY_VOLUME) {
			source.sendFailure(Component.translatable("commands.antcraft.colony.build.too_big", volume, MAX_COPY_VOLUME));
			return 0;
		}
		Optional<Colony> colony = nearestColony(source);
		if (colony.isEmpty()) {
			return 0;
		}
		// A box holding only what follows from blocks outside it, such as a door's upper half, has nothing to build.
		Optional<BuildPlan> plan = RegionPlan.copy(source.getLevel()::getBlockState, from, to, at);
		if (plan.isEmpty()) {
			source.sendFailure(Component.translatable("commands.antcraft.colony.build.nothing_to_copy"));
			return 0;
		}
		colony.get().addBuild(plan.get());
		source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.build.copied", volume, at.toShortString(), colony.get().builds().size()), false);
		return 1;
	}

	/**
	 * Adds a build from a structure template: one bundled under {@code data/<namespace>/structure/}
	 * or one a structure block saved in the world. Block states only, like {@code build copy}.
	 */
	private static int loadBuild(CommandSourceStack source, Identifier id, BlockPos at, Rotation rotation, Mirror mirror, int palette) {
		if (!checkMayEdit(source, ColonyActions.Edit.BUILD_COPY)) {
			return 0;
		}
		Optional<StructureTemplate> template = source.getServer().getStructureTemplateManager().get(id);
		if (template.isEmpty()) {
			source.sendFailure(Component.translatable("commands.antcraft.colony.build.no_structure", id.toString()));
			return 0;
		}
		int palettes = RegionPlan.paletteCount(template.get());
		if (palette >= palettes) {
			source.sendFailure(Component.translatable("commands.antcraft.colony.build.no_palette", id.toString(), palette, palettes));
			return 0;
		}
		Vec3i size = template.get().getSize();
		long volume = (long) size.getX() * size.getY() * size.getZ();
		if (volume > MAX_COPY_VOLUME) {
			source.sendFailure(Component.translatable("commands.antcraft.colony.build.too_big", volume, MAX_COPY_VOLUME));
			return 0;
		}
		Optional<Colony> colony = nearestColony(source);
		if (colony.isEmpty()) {
			return 0;
		}
		Optional<BuildPlan> plan = RegionPlan.load(template.get(), at, mirror, rotation, palette);
		if (plan.isEmpty()) {
			source.sendFailure(Component.translatable("commands.antcraft.colony.build.nothing_to_copy"));
			return 0;
		}
		colony.get().addBuild(id.toString(), plan.get());
		source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.build.loaded", id.toString(), at.toShortString(), colony.get().builds().size()), false);
		return 1;
	}

	/**
	 * Has the colony level the box between {@code from} and {@code to} at height {@code y}: miners dig
	 * what is above it and builders fill the holes up to it (see {@link Terraform}). Shown and
	 * cancelled with the other builds.
	 */
	private static int terraform(CommandSourceStack source, BlockPos from, BlockPos to, int y) {
		if (!checkMayEdit(source, ColonyActions.Edit.TERRAFORM)) {
			return 0;
		}
		BlockPos size = BlockPos.max(from, to).subtract(BlockPos.min(from, to)).offset(1, 1, 1);
		long volume = (long) size.getX() * size.getY() * size.getZ();
		if (volume > MAX_COPY_VOLUME) {
			source.sendFailure(Component.translatable("commands.antcraft.colony.terraform.too_big", volume, MAX_COPY_VOLUME));
			return 0;
		}
		Optional<Colony> colony = nearestColony(source);
		if (colony.isEmpty()) {
			return 0;
		}
		// Outside the territory bots do not go, and trees there would be dug out rather than felled.
		if (!Territory.of(colony.get()).contains(from, to)) {
			source.sendFailure(Component.translatable("commands.antcraft.colony.terraform.outside", colony.get().radius()));
			return 0;
		}
		Optional<BuildPlan> plan = Terraform.plan(source.getLevel()::getBlockState, from, to, y);
		if (plan.isEmpty()) {
			source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.terraform.level", y), false);
			return 0;
		}
		Colony c = colony.get();
		c.addBuild(plan.get());
		int columns = size.getX() * size.getZ();
		source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.terraform.started", columns, y, plan.get().steps().size(), c.builds().size()), false);
		return 1;
	}

	/** Gives up on the build steps builders reported blocked, and the batches waiting on them; the rest of their plans goes on. */
	private static int clearBlocked(CommandSourceStack source) {
		if (!checkMayEdit(source, ColonyActions.Edit.CLEAR_BLOCKED)) {
			return 0;
		}
		Optional<Colony> colony = nearestColony(source);
		if (colony.isEmpty()) {
			return 0;
		}
		int count = colony.get().clearBlocked();
		source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.blocked.cleared", count), false);
		return count;
	}

	private static int cancelBuild(CommandSourceStack source, int n) {
		if (!checkMayEdit(source, ColonyActions.Edit.BUILD_CANCEL)) {
			return 0;
		}
		Optional<Colony> found = nearestColony(source);
		if (found.isEmpty()) {
			return 0;
		}
		Colony colony = found.get();
		List<BuildPlan> builds = colony.builds();
		if (n > builds.size()) {
			source.sendFailure(Component.translatable("commands.antcraft.colony.build.not_found", n));
			return 0;
		}
		BuildPlan plan = builds.get(n - 1);
		if (isMineshaft(colony, plan)) {
			source.sendFailure(Component.translatable("commands.antcraft.colony.build.is_mineshaft", n));
			return 0;
		}
		colony.removeBuild(plan);
		source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.build.cancelled", n, plan.target().toShortString()), false);
		return 1;
	}

	/** Lists the projects, highest priority first, with what each still waits for, as the last survey found. */
	private static int listProjects(CommandSourceStack source) {
		Optional<Colony> found = nearestColony(source);
		if (found.isEmpty()) {
			return 0;
		}
		Colony colony = found.get();
		List<Project> projects = colony.projects();
		if (projects.isEmpty()) {
			source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.project.empty"), false);
		}
		for (int i = 0; i < projects.size(); i++) {
			Project project = projects.get(i);
			List<BuildPlan> plans = colony.plans(project);
			long left = plans.stream().flatMap(plan -> plan.steps().stream()).filter(step -> !step.isDone(source.getLevel().getBlockState(step.pos()))).count();
			int n = i + 1;
			source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.project.line", n, Project.displayName(project.name()), project.priority(),
				plans.size(), left, names(colony.waitingFor(project.id()))), false);
		}
		return projects.size();
	}

	/** Project {@code n} of {@code colony project list}, unless there is none or it is the mineshaft, which is moved instead. */
	private static Optional<Project> project(CommandSourceStack source, Colony colony, int n) {
		List<Project> projects = colony.projects();
		if (n > projects.size()) {
			source.sendFailure(Component.translatable("commands.antcraft.colony.project.not_found", n));
			return Optional.empty();
		}
		Project project = projects.get(n - 1);
		if (colony.plans(project).stream().anyMatch(plan -> isMineshaft(colony, plan))) {
			source.sendFailure(Component.translatable("commands.antcraft.colony.project.is_mineshaft", n));
			return Optional.empty();
		}
		return Optional.of(project);
	}

	private static int cancelProject(CommandSourceStack source, int n) {
		if (!checkMayEdit(source, ColonyActions.Edit.PROJECT_CANCEL)) {
			return 0;
		}
		Optional<Colony> colony = nearestColony(source);
		Optional<Project> project = colony.flatMap(c -> project(source, c, n));
		if (project.isEmpty()) {
			return 0;
		}
		colony.get().cancelProject(project.get().id());
		source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.project.cancelled", n, Project.displayName(project.get().name())), false);
		return 1;
	}

	private static int setProjectPriority(CommandSourceStack source, int n, int priority) {
		if (!checkMayEdit(source, ColonyActions.Edit.PROJECT_PRIORITY)) {
			return 0;
		}
		Optional<Colony> colony = nearestColony(source);
		Optional<Project> project = colony.flatMap(c -> project(source, c, n));
		if (project.isEmpty()) {
			return 0;
		}
		colony.get().setPriority(project.get().id(), priority);
		source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.project.priority", n, Project.displayName(project.get().name()), priority), false);
		return 1;
	}

	/** A member's line in {@code colony info}: its name and the job it holds, or that it has none. */
	static Component botLine(ColonyStatus.Bot bot) {
		return Component.translatable("commands.antcraft.colony.bot", bot.name(), JobType.describe(bot.job()));
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		boolean allowed = AntCraftConfig.botsAllowed(source.getServer());
		int count = AntBot.countIn(source.getLevel());
		source.sendSuccess(() -> Component.translatable(
			"commands.antcraft.status", allowed ? "enabled" : "disabled", count, AntCraftConfig.maxBotsPerLevel()), false);
		return count;
	}

	private static int walkTo(CommandContext<CommandSourceStack> ctx, List<AntBot> bots) {
		CommandSourceStack source = ctx.getSource();
		if (!checkAllowed(source) || !checkAny(source, bots)) {
			return 0;
		}
		BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
		bots.forEach(bot -> bot.setWalkTarget(pos));
		source.sendSuccess(() -> Component.translatable("commands.antcraft.goto", bots.size(), pos.toShortString()), false);
		return bots.size();
	}

	private static int stop(CommandSourceStack source, List<AntBot> bots) {
		if (!checkAny(source, bots)) {
			return 0;
		}
		bots.forEach(bot -> bot.setWalkTarget(null));
		source.sendSuccess(() -> Component.translatable("commands.antcraft.stop", bots.size()), false);
		return bots.size();
	}

	private static boolean checkAllowed(CommandSourceStack source) {
		if (AntCraftConfig.botsAllowed(source.getServer())) {
			return true;
		}
		source.sendFailure(Component.translatable("message.antcraft.disabled"));
		return false;
	}

	/** The colony screen's rule for {@code edit} ({@link ColonyActions#mayEdit}), so the matching command follows it too. */
	private static boolean checkMayEdit(CommandSourceStack source, ColonyActions.Edit edit) {
		if (ColonyActions.mayEdit(edit, AntCraftConfig.botsAllowed(source.getServer()))) {
			return true;
		}
		source.sendFailure(Component.translatable("message.antcraft.disabled"));
		return false;
	}

	private static boolean checkAny(CommandSourceStack source, List<AntBot> bots) {
		if (!bots.isEmpty()) {
			return true;
		}
		source.sendFailure(Component.translatable("commands.antcraft.no_bots"));
		return false;
	}

	private static List<AntBot> nearestBot(CommandSourceStack source) {
		ServerLevel level = source.getLevel();
		Vec3 origin = source.getPosition();
		return level.getEntities(ModEntities.ANT_BOT, bot -> bot.isAlive() && bot.distanceToSqr(origin) <= NEAREST_RANGE * NEAREST_RANGE)
			.stream()
			.min(Comparator.comparingDouble(bot -> bot.distanceToSqr(origin)))
			.map(bot -> List.<AntBot>of(bot))
			.orElse(List.of());
	}

	private static List<AntBot> bots(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Collection<? extends Entity> entities = EntityArgument.getEntities(ctx, "bots");
		List<AntBot> bots = new ArrayList<>();
		for (Entity entity : entities) {
			if (entity instanceof AntBot bot) {
				bots.add(bot);
			}
		}
		return bots;
	}
}
