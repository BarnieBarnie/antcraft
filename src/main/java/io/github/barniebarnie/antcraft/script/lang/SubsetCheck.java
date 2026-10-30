package io.github.barniebarnie.antcraft.script.lang;

import io.github.barniebarnie.antcraft.script.lang.Ast.Expr;
import io.github.barniebarnie.antcraft.script.lang.Ast.Stmt;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The checks of the subset that need more than one token: a nested function may not use a variable of a function
 * around it (closures are not supported). It may use globals and its own variables, and its enclosing function may
 * call it.
 */
final class SubsetCheck {
	private SubsetCheck() {
	}

	/** A function's name and the names local to it. */
	private record Scope(String name, Set<String> locals) {
	}

	static void check(Ast.Module module) {
		functionsIn(module.file(), module.body(), List.of());
	}

	/** Checks every function defined in {@code body}, at any depth of blocks, but not inside nested functions. */
	private static void functionsIn(String file, List<Stmt> body, List<Scope> enclosing) {
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

	private static void function(String file, Ast.Def def, List<Scope> enclosing) {
		Names names = new Names();
		names.body(def.body());
		Set<String> locals = new HashSet<>(names.stores);
		locals.addAll(def.paramNames());
		locals.removeAll(names.globals);
		for (Ast.Name load : names.loads) {
			if (locals.contains(load.id()) || names.globals.contains(load.id())) {
				continue;
			}
			for (Scope scope : enclosing.reversed()) {
				if (scope.locals().contains(load.id())) {
					throw new CompileError(file, load.line(), load.col(), "closures are not supported (`" + def.name()
							+ "` uses `" + load.id() + "` of `" + scope.name() + "`)");
				}
			}
		}
		List<Scope> inner = new ArrayList<>(enclosing);
		inner.add(new Scope(def.name(), locals));
		functionsIn(file, def.body(), inner);
	}

	/** The names a function body reads, binds and declares global, leaving out the bodies of nested functions. */
	static final class Names {
		final List<Ast.Name> loads = new ArrayList<>();
		final Set<String> stores = new HashSet<>();
		final Set<String> globals = new HashSet<>();

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
					s.params().forEach(p -> {
						if (p.defaultValue() != null) {
							expr(p.defaultValue());
						}
					});
				}
				case Ast.ClassDef s -> {
					stores.add(s.name());
					expr(s.base());
					for (Stmt member : s.body()) { // evaluated in this scope; the names they bind are the class's
						switch (member) {
							case Ast.Assign a -> expr(a.value());
							case Ast.Def d -> d.params().forEach(p -> expr(p.defaultValue()));
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
				case Ast.ListComp c -> {
					// As in Python, a comprehension has its own scope: its variables are not bound around it, and
					// only its first iterable is evaluated in the scope around it.
					expr(c.generators().getFirst().iter());
					Names inner = new Names();
					for (int i = 0; i < c.generators().size(); i++) {
						Ast.Comprehension generator = c.generators().get(i);
						inner.target(generator.target());
						if (i > 0) {
							inner.expr(generator.iter());
						}
						generator.ifs().forEach(inner::expr);
					}
					inner.expr(c.elt());
					inner.loads.stream().filter(load -> !inner.stores.contains(load.id())).forEach(loads::add);
				}
			}
		}
	}
}
