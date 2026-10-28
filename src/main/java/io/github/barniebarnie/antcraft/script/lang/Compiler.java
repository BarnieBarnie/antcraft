package io.github.barniebarnie.antcraft.script.lang;

import io.github.barniebarnie.antcraft.script.lang.Ast.Expr;
import io.github.barniebarnie.antcraft.script.lang.Ast.Stmt;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles a parsed module to bytecode for {@link Vm}: one {@link Code} for the module's top level and one for each
 * function, held as a constant of the code that defines it.
 *
 * <p>Names follow Python's scoping without closures: at the top level every name is global; in a function a name is
 * local when the function binds it (assigns it, loops over it, defines it or has it as a parameter) and does not
 * declare it {@code global}, and global otherwise. A global that is not set falls back to the natives (builtins). A
 * list comprehension is compiled inline, and its variables get hidden local slots of their own, so they do not leak.
 */
public final class Compiler {
	private final String file;
	private final String name;
	/** Null at the top level, where every name is global. */
	private final Map<String, Integer> locals;
	private final List<Op> ops = new ArrayList<>();
	private final List<Integer> args = new ArrayList<>();
	private final List<Integer> lines = new ArrayList<>();
	private final List<Object> constants = new ArrayList<>();
	private final Map<String, Integer> names = new HashMap<>();
	private final Deque<Loop> loops = new ArrayDeque<>();
	/**
	 * The names of the local slots: the function's locals, then one slot for each variable of each comprehension. A
	 * comprehension's variables are hidden slots, so they do not leak into the scope around it, even at the top level.
	 */
	private final List<String> localNames = new ArrayList<>();
	/** The variables of the comprehensions being compiled, innermost first, by name. */
	private final Deque<Map<String, Integer>> comprehensions = new ArrayDeque<>();
	private int line;

	/** A loop being compiled: where {@code continue} jumps to, and the {@code break} jumps to patch at its end. */
	private record Loop(boolean isFor, int top, List<Integer> breaks) {
	}

	private Compiler(String file, String name, Map<String, Integer> locals, int line) {
		this.file = file;
		this.name = name;
		this.locals = locals;
		this.line = line;
		if (locals != null) {
			localNames.addAll(locals.keySet());
		}
	}

	/** Compiles a module; constructs that do not run yet are a {@link CompileError}. */
	public static Code compile(Ast.Module module) {
		Compiler compiler = new Compiler(module.file(), "<module>", null, 1);
		compiler.body(module.body());
		compiler.emit(Op.LOAD_CONST, compiler.constant(PyNone.NONE));
		compiler.emit(Op.RETURN, 0);
		return compiler.code(0, 0);
	}

	private Code function(Ast.Def def) {
		SubsetCheck.Names bound = new SubsetCheck.Names();
		bound.body(def.body());
		Map<String, Integer> scope = new LinkedHashMap<>();
		def.params().forEach(p -> scope.put(p.name(), scope.size()));
		for (String local : bound.stores) {
			if (!bound.globals.contains(local)) {
				scope.putIfAbsent(local, scope.size());
			}
		}
		Compiler compiler = new Compiler(file, def.name(), scope, def.line());
		compiler.body(def.body());
		compiler.emit(Op.LOAD_CONST, compiler.constant(PyNone.NONE));
		compiler.emit(Op.RETURN, 0);
		int defaults = (int) def.params().stream().filter(p -> p.defaultValue() != null).count();
		return compiler.code(def.params().size(), defaults);
	}

	private Code code(int paramCount, int defaultCount) {
		String[] nameTable = new String[names.size()];
		names.forEach((n, i) -> nameTable[i] = n);
		String[] localTable = localNames.toArray(String[]::new);
		return new Code(name, file, ops.toArray(Op[]::new), args.stream().mapToInt(Integer::intValue).toArray(),
				lines.stream().mapToInt(Integer::intValue).toArray(), constants.toArray(), nameTable, localTable,
				paramCount, defaultCount);
	}

	// Emitting

	private int emit(Op op, int arg) {
		ops.add(op);
		args.add(arg);
		lines.add(line);
		return ops.size() - 1;
	}

	private int here() {
		return ops.size();
	}

	private void patch(int at, int target) {
		args.set(at, target);
	}

	private int constant(Object value) {
		constants.add(value);
		return constants.size() - 1;
	}

	private int name(String n) {
		return names.computeIfAbsent(n, k -> names.size());
	}

	private void at(Ast.Node node) {
		line = node.line();
	}

