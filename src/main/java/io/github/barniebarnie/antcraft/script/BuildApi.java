package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.blueprint.Blueprint;
import io.github.barniebarnie.antcraft.blueprint.BlueprintSummary;
import io.github.barniebarnie.antcraft.blueprint.Blueprints;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotHands;
import io.github.barniebarnie.antcraft.entity.navigation.BlockBreaker;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.LoadedBlocks;
import io.github.barniebarnie.antcraft.script.lang.Args;
import io.github.barniebarnie.antcraft.script.lang.Natives;
import io.github.barniebarnie.antcraft.script.lang.PyDict;
import io.github.barniebarnie.antcraft.script.lang.PyList;
import io.github.barniebarnie.antcraft.script.lang.PyNone;
import io.github.barniebarnie.antcraft.script.lang.PyTuple;
import io.github.barniebarnie.antcraft.script.lang.Values;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.RegionPlan;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/**
 * {@code build(blueprint, at, turn=0, mirror=False)} and the sense {@code blueprint(id)} (docs/scripting.md, Actions and
 * Senses): a blueprint built from what the bot carries, step by step as {@link BuildPlan} orders it.
 */
public final class BuildApi {
	private BuildApi() {
	}

	static void register(Natives natives, ScriptRun run) {
		natives.register("blueprint", 30, call -> {
			Object[] args = Args.bind("blueprint", call, 1, "id", "at", "turn", "mirror");
			Object id = args[0];
			Pos at = Args.absent(args[1]) || args[1] == PyNone.NONE ? null : Pos.of(args[1]);
			long turn = Args.absent(args[2]) ? 0 : Args.integer(args[2]);
			boolean mirror = !Args.absent(args[3]) && Values.truthy(args[3]);
			AntBot bot = WorkApi.bot(run, call);
			Identifier parsed = Identifier.tryParse(Values.str(id));
			Optional<Blueprint> blueprint = parsed == null ? Optional.empty() : Blueprints.get(parsed);
			if (blueprint.isEmpty()) {
				return PyNone.NONE;
			}
			Optional<StructureTemplate> template = ((ServerLevel) bot.level()).getServer().getStructureTemplateManager().get(blueprint.get().structure());
			return describe(blueprint.get(), BlueprintSummary.of(parsed, blueprint.get(), template), at, turn, mirror);
		});
		natives.register("build", call -> {
			Object[] args = Args.bind("build", call, 2, "blueprint", "at", "turn", "mirror");
			String id = Values.str(args[0]);
			Pos at = Pos.of(args[1]);
			long turn = Args.absent(args[2]) ? 0 : Args.integer(args[2]);
			boolean mirror = !Args.absent(args[3]) && Values.truthy(args[3]);
			AntBot bot = WorkApi.bot(run, call);
			String describe = "build(" + Values.repr(id) + ", " + at + (turn == 0 ? "" : ", turn=" + turn) + (mirror ? ", mirror=True" : "") + ")";
			Optional<BuildPlan> plan = plan((ServerLevel) bot.level(), id, WorkApi.blockPos(at), turn, mirror);
			if (plan.isEmpty()) {
				return run.done(describe, Result.fail(Result.NO_BLUEPRINT));
			}
			return run.start(new Build(run, bot, plan.get(), at, describe), call, at);
		});
	}

	/** The plan of blueprint {@code id} with its lowest corner at {@code at}, mirrored if asked, then turned {@code turn} quarters clockwise. */
	// ponytail: planned in one tick; spread over ticks as the path budget does if large blueprints show in the tick time.
	private static Optional<BuildPlan> plan(ServerLevel level, String id, BlockPos at, long turn, boolean mirror) {
		Identifier parsed = Identifier.tryParse(id);
		Optional<Blueprint> blueprint = parsed == null ? Optional.empty() : Blueprints.get(parsed);
		Rotation rotation = Rotation.values()[(int) Math.floorMod(turn, 4)];
		return blueprint.flatMap(found -> level.getServer().getStructureTemplateManager().get(found.structure())
			.flatMap(template -> RegionPlan.load(template, at, mirror ? Mirror.LEFT_RIGHT : Mirror.NONE, rotation, found.palette())));
	}

