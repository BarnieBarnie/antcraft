package io.github.barniebarnie.antcraft.script.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code print}, {@code debug}, {@code info}, {@code warn} and {@code error} write lines with their level. */
class OutputTest {
	@Test
	void eachPrintFunctionWritesAtItsLevel() {
		List<String> lines = new ArrayList<>();
		Natives natives = Natives.standard((level, line) -> lines.add(level + " " + line));
		Vm vm = new Vm(Compiler.compile(Parser.parse("t.py", """
				print('p', 1)
				debug('d', [1])
				info('i')
				warn('w', 2.5)
				error('e', None, sep='-')
				print('still running')
				""")), natives);
		assertEquals(Vm.Status.FINISHED, vm.run(1000));
		assertEquals(List.of("PRINT p 1", "DEBUG d [1]", "INFO i", "WARN w 2.5", "ERROR e-None", "PRINT still running"),
				lines);
	}

	@Test
	void printIsAboveEveryLogLevel() {
		for (Output.Level level : Output.Level.values()) {
			assertEquals(true, Output.Level.PRINT.compareTo(level) >= 0, level.name());
		}
		assertEquals(true, Output.Level.ERROR.compareTo(Output.Level.WARN) > 0);
		assertEquals(true, Output.Level.WARN.compareTo(Output.Level.INFO) > 0);
		assertEquals(true, Output.Level.INFO.compareTo(Output.Level.DEBUG) > 0);
	}
}
