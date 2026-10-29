package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.script.lang.Args;
import io.github.barniebarnie.antcraft.script.lang.Natives;
import io.github.barniebarnie.antcraft.script.lang.PyList;
import io.github.barniebarnie.antcraft.script.lang.PyNone;
import io.github.barniebarnie.antcraft.script.lang.ScriptError;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Moving the bot (docs/reference/index.md, The bot API): {@code goto(pos, near=0)}, {@code home()}, {@code set_home(pos)}, {@code dimension()}
 * and {@code home_dimension()}, {@code travel(portal)}, {@code portals()},
 * and the sense {@code reachable(pos, near=0)}, which plans a walk without taking it.
 * The world is reached through the bot's {@link Body}.
 */
public final class MoveApi {
	/** How far ahead, in blocks, {@code goto} loads the chunks its way enters before planning on. */
	static final int STRETCH = 48;
	/**
	 * How many chunks on either side of the way {@code goto} loads too: the bot's own ticket (see BotChunks) needs the
	 * chunks within three of the one it enters loaded before the bot ticks there.
	 */
	static final int BAND = 3;
	/** How often {@code goto} renews its tickets on the chunks ahead, which time out (see BotChunks). */
	static final int RENEW_TICKS = 20;
	/** How long {@code goto} waits for the chunks ahead to load before it gives up with {@code not_loaded}. */
	static final int LOAD_WAIT_TICKS = 1200;
	/**
	 * How long {@code goto} goes on without getting closer to its target before it gives up with {@code timeout}; long, so
	 * a way round a lake or a ridge, which leads away for a while, is not cut short.
	 */
	static final int STALL_TICKS = 2400;
	/**
	 * How far, in blocks from the bot's eyes to the block's centre, the block actions reach (docs/reference/index.md, Actions);
	 * a little short of a player's 4.5.
	 */
	public static final double REACH = 4.0;
	/**
	 * What a node of a {@code reachable} plan costs the script in instructions. A node takes far longer than one, some
	 * 3 µs against 9 ns (#322), but {@link io.github.barniebarnie.antcraft.pathing.PathBudget#SERVER} bounds what plans
	 * cost the server, and a charge at cost would hold a script up for seconds after each plan.
	 */
	public static final int INSTRUCTIONS_PER_NODE = 1;
	/**
	 * How long {@code travel} waits in the portal for the bot to change dimension before it gives up with {@code timeout};
	 * a portal takes a mob on the tick after it steps in.
	 */
	public static final int TRAVEL_WAIT_TICKS = 20;
	/** How many portals a bot remembers ({@code portals()}): the newest. */
	public static final int MAX_PORTALS = 8;
	/**
	 * How near, in blocks along each axis, a portal remembered must be to one remembered before, in the same dimension,
	 * to replace it.
	 */
	public static final int SAME_PORTAL = 16;

	/** What the move actions need of the bot. */
	public interface Body {
		/** The bot's home: where it was first spawned, until {@link #setHome} changes it. */
		Pos home();

		/** Sets the home in the dimension with id {@code dimension}. */
		void setHome(Pos home, String dimension);

		/** The id of the dimension the bot is in, such as {@code minecraft:overworld}. */
		String dimension();

		/** The id of the dimension of the bot's home. */
		String homeDimension();

		/** Whether the chunk at chunk coordinates {@code x}, {@code z} is loaded. */
		boolean chunkLoaded(int x, int z);

		/** Asks for the chunk to be loaded, generating it if need be; it loads over the next ticks and stays a while. */
		void loadChunk(int x, int z);

		/** Starts walking to within {@code near} blocks (Manhattan) of {@code target}, with the bot's path follower. */
		void startPath(Pos target, int near);

		/** Starts walking to where the bot sees {@code target} within {@link #REACH}, to work on it. */
		void startReach(Pos target);

		/**
		 * How the next walks pillar up: with {@code mayPlace} set, as {@code build} does, only with scaffold blocks the bot
		 * takes down again by hand, and only where {@code mayPlace} says, such as nowhere its blueprint puts a block; null
		 * for any scaffold block anywhere. Nor do they break a block {@code keep} says, such as one its blueprint has in place.
		 */
		default void setBuildScaffold(@Nullable Predicate<Pos> mayPlace, Predicate<Pos> keep) {
		}

		/** Whether the bot, where it stands, sees {@code target} within {@link #REACH}. */
		boolean inReach(Pos target);

		/** Advances the walk by a tick. */
		PathStatus tickPath();

		/** Stops the walk. */
		void stopPath();

		/**
		 * Starts planning, without walking, a path from where the bot stands to within {@code near} blocks (Manhattan) of
		 * {@code target}, as {@link #startPath} would plan it but over loaded blocks only, loading no chunks.
		 */
		Plan plan(Pos target, int near);