	/** {@code blueprint(id)}: its size, role, materials and spots relative to its origin, as plain data. */
	static PyDict describe(Blueprint blueprint, BlueprintSummary summary) {
		return describe(blueprint, summary, null, 0, false);
	}

	/**
	 * {@code blueprint(id, at, turn, mirror)}: as {@link #describe(Blueprint, BlueprintSummary)}, with the spots where a copy
	 * built at {@code at} (if not null) turned and mirrored as {@code build} does has them, in {@code at}'s dimension.
	 */
	static PyDict describe(Blueprint blueprint, BlueprintSummary summary, @Nullable Pos at, long turn, boolean mirror) {
		PyDict dict = new PyDict();
		dict.put("size", PyTuple.of((long) summary.size().getX(), (long) summary.size().getY(), (long) summary.size().getZ()));
		dict.put("role", blueprint.role().<Object>map(Blueprint.Role::getSerializedName).orElse(PyNone.NONE));
		PyDict materials = new PyDict();
		summary.materials().forEach((item, count) -> materials.put(BuiltInRegistries.ITEM.getKey(item).toString(), (long) count));
		dict.put("materials", materials);
		if (at == null) {
			dict.put("chests", spots(blueprint.chests(), null));
			dict.put("work_spots", spots(blueprint.workSpots(), null));
		} else {
			BlockPos origin = WorkApi.blockPos(at);
			Mirror mirrored = mirror ? Mirror.LEFT_RIGHT : Mirror.NONE;
			Rotation rotation = Rotation.values()[(int) Math.floorMod(turn, 4)];
			dict.put("chests", spots(blueprint.chestsAt(summary.size(), origin, mirrored, rotation), at.dimension()));
			dict.put("work_spots", spots(blueprint.workSpotsAt(summary.size(), origin, mirrored, rotation), at.dimension()));
		}
		return dict;
	}

	private static PyList spots(List<BlockPos> spots, @Nullable String dimension) {
		PyList list = new PyList();
		spots.forEach(spot -> list.items.add(new Pos(spot.getX(), spot.getY(), spot.getZ(), dimension)));
		return list;
	}

	/**
	 * Works through the plan's steps in order: walks into reach of the next one the world does not match yet, breaks what is
	 * in its way and places its block from what the bot carries. The world holds the progress, so a build called again, or
	 * resumed after a save, goes on where it stopped; its value, the blocks placed, is saved as its progress. Where no walk
	 * reaches a block, it stands on blocks already built or pillars up with the scaffold blocks it takes down by hand
	 * (dirt), never where the blueprint puts a block, and once the blueprint stands it takes that scaffold down again.
	 */
	// ponytail: no scooping fluids out of the way and no stand-ins; the old BuilderGoal had them, add back when a blueprint needs them.
	// ponytail: the scaffold to take down is not saved, and comes down only once the build is done: a build resumed after
	// a save, or one that stops short, leaves its scaffold up. Save it with the progress if that shows.
	private static final class Build implements ScriptRun.Action {
		/** How long the bot steps back to the middle of its block when it stands over the edge of the spot it places in. */
		private static final int NUDGE_TICKS = 10;
		private final ScriptRun run;
		private final AntBot bot;
		private final ServerLevel level;
		private final BuildPlan plan;
		private final Pos at;
		private final String describe;
		private final BuildPlan.Progress progress = new BuildPlan.Progress();
		private final BlockBreaker breaker;
		private ScriptRun.@Nullable Action walk;
		/** The block last walked to, so a bot that walked there and is still out of reach gives up. */
		private @Nullable BlockPos walked;
		private @Nullable BlockPos breaking;
		private boolean unloaded;
		private int nudged;
		/** Every spot the blueprint puts a block or air in, where no scaffold goes. */
		private final Set<BlockPos> spots = new HashSet<>();
		/** The bot's scaffold already up when the build started, not the build's to take down. */
		private final Set<BlockPos> before;
		/** The scaffold blocks the build's walks placed. */
		private final Set<BlockPos> scaffold = new HashSet<>();
		/** Whether the blueprint stands and the build is taking its scaffold down. */
		private boolean takingDown;
		private long placed;
		private @Nullable Result result;

