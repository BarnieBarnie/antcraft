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

	/** {@code def name(params): body}. */
	public record Def(String name, List<Param> params, List<Stmt> body, int line, int col) implements Stmt {
	}

	/** A parameter; {@code defaultValue} is null when it has none. */
	public record Param(String name, Expr defaultValue, int line, int col) implements Node {
	}

	/** {@code return value}; {@code value} is null for a bare {@code return}. */
	public record Return(Expr value, int line, int col) implements Stmt {
	}

	public record Global(List<String> names, int line, int col) implements Stmt {
	}

	/** {@code import a.b, c}: dotted module names. */
	public record Import(List<String> modules, int line, int col) implements Stmt {
	}

	/** {@code from module import a, b}. */
	public record FromImport(String module, List<String> names, int line, int col) implements Stmt {
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

		/** {@code {expr!conversion:spec}}: {@code conversion} is {@code '\0'} and {@code spec} null when absent. */
		public record Value(Expr expr, char conversion, String spec) implements Part {
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

	public record Call(Expr func, List<Expr> args, List<Keyword> keywords, int line, int col) implements Expr {
	}

	/** A keyword argument {@code name=value}. */
	public record Keyword(String name, Expr value, int line, int col) implements Node {
	}

	/** {@code [elt for ... in ... if ...]}. */
	public record ListComp(Expr elt, List<Comprehension> generators, int line, int col) implements Expr {
	}

	/** One {@code for target in iter if cond...} clause of a comprehension. */
	public record Comprehension(Expr target, Expr iter, List<Expr> ifs, int line, int col) implements Node {
	}
}