		/**
		 * The block of the nether portal at {@code portal} that the bot stands in to go through: the bottom one of its column,
		 * as far as it is loaded.
		 */
		default Pos portalFloor(Pos portal) {
			return portal;
		}

		/** The nether portals the bot went through, both ends, newest first, each with its dimension ({@link #remember}). */
		default List<Pos> portals() {
			return List.of();
		}

		/** Whether the block at {@code portal} is loaded and is no nether portal: a portal there is gone. */
		default boolean noPortalAt(Pos portal) {
			return false;
		}

		/** Forgets the nether portals remembered as the same portal as {@code portal}, in the bot's dimension ({@link #forget}). */
		default void forgetPortal(Pos portal) {
		}

		/**
		 * Ends the bot's portal cooldown, so the portal takes it at once: a mob that came through a portal is otherwise not
		 * taken back for 15 seconds, and never while it stands in one.
		 */
		default void clearPortalCooldown() {
		}

		/** How long a walk waits for the chunks ahead to load before it gives up with {@code not_loaded}. */
		default int loadWaitTicks() {
			return LOAD_WAIT_TICKS;
		}

		/** How long a walk goes on without getting closer before it gives up with {@code timeout}. */
		default int stallTicks() {
			return STALL_TICKS;
		}
	}

	/** A path being planned for {@code reachable}, a slice of nodes at a time. */
	public interface Plan {
		/**
		 * Runs the search for as many nodes as this tick has left in the server's path budget, which every bot's path
		 * follower shares (see PathBudget); returns how many it expanded.
		 */
		int run();

		/** Whether the search is over. */
		boolean done();

		/** Once {@link #done}: whether it found a whole path to the target. */
		boolean found();
	}

	public enum PathStatus {
		RUNNING,
		ARRIVED,
		FAILED
	}

	private MoveApi() {
	}

	static void register(Natives natives, ScriptRun run) {
		natives.register("home", call -> {
			Args.bind("home", call, 0);
			return run.host().body().home().in(run.host().body().homeDimension());
		});
		natives.register("home_dimension", call -> {
			Args.bind("home_dimension", call, 0);
			return run.host().body().homeDimension();
		});
		natives.register("dimension", call -> {
			Args.bind("dimension", call, 0);
			return run.host().body().dimension();
		});
		natives.register("set_home", call -> {
			Pos home = Pos.of(Args.bind("set_home", call, 1, "pos")[0]);
			Body body = run.host().body();
			body.setHome(home, home.dimension() != null ? home.dimension() : body.dimension());
			return PyNone.NONE;
		});
		natives.register("goto", call -> {
			Object[] args = Args.bind("goto", call, 1, "pos", "near");
			Pos target = Pos.of(args[0]);
			long near = Args.absent(args[1]) ? 0 : Args.integer(args[1]);
			if (near < 0) {
				throw new ScriptError("goto() near must be at least 0, not " + near);
			}
			return run.start(new Goto(run.host(), target, (int) Math.min(near, Integer.MAX_VALUE), false), call, target);
		});
		natives.register("travel", call -> {
			Pos portal = Pos.of(Args.bind("travel", call, 1, "portal")[0]);
			return run.start(new Travel(run.host(), portal), call, portal);
		});
		natives.register("portals", call -> {
			Args.bind("portals", call, 0);
			return new PyList(new ArrayList<>(run.host().body().portals()));
		});
		natives.register("reachable", call -> {
			Object[] args = Args.bind("reachable", call, 1, "pos", "near");
			Pos target = Pos.of(args[0]);
			long near = Args.absent(args[1]) ? 0 : Args.integer(args[1]);
			if (near < 0) {
				throw new ScriptError("reachable() near must be at least 0, not " + near);
			}
			if (run.elsewhere(target)) {
				return false;
			}
			Body body = run.host().body();
			long chunkX = Math.floorDiv(target.x(), 16);
			long chunkZ = Math.floorDiv(target.z(), 16);
			if (chunkX != (int) chunkX || chunkZ != (int) chunkZ || !body.chunkLoaded((int) chunkX, (int) chunkZ)) {
				return false;
			}
			Reachable reachable = new Reachable(run, body.plan(target, (int) Math.min(near, Integer.MAX_VALUE)));
			if (reachable.tick()) {
				return reachable.result();
			}
			return run.start(reachable, call);
		});
	}

	/**
	 * {@code reachable(pos, near=0)}: a plan run a share of the server's path budget at a time, charging the script
	 * {@link #INSTRUCTIONS_PER_NODE} per node, and pausing it while the plan goes on over the next ticks.
	 */
	private static final class Reachable implements ScriptRun.Action {
		private final ScriptRun run;
		private final Plan plan;
		private int expanded;