		Build(ScriptRun run, AntBot bot, BuildPlan plan, Pos at, String describe) {
			this.run = run;
			this.bot = bot;
			this.level = (ServerLevel) bot.level();
			this.plan = plan;
			this.at = at;
			this.describe = describe;
			this.breaker = new BlockBreaker(bot);
			for (BuildPlan.Step step : plan.steps()) {
				this.spots.add(step.pos());
				step.otherHalf().ifPresent(half -> this.spots.add(half.pos()));
			}
			this.before = bot.getProtectedScaffold();
		}

		/** The block at {@code pos}, or air for one not loaded, noting that the plan could not be read. */
		private BlockState state(BlockPos pos) {
			BlockState state = new LoadedBlocks(this.level).loaded(pos);
			if (state == null) {
				this.unloaded = true;
				return Blocks.AIR.defaultBlockState();
			}
			return state;
		}

		@Override
		public boolean tick() {
			if (this.result != null) {
				return true;
			}
			if (this.walk != null) {
				for (BlockPos pos : this.bot.getProtectedScaffold()) {
					if (!this.before.contains(pos)) {
						this.scaffold.add(pos);
					}
				}
				if (!this.walk.tick()) {
					return false;
				}
				Result walked = (Result) this.walk.result();
				this.walk = null;
				if (this.takingDown && !walked.ok()) {
					this.scaffold.remove(this.walked); // ponytail: scaffold no walk reaches stays up
				} else if (!walked.ok()) {
					return this.finish(Result.UNREACHABLE.equals(walked.reason()) ? Result.BLOCKED : walked.reason());
				}
			}
			if (this.breaking != null) {
				if (!this.breaker.tick(this.level, this.breaking, true)) {
					return false;
				}
				this.breaking = null;
			}
			this.unloaded = false;
			Optional<BuildPlan.Step> next = this.plan.nextStep(this::state, step -> step.isPlaceable() && !this.plan.waitsForWalls(this::state, step), this.progress);
			if (this.unloaded) {
				return this.walkTo(this.at, null);
			}
			if (next.isEmpty()) {
				return this.plan.nextStep(this::state).isEmpty() ? this.takeDown() : this.finish(Result.BLOCKED);
			}
			BuildPlan.Step step = next.get();
			BlockState current = this.state(step.pos());
			if (step.needsClearing(current)) {
				return this.clear(step.pos(), current);
			}
			Optional<BuildPlan.Step> half = step.otherHalf().filter(other -> !step.isPlaced(current) && other.needsClearing(this.state(other.pos())));
			if (half.isPresent()) {
				return this.clear(half.get().pos(), this.state(half.get().pos()));
			}
			Item item = step.item(current);
			if (item != Items.AIR && !BotHands.equip(this.bot, stack -> stack.is(item))) {
				return this.finish(Result.MISSING_INGREDIENTS);
			}
			if (BotHands.isProtected(this.level, step.pos())) {
				return this.finish(Result.PROTECTED);
			}
			if (!this.inReach(step.pos())) {
				return this.walkTo(EntityInfo.pos(step.pos()), step.pos());
			}
			BlockState state = step.isPlaced(current) ? current.setValue(BlockStateProperties.WATERLOGGED, step.isWaterlogged()) : step.dry();
			if (!step.isPlaced(current) && !this.level.isUnobstructed(state, step.pos(), CollisionContext.empty())) {
				// Only in its own way, standing off the middle of its block over the edge of the spot: it steps back first.
				if (this.nudged++ < NUDGE_TICKS && this.level.isUnobstructed(this.bot, state.getCollisionShape(this.level, step.pos()).move(step.pos()))) {
					this.bot.getMoveControl().setWantedPosition(this.bot.getBlockX() + 0.5, this.bot.getY(), this.bot.getBlockZ() + 0.5, 1.0);
					return false;
				}
				return this.finish(Result.BLOCKED);
			}
			this.nudged = 0;
			step.otherHalf().ifPresent(other -> this.level.setBlockAndUpdate(other.pos(), other.state()));
			this.level.setBlockAndUpdate(step.pos(), step.isPlaced(current) ? state : Block.updateFromNeighbourShapes(state, this.level, step.pos()));
			this.bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			if (item instanceof BucketItem) {
				this.bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(item == Items.BUCKET ? Items.WATER_BUCKET : Items.BUCKET));
			} else if (item != Items.FLINT_AND_STEEL) {
				this.bot.getMainHandItem().shrink(1);
			}
			if (!step.isPlaced(current)) {
				this.placed++;
			}
			return false;
		}

