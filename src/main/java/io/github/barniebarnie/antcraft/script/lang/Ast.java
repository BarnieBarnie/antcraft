package io.github.barniebarnie.antcraft.script.lang;

import java.util.List;

/**
 * The syntax tree of a script: immutable records, each with the line and column (from 1) where it starts. Only the
 * supported subset of Python has nodes; the parser rejects everything else. Lists are unmodifiable.
 */
public final class Ast {
	private Ast() {
	}

	/** Any node: it knows where in the source it starts. */
	public sealed interface Node {
		int line();

		int col();
	}

	// Statements

	public sealed interface Stmt extends Node {
	}

	/** A whole file. */
	public record Module(String file, List<Stmt> body) {
	}

	/** An expression evaluated for its effect, such as a call. */
	public record ExprStmt(Expr expr, int line, int col) implements Stmt {
	}

	/**
	 * {@code a = b = value}: each target is a {@link Name}, {@link Attribute}, {@link Subscript}, or a {@link TupleLit}
	 * or {@link ListLit} of targets.
	 */
	public record Assign(List<Expr> targets, Expr value, int line, int col) implements Stmt {
	}

	/** {@code target op= value}; the target is a {@link Name}, {@link Attribute} or {@link Subscript}. */
	public record AugAssign(Expr target, BinaryOp op, Expr value, int line, int col) implements Stmt {
	}

	/** {@code if}; an {@code elif} is an {@code If} alone in {@code orElse}. */
	public record If(Expr test, List<Stmt> body, List<Stmt> orElse, int line, int col) implements Stmt {
	}

	public record While(Expr test, List<Stmt> body, int line, int col) implements Stmt {
	}

	/** {@code for target in iter}; the target is as for {@link Assign}. */
	public record For(Expr target, Expr iter, List<Stmt> body, int line, int col) implements Stmt {
	}

	public record Break(int line, int col) implements Stmt {
	}

	public record Continue(int line, int col) implements Stmt {
	}

	public record Pass(int line, int col) implements Stmt {
	}

	/**
	 * {@code def name(params, *varArgs, **varKeywords): body}; {@code varArgs} and {@code varKeywords} are null when it
	 * has none. The first {@code posOnly} params are positional-only (before a {@code /}), the last {@code kwOnly}
	 * keyword-only (after {@code *} or {@code *varArgs}).
	 */
	public record Def(String name, List<Param> params, int posOnly, int kwOnly, String varArgs, String varKeywords,
			List<Stmt> body, int line, int col) implements Stmt {
		/** The params that can be given by position. */
		public List<Param> positional() {
			return params.subList(0, params.size() - kwOnly);
		}

		/** The names of its parameters, {@code *varArgs} and {@code **varKeywords} included, in order. */
		public List<String> paramNames() {
			List<String> names = new java.util.ArrayList<>(params.stream().map(Param::name).toList());
			if (varArgs != null) {
				names.add(varArgs);
			}
			if (varKeywords != null) {
				names.add(varKeywords);
			}
			return names;
		}
	}

	/**
	 * {@code class name(base): body}; {@code base} is null when it has none. The body holds only methods, attribute
	 * assignments to plain names, {@code pass} and docstrings (docs/language/classes.md).
	 */
	public record ClassDef(String name, Expr base, List<Stmt> body, int line, int col) implements Stmt {
	}

	/** A parameter; {@code defaultValue} is null when it has none. */
	public record Param(String name, Expr defaultValue, int line, int col) implements Node {
	}

	/** {@code return value}; {@code value} is null for a bare {@code return}. */
	public record Return(Expr value, int line, int col) implements Stmt {
	}

	/** {@code assert test, message}; {@code message} is null when left out. */
	public record Assert(Expr test, Expr message, int line, int col) implements Stmt {
	}

	public record Global(List<String> names, int line, int col) implements Stmt {
	}

	/** {@code nonlocal a, b}: names of a function around this one that it assigns. */
	public record Nonlocal(List<String> names, int line, int col) implements Stmt {
	}

	/** {@code import a.b, c}: dotted module names. */
	public record Import(List<String> modules, int line, int col) implements Stmt {
	}

	/** {@code from module import a, b}. */
	public record FromImport(String module, List<String> names, int line, int col) implements Stmt {
	}

	/** {@code try} with its {@code except} clauses, {@code else} and {@code finally}; at least a handler or a finally. */
	public record Try(List<Stmt> body, List<ExceptHandler> handlers, List<Stmt> orElse, List<Stmt> finalBody, int line,
			int col) implements Stmt {
	}

	/** {@code except type as name:}; {@code type} is null for a bare {@code except}, {@code name} null without {@code as}. */
	public record ExceptHandler(Expr type, String name, List<Stmt> body, int line, int col) implements Node {
	}

