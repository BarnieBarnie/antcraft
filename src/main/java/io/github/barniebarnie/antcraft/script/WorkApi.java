package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotHands;
import io.github.barniebarnie.antcraft.entity.Tools;
import io.github.barniebarnie.antcraft.entity.navigation.BlockBreaker;
import io.github.barniebarnie.antcraft.pathing.LoadedBlocks;
import io.github.barniebarnie.antcraft.script.lang.Args;
import io.github.barniebarnie.antcraft.script.lang.NativeCall;
import io.github.barniebarnie.antcraft.script.lang.Natives;
import io.github.barniebarnie.antcraft.script.lang.ScriptError;
import io.github.barniebarnie.antcraft.script.lang.Values;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The actions of the bot API that change the world (docs/scripting.md, Actions): {@code mine}, {@code place},
 * {@code pick_up}, {@code equip}, {@code use} and {@code drop}. Those that work on a block first walk into reach of it, as
 * {@code goto} walks ({@link MoveApi#reach}); each returns a {@link Result}.
 */
public final class WorkApi {
	/** The largest radius {@code pick_up} looks in. */
	public static final int MAX_PICK_UP_RADIUS = 16;

	private WorkApi() {
	}

	static void register(Natives natives, ScriptRun run) {
		natives.register("mine", call -> {
			Pos target = Pos.of(Args.bind("mine", call, 1, "pos")[0]);
			return run.start(new Mine(run, bot(run, call), target), call);
		});
		natives.register("place", call -> {
			Object[] args = Args.bind("place", call, 2, "pos", "item", "facing");
			Pos target = Pos.of(args[0]);
			Selector item = Selector.parse(args[1]);
			Direction facing = Args.absent(args[2]) ? null : facing(args[2]);
			return run.start(new Place(run, bot(run, call), target, item, facing), call);
		});
		natives.register("use", call -> {
			Object[] args = Args.bind("use", call, 1, "pos", "item");
			Pos target = Pos.of(args[0]);
			Selector item = Args.absent(args[1]) ? null : Selector.parse(args[1]);
			return run.start(new Use(run, bot(run, call), target, item), call);
		});
		natives.register("equip", 15, call -> {
			Selector item = Selector.parse(Args.bind("equip", call, 1, "item")[0]);
			boolean held = BotHands.equip(bot(run, call), item.items());
			return run.done("equip(" + item + ")", held ? Result.OK : Result.fail(Result.NO_ITEM));
		});
		natives.register("drop", 20, call -> {
			Object[] args = Args.bind("drop", call, 1, "item", "n");
			Selector item = Selector.parse(args[0]);
			long n = Args.absent(args[1]) ? Long.MAX_VALUE : Args.integer(args[1]);
			if (n < 1) {
				throw new ScriptError("drop() n must be at least 1, not " + n);
			}
			long dropped = BotHands.drop(bot(run, call), item.items(), n);
			return run.done("drop(" + item + ")", dropped == 0 ? Result.fail(Result.NO_ITEM) : Result.ok(dropped));
		});
		natives.register("pick_up", call -> {
			Object[] args = Args.bind("pick_up", call, 0, "what", "radius");
			Predicate<ItemStack> item = Args.absent(args[0])
				? stack -> true : Selector.parse(args[0]).items();
			long radius = Args.absent(args[1]) ? 8 : Args.integer(args[1]);
			if (radius < 0 || radius > MAX_PICK_UP_RADIUS) {
				throw new ScriptError("radius must be from 0 to " + MAX_PICK_UP_RADIUS + ", not " + radius);
			}
			StringJoiner given = new StringJoiner(", ", "pick_up(", ")");
			if (!Args.absent(args[0])) {
				given.add(Values.repr(args[0]));
			}
			if (!Args.absent(args[1])) {
				given.add("radius=" + radius);
			}
			return run.start(new PickUp(run, bot(run, call), item, (int) radius, given.toString()), call);
		});
	}

	static AntBot bot(ScriptRun run, NativeCall call) {
		AntBot bot = run.host().bot();
		if (bot == null || !(bot.level() instanceof ServerLevel)) {
			throw new ScriptError(call.name() + "() needs a bot in a world");
		}
		return bot;
	}

	/** A facing argument: {@code "north"}, {@code "south"}, {@code "east"}, {@code "west"}, {@code "up"} or {@code "down"}. */
	static Direction facing(Object value) {
		if (!(value instanceof String name)) {
			throw new ScriptError("facing must be a str, not '" + Values.typeName(value) + "'");
		}
		return switch (name) {
			case "north" -> Direction.NORTH;
			case "south" -> Direction.SOUTH;
			case "east" -> Direction.EAST;
			case "west" -> Direction.WEST;
			case "up" -> Direction.UP;
			case "down" -> Direction.DOWN;
			default -> throw new ScriptError("facing must be north, south, east, west, up or down, not " + Values.repr(name));
		};
	}

	static BlockPos blockPos(Pos pos) {
		return new BlockPos(clamp(pos.x()), clamp(pos.y()), clamp(pos.z()));
	}

	private static int clamp(long value) {
		return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
	}

	/**
	 * An action on one block: it checks what it can before walking ({@link #before}), walks into reach if the bot is
	 * not ({@code out_of_reach} when it cannot get there), then works on the block a tick at a time ({@link #work}),
	 * checking the block each tick ({@link #check}).
	 */
	abstract static class OnBlock implements ScriptRun.Action {
		final ScriptRun run;
		final AntBot bot;
		final ServerLevel level;
		final Pos target;
		final BlockPos pos;
		private ScriptRun.@Nullable Action walk;
		private boolean walked;
		private @Nullable Result result;

		OnBlock(ScriptRun run, AntBot bot, Pos target) {
			this.run = run;
			this.bot = bot;
			this.level = (ServerLevel) bot.level();
			this.target = target;
			this.pos = blockPos(target);
		}

		/** What fails before the block is read, such as {@code no_item}; null to go on. */
		@Nullable Result before() {
			return BotHands.isProtected(this.level, this.pos) ? Result.fail(Result.PROTECTED) : null;
		}

		/** What fails on the block as it is now, such as {@code target_gone}; null to go on. */
		@Nullable Result check(BlockState state) {
			return null;
		}

		/** A tick of work on the block, in reach; the result once done, null while it goes on. */
		abstract @Nullable Result work(BlockState state);

		@Override
		public final boolean tick() {
			if (this.result != null) {
				return true;
			}
			if (this.walk != null) {
				if (!this.walk.tick()) {
					return false;
				}
				Result walked = (Result) this.walk.result();
				this.walk = null;
				this.walked = true;
				if (!walked.ok()) {
					return this.finish(Result.UNREACHABLE.equals(walked.reason()) ? Result.fail(Result.OUT_OF_REACH) : walked);
				}
			}
			Result early = this.before();
			if (early != null) {
				return this.finish(early);
			}
			BlockState state = new LoadedBlocks(this.level).loaded(this.pos);
			if (state != null) {
				Result failed = this.check(state);
				if (failed != null) {
					return this.finish(failed);
				}
			}
			if (state == null || !this.run.host().body().inReach(this.target)) {
				if (this.walked) {
					return this.finish(Result.fail(state == null ? Result.NOT_LOADED : Result.OUT_OF_REACH));
				}
				this.walk = MoveApi.reach(this.run.host(), this.target);
				return this.tick();
			}
			Result done = this.work(state);
			return done != null && this.finish(done);
		}

		private boolean finish(Result result) {
			this.result = result;
			return true;
		}

		@Override
		public Object result() {
			return this.result == null ? Result.fail(Result.STOPPED) : this.result;
		}

		@Override
		public void cancel() {
			if (this.walk != null) {
				this.walk.cancel();
				this.walk = null;
			}
			this.stopWork();
		}

		/** Lets go of what the work holds, when the action is cancelled. */
		void stopWork() {
		}
	}

	/** {@code mine(pos)}: breaks the block with the best tool the bot carries, its drops going into the bot's inventory. */
	private static final class Mine extends OnBlock {
		private final BlockBreaker breaker;

		Mine(ScriptRun run, AntBot bot, Pos target) {
			super(run, bot, target);
			this.breaker = new BlockBreaker(bot);
		}

		@Override
		@Nullable Result check(BlockState state) {
			if (state.isAir() || state.liquid()) {
				return Result.fail(Result.TARGET_GONE);
			}
			if (state.getDestroySpeed(this.level, this.pos) < 0) {
				return Result.fail(Result.PROTECTED);
			}
			ItemStack tool = Tools.bestToolFor(this.bot.getInventory(), this.bot.getMainHandItem(), state);
			return Tools.canHarvest(tool, state) ? null : Result.fail(Result.NO_TOOL);
		}

		@Override
		@Nullable Result work(BlockState state) {
			if (!this.breaker.tick(this.level, this.pos, true)) {
				return null;
			}
			return this.breaker.spilled() ? Result.fail(Result.NO_SPACE) : Result.OK;
		}

		@Override
		void stopWork() {
			this.breaker.reset();
		}

		@Override
		public String describe() {
			return "mine(" + this.target + ")";
		}
	}

	/** {@code place(pos, item, facing=None)}: holds a block item {@code item} selects and places it. */
	private static final class Place extends OnBlock {
		private final Selector selector;
		private final Predicate<ItemStack> item;
		private final @Nullable Direction facing;

		Place(ScriptRun run, AntBot bot, Pos target, Selector selector, @Nullable Direction facing) {
			super(run, bot, target);
			this.selector = selector;
			Predicate<ItemStack> selected = selector.items();
			this.item = stack -> selected.test(stack) && stack.getItem() instanceof BlockItem;
			this.facing = facing;
		}

		@Override
		@Nullable Result before() {
			return BotHands.find(this.bot, this.item) == null ? Result.fail(Result.NO_ITEM) : super.before();
		}

		@Override
		@Nullable Result check(BlockState state) {
			return state.canBeReplaced() ? null : Result.fail(Result.BLOCKED);
		}

		@Override
		Result work(BlockState state) {
			BotHands.equip(this.bot, this.item);
			return BotHands.place(this.bot, this.pos, this.facing) ? Result.OK : Result.fail(Result.BLOCKED);
		}

		@Override
		public String describe() {
			return "place(" + this.target + ", " + this.selector + (this.facing == null ? "" : ", facing='" + this.facing.getSerializedName() + "'") + ")";
		}
	}

	/** {@code use(pos, item=None)}: uses the held item, or first holds one {@code item} selects, on the block. */
	private static final class Use extends OnBlock {
		private final @Nullable Selector selector;
		private final @Nullable Predicate<ItemStack> item;

		Use(ScriptRun run, AntBot bot, Pos target, @Nullable Selector selector) {
			super(run, bot, target);
			this.selector = selector;
			this.item = selector == null ? null : selector.items();
		}

		@Override
		@Nullable Result before() {
			return this.item != null && BotHands.find(this.bot, this.item) == null ? Result.fail(Result.NO_ITEM) : super.before();
		}

		@Override
		Result work(BlockState state) {
			if (this.item != null) {
				BotHands.equip(this.bot, this.item);
			}
			return BotHands.use(this.bot, this.pos) ? Result.OK : Result.fail(Result.BLOCKED);
		}

		@Override
		public String describe() {
			return "use(" + this.target + (this.selector == null ? "" : ", " + this.selector) + ")";
		}
	}

	/**
	 * {@code pick_up(what=None, radius=8)}: walks to each dropped item {@code what} selects within {@code radius} of where
	 * the bot stood, nearest first, and takes it. Its value is how many items it took.
	 */
	private static final class PickUp implements ScriptRun.Action {
		/** How far beyond the bot's box it takes items from: a little more than a player's pickup reach sideways. */
		private static final double GRAB = 1.0;

		private final ScriptRun run;
		private final AntBot bot;
		private final Predicate<ItemStack> item;
		private final String describe;
		private final Vec3 center;
		private final int radius;
		/** Items the bot could not walk to. */
		private final Set<Integer> unreachable = new HashSet<>();
		private @Nullable ItemEntity goal;
		private ScriptRun.@Nullable Action walk;
		/** How often the bot got to the current goal without taking it: it walks right onto it the second time. */
		private int arrivals;
		/** How many of the items the bot carried when it started, to tell how many it took, also by walking over them. */
		private final long before;
		private long taken;
		private @Nullable Result result;

		PickUp(ScriptRun run, AntBot bot, Predicate<ItemStack> item, int radius, String describe) {
			this.run = run;
			this.bot = bot;
			this.item = item;
			this.radius = radius;
			this.describe = describe;
			this.center = bot.position();
			this.before = BotHands.count(bot, item);
		}

		private List<ItemEntity> around() {
			double r2 = (double) this.radius * this.radius;
			return this.bot.level().getEntitiesOfClass(ItemEntity.class, new AABB(this.center, this.center).inflate(this.radius),
				entity -> entity.isAlive() && entity.distanceToSqr(this.center) <= r2 && this.item.test(entity.getItem()));
		}

		@Override
		public boolean tick() {
			if (this.result != null) {
				return true;
			}
			AABB grab = this.bot.getBoundingBox().inflate(GRAB, 0.5, GRAB);
			List<ItemEntity> around = this.around();
			for (ItemEntity entity : around) {
				if (entity.getBoundingBox().intersects(grab)) {
					BotHands.collect(this.bot, entity);
				}
			}
			this.taken = Math.max(0, BotHands.count(this.bot, this.item) - this.before);
			around = around.stream().filter(ItemEntity::isAlive).toList();
			List<ItemEntity> fitting = around.stream()
				.filter(entity -> !this.unreachable.contains(entity.getId()) && this.bot.getInventory().canAddItem(entity.getItem()))
				.sorted(Comparator.comparingDouble(entity -> entity.distanceToSqr(this.bot)))
				.toList();
			if (fitting.isEmpty()) {
				return this.finish(around);
			}
			if (this.goal == null || !fitting.contains(this.goal)) {
				this.stopWalk();
				this.goal = fitting.getFirst();
				this.arrivals = 0;
			}
			if (this.walk == null) {
				if (this.arrivals > 0 && (this.goal.hasPickUpDelay() || !this.goal.onGround())) {
					return false; // next to it, waiting for it to land and for its pickup delay to end
				}
				if (this.arrivals >= 2) {
					this.unreachable.add(this.goal.getId());
					this.goal = null;
					return false;
				}
				this.walk = MoveApi.walk(this.run.host(), EntityInfo.pos(this.goal.blockPosition()), this.arrivals == 0 ? 1 : 0);
			}
			if (!this.walk.tick()) {
				return false;
			}
			Result walked = (Result) this.walk.result();
			this.walk = null;
			if (walked.ok()) {
				this.arrivals++;
			} else if (Result.UNREACHABLE.equals(walked.reason())) {
				this.unreachable.add(this.goal.getId());
				this.goal = null;
			} else {
				this.result = Result.fail(walked.reason(), this.taken);
				return true;
			}
			return false;
		}

		/**
		 * Done: no item left that fits and can be reached, of the matching ones {@code around}. Items it could not walk to
		 * only fail the action when it took none.
		 */
		private boolean finish(List<ItemEntity> around) {
			this.stopWalk();
			if (around.stream().anyMatch(entity -> !this.unreachable.contains(entity.getId()))) {
				this.result = Result.fail(Result.NO_SPACE, this.taken);
			} else if (!around.isEmpty() && this.taken == 0) {
				this.result = Result.fail(Result.OUT_OF_REACH, this.taken);
			} else {
				this.result = this.taken == 0 ? Result.fail(Result.TARGET_GONE, this.taken) : Result.ok(this.taken);
			}
			return true;
		}

		private void stopWalk() {
			if (this.walk != null) {
				this.walk.cancel();
				this.walk = null;
			}
		}

		@Override
		public Object result() {
			return this.result == null ? Result.fail(Result.STOPPED, this.taken) : this.result;
		}

		@Override
		public void cancel() {
			this.stopWalk();
		}

		@Override
		public String describe() {
			return this.describe;
		}
	}
}
