package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.script.lang.Code;
import io.github.barniebarnie.antcraft.script.lang.NativeCall;
import io.github.barniebarnie.antcraft.script.lang.Natives;
import io.github.barniebarnie.antcraft.script.lang.Output.Level;
import io.github.barniebarnie.antcraft.script.lang.PyDict;
import io.github.barniebarnie.antcraft.script.lang.PyList;
import io.github.barniebarnie.antcraft.script.lang.Vm;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A script running on a bot (docs/scripting.md, Runtime). The bot calls {@link #tick} once a server tick, which:
 * <ol>
 * <li>runs the reflexes ({@link #reflexes}; none yet);</li>
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

		/** The block the bot stands in. */
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
	}

	/** Something a script waits on that takes ticks, such as {@code wait(ticks)}. */
	public interface Action {
		/** Advances the action by one server tick; true once it is done. */
		boolean tick();

		/** The result of the script's call, once the action is done. */
		Object result();

		/** What the action is doing, for {@code /antcraft bot status}. */
		String describe();
	}

	public enum State {
		/** Running, waiting on an action or out of budget for this tick. */
		RUNNING,
		FINISHED,
		FAILED,
		STOPPED
	}

	private final String name;
	private final List<String> args;
	private final Host host;
	private final BotLog log;
	private final Vm vm;
	private @Nullable Action action;
	private boolean stopRequested;
	private State state = State.RUNNING;

	public ScriptRun(String name, Code code, List<String> args, Host host, BotLog log) {
		this(name, code, args, host, log, Memory.create());
	}

	/** A run whose global {@code memory} is {@code memory}, the bot's, which outlives the run ({@link Memory}). */
	public ScriptRun(String name, Code code, List<String> args, Host host, BotLog log, PyDict memory) {
		this.name = name;
		this.args = List.copyOf(args);
		this.host = host;
		this.log = log;
		Natives natives = Natives.standard(this::write);
		BotApi.register(natives, this);
		this.vm = new Vm(code, natives);
		this.vm.setGlobal("ARGS", new PyList(new ArrayList<>(args)));
		this.vm.setGlobal("memory", memory);
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
		reflexes();
		if (action != null) {
			if (!action.tick()) {
				return;
			}
			Object result = action.result();
			action = null;
			vm.resume(result);
		}
		switch (vm.run(budget)) {
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
	}

	/** Where reflexes (hazards, health, drowning) will run before the script each tick, and may end the action. None yet. */
	private void reflexes() {
	}

	/** Starts {@code action} for the native call being made: the native returns what this gives, pausing the script. */
	Object start(Action action, NativeCall call) {
		this.action = action;
		return call.suspend();
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
			action = null;
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

	/** How many instructions the script ran so far. */
	public long executed() {
		return vm.executed();
	}
}
