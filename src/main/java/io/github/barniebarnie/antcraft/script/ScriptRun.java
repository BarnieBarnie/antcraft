package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.script.lang.Code;
import io.github.barniebarnie.antcraft.script.lang.ModuleLoader;
import io.github.barniebarnie.antcraft.script.lang.NativeCall;
import io.github.barniebarnie.antcraft.script.lang.Natives;
import io.github.barniebarnie.antcraft.script.lang.Output.Level;
import io.github.barniebarnie.antcraft.script.lang.PyDict;
import io.github.barniebarnie.antcraft.script.lang.PyList;
import io.github.barniebarnie.antcraft.script.lang.PyNone;
import io.github.barniebarnie.antcraft.script.lang.Values;
import io.github.barniebarnie.antcraft.script.lang.Vm;
import io.github.barniebarnie.antcraft.script.lang.VmState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * A script running on a bot (docs/features/scripts.md, Runtime). The bot calls {@link #tick} once a server tick, which:
 * <ol>
 * <li>runs the reflexes ({@link #reflexes}): while one holds the bot, the current action ends with {@code interrupted}
 * and the script waits;</li>
 * <li>advances the current action, if any, and resumes the script with the action's result once it is done;</li>
 * <li>runs the script until it starts an action, finishes, fails, stops or uses up the tick's instruction budget.</li>
 * </ol>
 * A native that takes ticks starts an {@link Action} with {@link #start} and returns what that gives, which pauses the
 * script until the action is done. The bot API lives in {@link BotApi}. Pure Java: the world is reached through the
 * {@link Host}.
 */
public final class ScriptRun {
	/** What the script sees of its bot, and where its output goes. */
	public interface Host {
		/** The game time, which log lines carry. */
		long gameTime();

		/** The block the bot stands in, in the bot's dimension. */
		Pos pos();

		/** The lowest level, below {@link Level#PRINT}, that goes to the owner's chat (config {@code scriptChatLevel}). */
		Level chatLevel();

		/** Sends a line of the script's output to the bot's owner. */
		void chat(Level level, String line);

		/** Shows what the bot says, as {@code say} does. */
		void say(String text);

		/** The bot in its world, which the senses read ({@link SenseApi}); null where there is none, as in unit tests. */
		default @Nullable AntBot bot() {
			return null;
		}

		/**
		 * Whether one of the bot's reflexes holds it this tick (docs/features/bots.md, Reflexes). The bot runs its reflexes
		 * before the script, and they cannot be turned off by it.
		 */
		default boolean reflex() {
			return false;
		}

		/** The bot's body, for the actions that move it ({@link MoveApi}). */
		default MoveApi.Body body() {
			throw new UnsupportedOperationException("this bot cannot move");
		}

		/** The loaded entity {@code uuid} of the bot's world, or null; how a resumed script finds its saved entities. */
		default @Nullable Entity entity(UUID uuid) {
			return null;
		}

		/** The {@code colony_memory} of the bot's colony, a dict every member shares, or {@code None} outside a colony. */
		default Object colonyMemory() {
			return PyNone.NONE;
		}
	}

	/** Something a script waits on that takes ticks, such as {@code wait(ticks)}. */
	public interface Action {
		/** Advances the action by one server tick; true once it is done. */
		boolean tick();

		/** The result of the script's call, once the action is done. */
		Object result();

		/** What the action is doing, for {@code /antcraft bot status}. */
		String describe();

		/** Whether the action walks the bot, such as {@code goto}: a bot holds its chunks loaded while it does (see BotChunks). */
		default boolean walks() {
			return false;
		}

		/**
		 * Whether the action waits out a reflex rather than end with {@code interrupted}: a search that does not move the bot,
		 * such as {@code mineshaft}'s, pauses while a reflex holds the bot and goes on once it lets go (#410).
		 */
		default boolean outlastsReflexes() {
			return false;
		}

		/** Ends the action early, because the script was stopped or a reflex took over; it lets go of what it holds, such as the bot's path. */
		default void cancel() {
		}

		/**
		 * How far the action got, saved with the bot: a resumed script starts its action again with the same arguments and
		 * hands it this ({@link #restore}). 0 for an action that starts over, such as {@code goto}, which plans its path again.
		 */
		default long progress() {
			return 0;
		}

		/** Goes on from {@code progress}, what {@link #progress} gave when the bot was saved. */
		default void restore(long progress) {
		}

		/** More the action saves beside {@link #progress}, such as the scaffold a build placed; none unless it says. */
		default long[] progressData() {
			return new long[0];
		}

		/** Goes on from {@code progress} and {@code data}, what {@link #progress} and {@link #progressData} gave when the bot was saved. */
		default void restore(long progress, long[] data) {
			this.restore(progress);
		}
	}

	/**
	 * A running script as saved with its bot (docs/features/scripts.md, Save and resume): its name and arguments, the state of
	 * its VM ({@link VmState}) and how far its action got. Without a VM state (the script held a value that cannot be
	 * saved, and {@code problem} tells which), it starts again from the top when the bot loads. {@code lastResult} is
	 * its last action's result, which {@code /antcraft bot status} shows. {@code progressData} is what the action saves
	 * beside its progress ({@link Action#progressData}), empty in saves from before it. {@code placed} is where the run's
	 * walks placed scaffold ({@link #takePlaced}), stored as {@link BlockPos#asLong} under {@code placed} with their dimensions
	 * under {@code placedDimensions}; a save from before that has none, and they are the bot's when it loads (#550).
	 */
	public record Saved(String name, List<String> args, byte @Nullable [] vm, long progress, long[] progressData, @Nullable String problem,
			@Nullable String lastResult, List<Pos> placed) {
		private static final String KEY = "Script";

		public Saved {
			args = List.copyOf(args);
			placed = List.copyOf(placed);
		}

		/** Stores it with the bot. */
		public void store(ValueOutput output) {
			CompoundTag tag = new CompoundTag();
			tag.putString("name", name);
			ListTag list = new ListTag();
			args.forEach(arg -> list.add(StringTag.valueOf(arg)));
			tag.put("args", list);
			if (vm != null) {
				tag.putByteArray("vm", vm);
				tag.putLong("progress", progress);
				if (progressData.length > 0) {
					tag.putLongArray("progressData", progressData);
				}
			}
			if (problem != null) {
				tag.putString("problem", problem);
			}
			if (lastResult != null) {
				tag.putString("lastResult", lastResult);
			}
			if (!placed.isEmpty()) {
				tag.putLongArray("placed", placed.stream().mapToLong(ScriptRun::asLong).toArray());
				ListTag dimensions = new ListTag();
				placed.forEach(pos -> dimensions.add(StringTag.valueOf(Objects.requireNonNullElse(pos.dimension(), ""))));
				tag.put("placedDimensions", dimensions);
			}
			output.store(KEY, CompoundTag.CODEC, tag);
		}

		/** The script saved with the bot, if it ran one. */
		public static Optional<Saved> read(ValueInput input) {
			return input.read(KEY, CompoundTag.CODEC).map(tag -> {
				ListTag list = tag.getListOrEmpty("args");
				List<String> args = new ArrayList<>();
				for (int i = 0; i < list.size(); i++) {
					args.add(list.getStringOr(i, ""));
				}
				long[] at = tag.getLongArray("placed").orElse(new long[0]);
				ListTag dimensions = tag.getListOrEmpty("placedDimensions");
				List<Pos> placed = new ArrayList<>();
				for (int i = 0; i < at.length; i++) {
					String dimension = dimensions.getStringOr(i, "");
					placed.add(new Pos(BlockPos.getX(at[i]), BlockPos.getY(at[i]), BlockPos.getZ(at[i]), dimension.isEmpty() ? null : dimension));
				}
				return new Saved(tag.getStringOr("name", ""), args, tag.getByteArray("vm").orElse(null),
						tag.getLongOr("progress", 0), tag.getLongArray("progressData").orElse(new long[0]), tag.getString("problem").orElse(null),
						tag.getString("lastResult").orElse(null), placed);
			});
		}
	}

	public enum State {
		/** Running, waiting on an action or out of budget for this tick. */
		RUNNING,
		FINISHED,
		FAILED,
		STOPPED
	}

	private final String name;
	/** The script's file, which errors name. */
	private final String file;
	private final List<String> args;
	private final Host host;
	private final BotLog log;
	private final Vm vm;
	/** The modules the script imported from the library, by name, with the source they had then. */
	private final Map<String, String> imported = new LinkedHashMap<>();
	private @Nullable Action action;
	private boolean stopRequested;
	private @Nullable String lastResult;
	private State state = State.RUNNING;
	/** Whether the run was resumed suspended in a native call, which it makes again on its first tick ({@link Vm#recall}). */
	private boolean recall;
	/** The progress of the action the run was resumed in, which it hands to the action it starts again. */
	private long progress;
	private long[] progressData = new long[0];
	/** Where the run's walks placed scaffold, oldest first, until {@code placed_scaffold()} takes them ({@link #takePlaced}). Saved. */
	private final LinkedHashSet<Pos> placed = new LinkedHashSet<>();
	/** How many {@link #placed} positions a run keeps, the last placed, for a script that never takes them. */
	static final int PLACED_MAX = 4096;
	/**
	 * Each position, in its dimension, where a running run's walk placed scaffold whose block has not changed since, to that
	 * run's {@link #id} (#539, #550). Server thread only.
	 * ponytail: a run whose bot is unloaded or discarded without it ending keeps its entries until their block changes or
	 * the server stops; a resumed run notes them again under its own id.
	 */
	private static final Map<Placed, Long> PLACED_BY = new HashMap<>();

	/** A key of {@link #PLACED_BY}: {@code pos} as {@link BlockPos#asLong} in {@code dimension}. */
	private record Placed(@Nullable String dimension, long pos) {
		Placed(Pos pos) {
			this(pos.dimension(), asLong(pos));
		}
	}
	private static long runs;
	/** Which run this is, in {@link #PLACED_BY}: a resumed run is a new one. */
	private final long id = ++runs;

	public ScriptRun(String name, Code code, List<String> args, Host host, BotLog log) {
		this(name, code, args, host, log, Memory.create());
	}

	/** A run whose global {@code memory} is {@code memory}, the bot's, which outlives the run ({@link Memory}). */
	public ScriptRun(String name, Code code, List<String> args, Host host, BotLog log, PyDict memory) {
		this(name, code, args, host, log, memory, ScriptLibrary.empty());
	}

	/**
	 * A run of the script {@code name} that imports its modules from {@code library} (docs/language/modules.md). Every
	 * module sees the bot API and {@code memory}; {@code ARGS} is a global of the script itself.
	 */
	public ScriptRun(String name, Code code, List<String> args, Host host, BotLog log, PyDict memory, ScriptLibrary library) {
		this.name = name;
		this.file = code.file();
		this.args = List.copyOf(args);
		this.host = host;
		this.log = log;
		this.vm = new Vm(name, code, this.natives(), this.loader(library));
		this.vm.setGlobal("ARGS", new PyList(new ArrayList<>(args)));
		this.vm.setBuiltin("memory", memory);
	}

	/** A run that goes on where {@code saved} was; its script is now compiled to {@code code}. */
	private ScriptRun(Saved saved, byte[] state, Code code, Host host, BotLog log, ScriptLibrary library) throws VmState.Unloadable {
		this.name = saved.name();
		this.file = code.file();
		this.args = saved.args();
		this.host = host;
		this.log = log;
		this.vm = VmState.load(state, code, this.natives(), this.loader(library), Vm.DEFAULT_MAX_DEPTH, new SavedValues(host::entity, host::colonyMemory),
				Memory::create);
		this.recall = this.vm.status() == Vm.Status.SUSPENDED;
		this.progress = saved.progress();
		this.progressData = saved.progressData();
	}

	/**
	 * Resumes the run {@code saved} with the bot (docs/features/scripts.md, Save and resume), its script now compiled to
	 * {@code code}: at the instruction it was at, starting the action it was in again with the same arguments. When the
	 * script or a module it imported changed since, or its state was not saved, the script starts again from the top
	 * with {@code memory}, with a line in the log that says why.
	 */
	public static ScriptRun resume(Saved saved, Code code, Host host, BotLog log, PyDict memory, ScriptLibrary library) {
		ScriptRun run = null;
		String why = saved.problem();
		if (saved.vm() != null) {
			try {
				run = new ScriptRun(saved, saved.vm(), code, host, log, library);
			} catch (VmState.Unloadable e) {
				why = e.getMessage();
			}
		}
		if (run == null) {
			log.add(host.gameTime(), Level.INFO, (why == null ? "" : why + ": ") + saved.name() + " starts again from the top");
			run = new ScriptRun(saved.name(), code, saved.args(), host, log, memory, library);
		}
		run.lastResult = saved.lastResult();
		// ponytail: a block changed while the bot was unloaded and the server ran on counts as unchanged.
		for (Pos at : saved.placed()) {
			run.notePlaced(at.dimension() == null ? at.in(host.pos().dimension()) : at);
		}
		return run;
	}

	/** Saves the run with the bot; see {@link #resume}. */
	public Saved save() {
		long saveProgress = this.recall ? this.progress : this.action == null ? 0 : this.action.progress();
		long[] saveData = this.recall ? this.progressData : this.action == null ? new long[0] : this.action.progressData();
		List<Pos> savePlaced = this.placed.stream().filter(this::owns).toList();
		try {
			return new Saved(this.name, this.args, VmState.save(this.vm, new SavedValues(this.host::entity, this.host::colonyMemory)), saveProgress, saveData,
					null, this.lastResult, savePlaced);
		} catch (VmState.NotSaveable e) {
			return new Saved(this.name, this.args, null, 0, new long[0], e.getMessage(), this.lastResult, savePlaced);
		}
	}

	/**
	 * Notes that one of the run's walks placed scaffold at {@code pos}, for {@code placed_scaffold()} ({@link #takePlaced}).
	 * Keeps the last {@link #PLACED_MAX}.
	 */
	public void notePlaced(Pos pos) {
		this.placed.remove(pos);
		this.placed.add(pos);
		PLACED_BY.put(new Placed(pos), this.id);
		if (this.placed.size() > PLACED_MAX) {
			this.disown(this.placed.removeFirst());
		}
	}

	/** Whether {@code pos}'s block is still this run's scaffold. */
	private boolean owns(Pos pos) {
		return Objects.equals(PLACED_BY.get(new Placed(pos)), this.id);
	}

	/** Whether {@code pos}'s block was still this run's scaffold, which it no longer is. */
	private boolean disown(Pos pos) {
		return PLACED_BY.remove(new Placed(pos), this.id);
	}

	/**
	 * Where the run's walks placed scaffold since the last call, oldest first, leaving out each whose block changed
	 * since ({@link #blockChanged}); none noted after.
	 */
	public List<Pos> takePlaced() {
		List<Pos> out = this.placed.stream().filter(this::disown).toList();
		this.placed.clear();
		return out;
	}

	/** Takes the run's scaffold out of {@link #PLACED_BY}, as it ends (#550). */
	private void ended() {
		this.placed.forEach(this::disown);
		this.placed.clear();
	}

	/** Whether any run's scaffold is noted, so {@link #blockChanged} has something to do. */
	public static boolean anyPlaced() {
		return !PLACED_BY.isEmpty();
	}

	/**
	 * Notes that the block at {@code pos} ({@link BlockPos#asLong}) in {@code dimension} changed, as the server does for
	 * each block that does (ServerLevelMixin): scaffold placed there is no run's any more, even once the same block is
	 * back (#539).
	 */
	public static void blockChanged(String dimension, long pos) {
		if (!PLACED_BY.isEmpty()) {
			PLACED_BY.remove(new Placed(dimension, pos));
		}
	}

	/** How many positions {@link #PLACED_BY} holds, for tests. */
	static int indexed() {
		return PLACED_BY.size();
	}

	/** Forgets whose scaffold every position holds, when the server stops. */
	public static void forgetPlacedBy() {
		PLACED_BY.clear();
	}

	private static long asLong(Pos pos) {
		return BlockPos.asLong((int) pos.x(), (int) pos.y(), (int) pos.z());
	}

	/** The script's {@code memory}: the bot's, or the one a resumed run was saved with, with the run's own references into it. */
	public PyDict memory() {
		return (PyDict) Objects.requireNonNull(this.vm.builtin("memory"));
	}

	private Natives natives() {
		Natives natives = Natives.standard(this::write);
		BotApi.register(natives, this);
		return natives;
	}

	/** The library's modules, recording the source of each one imported. */
	private ModuleLoader loader(ScriptLibrary library) {
		return new ModuleLoader() {
			@Override
			public @Nullable Code load(String module) {
				library.get(module).ifPresent(script -> imported.put(module, script.source()));
				return library.load(module);
			}

			@Override
			public boolean isPackage(String module) {
				return library.isPackage(module);
			}
		};
	}

	/** Counts {@code instructions} against the script's budget for work a native did, such as a block search. */
	void charge(long instructions) {
		vm.charge(instructions);
	}

	/** Writes a line of output: to the log, and to the owner's chat from the chat level up. */
	void write(Level level, String line) {
		log.add(host.gameTime(), level, line);
		if (BotLog.showsInChat(level, host.chatLevel())) {
			host.chat(level, line);
		}
	}

	/** {@code say(text)}: the host shows it, and it goes to the log as a print. */
	void say(String text) {
		log.add(host.gameTime(), Level.PRINT, text);
		host.say(text);
	}

	Host host() {
		return host;
	}

	/** Runs one server tick of the script with at most {@code budget} instructions; see the class comment. */
	public void tick(long budget) {
		if (state != State.RUNNING) {
			return;
		}
		Vm.Status status;
		// Each tick, so it follows the bot into and out of a colony, and a resumed run uses the colony's live dict.
		vm.setBuiltin("colony_memory", host.colonyMemory());
		try {
			if (recall) {
				recall = false;
				if (vm.recall() == Vm.Status.SUSPENDED && action != null) {
					action.restore(progress, progressData);
				}
			}
			// After a resumed run restored its action, so a reflex interrupts that as any other.
			if (reflexes()) {
				return;
			}
			if (action != null) {
				if (!action.tick()) {
					return;
				}
				Object result = action.result();
				if (result instanceof Result r) {
					done(action.describe(), r);
				}
				action = null;
				vm.resume(result);
			}
			status = vm.run(budget);
		} catch (RuntimeException e) {
			// A bug in a native or an action, not in the script: stop the script rather than the bot's tick.
			AntCraft.LOGGER.warn("Script {} failed with an internal error at {}:{}", name, file, vm.line(), e);
			action = null;
			state = State.FAILED;
			ended();
			write(Level.ERROR, file + ":" + vm.line() + ": internal error: " + e);
			return;
		}
		switch (status) {
			case FINISHED -> state = State.FINISHED;
			case FAILED -> {
				state = State.FAILED;
				write(Level.ERROR, vm.error().getMessage());
			}
			case SUSPENDED -> {
				if (stopRequested) {
					state = State.STOPPED;
				} else if (action == null) {
					throw new IllegalStateException(name + ": a native paused the script without starting an action");
				}
			}
			case RUNNING -> {
			}
		}
		if (state != State.RUNNING) {
			ended();
		}
	}

	/**
	 * Whether a reflex holds the bot this tick ({@link Host#reflex}): then the current action, if any, ends with
	 * {@code interrupted}, which the script goes on with once the reflex lets go, and the script does not run. An action
	 * that {@link Action#outlastsReflexes outlasts reflexes} only pauses.
	 */
	private boolean reflexes() {
		if (!host.reflex()) {
			return false;
		}
		if (action != null && !action.outlastsReflexes()) {
			Action interrupted = action;
			action = null;
			interrupted.cancel();
			Result result = Result.fail(Result.INTERRUPTED);
			done(interrupted.describe(), result);
			vm.resume(result);
		}
		return true;
	}

	/** Starts {@code action} for the native call being made: the native returns what this gives, pausing the script. */
	Object start(Action action, NativeCall call) {
		this.action = action;
		return call.suspend();
	}

	/**
	 * Starts {@code action} on {@code target}, or fails it at once with {@code wrong_dimension} when {@code target} is
	 * in another dimension than the bot's (#357).
	 */
	Object start(Action action, NativeCall call, @Nullable Pos target) {
		return elsewhere(target) ? done(action.describe(), Result.fail(Result.WRONG_DIMENSION)) : start(action, call);
	}

	/**
	 * Whether {@code pos} is in another dimension than the bot's: every native that takes a position refuses it, an
	 * action with {@code wrong_dimension}, a sense with False or None (#357). A position without a dimension is the bot's.
	 */
	boolean elsewhere(@Nullable Pos pos) {
		return pos != null && pos.dimension() != null && !pos.dimension().equals(host.body().dimension());
	}

	/**
	 * The result of an action done at once, without ticks (such as {@code equip}): logged at debug when it failed, as
	 * an action's is.
	 */
	Result done(String describe, Result result) {
		lastResult = describe(describe, result);
		if (!result.ok()) {
			write(Level.DEBUG, describe + " -> " + why(result));
		}
		return result;
	}

	/** Ends the script at the end of the native call being made, as {@code stop()} does. */
	Object requestStop(NativeCall call) {
		this.stopRequested = true;
		return call.suspend();
	}

	/** Ends the script from outside, such as by {@code /antcraft bot stop}; the current action is dropped. */
	public void stop() {
		if (state == State.RUNNING) {
			state = State.STOPPED;
			if (action != null) {
				action.cancel();
			}
			action = null;
			ended();
		}
	}

	public String name() {
		return name;
	}

	public List<String> args() {
		return args;
	}

	public State state() {
		return state;
	}

	/** Whether the script is still running: not finished, failed or stopped. */
	public boolean running() {
		return state == State.RUNNING;
	}

	/** The source line about to run, or the line waiting on an action; 0 once the script finished. */
	public int line() {
		return vm.line();
	}

	/** What the current action is doing, or null while the script runs no action. */
	public @Nullable String action() {
		return action == null ? null : action.describe();
	}

	/** Whether the script runs an action that walks the bot ({@link Action#walks}), such as {@code goto}. */
	public boolean walking() {
		return action != null && action.walks();
	}

	/** The last action the script finished and its result ({@link #describe}), or null before the first. */
	public @Nullable String lastResult() {
		return lastResult;
	}

	/** An action and its result as {@code /antcraft bot status} shows them: {@code goto(...) -> unreachable}, {@code craft(...) -> ok, 4}. */
	static String describe(String action, Result result) {
		String line = action + " -> " + (result.ok() ? "ok" : why(result));
		return result.value() instanceof PyNone ? line : line + ", " + Values.repr(result.value());
	}

	/** A failure's reason, and its detail after a colon where it has one: {@code blocked: minecraft:water is in the way at Pos(1, 2, 3)}. */
	private static String why(Result result) {
		return result.detail() == null ? result.reason() : result.reason() + ": " + result.detail();
	}

	/** The modules the script imported from its library, by name, with the source each had when it was imported. */
	public Map<String, String> imported() {
		return Collections.unmodifiableMap(imported);
	}

	/** Whether a module the script imported has another source in {@code library}, or is gone from it. */
	public boolean importsChanged(ScriptLibrary library) {
		return imported.entrySet().stream().anyMatch(module -> !Objects.equals(module.getValue(),
				library.get(module.getKey()).map(ScriptLibrary.Script::source).orElse(null)));
	}

	/** How many instructions the script ran so far. */
	public long executed() {
		return vm.executed();
	}
}
