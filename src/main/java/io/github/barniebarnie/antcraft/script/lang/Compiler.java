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
	/**
	 * The {@code try} blocks the code being compiled is in, innermost last, each the body of its {@code finally} or null
	 * for none: what a {@code return}, {@code break} or {@code continue} leaving them pops and runs on its way out.
	 */
	private final List<List<Stmt>> blocks = new ArrayList<>();
	/** The hidden local slots of the exceptions the {@code except} clauses being compiled handle, innermost first. */
	private final Deque<Integer> handling = new ArrayDeque<>();
	/** Whether this compiles a method with a {@code self}, where {@code super()} works. */
	private boolean method;
	/** The local of a method that holds the class that defined it. */
	static final String CLASS_SLOT = "__class__";

	/**
	 * A loop being compiled: where {@code continue} jumps to, the {@code break} jumps to patch at its end, and how many
	 * {@code try} blocks are around it.
	 */
	private record Loop(boolean isFor, int top, List<Integer> breaks, int blocks) {
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
		return compiler.code(0, 0, false, false);
	}

	/**
	 * A function's code. A method's has a hidden local {@value #CLASS_SLOT} right after its parameters, which a call
	 * sets to the class that defined it, for its {@code super()}.
	 */
	private Code function(Ast.Def def, boolean method) {
		SubsetCheck.Names bound = new SubsetCheck.Names();
		bound.body(def.body());
		Map<String, Integer> scope = new LinkedHashMap<>();
		def.paramNames().forEach(p -> scope.put(p, scope.size()));
		if (method) {
			scope.putIfAbsent(CLASS_SLOT, scope.size());
		}
		for (String local : bound.stores) {
			if (!bound.globals.contains(local)) {
				scope.putIfAbsent(local, scope.size());
			}
		}
		Compiler compiler = new Compiler(file, def.name(), scope, def.line());
		compiler.method = method && !def.params().isEmpty();
		compiler.body(def.body());
		compiler.emit(Op.LOAD_CONST, compiler.constant(PyNone.NONE));
		compiler.emit(Op.RETURN, 0);
		int defaults = (int) def.params().stream().filter(p -> p.defaultValue() != null).count();
		return compiler.code(def.params().size(), defaults, def.varArgs() != null, def.varKeywords() != null);
	}

	private Code code(int paramCount, int defaultCount, boolean varArgs, boolean varKeywords) {
		String[] nameTable = new String[names.size()];
		names.forEach((n, i) -> nameTable[i] = n);
		String[] localTable = localNames.toArray(String[]::new);
		return new Code(name, file, ops.toArray(Op[]::new), args.stream().mapToInt(Integer::intValue).toArray(),
				lines.stream().mapToInt(Integer::intValue).toArray(), constants.toArray(), nameTable, localTable,
				paramCount, defaultCount, varArgs, varKeywords);
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
				loop(new Loop(false, top, new ArrayList<>(), blocks.size()), s.body(), toEnd);
			}
			case Ast.For s -> {
				expr(s.iter());
				at(s);
				emit(Op.GET_ITER, 0);
				int top = here();
				int toEnd = emit(Op.FOR_ITER, 0);
				store(s.target());
				loop(new Loop(true, top, new ArrayList<>(), blocks.size()), s.body(), toEnd);
			}
			case Ast.Break s -> {
				Loop loop = loops.peek();
				leaveBlocks(loop.blocks());
				at(s);
				if (loop.isFor()) {
					emit(Op.POP, 0);
				}
				loop.breaks().add(emit(Op.JUMP, 0));
			}
			case Ast.Continue s -> {
				leaveBlocks(loops.peek().blocks());
				at(s);
				emit(Op.JUMP, loops.peek().top());
			}
			case Ast.Pass s -> {
			}
			case Ast.Def s -> {
				for (Ast.Param p : s.params()) {
					if (p.defaultValue() != null) {
						expr(p.defaultValue());
					}
				}
				at(s);
				emit(Op.MAKE_FUNCTION, constant(function(s, false)));
				storeName(s.name());
			}
			case Ast.ClassDef s -> classDef(s);
			case Ast.Return s -> {
				if (s.value() == null) {
					emit(Op.LOAD_CONST, constant(PyNone.NONE));
				} else {
					expr(s.value());
				}
				at(s);
				if (!blocks.isEmpty()) { // kept aside while the finally blocks run, which may break out of a loop
					int result = hidden("$return");
					emit(Op.STORE_LOCAL, result);
					leaveBlocks(0);
					at(s);
					emit(Op.LOAD_LOCAL, result);
				}
				emit(Op.RETURN, 0);
			}
			case Ast.Try s -> tryStatement(s);
			case Ast.Raise s -> {
				if (s.exception() != null) {
					expr(s.exception());
				} else if (handling.isEmpty()) {
					throw new CompileError(file, s.line(), s.col(), "a bare 'raise' is only supported inside an 'except' block");
				} else {
					emit(Op.LOAD_LOCAL, handling.peek());
				}
				at(s);
				emit(Op.RAISE, 0);
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
			case Ast.Import s -> {
				// import a.b.c imports a, a.b and a.b.c, and binds a.
				for (String module : s.modules()) {
					String top = module.split("\\.")[0];
					if (!top.equals(module)) {
						emit(Op.IMPORT, name(module));
						emit(Op.POP, 0);
					}
					emit(Op.IMPORT, name(top));
					storeName(top);
				}
			}
			case Ast.FromImport s -> {
				emit(Op.IMPORT, name(s.module()));
				for (String imported : s.names()) {
					emit(Op.IMPORT_FROM, name(imported));
					storeName(imported);
				}
				emit(Op.POP, 0);
			}
		}
	}

	/**
	 * {@code class}: the base, then the value of each attribute of the body in order (the values of assignments, and a
	 * function for each method), evaluated in the scope around the class, then {@link Op#BUILD_CLASS}.
	 */
	private void classDef(Ast.ClassDef s) {
		if (s.base() != null) {
			expr(s.base());
		}
		List<String> attributes = new ArrayList<>();
		for (Stmt member : s.body()) {
			switch (member) {
				case Ast.Assign a -> {
					expr(a.value());
					for (int i = 0; i < a.targets().size(); i++) {
						if (i < a.targets().size() - 1) {
							emit(Op.DUP, 0);
						}
						attributes.add(((Ast.Name) a.targets().get(i)).id());
					}
				}
				case Ast.Def d -> {
					for (Ast.Param p : d.params()) {
						if (p.defaultValue() != null) {
							expr(p.defaultValue());
						}
					}
					at(d);
					emit(Op.MAKE_FUNCTION, constant(function(d, true)));
					attributes.add(d.name());
				}
				default -> { // pass, or a docstring
				}
			}
		}
		at(s);
		emit(Op.BUILD_CLASS, constant(new Code.ClassShape(s.name(), s.base() != null, List.copyOf(attributes))));
		storeName(s.name());
	}

	/** A hidden local slot, which no name of the script reaches. */
	private int hidden(String name) {
		localNames.add(name);
		return localNames.size() - 1;
	}

	/**
	 * {@code try}: a block whose errors jump to the {@code except} clauses, inside a block whose errors jump to the
	 * {@code finally} body, which then raises the error again. The finally body is also compiled where the try ends
	 * normally, and where a {@code return}, {@code break} or {@code continue} leaves it ({@link #leaveBlocks}). The
	 * exception being handled is kept in a hidden local, so the operand stack stays as it is around the try.
	 */
	private void tryStatement(Ast.Try s) {
		boolean hasFinally = !s.finalBody().isEmpty();
		int toFinally = 0;
		if (hasFinally) {
			toFinally = emit(Op.SETUP_TRY, 0);
			blocks.add(s.finalBody());
		}
		if (s.handlers().isEmpty()) {
			body(s.body());
		} else {
			int toHandlers = emit(Op.SETUP_TRY, 0);
			blocks.add(null);
			body(s.body());
			blocks.removeLast();
			at(s);
			emit(Op.POP_BLOCK, 0);
			body(s.orElse());
			List<Integer> toEnd = new ArrayList<>();
			toEnd.add(emit(Op.JUMP, 0));
			patch(toHandlers, here());
			int exception = hidden("$exception");
			emit(Op.STORE_LOCAL, exception);
			handling.push(exception);
			for (Ast.ExceptHandler handler : s.handlers()) {
				at(handler);
				int toNext = -1;
				if (handler.type() != null) {
					emit(Op.LOAD_LOCAL, exception);
					expr(handler.type());
					at(handler);
					emit(Op.EXC_MATCH, 0);
					toNext = emit(Op.JUMP_IF_FALSE, 0);
				}
				if (handler.name() != null) {
					emit(Op.LOAD_LOCAL, exception);
					emit(Op.EXC_VALUE, 0);
					storeName(handler.name());
				}
				body(handler.body());
				toEnd.add(emit(Op.JUMP, 0));
				if (toNext >= 0) {
					patch(toNext, here());
				}
			}
			handling.pop();
			if (s.handlers().getLast().type() != null) { // none matched
				emit(Op.LOAD_LOCAL, exception);
				emit(Op.RAISE, 0);
			}
			toEnd.forEach(at -> patch(at, here()));
		}
		if (hasFinally) {
			blocks.removeLast();
			at(s);
			emit(Op.POP_BLOCK, 0);
			body(s.finalBody());
			int toEnd = emit(Op.JUMP, 0);
			patch(toFinally, here());
			int exception = hidden("$exception");
			emit(Op.STORE_LOCAL, exception);
			body(s.finalBody());
			emit(Op.LOAD_LOCAL, exception);
			emit(Op.RAISE, 0);
			patch(toEnd, here());
		}
	}

	/**
	 * Leaves the {@code try} blocks above the first {@code downTo}, innermost first, as a {@code return}, {@code break}
	 * or {@code continue} does: pops each and runs its {@code finally} body, if it has one, inside the blocks around it.
	 */
	private void leaveBlocks(int downTo) {
		for (int i = blocks.size() - 1; i >= downTo; i--) {
			List<Stmt> finalBody = blocks.get(i);
			emit(Op.POP_BLOCK, 0);
			if (finalBody != null) {
				List<List<Stmt>> inner = new ArrayList<>(blocks.subList(i, blocks.size()));
				blocks.subList(i, blocks.size()).clear();
				body(finalBody);
				blocks.addAll(inner);
			}
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
			case Ast.Starred s -> throw new IllegalStateException("a starred value is only compiled as a call's argument");
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
		if (c.func() instanceof Ast.Name n && n.id().equals("super") && slot("super") < 0) {
			if (!method || !c.args().isEmpty() || !c.keywords().isEmpty()) {
				throw new CompileError(file, c.line(), c.col(),
						"`super()` is only supported without arguments, in a method of a class");
			}
			at(c);
			emit(Op.LOAD_LOCAL, slot(CLASS_SLOT));
			emit(Op.LOAD_LOCAL, 0);
			emit(Op.SUPER, 0);
			return;
		}
		expr(c.func());
		List<Integer> starred = new ArrayList<>();
		for (int i = 0; i < c.args().size(); i++) {
			if (c.args().get(i) instanceof Ast.Starred s) {
				starred.add(i);
				expr(s.value());
			} else {
				expr(c.args().get(i));
			}
		}
		c.keywords().forEach(k -> expr(k.value()));
		at(c);
		if (c.keywords().isEmpty() && starred.isEmpty()) {
			emit(Op.CALL, c.args().size());
		} else {
			List<String> keywords = c.keywords().stream().map(Ast.Keyword::name).toList(); // null for a **value
			List<String> named = keywords.stream().filter(java.util.Objects::nonNull).toList();
			if (Set.copyOf(named).size() < named.size()) {
				throw new CompileError(file, c.line(), c.col(), "keyword argument repeated");
			}
			emit(Op.CALL_KW, constant(new Code.CallShape(c.args().size(), keywords, List.copyOf(starred))));
		}
	}
}