	// Statements

	private void body(List<Stmt> body) {
		body.forEach(this::statement);
	}

	private void statement(Stmt statement) {
		at(statement);
		switch (statement) {
			case Ast.ExprStmt s -> {
				expr(s.expr());
				emit(Op.POP, 0);
			}
			case Ast.Assign s -> {
				expr(s.value());
				for (int i = 0; i < s.targets().size(); i++) {
					if (i < s.targets().size() - 1) {
						emit(Op.DUP, 0);
					}
					store(s.targets().get(i));
				}
			}
			case Ast.AugAssign s -> augmented(s);
			case Ast.If s -> {
				expr(s.test());
				int toElse = emit(Op.JUMP_IF_FALSE, 0);
				body(s.body());
				if (s.orElse().isEmpty()) {
					patch(toElse, here());
				} else {
					int toEnd = emit(Op.JUMP, 0);
					patch(toElse, here());
					body(s.orElse());
					patch(toEnd, here());
				}
			}
			case Ast.While s -> {
				int top = here();
				expr(s.test());
				int toEnd = emit(Op.JUMP_IF_FALSE, 0);
				loop(new Loop(false, top, new ArrayList<>()), s.body(), toEnd);
			}
			case Ast.For s -> {
				expr(s.iter());
				at(s);
				emit(Op.GET_ITER, 0);
				int top = here();
				int toEnd = emit(Op.FOR_ITER, 0);
				store(s.target());
				loop(new Loop(true, top, new ArrayList<>()), s.body(), toEnd);
			}
			case Ast.Break s -> {
				Loop loop = loops.peek();
				if (loop.isFor()) {
					emit(Op.POP, 0);
				}
				loop.breaks().add(emit(Op.JUMP, 0));
			}
			case Ast.Continue s -> emit(Op.JUMP, loops.peek().top());
			case Ast.Pass s -> {
			}
			case Ast.Def s -> {
				for (Ast.Param p : s.params()) {
					if (p.defaultValue() != null) {
						expr(p.defaultValue());
					}
				}
				at(s);
				emit(Op.MAKE_FUNCTION, constant(function(s)));
				storeName(s.name());
			}
			case Ast.Return s -> {
				if (s.value() == null) {
					emit(Op.LOAD_CONST, constant(PyNone.NONE));
				} else {
					expr(s.value());
				}
				at(s);
				emit(Op.RETURN, 0);
			}
			case Ast.Assert s -> {
				expr(s.test());
				int toEnd = emit(Op.JUMP_IF_TRUE, 0);
				if (s.message() != null) {
					expr(s.message());
				}
				at(s);
				emit(Op.ASSERT_FAIL, s.message() == null ? 0 : 1);
				patch(toEnd, here());
			}
			case Ast.Global s -> {
			}
			case Ast.Import s -> throw new CompileError(file, s.line(), s.col(), "`import` is not available yet");
			case Ast.FromImport s ->
				throw new CompileError(file, s.line(), s.col(), "`from ... import` is not available yet");
		}
	}

	/** The body of a loop whose head is compiled, then the jump back to its top and the exits patched to its end. */
	private void loop(Loop loop, List<Stmt> body, int exit) {
		loops.push(loop);
		body(body);
		loops.pop();
		emit(Op.JUMP, loop.top());
		patch(exit, here());
		loop.breaks().forEach(at -> patch(at, here()));
	}

	private void augmented(Ast.AugAssign s) {
		switch (s.target()) {
			case Ast.Name n -> {
				loadName(n.id());
				expr(s.value());
				at(s);
				emit(Op.INPLACE, s.op().ordinal());
				storeName(n.id());
			}
			case Ast.Attribute a -> {
				expr(a.value());
				emit(Op.DUP, 0);
				at(a);
				emit(Op.LOAD_ATTR, name(a.attr()));
				expr(s.value());
				at(s);
				emit(Op.INPLACE, s.op().ordinal());
				emit(Op.ROT2, 0);
				at(a);
				emit(Op.STORE_ATTR, name(a.attr()));
			}
			case Ast.Subscript sub -> {
				expr(sub.value());
				index(sub.index());
				emit(Op.DUP2, 0);
				at(sub);
				emit(Op.GET_ITEM, 0);
				expr(s.value());
				at(s);
				emit(Op.INPLACE, s.op().ordinal());
				emit(Op.ROT3, 0);
				at(sub);
				emit(Op.SET_ITEM, 0);
			}
			default -> throw new IllegalStateException("the parser allows no other target");
		}
	}