		Reachable(ScriptRun run, Plan plan) {
			this.run = run;
			this.plan = plan;
		}

		@Override
		public boolean tick() {
			if (!this.plan.done()) {
				int ran = this.plan.run();
				this.expanded += ran;
				this.run.charge((long) ran * INSTRUCTIONS_PER_NODE);
			}
			return this.plan.done();
		}

		@Override
		public Object result() {
			return this.plan.done() && this.plan.found();
		}

		@Override
		public String describe() {
			return "reachable (" + this.expanded + " nodes searched)";
		}
	}

	/**
	 * {@code travel(portal)}: walks into the nether portal at {@code portal} and waits there until the portal takes the bot
	 * to the other dimension. Vanilla makes the bot anew there from its save, so the script resumes in this action in the
	 * new dimension; its progress is the dimension it started in, and it is ok once the bot is in another one.
	 */
	private static final class Travel implements ScriptRun.Action {
		private final ScriptRun.Host host;
		private final Pos portal;
		/**
		 * The hash of the dimension the bot started in, its progress.
		 * ponytail: the hash stands in for the id, which a long cannot hold; two ids that hash alike read as no change.
		 * Save the id itself if datapack dimensions ever collide.
		 */
		private int from;
		private ScriptRun.@Nullable Action walk;
		private @Nullable Result result;
		private int waited;

		Travel(ScriptRun.Host host, Pos portal) {
			this.host = host;
			this.portal = portal;
			this.from = host.body().dimension().hashCode();
		}

		@Override
		public boolean tick() {
			Body body = host.body();
			if (body.dimension().hashCode() != from) {
				result = Result.OK;
				return true;
			}
			// Only a loaded block tells, so a portal in a chunk not loaded yet is not forgotten.
			if (body.noPortalAt(portal)) {
				cancel();
				walk = null;
				body.forgetPortal(portal);
				result = Result.fail(Result.NO_PORTAL);
				return true;
			}
			body.clearPortalCooldown();
			if (walk == null && waited == 0) {
				walk = new Goto(host, body.portalFloor(portal), 0, false);
			}
			if (walk != null) {
				if (!walk.tick()) {
					return false;
				}
				Result walked = (Result) walk.result();
				walk = null;
				if (!walked.ok()) {
					result = walked;
					return true;
				}
			}
			// In the portal: it takes the bot next tick, unless something keeps it out.
			if (++waited > TRAVEL_WAIT_TICKS) {
				result = Result.fail(Result.TIMEOUT);
				return true;
			}
			return false;
		}

		@Override
		public long progress() {
			return from;
		}

		@Override
		public void restore(long progress) {
			from = (int) progress;
		}

		@Override
		public Object result() {
			return result == null ? Result.fail(Result.STOPPED) : result;
		}

		@Override
		public boolean walks() {
			return true;
		}

		@Override
		public void cancel() {
			if (walk != null) {
				walk.cancel();
			}
		}

		@Override
		public String describe() {
			return "travel(" + portal + ")";
		}
	}

	/**
	 * {@code portals} with {@code portal}, which has a dimension, put first: in place of any in the same dimension within
	 * {@link #SAME_PORTAL} blocks, which are the same portal or one beside it, and keeping the newest {@link #MAX_PORTALS}.
	 */
	public static List<Pos> remember(List<Pos> portals, Pos portal) {
		List<Pos> kept = new ArrayList<>();
		kept.add(portal);
		for (Pos old : portals) {
			if (!samePortal(old, portal, portal.dimension()) && kept.size() < MAX_PORTALS) {
				kept.add(old);
			}
		}
		return List.copyOf(kept);
	}

	/**
	 * {@code portals} without those that are the same portal as the gone one at {@code portal} in {@code dimension}, the
	 * bot's: in that dimension and within {@link #SAME_PORTAL} blocks of it ({@code travel}, {@code no_portal}).
	 */
	public static List<Pos> forget(List<Pos> portals, Pos portal, String dimension) {
		return portals.stream().filter(old -> !samePortal(old, portal, dimension)).toList();
	}

	/** Whether {@code old} is in {@code dimension} and within {@link #SAME_PORTAL} blocks of {@code portal} along each axis. */
	private static boolean samePortal(Pos old, Pos portal, String dimension) {
		long apart = Math.max(Math.abs(old.x() - portal.x()), Math.max(Math.abs(old.y() - portal.y()), Math.abs(old.z() - portal.z())));
		return Objects.equals(old.dimension(), dimension) && apart <= SAME_PORTAL;
	}

