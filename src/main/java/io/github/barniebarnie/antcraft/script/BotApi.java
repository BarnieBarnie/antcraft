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
 */
public final class BotApi {
	private BotApi() {
	}

	public static void register(Natives natives, ScriptRun run) {
		Pos.register(natives);
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
			return n <= 0 ? PyNone.NONE : run.start(new Wait(n), call);
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
			return PyNone.NONE;
		}

		@Override
		public String describe() {
			return "wait(" + ticks + ")";
		}
	}
}