	/** {@code raise exception}; {@code exception} is null for a bare {@code raise}. */
	public record Raise(Expr exception, int line, int col) implements Stmt {
	}

	// Expressions

	public sealed interface Expr extends Node {
	}

	public record Name(String id, int line, int col) implements Expr {
	}

	public record IntLit(long value, int line, int col) implements Expr {
	}

	public record FloatLit(double value, int line, int col) implements Expr {
	}

	public record StrLit(String value, int line, int col) implements Expr {
	}

	public record BoolLit(boolean value, int line, int col) implements Expr {
	}

	public record NoneLit(int line, int col) implements Expr {
	}

	/** An f-string, as literal text and formatted values in order. */
	public record FString(List<Part> parts, int line, int col) implements Expr {
		public sealed interface Part {
		}

		public record Text(String text) implements Part {
		}

		/**
		 * {@code {expr!conversion:spec}}: {@code conversion} is {@code '\0'} and {@code spec} null when absent; a spec
		 * is text and nested values ({@code {x:{width}}}).
		 */
		public record Value(Expr expr, char conversion, List<Part> spec) implements Part {
			/** The spec's text when it holds no nested values, {@code ""} when there is none, else null. */
			public String constantSpec() {
				if (spec == null) {
					return "";
				}
				StringBuilder out = new StringBuilder();
				for (Part part : spec) {
					if (!(part instanceof Text text)) {
						return null;
					}
					out.append(text.text());
				}
				return out.toString();
			}
		}
	}

	public record ListLit(List<Expr> elements, int line, int col) implements Expr {
	}

	public record TupleLit(List<Expr> elements, int line, int col) implements Expr {
	}

	/** A dict display; {@code keys} and {@code values} are as long as each other. */
	public record DictLit(List<Expr> keys, List<Expr> values, int line, int col) implements Expr {
	}

	public enum UnaryOp {
		NEG, POS, NOT
	}

	public record Unary(UnaryOp op, Expr operand, int line, int col) implements Expr {
	}

	public enum BinaryOp {
		ADD("+"), SUB("-"), MUL("*"), DIV("/"), FLOOR_DIV("//"), MOD("%"), POW("**");

		public final String symbol;

		BinaryOp(String symbol) {
			this.symbol = symbol;
		}
	}

	public record BinOp(BinaryOp op, Expr left, Expr right, int line, int col) implements Expr {
	}

	public enum BoolOpKind {
		AND, OR
	}

	/** {@code left and right} or {@code left or right}: evaluates to an operand, short-circuiting. */
	public record BoolOp(BoolOpKind op, Expr left, Expr right, int line, int col) implements Expr {
	}

	public enum CmpOp {
		EQ("=="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">="), IN("in"), NOT_IN("not-in"), IS("is"), IS_NOT("is-not");

		public final String symbol;

		CmpOp(String symbol) {
			this.symbol = symbol;
		}
	}

	/**
	 * {@code left op0 c0 op1 c1 ...}: a chained comparison, true when every pair holds, each operand evaluated once.
	 * {@code IS} and {@code IS_NOT} only compare with {@code None}.
	 */
	public record Compare(Expr left, List<CmpOp> ops, List<Expr> comparators, int line, int col) implements Expr {
	}

	/** {@code body if test else orElse}. */
	public record IfExp(Expr test, Expr body, Expr orElse, int line, int col) implements Expr {
	}

	public record Attribute(Expr value, String attr, int line, int col) implements Expr {
	}

	/** {@code value[index]}; the index is a {@link Slice} for slicing. */
	public record Subscript(Expr value, Expr index, int line, int col) implements Expr {
	}

	/** {@code lower:upper:step} inside a subscript; each part is null when left out. */
	public record Slice(Expr lower, Expr upper, Expr step, int line, int col) implements Expr {
	}

	/** A call; an argument {@link Starred} spreads its items as positional arguments. */
	public record Call(Expr func, List<Expr> args, List<Keyword> keywords, int line, int col) implements Expr {
	}

	/** {@code *value} among a call's arguments. */
	public record Starred(Expr value, int line, int col) implements Expr {
	}

	/** A keyword argument {@code name=value}, or {@code **value} when {@code name} is null. */
	public record Keyword(String name, Expr value, int line, int col) implements Node {
	}

	/** {@code lambda params: value}: a {@link Def} named {@code <lambda>} whose body returns the value. */
	public record Lambda(Def def, int line, int col) implements Expr {
	}

	/** {@code [elt for ... in ... if ...]}, or a generator expression, which is evaluated as a list. */
	public record ListComp(Expr elt, List<Comprehension> generators, int line, int col) implements Expr {
	}

	/** One {@code for target in iter if cond...} clause of a comprehension. */
	public record Comprehension(Expr target, Expr iter, List<Expr> ifs, int line, int col) implements Node {
	}
}
