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

	/** {@code Pos(x, y, z, dimension=None)} makes a position; a tuple or a Pos as the only argument converts it (#416). */
	@Test
	void theConstructorMakesAPositionOrConvertsATupleOrAPos() {
		assertEquals("Pos(1, 3, 3) 1 None", run("q = Pos(1, 2, 3).up()\nprint(q, q.x, q.dimension)"));
		assertEquals("True True False Pos(1, 2, 3)", run("print(Pos((1, 2, 3)) == p, Pos(p) == p, Pos((1, 2, 3)) == (1, 2, 3), Pos((1, 2, 3)))"));
		assertEquals("minecraft:the_nether minecraft:the_nether None",
			run("print(Pos(1, 2, 3, 'minecraft:the_nether').dimension, Pos((1, 2, 3), dimension='minecraft:the_nether').dimension,"
				+ " Pos(x=1, y=2, z=3).dimension)"));
		assertEquals("minecraft:the_nether minecraft:the_end", run("print(Pos(p).dimension, Pos(p, dimension='minecraft:the_end').dimension)",
			new Pos(1, 2, 3, "minecraft:the_nether")));
	}

	@Test
	void theConstructorStopsTheScriptOnAnythingButInts() {
		assertEquals("error: t.py:1: Pos() takes ints x, y and z, not 'str'", run("q = Pos(1, 'a', 3)"));
		assertEquals("error: t.py:1: Pos() takes ints x, y and z, not 'float'", run("q = Pos(1.5, 2, 3)"));
		assertEquals("error: t.py:1: Pos() takes ints x, y and z, not 'NoneType'", run("q = Pos(1, 2)"));
		assertEquals("error: t.py:1: a position is a Pos or a tuple of three ints, not 'str'", run("q = Pos('home')"));
		assertEquals("error: t.py:1: a position is a Pos or a tuple of three ints, not 'tuple'", run("q = Pos((1, 2))"));
		assertEquals("error: t.py:1: Pos() takes a dimension id such as 'minecraft:overworld' or None, not 'int'",
			run("q = Pos(1, 2, 3, 0)"));
		assertEquals("error: t.py:1: Pos() takes at least one argument (0 given)", run("q = Pos()"));
	}

	@Test
	void isinstanceKnowsPos() {
		assertEquals("True False True False", run("print(isinstance(p, Pos), isinstance((1, 2, 3), Pos), isinstance(p, (int, Pos)), isinstance(p, tuple))"));
	}

	@Test
	void wrongOperandsAreScriptErrors() {
		assertEquals("error: t.py:1: unsupported operand type(s) for +: 'Pos' and 'int'", run("x = p + 1"));
		assertEquals("error: t.py:1: 'Pos' object has no attribute 'w'", run("x = p.w"));
		assertEquals("error: t.py:1: a position is a Pos or a tuple of three ints, not 'str'", run("x = p.distance('home')"));
	}
}
