package io.github.barniebarnie.antcraft.script.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The instruction budget, pausing between instructions, and natives that suspend the script. */
class VmBudgetTest {
	private static final String PROGRAM = """
			def collatz(n):
			    steps = 0
			    while n != 1:
			        n = n // 2 if n % 2 == 0 else 3 * n + 1
			        steps += 1
			    return steps

			def describe(d, keys=None):
			    out = []
			    for k in d:
			        if keys is None or k in keys:
			            out += [(k, d[k])]
			    return out

			lengths = {}
			for i in range(1, 30):
			    lengths[i] = collatz(i)
			best = 1
			for i in lengths:
			    if lengths[i] > lengths[best]:
			        best = i
			words = ['ant', 'craft', 'bot']
			joined = ''
			for w in words[::-1]:
			    joined += w[:2]
			a, (b, c) = 1, [2.5, 'x' * 3]
			print(best, lengths[best], joined, a, b, c, describe({'x': 1, 'y': [2]}, keys=('y',)))
			""";

	private static Vm vm(String source, List<String> printed) {
		return new Vm(Compiler.compile(Parser.parse("t.py", source)), Natives.standard(Output.lines(printed::add)));
	}

	@Test
	void runningInSlicesOfOneGivesTheSameResultAsRunningAtOnce() {
		runInSlices(1);
	}

	@Test
	void runningInSlicesOfSevenGivesTheSameResultAsRunningAtOnce() {
		runInSlices(7);
	}

	@Test
	void runningInSlicesOfAThousandGivesTheSameResultAsRunningAtOnce() {
		runInSlices(1000);
	}

	private static void runInSlices(int slice) {
		List<String> once = new ArrayList<>();
		Vm whole = vm(PROGRAM, once);
		assertEquals(Vm.Status.FINISHED, whole.run(10_000_000));
		assertEquals(List.of("27 111 bocran 1 2.5 xxx [('y', [2])]"), once);

		List<String> sliced = new ArrayList<>();
		Vm vm = vm(PROGRAM, sliced);
		Vm.Status status;
		int runs = 0;
		do {
			long before = vm.executed();
			status = vm.run(slice);
			runs++;
			assertTrue(vm.executed() - before <= slice);
		} while (status == Vm.Status.RUNNING);
		assertEquals(Vm.Status.FINISHED, status);
		assertEquals(once, sliced);
		assertEquals(whole.executed(), vm.executed());
		assertEquals(whole.globals().keySet(), vm.globals().keySet());
		assertTrue(runs >= whole.executed() / slice);
	}

	@Test
	void anEndlessLoopReportsRunningAfterExactlyItsBudget() {
		Vm vm = vm("while True: pass", new ArrayList<>());
		assertEquals(Vm.Status.RUNNING, vm.run(2000));
		assertEquals(2000, vm.executed());
		assertEquals(Vm.Status.RUNNING, vm.run(2000));
		assertEquals(4000, vm.executed());
	}

	@Test
	void aNativeThatSuspendsPausesTheScriptUntilResumedWithTheCallsResult() {
		List<String> printed = new ArrayList<>();
		Natives natives = Natives.standard(Output.lines(printed::add));
		List<List<Object>> asked = new ArrayList<>();
		natives.register("ask", call -> {
			asked.add(call.args());
			return call.suspend();
		});
		Vm vm = new Vm(Compiler.compile(Parser.parse("t.py", "x = ask('q', 2) + 1\nprint(x)")), natives);

		assertEquals(Vm.Status.SUSPENDED, vm.run(1000));
		assertEquals(List.of(List.of("q", 2L)), asked);
		long executed = vm.executed();
		assertEquals(Vm.Status.SUSPENDED, vm.run(1000));
		assertEquals(executed, vm.executed());
		assertEquals(List.of(), printed);

		vm.resume(41L);
		assertEquals(Vm.Status.FINISHED, vm.run(1000));
		assertEquals(List.of("42"), printed);
	}

