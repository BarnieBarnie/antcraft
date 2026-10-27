package io.github.barniebarnie.antcraft.script.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

/** Runs test scripts with the standard builtins. */
final class Scripts {
	private Scripts() {
	}

	/** Runs a script to its end and gives what it printed, one line per {@code print}. */
	static String run(String source) {
		List<String> printed = new ArrayList<>();
		Vm vm = new Vm(Compiler.compile(Parser.parse("t.py", source)), Natives.standard(Output.lines(printed::add)));
		Vm.Status status = vm.run(1_000_000);
		if (status == Vm.Status.FAILED) {
			throw vm.error();
		}
		assertEquals(Vm.Status.FINISHED, status);
		return String.join("\n", printed);
	}

	/** What {@code print(expression)} shows. */
	static String eval(String expression) {
		return run("print(" + expression + ")");
	}

	/** Runs a script that must fail and gives its error message, which names file {@code t.py}. */
	static String fails(String source) {
		Vm vm = new Vm(Compiler.compile(Parser.parse("t.py", source)), Natives.standard(Output.lines(line -> {
		})));
		assertEquals(Vm.Status.FAILED, vm.run(1_000_000));
		return vm.error().getMessage();
	}

	/** The message of the error {@code expression} fails with, without the position. */
	static String error(String expression) {
		String message = fails("x = " + expression);
		assertEquals("t.py:1: ", message.substring(0, 8), message);
		return message.substring(8);
	}
}