	/** Pops TOS into an assignment target. */
	private void store(Expr target) {
		switch (target) {
			case Ast.Name n -> storeName(n.id());
			case Ast.TupleLit t -> unpack(t, t.elements());
			case Ast.ListLit l -> unpack(l, l.elements());
			case Ast.Attribute a -> {
				expr(a.value());
				at(a);
				emit(Op.STORE_ATTR, name(a.attr()));
			}
			case Ast.Subscript s -> {
				expr(s.value());
				index(s.index());
				at(s);
				emit(Op.SET_ITEM, 0);
			}
			default -> throw new IllegalStateException("the parser allows no other target");
		}
	}

	private void unpack(Expr target, List<Expr> elements) {
		at(target);
		emit(Op.UNPACK, elements.size());
		elements.forEach(this::store);
	}

	/** The local slot of {@code n}: a comprehension's variable first, innermost comprehension first; else -1. */
	private int slot(String n) {
		for (Map<String, Integer> scope : comprehensions) {
			Integer slot = scope.get(n);
			if (slot != null) {
				return slot;
			}
		}
		return locals != null && locals.containsKey(n) ? locals.get(n) : -1;
	}

	private void loadName(String n) {
		int slot = slot(n);
		if (slot >= 0) {
			emit(Op.LOAD_LOCAL, slot);
		} else {
			emit(Op.LOAD_GLOBAL, name(n));
		}
	}

	private void storeName(String n) {
		int slot = slot(n);
		if (slot >= 0) {
			emit(Op.STORE_LOCAL, slot);
		} else {
			emit(Op.STORE_GLOBAL, name(n));
		}
	}

	// Expressions

	/** Pushes the text of {@code parts}, an f-string's or a nested spec's, joined. */
	private void fString(Ast.FString f, List<Ast.FString.Part> parts) {
		for (Ast.FString.Part part : parts) {
			switch (part) {
				case Ast.FString.Text text -> {
					at(f);
					emit(Op.LOAD_CONST, constant(text.text()));
				}
				case Ast.FString.Value value -> {
					expr(value.expr());
					String spec = value.constantSpec();
					if (spec != null) {
						at(value.expr());
						emit(Op.FORMAT, constant(new Code.FormatField(value.conversion(), spec)));
					} else {
						fString(f, value.spec());
						at(value.expr());
						emit(Op.FORMAT_SPEC, value.conversion());
					}
				}
			}
		}
		at(f);
		emit(Op.BUILD_STRING, parts.size());
	}

	private void expr(Expr e) {
		switch (e) {
			case Ast.Name n -> {
				at(n);
				loadName(n.id());
			}
			case Ast.IntLit n -> constant(n, n.value());
			case Ast.FloatLit n -> constant(n, n.value());
			case Ast.StrLit n -> constant(n, n.value());
			case Ast.BoolLit n -> constant(n, n.value());
			case Ast.NoneLit n -> constant(n, PyNone.NONE);
			case Ast.FString f -> fString(f, f.parts());
			case Ast.ListLit l -> {
				l.elements().forEach(this::expr);
				at(l);
				emit(Op.BUILD_LIST, l.elements().size());
			}
			case Ast.TupleLit t -> {
				t.elements().forEach(this::expr);
				at(t);
				emit(Op.BUILD_TUPLE, t.elements().size());
			}
			case Ast.DictLit d -> {
				for (int i = 0; i < d.keys().size(); i++) {
					expr(d.keys().get(i));
					expr(d.values().get(i));
				}
				at(d);
				emit(Op.BUILD_DICT, d.keys().size());
			}
			case Ast.Unary u -> {
				expr(u.operand());
				at(u);
				emit(Op.UNARY, u.op().ordinal());
			}
			case Ast.BinOp b -> {
				expr(b.left());
				expr(b.right());
				at(b);
				emit(Op.BINARY, b.op().ordinal());
			}
			case Ast.BoolOp b -> {
				expr(b.left());
				at(b);
				int toEnd = emit(b.op() == Ast.BoolOpKind.AND ? Op.JUMP_IF_FALSE_OR_POP : Op.JUMP_IF_TRUE_OR_POP, 0);
				expr(b.right());
				patch(toEnd, here());
			}
			case Ast.Compare c -> compare(c);
			case Ast.IfExp i -> {
				expr(i.test());
				at(i);
				int toElse = emit(Op.JUMP_IF_FALSE, 0);
				expr(i.body());
				int toEnd = emit(Op.JUMP, 0);
				patch(toElse, here());
				expr(i.orElse());
				patch(toEnd, here());
			}
			case Ast.Attribute a -> {
				expr(a.value());
				at(a);
				emit(Op.LOAD_ATTR, name(a.attr()));
			}
			case Ast.Subscript s -> {
				expr(s.value());
				index(s.index());
				at(s);
				emit(Op.GET_ITEM, 0);
			}
			case Ast.Slice s -> throw new IllegalStateException("a slice is only compiled as an index");
			case Ast.Call c -> call(c);
			case Ast.ListComp c -> listComp(c);
		}
	}

