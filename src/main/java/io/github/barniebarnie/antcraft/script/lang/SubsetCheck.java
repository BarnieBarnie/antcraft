package io.github.barniebarnie.antcraft.script.lang;

import io.github.barniebarnie.antcraft.script.lang.Ast.Expr;
import io.github.barniebarnie.antcraft.script.lang.Ast.Stmt;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The checks of the subset that need more than one token: each name of a {@code nonlocal} must be a variable of a
 * function around it. {@link Names} is also the scope analysis of the compiler (docs/language/functions.md, Closures).
 */
final class SubsetCheck {
	private SubsetCheck() {
	}

	static void check(Ast.Module module) {
		functionsIn(module.file(), module.body(), List.of());
	}

	/** Checks every function defined in {@code body}, at any depth of blocks, but not inside nested functions. */
	private static void functionsIn(String file, List<Stmt> body, List<Set<String>> enclosing) {
		for (Stmt statement : body) {
			switch (statement) {
				case Ast.Def def -> function(file, def, enclosing);
				// As in Python, a class body is no scope its methods see: they see the scopes around the class.
				case Ast.ClassDef c -> functionsIn(file, c.body(), enclosing);
				case Ast.If s -> {
					functionsIn(file, s.body(), enclosing);
					functionsIn(file, s.orElse(), enclosing);
				}
				case Ast.While s -> functionsIn(file, s.body(), enclosing);
				case Ast.For s -> functionsIn(file, s.body(), enclosing);
				case Ast.Try s -> {
					functionsIn(file, s.body(), enclosing);
					s.handlers().forEach(h -> functionsIn(file, h.body(), enclosing));
					functionsIn(file, s.orElse(), enclosing);
					functionsIn(file, s.finalBody(), enclosing);
				}
				default -> {
				}
			}
		}
	}

	/** Checks {@code def}; {@code enclosing} holds the variables of each function around it. */
	private static void function(String file, Ast.Def def, List<Set<String>> enclosing) {
		Names names = Names.of(def);
		for (Ast.Nonlocal statement : names.nonlocals) {
			for (String name : statement.names()) {
				if (def.paramNames().contains(name)) {
					throw new CompileError(file, statement.line(), statement.col(), "name '" + name + "' is parameter and nonlocal");
				}
				if (enclosing.stream().noneMatch(locals -> locals.contains(name))) {
					throw new CompileError(file, statement.line(), statement.col(), "no binding for nonlocal '" + name + "' found");
				}
			}
		}
		List<Set<String>> inner = new ArrayList<>(enclosing);
		inner.add(names.locals(def));
		functionsIn(file, def.body(), inner);
	}

	/**
	 * The names a function body reads, binds and declares global or nonlocal, leaving out the bodies of nested functions,
	 * and the names those nested functions use from the scopes around them.
	 */
	static final class Names {
		final List<Ast.Name> loads = new ArrayList<>();
		final Set<String> stores = new HashSet<>();
		final Set<String> globals = new HashSet<>();
		final List<Ast.Nonlocal> nonlocals = new ArrayList<>();
		/** The free names of the functions and lambdas nested in the body, less those a comprehension of it binds. */
		final Set<String> inner = new HashSet<>();

		/** The names of the body of {@code def}. */
		static Names of(Ast.Def def) {
			Names names = new Names();
			names.body(def.body());
			return names;
		}

		/** The names of a comprehension's own scope: its variables are the {@code stores}. */
		static Names comprehension(Ast.ListComp c) {
			Names names = new Names();
			for (int i = 0; i < c.generators().size(); i++) {
				Ast.Comprehension generator = c.generators().get(i);
				names.target(generator.target());
				if (i > 0) {
					names.expr(generator.iter());
				}
				generator.ifs().forEach(names::expr);
			}
			names.expr(c.elt());
			return names;
		}

		Set<String> nonlocalNames() {
			Set<String> names = new HashSet<>();
			nonlocals.forEach(n -> names.addAll(n.names()));
			return names;
		}

		/** The variables of {@code def}, whose body these are the names of: its parameters and the names it binds. */
		Set<String> locals(Ast.Def def) {
			Set<String> locals = new HashSet<>(stores);
			locals.addAll(def.paramNames());
			locals.removeAll(globals);
			locals.removeAll(nonlocalNames());
			return locals;
		}

		/**
		 * The names {@code def} uses from the scopes around it: those it reads without binding or declares nonlocal, and
		 * those its nested functions use that it does not bind. The compiler resolves each to a variable of a function
		 * around, else to a global.
		 */
		Set<String> free(Ast.Def def) {
			Set<String> free = new HashSet<>(inner);
			loads.forEach(load -> free.add(load.id()));
			free.removeAll(locals(def));
			free.removeAll(globals);
			free.addAll(nonlocalNames());
			return free;
		}

		/** A nested function: its defaults are evaluated in this scope, and its free names are {@link #inner}. */
		private void function(Ast.Def def) {
			def.params().forEach(p -> expr(p.defaultValue()));
			inner.addAll(of(def).free(def));
		}