	/**
	 * The chunks, as {@code {x, z}}, that the way from {@code from} straight towards {@code to} crosses in its next
	 * {@link #STRETCH} blocks, and those within {@code band} of them.
	 */
	static List<long[]> stretch(Pos from, Pos to, int band) {
		Set<String> seen = new HashSet<>();
		List<long[]> chunks = new ArrayList<>();
		double dx = to.x() - from.x();
		double dz = to.z() - from.z();
		double length = Math.sqrt(dx * dx + dz * dz);
		double reach = Math.min(STRETCH, length);
		for (double d = 0; ; d = Math.min(d + 8, reach)) {
			long x = length == 0 ? from.x() : Math.round(from.x() + dx * d / length);
			long z = length == 0 ? from.z() : Math.round(from.z() + dz * d / length);
			long chunkX = Math.floorDiv(x, 16);
			long chunkZ = Math.floorDiv(z, 16);
			for (long cx = chunkX - band; cx <= chunkX + band; cx++) {
				for (long cz = chunkZ - band; cz <= chunkZ + band; cz++) {
					if (seen.add(cx + "," + cz)) {
						chunks.add(new long[] {cx, cz});
					}
				}
			}
			if (d >= reach) {
				return chunks;
			}
		}
	}

	/**
	 * The walk of the block actions ({@link WorkApi}): like {@code goto}, to where the bot sees {@code target} within
	 * {@link #REACH}; {@code unreachable} when it cannot get there.
	 */
	static ScriptRun.Action reach(ScriptRun.Host host, Pos target) {
		return new Goto(host, target, 0, true);
	}

	/** Like {@code goto(target, near)}, for the actions that walk to something first, such as {@code pick_up}. */
	static ScriptRun.Action walk(ScriptRun.Host host, Pos target, int near) {
		return new Goto(host, target, near, false);
	}

	/**
	 * {@code goto(pos, near=0)}: loads the chunks of the next stretch of the way (or, with {@code botsLoadChunks} off,
	 * gives up with {@code not_loaded} if one is not loaded), then walks with the bot's path follower, planning on as it goes.
	 * With {@code reach}, it walks into reach of {@code target} instead ({@link #reach}).
	 */
	private static final class Goto implements ScriptRun.Action {
		private final ScriptRun.Host host;
		private final Pos target;
		private final int near;
		private final boolean reach;
		private @Nullable Body body;
		private @Nullable Result result;
		private boolean walking;
		private int waited;
		private int ticks;
		private long closest = Long.MAX_VALUE;
		private int stalled;

		Goto(ScriptRun.Host host, Pos target, int near, boolean reach) {
			this.host = host;
			this.target = target;
			this.near = near;
			this.reach = reach;
		}

		@Override
		public boolean tick() {
			if (body == null) {
				body = host.body();
			}
			Pos at = host.pos();
			boolean loads = AntCraftConfig.botsLoadChunks();
			// Also renewed on the loaded ones, which keeps them from unloading before the bot gets there.
			boolean renew = loads && ticks++ % RENEW_TICKS == 0;
			boolean missing = false;
			// A bot that loads no chunks has no ticket to need the band around its way loaded: only the way itself.
			for (long[] chunk : stretch(at, target, loads ? BAND : 0)) {
				int x = (int) chunk[0];
				int z = (int) chunk[1];
				boolean loaded = body.chunkLoaded(x, z);
				if (loads && (renew || !loaded)) {
					body.loadChunk(x, z);
				}
				missing |= !loaded;
			}
			if (missing) {
				if (!loads || ++waited > body.loadWaitTicks()) {
					return finish(Result.fail(Result.NOT_LOADED));
				}
				return false;
			}
			waited = 0;
			if (!walking) {
				if (reach) {
					body.startReach(target);
				} else {
					body.startPath(target, near);
				}
				walking = true;
			}
			switch (body.tickPath()) {
				case ARRIVED -> {
					return finish(Result.OK);
				}
				case FAILED -> {
					return finish(Result.fail(Result.UNREACHABLE));
				}
				case RUNNING -> {
				}
			}
			long distance = Math.abs(at.x() - target.x()) + Math.abs(at.y() - target.y()) + Math.abs(at.z() - target.z());
			if (distance < closest) {
				closest = distance;
				stalled = 0;
			} else if (++stalled >= body.stallTicks()) {
				return finish(Result.fail(Result.TIMEOUT));
			}
			return false;
		}

		private boolean finish(Result result) {
			this.result = result;
			if (walking && body != null) {
				body.stopPath();
			}
			walking = false;
			return true;
		}

		@Override
		public Object result() {
			return result == null ? Result.fail(Result.STOPPED) : result;
		}

		@Override
		public boolean walks() {
			return true;
		}

		@Override
		public void cancel() {
			finish(Result.fail(Result.STOPPED));
		}

		@Override
		public String describe() {
			return "goto(" + target + (near == 0 ? "" : ", near=" + near) + ")";
		}
	}
}
