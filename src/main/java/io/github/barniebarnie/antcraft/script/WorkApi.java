package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotHands;
import io.github.barniebarnie.antcraft.entity.Bow;
import io.github.barniebarnie.antcraft.entity.KiteGoal;
import io.github.barniebarnie.antcraft.entity.Tools;
import io.github.barniebarnie.antcraft.entity.navigation.BlockBreaker;
import io.github.barniebarnie.antcraft.work.Mining;
import io.github.barniebarnie.antcraft.pathing.LevelWorldView;
import io.github.barniebarnie.antcraft.pathing.LoadedBlocks;
import io.github.barniebarnie.antcraft.script.lang.Args;
import io.github.barniebarnie.antcraft.script.lang.NativeCall;
import io.github.barniebarnie.antcraft.script.lang.Natives;
import io.github.barniebarnie.antcraft.script.lang.PyNone;
import io.github.barniebarnie.antcraft.script.lang.ScriptError;
import io.github.barniebarnie.antcraft.script.lang.Values;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.food.FoodConstants;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * The actions of the bot API that change the world (docs/reference/index.md, Working blocks): {@code mine}, {@code place},
 * {@code pick_up}, {@code equip}, {@code use}, {@code drop}, {@code eat}, {@code attack} and {@code shoot}. Those that work on a block first walk into reach of it, as
 * {@code goto} walks ({@link MoveApi#reach}); each returns a {@link Result}.
 */
public final class WorkApi {
	/** The largest radius {@code pick_up} looks in. */
	public static final int MAX_PICK_UP_RADIUS = 16;

	private WorkApi() {
	}

	static void register(Natives natives, ScriptRun run) {
		natives.register("mine", call -> {
			Object[] args = Args.bind("mine", call, 1, "pos", "hand");
			Pos target = Pos.of(args[0]);
			boolean hand = !Args.absent(args[1]) && Values.truthy(args[1]);
			return run.start(new Mine(run, bot(run, call), target, hand), call, target);
		});
		natives.register("place", call -> {
			Object[] args = Args.bind("place", call, 2, "pos", "item", "facing", "half");
			Pos target = Pos.of(args[0]);
			Selector item = Selector.parse(args[1]);
			Direction facing = Args.absent(args[2]) ? null : facing(args[2]);
			Half half = Args.absent(args[3]) ? null : half(args[3]);
			return run.start(new Place(run, bot(run, call), target, item, facing, half), call, target);
		});
		natives.register("use", call -> {
			Object[] args = Args.bind("use", call, 1, "pos", "item", "face");
			Selector item = Args.absent(args[1]) ? null : Selector.parse(args[1]);
			Direction face = Args.absent(args[2]) ? null : facing(args[2]);
			if (args[0] instanceof EntityInfo info) {
				// On a mob, as a player's right click on it: feeding, shearing, milking (#837).
				AntBot bot = bot(run, call);
				UseOn use = new UseOn(run, bot, info, item);
				@Nullable Entity entity = info.entity();
				@Nullable Result refused = entity == null || !entity.isAlive() || !(entity instanceof LivingEntity) ? Result.fail(Result.TARGET_GONE)
					: entity.level() != bot.level() ? Result.fail(Result.WRONG_DIMENSION)
					: entity == bot ? Result.fail(Result.PROTECTED)
					: use.item != null && BotHands.find(bot, use.item) == null ? Result.fail(Result.NO_ITEM) : null;
				return refused != null ? run.done(use.describe(), refused) : run.start(use, call);
			}
			Pos target = Pos.of(args[0]);
			return run.start(new Use(run, bot(run, call), target, item, face), call, target);
		});
		natives.register("equip", 15, call -> {
			Selector item = Selector.parse(Args.bind("equip", call, 1, "item")[0]);
			boolean held = BotHands.equip(bot(run, call), item.items());
			return run.done("equip(" + item + ")", held ? Result.OK : Result.fail(Result.NO_ITEM));
		});
		natives.register("eat", call -> {
			Eat eat = new Eat(bot(run, call), Selector.parse(Args.bind("eat", call, 1, "item")[0]));
			Result refused = eat.check();
			return refused != null ? run.done(eat.describe(), refused) : run.start(eat, call);
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
		natives.register("attack", call -> {
			Object[] args = Args.bind("attack", call, 1, "entity", "min_health");
			if (!(args[0] instanceof EntityInfo info)) {
				throw new ScriptError("attack() takes an Entity, not '" + Values.typeName(args[0]) + "'");
			}
			long minHealth = Args.absent(args[1]) ? 0 : Args.integer(args[1]);
			AntBot bot = bot(run, call);
			Attack attack = new Attack(run, bot, info, minHealth);
			@Nullable Result refused = refuse(bot, info.entity());
			if (refused != null) {
				return run.done(attack.describe(), refused);
			}
			// A creeper is safer shot than kited, where the bot carries a bow and arrows (#707).
			return run.start(info.entity() instanceof Creeper && Bow.armed(bot) ? new Shoot(run, bot, info, minHealth) : attack, call);
		});
		natives.register("shoot", call -> {
			Object[] args = Args.bind("shoot", call, 1, "entity", "min_health");
			if (!(args[0] instanceof EntityInfo info)) {
				throw new ScriptError("shoot() takes an Entity, not '" + Values.typeName(args[0]) + "'");
			}
			long minHealth = Args.absent(args[1]) ? 0 : Args.integer(args[1]);
			AntBot bot = bot(run, call);
			Shoot shoot = new Shoot(run, bot, info, minHealth);
			@Nullable Result refused = refuse(bot, info.entity());
			if (refused == null && BotHands.find(bot, Bow.WEAPON) == null) {
				refused = Result.fail(Result.NO_TOOL);
			}
			if (refused == null && BotHands.find(bot, Bow.ARROWS) == null) {
				refused = Result.fail(Result.NO_ITEM);
			}
			return refused != null ? run.done(shoot.describe(), refused) : run.start(shoot, call);
		});
	}

	/** Why {@code bot} may not fight {@code entity} at all ({@code attack}, {@code shoot}), or null if it may. */
	private static @Nullable Result refuse(AntBot bot, @Nullable Entity entity) {
		if (entity == null || !entity.isAlive()) {
			return Result.fail(Result.TARGET_GONE);
		}
		if (entity.level() != bot.level()) {
			return Result.fail(Result.WRONG_DIMENSION);
		}
		if (entity == bot || !mayAttack(entity)) {
			return Result.fail(Result.PROTECTED);
		}
		return null;
	}

	/** What {@code attack} sees in a target, to tell whether it may hit it ({@link #mayAttack}). */
	enum Target {
		/** A hostile mob, such as a zombie. */
		HOSTILE,
		/** An animal neither tamed nor named. */
		ANIMAL,
		/** An animal that is tamed or has a name. */
		TAMED,
		/** Another bot. */
		BOT,
		PLAYER,
		/** Anything else: a villager, an iron golem, a dropped item. */
		OTHER
	}

	static Target target(Entity entity) {
		return switch (entity) {
			case Player player -> Target.PLAYER;
			case AntBot bot -> Target.BOT;
			case Enemy enemy -> Target.HOSTILE;
			case Animal animal -> animal.hasCustomName() || animal instanceof OwnableEntity owned && owned.getOwnerReference() != null
				? Target.TAMED : Target.ANIMAL;
			default -> Target.OTHER;
		};
	}

	/** Whether a bot may hit {@code entity} under the server's config; also what a sword's sweep spares (BotHands.strike). */
	public static boolean mayAttack(Entity entity) {
		return mayAttack(target(entity), AntCraftConfig.values());
	}

	/**
	 * Whether {@code attack} may hit {@code target}: hostile mobs and untamed, unnamed animals always; players, other bots and
	 * tamed or named animals only where the config allows them (docs/features/config.md); anything else never.
	 */
	static boolean mayAttack(Target target, AntCraftConfig.Values config) {
		return switch (target) {
			case HOSTILE, ANIMAL -> true;
			case PLAYER -> config.botsAttackPlayers();
			case BOT -> config.botsAttackBots();
			case TAMED -> config.botsAttackTamed();
			case OTHER -> false;
		};
	}

	static AntBot bot(ScriptRun run, NativeCall call) {
		AntBot bot = run.host().bot();
		if (bot == null || !(bot.level() instanceof ServerLevel)) {
			throw new ScriptError(call.name() + "() needs a bot in a world");
		}
		return bot;
	}

	/** A facing argument: {@code "north"}, {@code "south"}, {@code "east"}, {@code "west"}, {@code "up"} or {@code "down"}. */
	/** A block's half, top or bottom, for {@code place}. */
	static Half half(Object value) {
		if ("top".equals(value)) {
			return Half.TOP;
		}
		if ("bottom".equals(value)) {
			return Half.BOTTOM;
		}
		throw new ScriptError("half must be top or bottom, not " + Values.repr(value));
	}

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

	/**
	 * {@code mine(pos, hand=False)}: breaks the block with the best tool the bot carries, or with {@code hand} as with a
	 * bare hand, its drops going into the bot's inventory.
	 */
	private static final class Mine extends OnBlock {
		private final BlockBreaker breaker;
		private final boolean hand;

		Mine(ScriptRun run, AntBot bot, Pos target, boolean hand) {
			super(run, bot, target);
			this.breaker = new BlockBreaker(bot);
			this.hand = hand;
		}

		@Override
		@Nullable Result check(BlockState state) {
			if (state.isAir() || state.liquid()) {
				return Result.fail(Result.TARGET_GONE);
			}
			if (state.getDestroySpeed(this.level, this.pos) < 0) {
				return Result.fail(Result.PROTECTED);
			}
			ItemStack tool = this.hand ? ItemStack.EMPTY : Tools.bestToolFor(this.bot.getInventory(), this.bot.getMainHandItem(), state);
			return Tools.canHarvest(tool, state) ? null : Result.fail(Result.NO_TOOL);
		}

		@Override
		@Nullable Result work(BlockState state) {
			if (!this.breaker.tick(this.level, this.pos, true, this.hand)) {
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
			return "mine(" + this.target + (this.hand ? ", hand=True" : "") + ")";
		}
	}

	/** {@code place(pos, item, facing=None, half=None)}: holds a block item {@code item} selects and places it. */
	private static final class Place extends OnBlock {
		private final Selector selector;
		private final Predicate<ItemStack> item;
		private final @Nullable Direction facing;
		private final @Nullable Half half;
		private final BlockBreaker breaker;
		/** How long, in ticks, the bot may step out of the block it places before it gives up. */
		private static final int STEP_TICKS = 20;
		private int stepped;

		Place(ScriptRun run, AntBot bot, Pos target, Selector selector, @Nullable Direction facing, @Nullable Half half) {
			super(run, bot, target);
			this.selector = selector;
			Predicate<ItemStack> selected = selector.items();
			this.item = stack -> selected.test(stack) && stack.getItem() instanceof BlockItem;
			this.facing = facing;
			this.half = half;
			this.breaker = new BlockBreaker(bot);
		}

		@Override
		void stopWork() {
			this.breaker.reset();
		}

		@Override
		@Nullable Result before() {
			return BotHands.find(this.bot, this.item) == null ? Result.fail(Result.NO_ITEM) : super.before();
		}

		@Override
		@Nullable Result check(BlockState state) {
			return state.canBeReplaced() || Mining.isSoft(state) ? null
				: Result.fail(Result.BLOCKED, PyNone.NONE, BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " at " + EntityInfo.pos(this.pos) + " cannot be replaced");
		}

		@Override
		@Nullable Result work(BlockState state) {
			ItemStack placing = BotHands.find(this.bot, this.item);
			// A soft block in the way is broken first, its drops kept, unless it is what is placed and takes it, as snow onto
			// snow (#747); a sapling onto the same sapling is broken too, as it takes none (#759).
			if (Mining.isSoft(state) && !(placing != null && placing.getItem() instanceof BlockItem block && state.is(block.getBlock())
				&& state.canBeReplaced())) {
				return this.breaker.tick(this.level, this.pos, true) ? this.work(this.level.getBlockState(this.pos)) : null;
			}
			BotHands.equip(this.bot, this.item);
			Item held = this.bot.getMainHandItem().getItem();
			if (BotHands.place(this.bot, this.pos, this.facing, this.half)) {
				return Result.OK;
			}
			// The bot's own body over the edge of the block, as it stands off the middle of the one beside (never in it: not in
			// reach from there), keeps the game from placing there: it steps back to the middle of its block first (#822).
			// Another bot standing there is asked to make way, as build asks (#824); a player or mob is not waited for.
			if (this.stepped++ < STEP_TICKS) {
				AABB spot = new AABB(this.pos);
				boolean waiting = false;
				if (this.bot.getBoundingBox().intersects(spot)) {
					this.bot.getMoveControl().setWantedPosition(this.bot.getBlockX() + 0.5, this.bot.getY(), this.bot.getBlockZ() + 0.5, 1.0);
					waiting = true;
				}
				for (Entity other : this.level.getEntities(this.bot, spot, entity -> entity instanceof AntBot)) {
					((AntBot) other).makeWay(Set.of(this.pos), () -> false);
					waiting = true;
				}
				if (waiting) {
					return null;
				}
			}
			return Result.fail(Result.BLOCKED, PyNone.NONE, "the game would not place " + BuiltInRegistries.ITEM.getKey(held) + " at " + EntityInfo.pos(this.pos));
		}

		@Override
		public String describe() {
			return "place(" + this.target + ", " + this.selector + (this.facing == null ? "" : ", facing='" + this.facing.getSerializedName() + "'")
				+ (this.half == null ? "" : ", half='" + this.half.getSerializedName() + "'") + ")";
		}
	}

	/**
	 * {@code use(pos, item=None, face=None)}: uses the held item, or first holds one {@code item} selects, on the block; aimed
	 * at the middle of its {@code face} where one is given (#877).
	 */
	private static final class Use extends OnBlock {
		private final @Nullable Selector selector;
		private final @Nullable Predicate<ItemStack> item;
		private final @Nullable Direction face;

		Use(ScriptRun run, AntBot bot, Pos target, @Nullable Selector selector, @Nullable Direction face) {
			super(run, bot, target);
			this.selector = selector;
			this.item = selector == null ? null : selector.items();
			this.face = face;
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
			ItemStack held = this.bot.getMainHandItem();
			String using = held.isEmpty() ? "an empty hand" : BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
			String failed = BotHands.use(this.bot, this.pos, this.face);
			if (Result.BLOCKED.equals(failed)) {
				return Result.fail(failed, PyNone.NONE, "using " + using + " on " + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " at " + EntityInfo.pos(this.pos) + " did nothing");
			}
			return failed == null ? Result.OK : Result.fail(failed);
		}

		@Override
		public String describe() {
			return "use(" + this.target + (this.selector == null ? "" : ", " + this.selector)
				+ (this.face == null ? "" : ", face='" + this.face.getSerializedName() + "'") + ")";
		}
	}

	/**
	 * {@code attack(entity, min_health)}: walks into melee reach of the target, following it as it moves, and strikes it with what the
	 * bot holds as often as a player would with it ({@link BotHands#strikeTicks}) until it dies (ok). It fails with {@code target_gone} once the
	 * target is gone otherwise or more than {@link SenseApi#MAX_ENTITY_RADIUS} blocks away, and with the walk's reason,
	 * such as {@code unreachable}, when the bot cannot get to it, and with {@code hurt} once the bot's health is under
	 * {@code min_health}, letting go of the target so its own goals do not chase it on (#718). A creeper {@code attack}
	 * shoots ({@link Shoot}) ends with {@code hurt} likewise (#724).
	 */
	private static class Attack implements ScriptRun.Action {
		/** How far, in blocks, the target may move from where the bot walks to before the bot walks to where it is now. */
		private static final double MOVED = 2;
		/** How often the bot may get to the target without it being in reach before it gives up with {@code unreachable}. */
		private static final int ARRIVALS = 3;
		/**
		 * How many ticks the bot, arrived but out of reach of a target that stands on its level, steps straight at it before it
		 * walks again: a walk to within a block of the target's block can end out of melee reach of a mob at the block's far
		 * edge, and each walk after it ends there at once (#825).
		 */
		private static final int STEP_IN_TICKS = 20;

		private final ScriptRun run;
		final AntBot bot;
		final EntityInfo info;
		private final long minHealth;
		private ScriptRun.@Nullable Action walk;
		private @Nullable Vec3 walkedTo;
		private int arrivals;
		private int steppedIn;
		private int struckAt = Integer.MIN_VALUE / 2;
		private @Nullable Result result;

		Attack(ScriptRun run, AntBot bot, EntityInfo info, long minHealth) {
			this.run = run;
			this.bot = bot;
			this.info = info;
			this.minHealth = minHealth;
		}

		@Override
		public boolean tick() {
			if (this.result != null) {
				return true;
			}
			@Nullable Entity entity = this.info.entity();
			if (entity instanceof LivingEntity living && living.isDeadOrDying()) {
				return this.finish(this.died());
			}
			if (!(entity instanceof LivingEntity target) || entity.isRemoved() || entity.level() != this.bot.level()
				|| entity.distanceTo(this.bot) > SenseApi.MAX_ENTITY_RADIUS) {
				return this.finish(Result.fail(Result.TARGET_GONE));
			}
			if (this.bot.getHealth() < this.minHealth) {
				this.bot.setTarget(null);
				return this.finish(Result.fail(Result.HURT));
			}
			if (KiteGoal.litNear(this.bot) != null) {
				// A creeper near with its fuse lit: the bot's kite goal strikes it and backs off out of the blast until it goes out (#706).
				this.stopWalk();
				return false;
			}
			if (BotHands.inStrikingReach(this.bot, target)) {
				this.stopWalk();
				this.arrivals = 0;
				this.steppedIn = 0;
				this.bot.getLookControl().setLookAt(target);
				@Nullable Result done = this.inReach(target);
				return done != null && this.finish(done);
			}
			if (this.walk != null && this.walkedTo != null && target.position().distanceTo(this.walkedTo) > MOVED) {
				this.stopWalk();
			}
			if (this.walk == null) {
				if (this.arrivals > 0 && this.steppedIn < STEP_IN_TICKS && Math.abs(target.getY() - this.bot.getY()) < 1) {
					// Straight at it, as the kite goal steps in: a walk there would end where the bot stands (#825).
					this.steppedIn++;
					this.bot.getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), 1);
					return false;
				}
				if (this.arrivals >= ARRIVALS) {
					return this.finish(Result.fail(Result.UNREACHABLE));
				}
				this.walk = MoveApi.walk(this.run.host(), EntityInfo.pos(ground(target)), 1);
				this.walkedTo = target.position();
			}
			if (!this.walk.tick()) {
				return false;
			}
			Result walked = (Result) this.walk.result();
			this.walk = null;
			if (!walked.ok()) {
				return this.finish(walked);
			}
			this.arrivals++;
			return false;
		}

		/** A tick in reach of the target: a blow when the bot may strike again; the result once done, null while it goes on. */
		@Nullable Result inReach(LivingEntity target) {
			if (this.bot.tickCount - this.struckAt >= BotHands.strikeTicks(this.bot)) {
				BotHands.strike(this.bot, (ServerLevel) this.bot.level(), target);
				this.struckAt = this.bot.tickCount;
			}
			return null;
		}

		/** The result once the target died: ok for a fight. */
		Result died() {
			return Result.OK;
		}

		/**
		 * Where the bot walks to: the target's block for a mob that stands, the one over the ground under it for one that
		 * flies or hovers. A walk to within 1 block of a mob in the air searched its whole budget for a way up, tick after
		 * tick (#799); under it, the bot that still cannot strike it gives up after {@link #ARRIVALS}.
		 */
		private static BlockPos ground(LivingEntity target) {
			BlockPos.MutableBlockPos pos = target.blockPosition().mutable();
			while (pos.getY() > target.level().getMinY() && target.level().getBlockState(pos.below()).isAir()) {
				pos.move(Direction.DOWN);
			}
			return pos;
		}

		private boolean finish(Result result) {
			this.stopWalk();
			this.result = result;
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
			return this.result == null ? Result.fail(Result.STOPPED) : this.result;
		}

		@Override
		public boolean walks() {
			return true;
		}

		@Override
		public void cancel() {
			this.stopWalk();
		}

		@Override
		public String describe() {
			return "attack(" + this.info + ")";
		}
	}

	/**
	 * {@code use(entity, item=None)}: walks into reach of a mob as {@code attack} does and uses the held item on it, or first
	 * holds one {@code item} selects, as a player's right click on it does: wheat to a cow puts it in love, shears shear a
	 * sheep (#837). Ok when that did something, {@code blocked} when not, {@code target_gone} once it is gone.
	 */
	private static final class UseOn extends Attack {
		private final @Nullable Selector selector;
		final @Nullable Predicate<ItemStack> item;

		UseOn(ScriptRun run, AntBot bot, EntityInfo info, @Nullable Selector selector) {
			super(run, bot, info, 0);
			this.selector = selector;
			this.item = selector == null ? null : selector.items();
		}

		@Override
		@Nullable Result inReach(LivingEntity target) {
			if (this.item != null && !BotHands.equip(this.bot, this.item)) {
				return Result.fail(Result.NO_ITEM);
			}
			return BotHands.use(this.bot, target) ? Result.OK : Result.fail(Result.BLOCKED);
		}

		@Override
		Result died() {
			return Result.fail(Result.TARGET_GONE);
		}

		@Override
		public String describe() {
			return "use(" + this.info + (this.selector == null ? "" : ", " + this.selector) + ")";
		}
	}

	/**
	 * {@code eat(item)}: eats or drinks a carried item {@code item} selects that can be, taking its eat time as a player does
	 * (32 ticks for most food), then gives its nutrition and saturation and its effects, such as a golden apple's, and uses
	 * it up, keeping what is left of it, such as a stew's bowl (#702).
	 */
	static final class Eat implements ScriptRun.Action {
		private final AntBot bot;
		private final Selector selector;
		private final Predicate<ItemStack> item;
		private int ticks;
		private @Nullable Result result;

		/**
		 * The eat a hungry bot starts between the steps of a long action, as a player would (#773): the carried food with the
		 * most points that wastes none of them, of those with no effect on eating (as {@code foods()}, and no suspicious stew,
		 * as lib.food, #781) and no block to place, so a build keeps its carrots and berries; null if there is none.
		 */
		static @Nullable Eat ifHungry(AntBot bot) {
			int room = FoodConstants.MAX_FOOD - bot.getFood().food();
			if (room <= 0) {
				return null;
			}
			ItemStack best = null;
			int most = 0;
			for (ItemStack stack : bot.getInventory().getItems()) {
				FoodProperties food = stack.get(DataComponents.FOOD);
				Consumable consumable = stack.get(DataComponents.CONSUMABLE);
				if (food != null && food.nutrition() <= room && food.nutrition() > most && !(stack.getItem() instanceof BlockItem)
					&& (consumable == null || consumable.onConsumeEffects().isEmpty())
					&& !stack.has(DataComponents.SUSPICIOUS_STEW_EFFECTS)) { // its effects are its own, as lib.food's LEFT_OUT
					best = stack;
					most = food.nutrition();
				}
			}
			return best == null ? null : new Eat(bot, Selector.parse(BuiltInRegistries.ITEM.getKey(best.getItem()).toString()));
		}

		Eat(AntBot bot, Selector selector) {
			this.bot = bot;
			this.selector = selector;
			Predicate<ItemStack> selected = selector.items();
			this.item = stack -> selected.test(stack) && stack.has(DataComponents.CONSUMABLE);
		}

		/** Why the bot cannot eat now: it carries nothing to eat, or it is full; null if it can. */
		@Nullable Result check() {
			ItemStack stack = BotHands.find(this.bot, this.item);
			if (stack == null) {
				return Result.fail(Result.NO_ITEM);
			}
			FoodProperties food = stack.get(DataComponents.FOOD);
			return food != null && !food.canAlwaysEat() && this.bot.getFood().food() >= FoodConstants.MAX_FOOD
				? Result.fail(Result.FULL) : null;
		}

		@Override
		public boolean tick() {
			if (this.result != null) {
				return true;
			}
			this.result = this.check();
			if (this.result != null) {
				return true;
			}
			ItemStack stack = Objects.requireNonNull(BotHands.find(this.bot, this.item));
			Consumable consumable = Objects.requireNonNull(stack.get(DataComponents.CONSUMABLE));
			int left = consumable.consumeTicks() - ++this.ticks;
			if (left > 0) {
				if (consumable.shouldEmitParticlesAndSounds(left)) {
					consumable.emitParticlesAndSounds(this.bot.getRandom(), this.bot, stack, 5);
				}
				return false;
			}
			FoodProperties food = stack.get(DataComponents.FOOD);
			ItemStack rest = stack.finishUsingItem(this.bot.level(), this.bot); // its effects, and it is used up
			if (food != null) {
				this.bot.getFood().eat(food.nutrition(), food.saturation());
			}
			if (rest != stack && !rest.isEmpty()) {
				ItemStack over = this.bot.getInventory().addItem(rest);
				if (!over.isEmpty()) {
					this.bot.spawnAtLocation((ServerLevel) this.bot.level(), over);
				}
			}
			this.result = Result.OK;
			return true;
		}

		@Override
		public Object result() {
			return this.result == null ? Result.fail(Result.STOPPED) : this.result;
		}

		@Override
		public long progress() {
			return this.ticks;
		}

		@Override
		public void restore(long progress) {
			this.ticks = (int) progress;
		}

		@Override
		public String describe() {
			return "eat(" + this.selector + ")";
		}
	}

	/**
	 * {@code shoot(entity, min_health)}: draws the bot's bow or crossbow and shoots arrows at the target ({@link Bow#fire}), keeping
	 * between {@link Bow#NEAR} and {@link Bow#RANGE} blocks of it: it walks closer while the target is further or out of sight,
	 * and backs off while it is nearer, leaving that to the kite goal while a creeper's fuse is lit. It ends ok once the
	 * target dies, fails as {@code attack} does and with {@code no_tool} or {@code no_item}
	 * once the bow or the arrows are gone, and with {@code hurt} as {@code attack} does (#724).
	 */
	private static final class Shoot implements ScriptRun.Action {
		/** How far, in blocks, the target may move from where the bot walks to before the bot walks to where it is now. */
		private static final double MOVED = 2;
		/**
		 * How far, in blocks from the bot's eyes to the target's block, it walks to see the target from: within the bow's
		 * {@link Bow#RANGE} from its feet, which are up to 1.62 lower and half a block off. Not up to the target, which for
		 * a flyer is a long search that fails (#792).
		 */
		private static final double SIGHT = Bow.RANGE - 2;

		private final ScriptRun run;
		private final AntBot bot;
		private final EntityInfo info;
		private final long minHealth;
		private ScriptRun.@Nullable Action walk;
		private @Nullable Vec3 walkedTo;
		/** Whether it got to where its walk saw the target but the bot still does not: it walks right up to it then. */
		private boolean closeIn;
		private @Nullable Vec3 away;
		private @Nullable Result result;

		Shoot(ScriptRun run, AntBot bot, EntityInfo info, long minHealth) {
			this.run = run;
			this.bot = bot;
			this.info = info;
			this.minHealth = minHealth;
		}

		@Override
		public boolean tick() {
			if (this.result != null) {
				return true;
			}
			@Nullable Entity entity = this.info.entity();
			if (entity instanceof LivingEntity living && living.isDeadOrDying()) {
				return this.finish(Result.OK);
			}
			if (!(entity instanceof LivingEntity target) || entity.isRemoved() || entity.level() != this.bot.level()
				|| entity.distanceTo(this.bot) > SenseApi.MAX_ENTITY_RADIUS) {
				return this.finish(Result.fail(Result.TARGET_GONE));
			}
			if (this.bot.getHealth() < this.minHealth) {
				// As attack: a target its own goal took at full health would outlast the shoot by a tick (#724).
				this.bot.setTarget(null);
				return this.finish(Result.fail(Result.HURT));
			}
			if (!BotHands.equip(this.bot, Bow.WEAPON)) {
				return this.finish(Result.fail(Result.NO_TOOL));
			}
			if (BotHands.find(this.bot, Bow.ARROWS) == null) {
				return this.finish(Result.fail(Result.NO_ITEM));
			}
			this.bot.setTarget(null);
			double distance = this.bot.distanceTo(target);
			boolean inSight = this.bot.getSensing().hasLineOfSight(target);
			boolean inRange = inSight && distance <= Bow.RANGE;
			if (!inRange && KiteGoal.litNear(this.bot) == null) {
				Result walked = this.walkTo(target);
				if (walked != null) {
					return this.finish(walked);
				}
			} else {
				this.stopWalk();
				if (distance < Bow.NEAR && KiteGoal.litNear(this.bot) == null) {
					this.away = Bow.backOff(this.bot, target, this.away);
				} else if (this.away != null) {
					this.away = null;
					this.bot.getNavigation().stop();
				}
			}
			Bow.draw(this.bot, target, inRange);
			return false;
		}

		/**
		 * Walks on to where the bot sees {@code target} within {@link #SIGHT}, or right up to it once it got there and still
		 * does not, planning again when it moved; the walk's failure, or null while it goes on.
		 */
		private @Nullable Result walkTo(LivingEntity target) {
			if (this.walkedTo != null && target.position().distanceTo(this.walkedTo) > MOVED) {
				this.stopWalk();
				this.closeIn = false;
			}
			if (this.walk == null) {
				Pos at = EntityInfo.pos(target.blockPosition());
				this.walk = this.closeIn ? MoveApi.walk(this.run.host(), at, 1) : MoveApi.reach(this.run.host(), at, SIGHT);
				this.walkedTo = target.position();
			}
			if (!this.walk.tick()) {
				return null;
			}
			Result walked = (Result) this.walk.result();
			this.walk = null;
			this.closeIn = walked.ok();
			return walked.ok() ? null : walked;
		}

		private boolean finish(Result result) {
			this.cancel();
			this.result = result;
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
			return this.result == null ? Result.fail(Result.STOPPED) : this.result;
		}

		@Override
		public boolean walks() {
			return true;
		}

		@Override
		public boolean aims() {
			return true;
		}

		@Override
		public void cancel() {
			this.stopWalk();
			this.bot.stopUsingItem();
			if (this.away != null) {
				this.away = null;
				this.bot.getNavigation().stop();
			}
		}

		@Override
		public String describe() {
			return "shoot(" + this.info + ")";
		}
	}

	/**
	 * {@code pick_up(what=None, radius=8)}: walks to each dropped item {@code what} selects within {@code radius} of where
	 * the bot stood, nearest first, and takes it. Its value is how many items it took.
	 */
	private static final class PickUp implements ScriptRun.Action {
		/** How far beyond the bot's box it takes items from: a little more than a player's pickup reach sideways. */
		private static final double GRAB = 1.0;
		/** How much farther it reaches for an item lying in a block it cannot walk onto: from anywhere in the block beside it. */
		private static final double REACH_IN = 0.5;

		private final ScriptRun run;
		private final AntBot bot;
		private final Predicate<ItemStack> item;
		private final String describe;
		private final Vec3 center;
		private final int radius;
		/** Items the bot could not walk to. */
		private final Set<Integer> unreachable = new HashSet<>();
		private @Nullable Entity goal;
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

		/**
		 * What {@code entity} holds for the bot to take: a dropped item's stack, or the arrow of one stuck in a block that a
		 * player could pick up (#720); null for anything else.
		 */
		private static @Nullable ItemStack held(Entity entity) {
			if (entity instanceof ItemEntity dropped) {
				return dropped.getItem();
			}
			return entity instanceof AbstractArrow arrow && arrow.pickup == AbstractArrow.Pickup.ALLOWED && arrow.isInGround()
				? arrow.getPickupItemStackOrigin() : null;
		}

		/**
		 * Whether {@code dropped} lies in a block, below the top of its collision shape, as in a composter: the bot cannot
		 * stand there without breaking it. Not one lying on top, as on farmland or a slab, which the bot walks onto. Also one
		 * in a hazard a path never enters, such as a sweet berry bush (#832).
		 */
		private boolean inBlock(ItemEntity dropped) {
			BlockPos at = dropped.blockPosition();
			if (new LevelWorldView(this.bot.level(), this.bot.getInventory(), this.bot.getMainHandItem(), Set.of()).isHazard(at)) {
				return true;
			}
			VoxelShape shape = this.bot.level().getBlockState(at).getCollisionShape(this.bot.level(), at);
			return !shape.isEmpty() && dropped.getY() - at.getY() < shape.max(Direction.Axis.Y) - 1.0E-3;
		}

		private List<Entity> around() {
			double r2 = (double) this.radius * this.radius;
			return this.bot.level().getEntities((Entity) null, new AABB(this.center, this.center).inflate(this.radius), entity -> {
				ItemStack held = held(entity);
				return held != null && entity.isAlive() && entity.distanceToSqr(this.center) <= r2 && this.item.test(held);
			});
		}

		@Override
		public boolean tick() {
			if (this.result != null) {
				return true;
			}
			AABB grab = this.bot.getBoundingBox().inflate(GRAB, 0.5, GRAB);
			List<Entity> around = this.around();
			for (Entity entity : around) {
				if (entity.getBoundingBox().intersects(grab)) {
					if (entity instanceof ItemEntity dropped) {
						BotHands.collect(this.bot, dropped);
					} else {
						BotHands.collect(this.bot, (AbstractArrow) entity);
					}
				}
			}
			this.taken = Math.max(0, BotHands.count(this.bot, this.item) - this.before);
			around = around.stream().filter(Entity::isAlive).toList();
			List<Entity> fitting = around.stream()
				.filter(entity -> !this.unreachable.contains(entity.getId()) && this.bot.getInventory().canAddItem(held(entity)))
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
				if (this.arrivals == 0 && this.goal instanceof ItemEntity dropped && !dropped.onGround() && !dropped.isInLiquid()) {
					// Still falling: a walk to where it is now heads for a block in the air, which a path pillars up to, as
					// it did over cane just cut, putting scaffold in place of the cane under it (#853). It lands first.
					return false;
				}
				if (this.arrivals > 0 && this.goal instanceof ItemEntity dropped && (dropped.hasPickUpDelay() || !dropped.onGround())) {
					return false; // next to it, waiting for it to land and for its pickup delay to end
				}
				if (this.arrivals > 0 && this.goal instanceof ItemEntity dropped && this.inBlock(dropped)) {
					// Lying in a block, such as bone meal in a composter: walking onto it would break the block (#817).
					if (dropped.getBoundingBox().intersects(this.bot.getBoundingBox().inflate(GRAB + REACH_IN, 0.5, GRAB + REACH_IN))) {
						BotHands.collect(this.bot, dropped);
						return false;
					}
					this.arrivals = 2;
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
		private boolean finish(List<Entity> around) {
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