		void body(List<Stmt> body) {
			body.forEach(this::statement);
		}

		void statement(Stmt statement) {
			switch (statement) {
				case Ast.ExprStmt s -> expr(s.expr());
				case Ast.Assign s -> {
					s.targets().forEach(this::target);
					expr(s.value());
				}
				case Ast.AugAssign s -> {
					expr(s.target());
					target(s.target());
					expr(s.value());
				}
				case Ast.If s -> {
					expr(s.test());
					body(s.body());
					body(s.orElse());
				}
				case Ast.While s -> {
					expr(s.test());
					body(s.body());
				}
				case Ast.For s -> {
					target(s.target());
					expr(s.iter());
					body(s.body());
				}
				case Ast.Def s -> {
					stores.add(s.name());
					function(s);
				}
				case Ast.ClassDef s -> {
					stores.add(s.name());
					expr(s.base());
					for (Stmt member : s.body()) { // evaluated in this scope; the names they bind are the class's
						switch (member) {
							case Ast.Assign a -> expr(a.value());
							case Ast.Def d -> function(d);
							default -> {
							}
						}
					}
				}
				case Ast.Return s -> {
					if (s.value() != null) {
						expr(s.value());
					}
				}
				case Ast.Assert s -> {
					expr(s.test());
					expr(s.message());
				}
				case Ast.Global s -> globals.addAll(s.names());
				case Ast.Nonlocal s -> nonlocals.add(s);
				case Ast.Import s -> s.modules().forEach(m -> stores.add(m.split("\\.")[0]));
				case Ast.FromImport s -> stores.addAll(s.names());
				case Ast.Break s -> {
				}
				case Ast.Continue s -> {
				}
				case Ast.Pass s -> {
				}
				case Ast.Try s -> {
					body(s.body());
					for (Ast.ExceptHandler handler : s.handlers()) {
						expr(handler.type());
						if (handler.name() != null) {
							stores.add(handler.name());
						}
						body(handler.body());
					}
					body(s.orElse());
					body(s.finalBody());
				}
				case Ast.Raise s -> expr(s.exception());
			}
		}

		void target(Expr target) {
			switch (target) {
				case Ast.Name name -> stores.add(name.id());
				case Ast.TupleLit tuple -> tuple.elements().forEach(this::target);
				case Ast.ListLit list -> list.elements().forEach(this::target);
				case Ast.Attribute attribute -> expr(attribute.value());
				case Ast.Subscript subscript -> {
					expr(subscript.value());
					expr(subscript.index());
				}
				default -> expr(target);
			}
		}

		/** The values of an f-string's parts, and of their specs' nested values. */
		private void parts(List<Ast.FString.Part> parts) {
			for (Ast.FString.Part part : parts) {
				if (part instanceof Ast.FString.Value value) {
					expr(value.expr());
					if (value.spec() != null) {
						parts(value.spec());
					}
				}
			}
		}

		void expr(Expr e) {
			if (e == null) {
				return;
			}
			switch (e) {
				case Ast.Name name -> loads.add(name);
				case Ast.IntLit ignored -> {
				}
				case Ast.FloatLit ignored -> {
				}
				case Ast.StrLit ignored -> {
				}
				case Ast.BoolLit ignored -> {
				}
				case Ast.NoneLit ignored -> {
				}
				case Ast.FString f -> parts(f.parts());
				case Ast.ListLit l -> l.elements().forEach(this::expr);
				case Ast.TupleLit t -> t.elements().forEach(this::expr);
				case Ast.DictLit d -> {
					d.keys().forEach(this::expr);
					d.values().forEach(this::expr);
				}
				case Ast.Unary u -> expr(u.operand());
				case Ast.BinOp b -> {
					expr(b.left());
					expr(b.right());
				}
				case Ast.BoolOp b -> {
					expr(b.left());
					expr(b.right());
				}
				case Ast.Compare c -> {
					expr(c.left());
					c.comparators().forEach(this::expr);
				}
				case Ast.IfExp i -> {
					expr(i.test());
					expr(i.body());
					expr(i.orElse());
				}
				case Ast.Attribute a -> expr(a.value());
				case Ast.Subscript s -> {
					expr(s.value());
					expr(s.index());
				}
				case Ast.Starred s -> expr(s.value());
				case Ast.Slice s -> {
					expr(s.lower());
					expr(s.upper());
					expr(s.step());
				}
				case Ast.Call c -> {
					expr(c.func());
					c.args().forEach(this::expr);
					c.keywords().forEach(k -> expr(k.value()));
				}
				case Ast.Lambda l -> function(l.def());
				case Ast.ListComp c -> {
					// As in Python, a comprehension has its own scope: its variables are not bound around it, and
					// only its first iterable is evaluated in the scope around it.
					expr(c.generators().getFirst().iter());
					Names inner = comprehension(c);
					inner.loads.stream().filter(load -> !inner.stores.contains(load.id())).forEach(loads::add);
					inner.inner.stream().filter(name -> !inner.stores.contains(name)).forEach(this.inner::add);
				}
			}
		}
	}
}