	/**
	 * {@code [elt for target in iter if test ...]}, inline: the new list stays under the iterators of its loops. As in
	 * Python, the first iterable is evaluated in the scope around the comprehension, and the variables of its loops
	 * are its own (hidden local slots), so they neither leak out nor overwrite variables of the same name.
	 */
	private void listComp(Ast.ListComp c) {
		at(c);
		emit(Op.BUILD_LIST, 0);
		expr(c.generators().getFirst().iter());
		SubsetCheck.Names bound = new SubsetCheck.Names();
		c.generators().forEach(g -> bound.target(g.target()));
		Map<String, Integer> scope = new HashMap<>();
		for (String variable : bound.stores) {
			scope.put(variable, localNames.size());
			localNames.add(variable);
		}
		comprehensions.push(scope);
		generator(c, 0);
		comprehensions.pop();
	}

	private void generator(Ast.ListComp c, int index) {
		Ast.Comprehension g = c.generators().get(index);
		if (index > 0) {
			expr(g.iter());
		}
		at(g);
		emit(Op.GET_ITER, 0);
		int top = here();
		int toEnd = emit(Op.FOR_ITER, 0);
		store(g.target());
		for (Expr test : g.ifs()) {
			expr(test);
			at(g);
			emit(Op.JUMP_IF_FALSE, top);
		}
		if (index + 1 < c.generators().size()) {
			generator(c, index + 1);
		} else {
			expr(c.elt());
			at(c);
			emit(Op.LIST_APPEND, index + 1);
		}
		emit(Op.JUMP, top);
		patch(toEnd, here());
	}

	private void constant(Expr literal, Object value) {
		at(literal);
		emit(Op.LOAD_CONST, constant(value));
	}

	/** Pushes an index, building a slice for {@code a[lower:upper:step]}. */
	private void index(Expr index) {
		if (index instanceof Ast.Slice s) {
			for (Expr part : new Expr[] {s.lower(), s.upper(), s.step()}) {
				if (part == null) {
					emit(Op.LOAD_CONST, constant(PyNone.NONE));
				} else {
					expr(part);
				}
			}
			at(s);
			emit(Op.BUILD_SLICE, 0);
		} else {
			expr(index);
		}
	}

	/**
	 * {@code a < b < c}: each middle operand is kept under the result of its comparison, so it is evaluated once, and
	 * the chain stops at the first false comparison.
	 */
	private void compare(Ast.Compare c) {
		expr(c.left());
		int n = c.ops().size();
		List<Integer> toCleanup = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			expr(c.comparators().get(i));
			at(c);
			if (i < n - 1) {
				emit(Op.DUP, 0);
				emit(Op.ROT3, 0);
				emit(Op.COMPARE, c.ops().get(i).ordinal());
				toCleanup.add(emit(Op.JUMP_IF_FALSE_OR_POP, 0));
			} else {
				emit(Op.COMPARE, c.ops().get(i).ordinal());
			}
		}
		if (n > 1) {
			int toEnd = emit(Op.JUMP, 0);
			toCleanup.forEach(at -> patch(at, here()));
			emit(Op.ROT2, 0);
			emit(Op.POP, 0);
			patch(toEnd, here());
		}
	}

	private void call(Ast.Call c) {
		expr(c.func());
		c.args().forEach(this::expr);
		c.keywords().forEach(k -> expr(k.value()));
		at(c);
		if (c.keywords().isEmpty()) {
			emit(Op.CALL, c.args().size());
		} else {
			List<String> keywords = c.keywords().stream().map(Ast.Keyword::name).toList();
			if (Set.copyOf(keywords).size() < keywords.size()) {
				throw new CompileError(file, c.line(), c.col(), "keyword argument repeated");
			}
			emit(Op.CALL_KW, constant(new Code.CallShape(c.args().size(), keywords)));
		}
	}
}
