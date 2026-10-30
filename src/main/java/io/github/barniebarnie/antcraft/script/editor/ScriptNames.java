package io.github.barniebarnie.antcraft.script.editor;

import io.github.barniebarnie.antcraft.script.BotApi;
import io.github.barniebarnie.antcraft.script.lang.Natives;
import io.github.barniebarnie.antcraft.script.lang.PyException;
import java.util.Set;
import java.util.TreeSet;

/** The names a script has without defining them, which the editor colours (docs/features/scripts.md, Editing in game). */
public final class ScriptNames {
	private ScriptNames() {
	}

	/**
	 * The builtins, the bot API's functions and the exception types, as a bot's script sees them: registered natives,
	 * so a new one is here without more code. Methods ({@code type.name}) and internal helpers ({@code $name}) are left
	 * out.
	 */
	public static Set<String> natives() {
		Natives natives = Natives.standard(null);
		// Registering only stores the functions; none runs, so no bot is needed.
		BotApi.register(natives, null);
		Set<String> names = new TreeSet<>();
		for (String name : natives.names()) {
			if (!name.contains(".") && !name.startsWith("$")) {
				names.add(name);
			}
		}
		names.addAll(PyException.types());
		// ponytail: the builtin values ScriptRun sets, named here by hand.
		names.add("memory");
		names.add("colony_memory");
		return names;
	}
}
