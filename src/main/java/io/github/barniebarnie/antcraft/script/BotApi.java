package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.script.lang.Args;
import io.github.barniebarnie.antcraft.script.lang.Natives;
import io.github.barniebarnie.antcraft.script.lang.PyNone;
import io.github.barniebarnie.antcraft.script.lang.ScriptError;
import io.github.barniebarnie.antcraft.script.lang.Values;

/**
 * The bot API a script has besides the language's builtins (docs/scripting.md, The bot API): the natives of one
 * {@link ScriptRun}. Senses return at once; actions start a {@link ScriptRun.Action} and pause the script until it is done.
 * New senses and actions are registered here.
 *
 * <p>A native that is done at once and touches the world (such as {@code craft}, {@code drop}, {@code count} or
 * {@code entities}) is registered with a cost in instructions (see {@link Natives#register(String, long,
 * io.github.barniebarnie.antcraft.script.lang.NativeFunction)}), so a script that loops on it stays within about the time
 * its budget allows. Each cost is the native's time per call in the benchmark divided by that of a plain instruction,
 * about 20 ns there (#328). Natives that cost about an instruction ({@code pos}, {@code time}) have none, and nor do
 * actions that pause the script, as a bot makes at most one of them a tick. Natives that read many blocks, such as
 * {@code scan} or {@code reachable}, charge for the blocks they read instead.
 */
public final class BotApi {
	private BotApi() {
	}

	public static void register(Natives natives, ScriptRun run) {
		Pos.register(natives);
		SenseApi.register(natives, run);
		MoveApi.register(natives, run);
		WorkApi.register(natives, run);
		CraftApi.register(natives, run);
		BuildApi.register(natives, run);
		natives.register("pos", call -> {
			Args.bind("pos", call, 0);
			return run.host().pos();
		});
		natives.register("say", call -> {
			String text = Values.str(Args.bind("say", call, 1, "text")[0]);
			run.say(text);
			return PyNone.NONE;
		});
		natives.register("wait", call -> {
			Object ticks = Args.bind("wait", call, 1, "ticks")[0];
			if (!(ticks instanceof Long n)) {
				throw new ScriptError("wait() takes a number of ticks (int), not '" + Values.typeName(ticks) + "'");
			}
			return n <= 0 ? Result.OK : run.start(new Wait(n), call);
		});
		natives.register("stop", call -> {
			Args.bind("stop", call, 0);
			return run.requestStop(call);
		});
	}

	/** {@code wait(ticks)}: the script goes on {@code ticks} ticks after the call. */
	private static final class Wait implements ScriptRun.Action {
		private final long ticks;
		private long left;

		Wait(long ticks) {
			this.ticks = ticks;
			this.left = ticks;
		}

		@Override
		public boolean tick() {
			return --left <= 0;
		}

		@Override
		public Object result() {
			return Result.OK;
		}

		@Override
		public String describe() {
			return "wait(" + ticks + ")";
		}

		@Override
		public long progress() {
			return ticks - left;
		}

		@Override
		public void restore(long progress) {
			left = ticks - progress;
		}
	}
}