	@Test
	void aNativeThatReturnsAValueDoesNotPause() {
		List<String> printed = new ArrayList<>();
		Natives natives = Natives.standard(Output.lines(printed::add));
		natives.register("twice", call -> (Long) call.args().get(0) * 2);
		Vm vm = new Vm(Compiler.compile(Parser.parse("t.py", "print(twice(21))")), natives);
		assertEquals(Vm.Status.FINISHED, vm.run(1000));
		assertEquals(List.of("42"), printed);
	}

	@Test
	void nativesSeeKeywordArguments() {
		List<String> printed = new ArrayList<>();
		Natives natives = Natives.standard(Output.lines(printed::add));
		natives.register("near", call -> call.kwargs().getOrDefault("near", 0L));
		Vm vm = new Vm(Compiler.compile(Parser.parse("t.py", "print(near(1), near(1, near=3))")), natives);
		assertEquals(Vm.Status.FINISHED, vm.run(1000));
		assertEquals(List.of("0 3"), printed);
	}

	@Test
	void methodsRegisteredForATypeAreCalledOnItsValues() {
		List<String> printed = new ArrayList<>();
		Natives natives = Natives.standard(Output.lines(printed::add));
		natives.registerMethod("str", "shout", call -> call.args().get(0) + "!");
		Vm vm = new Vm(Compiler.compile(Parser.parse("t.py", "s = 'hey'\nprint(s.shout())")), natives);
		assertEquals(Vm.Status.FINISHED, vm.run(1000));
		assertEquals(List.of("hey!"), printed);
	}

	@Test
	void chargedInstructionsCountAgainstThisAndLaterBudgets() {
		Natives natives = Natives.standard(Output.lines(line -> {
		}));
		Vm[] holder = new Vm[1];
		natives.register("costly", call -> {
			holder[0].charge(4500);
			return PyNone.NONE;
		});
		// Each loop pass runs a handful of instructions besides the call.
		Vm vm = new Vm(Compiler.compile(Parser.parse("t.py", "n = 0\nwhile True:\n    costly()\n    n += 1\n")), natives);
		holder[0] = vm;
		assertEquals(Vm.Status.RUNNING, vm.run(2000));
		long first = vm.executed();
		assertTrue(first >= 4500 && first < 4520, first + " instructions counted");
		assertEquals(0L, vm.globals().get("n"));
		// The rest of the charge uses up the whole of the next budget, and some of the one after.
		assertEquals(Vm.Status.RUNNING, vm.run(2000));
		assertEquals(first, vm.executed());
		assertEquals(0L, vm.globals().get("n"));
		assertEquals(Vm.Status.RUNNING, vm.run(2000));
		assertEquals(1L, vm.globals().get("n"));
	}

	@Test
	void aNativeWithACostUsesUpTheBudgetAsThatManyInstructionsEachCall() {
		long[] calls = new long[2];
		Natives natives = Natives.standard(Output.lines(line -> {
		}));
		natives.register("cheap", call -> {
			calls[0]++;
			return PyNone.NONE;
		});
		natives.register("dear", 200, call -> {
			calls[1]++;
			return PyNone.NONE;
		});
		assertEquals(200, natives.cost("dear"));
		assertEquals(0, natives.cost("cheap"));
		Vm cheap = new Vm(Compiler.compile(Parser.parse("t.py", "while True:\n    cheap()\n")), natives);
		Vm dear = new Vm(Compiler.compile(Parser.parse("t.py", "while True:\n    dear()\n")), natives);
		assertEquals(Vm.Status.RUNNING, cheap.run(10_000));
		assertEquals(Vm.Status.RUNNING, dear.run(10_000));
		// A loop pass is a few instructions plus the call, which counts as 200 more for dear().
		long pass = 10_000 / calls[0];
		assertTrue(pass >= 2 && pass <= 6, pass + " instructions a pass");
		assertTrue(calls[1] >= 10_000 / (200 + pass) && calls[1] <= 10_000 / 200 + 1, calls[1] + " calls of dear()");
		// What the last call cost beyond the budget comes off the next one.
		assertTrue(dear.executed() >= 10_000 && dear.executed() < 10_000 + 200 + pass, dear.executed() + " instructions counted");
		long before = calls[1];
		assertEquals(Vm.Status.RUNNING, dear.run(10_000));
		assertTrue(calls[1] - before <= 10_000 / 200 + 1);
	}
}
