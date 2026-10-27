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
				case Ast.If s -> {
					functionsIn(file, s.body(), enclosing);
					functionsIn(file, s.orElse(), enclosing);
				}
				case Ast.While s -> functionsIn(file, s.body(), enclosing);
				case Ast.For s -> functionsIn(file, s.body(), enclosing);
				default -> {
				}
			}
		}
	}

	private static void function(String file, Ast.Def def, List<Scope> enclosing) {
		Names names = new Names();
		names.body(def.body());
		Set<String> locals = new HashSet<>(names.stores);
		def.params().forEach(p -> locals.add(p.name()));
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
				case Ast.FString f -> f.parts().forEach(part -> {
					if (part instanceof Ast.FString.Value value) {
						expr(value.expr());
					}
				});
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
					for (Ast.Comprehension generator : c.generators()) {
						target(generator.target());
						expr(generator.iter());
						generator.ifs().forEach(this::expr);
					}
					expr(c.elt());
				}
			}
		}
	}
}