		/** Takes the scaffold down, the highest block first, so a pillar comes down under the bot; then the build is done. */
		private boolean takeDown() {
			this.takingDown = true;
			this.scaffold.removeIf(pos -> !this.state(pos).getBlock().asItem().getDefaultInstance().is(PathFollower.FAST_SCAFFOLD_BLOCKS));
			Optional<BlockPos> top = this.scaffold.stream().max(Comparator.comparingInt(BlockPos::getY));
			if (top.isEmpty()) {
				return this.finish(null);
			}
			if (!this.inReach(top.get())) {
				if (top.get().equals(this.walked)) {
					this.scaffold.remove(top.get());
					return false;
				}
				return this.walkTo(EntityInfo.pos(top.get()), top.get());
			}
			this.breaking = top.get();
			return false;
		}

		private boolean clear(BlockPos pos, BlockState current) {
			if (BotHands.isProtected(this.level, pos) || current.getDestroySpeed(this.level, pos) < 0) {
				return this.finish(Result.PROTECTED);
			}
			if (!current.getFluidState().isEmpty() && current.liquid()) {
				return this.finish(Result.BLOCKED);
			}
			if (!this.inReach(pos)) {
				return this.walkTo(EntityInfo.pos(pos), pos);
			}
			this.breaking = pos;
			return false;
		}

		private boolean inReach(BlockPos pos) {
			return this.run.host().body().inReach(EntityInfo.pos(pos));
		}

		/** Walks into reach of {@code target}; when it already walked to {@code pos} and is still not there, gives up. */
		private boolean walkTo(Pos target, @Nullable BlockPos pos) {
			if (pos == null ? this.walked != null && this.walked.equals(WorkApi.blockPos(this.at)) : pos.equals(this.walked)) {
				return this.finish(pos == null ? Result.NOT_LOADED : Result.BLOCKED);
			}
			this.walked = pos == null ? WorkApi.blockPos(this.at) : pos;
			this.run.host().body().setBuildScaffold(this.takingDown ? spot -> false : spot -> !this.spots.contains(WorkApi.blockPos(spot)));
			this.walk = MoveApi.reach(this.run.host(), target);
			return false;
		}

		private boolean finish(@Nullable String reason) {
			this.run.host().body().setBuildScaffold(null);
			this.result = reason == null ? Result.ok(this.placed) : Result.fail(reason, this.placed);
			return true;
		}

		@Override
		public Object result() {
			return this.result == null ? Result.fail(Result.STOPPED, this.placed) : this.result;
		}

		@Override
		public boolean walks() {
			return this.walk != null;
		}

		@Override
		public void cancel() {
			if (this.walk != null) {
				this.walk.cancel();
				this.walk = null;
			}
			this.breaker.reset();
			this.run.host().body().setBuildScaffold(null);
		}

		@Override
		public long progress() {
			return this.placed;
		}

		@Override
		public void restore(long progress) {
			this.placed = progress;
		}

		@Override
		public String describe() {
			return this.describe;
		}
	}
}
