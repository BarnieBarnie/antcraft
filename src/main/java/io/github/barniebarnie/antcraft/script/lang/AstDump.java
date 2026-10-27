package io.github.barniebarnie.antcraft.script.lang;

import io.github.barniebarnie.antcraft.script.lang.Ast.Expr;
import io.github.barniebarnie.antcraft.script.lang.Ast.Stmt;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Writes a syntax tree as compact s-expressions, without positions: {@code (+ 1 (* 2 3))}. For tests and debugging.
 */
public final class AstDump {
	private AstDump() {
	}

	/** The module's statements, one a line. */
	public static String dump(Ast.Module module) {
		return module.body().stream().map(AstDump::dump).collect(Collectors.joining("\n"));
	}

	public static String dump(Stmt statement) {
		return switch (statement) {
			case Ast.ExprStmt s -> dump(s.expr());
			case Ast.Assign s -> "(= " + exprs(s.targets()) + " " + dump(s.value()) + ")";
			case Ast.AugAssign s -> "(" + s.op().symbol + "= " + dump(s.target()) + " " + dump(s.value()) + ")";
			case Ast.If s -> "(if " + dump(s.test()) + " " + block(s.body()) + " " + block(s.orElse()) + ")";
			case Ast.While s -> "(while " + dump(s.test()) + " " + block(s.body()) + ")";
			case Ast.For s -> "(for " + dump(s.target()) + " " + dump(s.iter()) + " " + block(s.body()) + ")";
			case Ast.Break s -> "break";
			case Ast.Continue s -> "continue";
			case Ast.Pass s -> "pass";
			case Ast.Def s -> "(def " + s.name() + " (" + s.params().stream()
					.map(p -> p.defaultValue() == null ? p.name() : "(" + p.name() + " " + dump(p.defaultValue()) + ")")
					.collect(Collectors.joining(" ")) + ") " + block(s.body()) + ")";
			case Ast.Return s -> s.value() == null ? "return" : "(return " + dump(s.value()) + ")";
			case Ast.Global s -> "(global " + String.join(" ", s.names()) + ")";
			case Ast.Import s -> "(import " + String.join(" ", s.modules()) + ")";
			case Ast.FromImport s -> "(from " + s.module() + " " + String.join(" ", s.names()) + ")";
		};
	}

	public static String dump(Expr e) {
		return switch (e) {
			case null -> "_";
			case Ast.Name n -> n.id();
			case Ast.IntLit n -> Long.toString(n.value());
			case Ast.FloatLit n -> Double.toString(n.value());
			case Ast.StrLit s -> quote(s.value());
			case Ast.BoolLit b -> b.value() ? "True" : "False";
			case Ast.NoneLit n -> "None";
			case Ast.FString f -> "(fstr" + f.parts().stream().map(AstDump::part).collect(Collectors.joining()) + ")";
			case Ast.ListLit l -> "(list" + prefixed(l.elements()) + ")";
			case Ast.TupleLit t -> "(tuple" + prefixed(t.elements()) + ")";
			case Ast.DictLit d -> {
				StringBuilder out = new StringBuilder("(dict");
				for (int i = 0; i < d.keys().size(); i++) {
					out.append(" (").append(dump(d.keys().get(i))).append(' ').append(dump(d.values().get(i))).append(')');
				}
				yield out.append(')').toString();
			}
			case Ast.Unary u -> "(" + u.op().name().toLowerCase() + " " + dump(u.operand()) + ")";
			case Ast.BinOp b -> "(" + b.op().symbol + " " + dump(b.left()) + " " + dump(b.right()) + ")";
			case Ast.BoolOp b -> "(" + b.op().name().toLowerCase() + " " + dump(b.left()) + " " + dump(b.right()) + ")";
			case Ast.Compare c -> {
				StringBuilder out = new StringBuilder("(cmp ").append(dump(c.left()));
				for (int i = 0; i < c.ops().size(); i++) {
					out.append(' ').append(c.ops().get(i).symbol).append(' ').append(dump(c.comparators().get(i)));
				}
				yield out.append(')').toString();
			}
			case Ast.IfExp i -> "(if " + dump(i.test()) + " " + dump(i.body()) + " " + dump(i.orElse()) + ")";
			case Ast.Attribute a -> "(. " + dump(a.value()) + " " + a.attr() + ")";
			case Ast.Subscript s -> "(index " + dump(s.value()) + " " + dump(s.index()) + ")";
			case Ast.Slice s -> "(slice " + dump(s.lower()) + " " + dump(s.upper()) + " " + dump(s.step()) + ")";
			case Ast.Call c -> "(call " + dump(c.func()) + prefixed(c.args()) + c.keywords().stream()
					.map(k -> " (kw " + k.name() + " " + dump(k.value()) + ")").collect(Collectors.joining()) + ")";
			case Ast.ListComp c -> "(listcomp " + dump(c.elt()) + c.generators().stream()
					.map(g -> " (for " + dump(g.target()) + " " + dump(g.iter())
							+ g.ifs().stream().map(i -> " (if " + dump(i) + ")").collect(Collectors.joining()) + ")")
					.collect(Collectors.joining()) + ")";
		};
	}

	private static String part(Ast.FString.Part part) {
		return switch (part) {
			case Ast.FString.Text t -> " " + quote(t.text());
			case Ast.FString.Value v -> " (fmt " + dump(v.expr()) + (v.conversion() == '\0' ? "" : " " + v.conversion())
					+ (v.spec() == null ? "" : " " + quote(v.spec())) + ")";
		};
	}

	private static String block(List<Stmt> body) {
		return body.stream().map(AstDump::dump).collect(Collectors.joining(" ", "[", "]"));
	}

	private static String exprs(List<Expr> list) {
		return list.stream().map(AstDump::dump).collect(Collectors.joining(" "));
	}

	private static String prefixed(List<Expr> list) {
		return list.stream().map(e -> " " + dump(e)).collect(Collectors.joining());
	}

	private static String quote(String s) {
		return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'";
	}
}
