package io.github.barniebarnie.antcraft.script;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.barniebarnie.antcraft.script.lang.Compiler;
import io.github.barniebarnie.antcraft.script.lang.Natives;
import io.github.barniebarnie.antcraft.script.lang.Output;
import io.github.barniebarnie.antcraft.script.lang.Parser;
import io.github.barniebarnie.antcraft.script.lang.Vm;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The {@code Pos} type of the bot API (docs/reference/types/pos.md). */
class PosTest {
	/** Runs {@code source} with a global {@code p} = {@code Pos(1, 2, 3)} and gives what it printed. */
	private static String run(String source) {
		return run(source, new Pos(1, 2, 3));
	}

	/** Runs {@code source} with a global {@code p} = {@code pos} and gives what it printed. */
	private static String run(String source, Pos pos) {
		List<String> printed = new ArrayList<>();
		Natives natives = Natives.standard(Output.lines(printed::add));
		Pos.register(natives);
		Vm vm = new Vm(Compiler.compile(Parser.parse("t.py", source)), natives);
		vm.setGlobal("p", pos);
		Vm.Status status = vm.run(100_000);
		if (status == Vm.Status.FAILED) {
			return "error: " + vm.error().getMessage();
		}
		return String.join("\n", printed);
	}

	@Test
	void hasItsCoordinatesAndPrintsAsPos() {
		assertEquals("1 2 3\nPos(1, 2, 3)\nPos(-4, 0, 7)", run("print(p.x, p.y, p.z)\nprint(p)\nprint(f'{p - (5, 2, -4)}')"));
		assertEquals("[Pos(1, 2, 3)]", run("print([p])"));
	}

	@Test
	void stepsInEachDirection() {
		assertEquals("Pos(1, 3, 3) Pos(1, 0, 3) Pos(1, 2, 2) Pos(1, 2, 4) Pos(2, 2, 3) Pos(-2, 2, 3)",
			run("print(p.up(), p.down(2), p.north(), p.south(), p.east(), p.west(n=3))"));
	}

	@Test
	void addsAndSubtractsPositionsAndTuples() {
		assertEquals("Pos(2, 4, 6) Pos(0, 0, 0) Pos(2, 2, 2) Pos(0, 2, 4) Pos(0, 0, 0)",
			run("print(p + p, p - p, p + (1, 0, -1), (-1, 0, 1) + p, (1, 2, 3) - p)"));
	}

	@Test
	void measuresDistance() {
		assertEquals("5.0 0.0", run("q = p + (3, 4, 0)\nprint(p.distance(q), p.distance(p))"));
		assertEquals("1.0", run("print(p.distance((1, 2, 4)))"));
	}

	@Test
	void isEqualByCoordinatesAndUsableAsADictKey() {
		assertEquals("True False 2", run("""
			d = {p: 1}
			d[p.up().down()] = 2
			print(p == p.up().down(), p == (1, 2, 3), d[p])
			"""));
	}

	/**
	 * A position from {@code home()} carries its dimension: it prints as any other, compares by its coordinates, and
	 * what is made from it keeps the dimension. One a script made has none (#349).
	 */
	@Test
	void keepsItsDimensionThroughArithmeticButNotInEqualityOrPrinting() {
		Pos nether = new Pos(1, 2, 3, "minecraft:the_nether");
		assertEquals("Pos(1, 2, 3) minecraft:the_nether True 1",
			run("print(p, p.dimension, p == p.down().up(), len({p: 1, p.up().down(): 2}))", nether));
		assertEquals(new Pos(1, 2, 3), nether);
		assertEquals(new Pos(1, 2, 3).hashCode(), nether.hashCode());
		assertEquals("minecraft:the_nether minecraft:the_nether minecraft:the_nether minecraft:the_nether",
			run("print(p.up().dimension, (p + (1, 0, 0)).dimension, ((1, 0, 0) + p).dimension, ((1, 0, 0) - p).dimension)", nether));
		assertEquals("None None", run("print(p.dimension, (p + p).dimension)"));
	}

	@Test
	void wrongOperandsAreScriptErrors() {
		assertEquals("error: t.py:1: unsupported operand type(s) for +: 'Pos' and 'int'", run("x = p + 1"));
		assertEquals("error: t.py:1: 'Pos' object has no attribute 'w'", run("x = p.w"));
		assertEquals("error: t.py:1: a position is a Pos or a tuple of three ints, not 'str'", run("x = p.distance('home')"));
	}
}
