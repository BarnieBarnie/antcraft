package io.github.barniebarnie.antcraft.script.lang;

import io.github.barniebarnie.antcraft.script.lang.Ast.BinaryOp;
import io.github.barniebarnie.antcraft.script.lang.Ast.CmpOp;
import io.github.barniebarnie.antcraft.script.lang.Ast.Expr;
import io.github.barniebarnie.antcraft.script.lang.Ast.Stmt;
import io.github.barniebarnie.antcraft.script.lang.Token.Kind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses a script into an {@link Ast.Module}, by recursive descent over Python 3's grammar, and checks it against the
 * supported subset (docs/language/index.md): anything outside it is a {@link CompileError} naming the construct.
 *
 * <p>Statements and atoms sit at their first token; operators, calls ({@code (}), subscripts ({@code [}), attributes
 * ({@code .}) and conditional expressions ({@code if}) at their operator, so a runtime error can point at it.
 */
public final class Parser {
	/** Python's keywords, those outside the subset too. */
	public static final Set<String> KEYWORDS = Set.of("False", "None", "True", "and", "as", "assert", "async", "await",
			"break", "class", "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global", "if",
			"import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with",
			"yield");
	/** Keywords of constructs outside the subset. */
	private static final Set<String> UNSUPPORTED_KEYWORDS = Set.of("with", "yield", "async", "await", "del");
	/** Operators outside the subset. */
	private static final Set<String> UNSUPPORTED_OPERATORS = Set.of("&", "|", "^", "~", "<<", ">>", "@", ":=", "&=", "|=",
			"^=", "<<=", ">>=", "@=", "...");
	private static final Map<String, BinaryOp> AUGMENTED = Map.of("+=", BinaryOp.ADD, "-=", BinaryOp.SUB, "*=",
			BinaryOp.MUL, "/=", BinaryOp.DIV, "//=", BinaryOp.FLOOR_DIV, "%=", BinaryOp.MOD, "**=", BinaryOp.POW);
	private static final Map<String, CmpOp> COMPARISONS = Map.of("==", CmpOp.EQ, "!=", CmpOp.NE, "<", CmpOp.LT, "<=",
			CmpOp.LE, ">", CmpOp.GT, ">=", CmpOp.GE);

	private final String file;
	private final List<Token> tokens;
	private int index;
	private int functionDepth;
	private int loopDepth;

	private Parser(String file, List<Token> tokens) {
		this.file = file;
		this.tokens = tokens;
	}

	/** Parses and checks a whole script; {@code file} names it in errors. */
	public static Ast.Module parse(String file, String source) {
		Parser parser = new Parser(file, Tokenizer.tokenize(file, source));
		List<Stmt> body = new ArrayList<>();
		while (parser.peek().kind() != Kind.EOF) {
			body.addAll(parser.statement());
		}
		Ast.Module module = new Ast.Module(file, List.copyOf(body));
		SubsetCheck.check(module);
		return module;
	}

	// Statements

	private List<Stmt> statement() {
		Token t = peek();
		if (t.kind() == Kind.INDENT) {
			throw error(t, "unexpected indent");
		}
		if (t.isOp("@")) {
			throw error(t, "decorators are not supported");
		}
		if (t.kind() == Kind.NAME) {
			rejectUnsupportedKeyword(t);
			switch (t.text()) {
				case "if" -> {
					return List.of(ifStatement());
				}
				case "while" -> {
					return List.of(whileStatement());
				}
				case "for" -> {
					return List.of(forStatement());
				}
				case "def" -> {
					return List.of(def());
				}
				case "class" -> {
					return List.of(classDef());
				}
				case "try" -> {
					return List.of(tryStatement());
				}
				default -> {
				}
			}
		}
		return simpleStatements();
	}

	private Ast.Try tryStatement() {
		Token t = next();
		expectOp(":", "expected ':' after 'try'");
		List<Stmt> body = block("'try' statement on line " + t.line());
		List<Ast.ExceptHandler> handlers = new ArrayList<>();
		while (peek().isName("except")) {
			Token e = next();
			if (!handlers.isEmpty() && handlers.getLast().type() == null) {
				throw error(handlers.getLast(), "default 'except:' must be last");
			}
			Expr type = null;
			String name = null;
			if (!peek().isOp(":")) {
				type = test();
				if (peek().isName("as")) {
					next();
					name = name("expected a name after 'as'");
				}
			}
			expectOp(":", "expected ':' after 'except' clause");
			handlers.add(new Ast.ExceptHandler(type, name, block("'except' statement on line " + e.line()), e.line(), e.col()));
		}
		List<Stmt> orElse = List.of();
		if (!handlers.isEmpty() && peek().isName("else")) {
			Token e = next();
			expectOp(":", "expected ':' after 'else'");
			orElse = block("'else' statement on line " + e.line());
		}
		List<Stmt> finalBody = List.of();
		if (peek().isName("finally")) {
			Token f = next();
			expectOp(":", "expected ':' after 'finally'");
			finalBody = block("'finally' statement on line " + f.line());
		} else if (handlers.isEmpty()) {
			throw error(peek(), "expected 'except' or 'finally' block");
		}
		return new Ast.Try(body, List.copyOf(handlers), orElse, finalBody, t.line(), t.col());
	}

	/** Small statements separated by {@code ;}, up to and with the end of the line. */
	private List<Stmt> simpleStatements() {
		List<Stmt> statements = new ArrayList<>();
		statements.add(smallStatement());
		while (peek().isOp(";")) {
			next();
			if (peek().kind() == Kind.NEWLINE) {
				break;
			}
			statements.add(smallStatement());
		}
		if (peek().kind() != Kind.NEWLINE) {
			throw unexpected(peek());
		}
		next();
		return statements;
	}

	private Stmt smallStatement() {
		Token t = peek();
		if (t.kind() == Kind.NAME) {
			rejectUnsupportedKeyword(t);
			switch (t.text()) {
				case "pass" -> {
					next();
					return new Ast.Pass(t.line(), t.col());
				}
				case "break", "continue" -> {
					next();
					if (loopDepth == 0) {
						throw error(t, "'" + t.text() + "' outside loop");
					}
					return t.text().equals("break") ? new Ast.Break(t.line(), t.col()) : new Ast.Continue(t.line(), t.col());
				}
				case "return" -> {
					next();
					if (functionDepth == 0) {
						throw error(t, "'return' outside function");
					}
					Expr value = startsExpression(peek()) ? testList() : null;
					return new Ast.Return(value, t.line(), t.col());
				}
				case "assert" -> {
					next();
					Expr test = test();
					Expr message = null;
					if (peek().isOp(",")) {
						next();
						message = test();
					}
					return new Ast.Assert(test, message, t.line(), t.col());
				}
				case "global", "nonlocal" -> {
					next();
					if (t.text().equals("nonlocal") && functionDepth == 0) {
						throw error(t, "nonlocal declaration not allowed at module level");
					}
					List<String> names = new ArrayList<>();
					names.add(name("expected a name after '" + t.text() + "'"));
					while (peek().isOp(",")) {
						next();
						names.add(name("expected a name after ','"));
					}
					return t.text().equals("global") ? new Ast.Global(List.copyOf(names), t.line(), t.col())
							: new Ast.Nonlocal(List.copyOf(names), t.line(), t.col());
				}
				case "raise" -> {
					next();
					Expr exception = startsExpression(peek()) ? test() : null;
					if (peek().isName("from")) {
						throw unsupported(peek(), "`raise ... from`");
					}
					return new Ast.Raise(exception, t.line(), t.col());
				}
				case "import" -> {
					return importStatement();
				}
				case "from" -> {
					return fromImport();
				}
				default -> {
				}
			}
		}
		Expr first = testList();
		if (peek().isOp("=")) {
			List<Expr> targets = new ArrayList<>();
			targets.add(first);
			Expr value;
			while (true) {
				next();
				Expr right = testList();
				if (!peek().isOp("=")) {
					value = right;
					break;
				}
				targets.add(right);
			}
			for (Expr target : targets) {
				checkTarget(target);
			}
			return new Ast.Assign(List.copyOf(targets), value, t.line(), t.col());
		}
		if (peek().kind() == Kind.OP && AUGMENTED.containsKey(peek().text())) {
			BinaryOp op = AUGMENTED.get(next().text());
			if (!(first instanceof Ast.Name || first instanceof Ast.Attribute || first instanceof Ast.Subscript)) {
				throw error(start(first), "illegal target for augmented assignment");
			}
			return new Ast.AugAssign(first, op, testList(), t.line(), t.col());
		}
		if (peek().isOp(":")) {
			throw error(peek(), "annotations are not supported");
		}
		return new Ast.ExprStmt(first, t.line(), t.col());
	}

	private Stmt importStatement() {
		Token t = next();
		List<String> modules = new ArrayList<>();
		while (true) {
			modules.add(dottedName());
			if (peek().isName("as")) {
				throw unsupported(peek(), "`import ... as`");
			}
			if (!peek().isOp(",")) {
				break;
			}
			next();
		}
		return new Ast.Import(List.copyOf(modules), t.line(), t.col());
	}

	private Stmt fromImport() {
		Token t = next();
		if (peek().isOp(".") || peek().isOp("...")) {
			throw error(peek(), "relative imports are not supported");
		}
		String module = dottedName();
		if (!peek().isName("import")) {
			throw unexpected(peek(), "expected 'import'");
		}
		next();
		if (peek().isOp("*")) {
			throw unsupported(peek(), "`from ... import *`");
		}
		boolean parenthesized = peek().isOp("(");
		if (parenthesized) {
			next();
		}
		List<String> names = new ArrayList<>();
		while (true) {
			names.add(name("expected a name to import"));
			if (peek().isName("as")) {
				throw unsupported(peek(), "`import ... as`");
			}
			if (!peek().isOp(",")) {
				break;
			}
			next();
			if (parenthesized && peek().isOp(")")) {
				break;
			}
		}
		if (parenthesized) {
			expectOp(")", "expected ')'");
		}
		return new Ast.FromImport(module, List.copyOf(names), t.line(), t.col());
	}

	private String dottedName() {
		StringBuilder name = new StringBuilder(name("expected a module name"));
		while (peek().isOp(".")) {
			next();
			name.append('.').append(name("expected a module name after '.'"));
		}
		return name.toString();
	}

	private Ast.If ifStatement() {
		Token t = next();
		Expr test = test();
		expectOp(":", "expected ':' after '" + t.text() + "' condition");
		List<Stmt> body = block("'" + t.text() + "' statement on line " + t.line());
		List<Stmt> orElse = List.of();
		if (peek().isName("elif")) {
			orElse = List.of(ifStatement());
		} else if (peek().isName("else")) {
			Token e = next();
			expectOp(":", "expected ':' after 'else'");
			orElse = block("'else' statement on line " + e.line());
		}
		return new Ast.If(test, body, orElse, t.line(), t.col());
	}

	private Ast.While whileStatement() {
		Token t = next();
		Expr test = test();
		expectOp(":", "expected ':' after 'while' condition");
		List<Stmt> body = loopBody("'while' statement on line " + t.line());
		return new Ast.While(test, body, t.line(), t.col());
	}

	private Ast.For forStatement() {
		Token t = next();
		Expr target = targetList();
		if (!peek().isName("in")) {
			throw unexpected(peek(), "expected 'in' after 'for' target");
		}
		next();
		Expr iter = testList();
		expectOp(":", "expected ':' after 'for' clause");
		List<Stmt> body = loopBody("'for' statement on line " + t.line());
		return new Ast.For(target, iter, body, t.line(), t.col());
	}

	private List<Stmt> loopBody(String what) {
		loopDepth++;
		List<Stmt> body = block(what);
		loopDepth--;
		if (peek().isName("else")) {
			throw error(peek(), "`else` on a loop is not supported");
		}
		return body;
	}

	private Ast.Def def() {
		Token t = next();
		String name = name("expected a function name");
		expectOp("(", "expected '(' after the function name");
		Ast.Def params = parameters(name, ")", t);
		next();
		if (peek().isOp("->")) {
			throw error(peek(), "annotations are not supported");
		}
		expectOp(":", "expected ':' after function signature");
		int loops = loopDepth;
		loopDepth = 0;
		functionDepth++;
		List<Stmt> body = block("function definition on line " + t.line());
		functionDepth--;
		loopDepth = loops;
		return new Ast.Def(name, params.params(), params.posOnly(), params.kwOnly(), params.varArgs(),
				params.varKeywords(), body, t.line(), t.col());
	}

	/**
	 * The parameters of a {@code def} or {@code lambda}, up to the {@code close} token ({@code )} or {@code :}), which is
	 * left to read: a {@link Ast.Def} with no body.
	 */
	private Ast.Def parameters(String name, String close, Token t) {
		List<Ast.Param> params = new ArrayList<>();
		Set<String> names = new HashSet<>();
		boolean defaults = false;
		int posOnly = 0;
		Token star = null; // the * or *varArgs after which parameters are keyword-only
		int kwOnly = 0;
		String varArgs = null;
		String varKeywords = null;
		while (!peek().isOp(close)) {
			Token p = peek();
			if (varKeywords != null) {
				throw error(p, "arguments cannot follow var-keyword argument");
			}
			if (p.isOp("/")) {
				if (star != null) {
					throw error(p, "/ must be ahead of *");
				}
				if (posOnly > 0) {
					throw error(p, "/ may appear only once");
				}
				if (params.isEmpty()) {
					throw error(p, "at least one argument must precede /");
				}
				next();
				posOnly = params.size();
			} else if (p.isOp("*") && (peek(1).isOp(",") || peek(1).isOp(close))) {
				if (star != null) {
					throw error(p, "* argument may appear only once");
				}
				next();
				star = p;
			} else if (p.isOp("*") || p.isOp("**")) {
				if (p.isOp("*") && star != null) {
					throw error(p, "* argument may appear only once");
				}
				if (p.isOp("**") && star != null && varArgs == null && kwOnly == 0) {
					throw error(star, "named arguments must follow bare *");
				}
				next();
				Token n = peek();
				String param = name("expected a parameter name");
				if (!names.add(param)) {
					throw error(n, "duplicate parameter '" + param + "'");
				}
				if (p.isOp("*")) {
					varArgs = param;
					star = p;
				} else {
					varKeywords = param;
				}
			} else {
				String param = name("expected a parameter name");
				if (peek().isOp(":") && !close.equals(":")) {
					throw error(peek(), "annotations are not supported");
				}
				Expr defaultValue = null;
				if (peek().isOp("=")) {
					next();
					defaultValue = test();
					defaults = true;
				} else if (defaults && star == null) {
					throw error(p, "parameter without a default follows parameter with a default");
				}
				if (!names.add(param)) {
					throw error(p, "duplicate parameter '" + param + "'");
				}
				params.add(new Ast.Param(param, defaultValue, p.line(), p.col()));
				if (star != null) {
					kwOnly++;
				}
			}
			if (peek().isOp(",")) {
				next();
			} else if (!peek().isOp(close)) {
				throw unexpected(peek());
			}
		}
		if (star != null && varArgs == null && kwOnly == 0) {
			throw error(star, "named arguments must follow bare *");
		}
		return new Ast.Def(name, List.copyOf(params), posOnly, kwOnly, varArgs, varKeywords, List.of(), t.line(), t.col());
	}

	/** {@code lambda params: value}: a function named {@code <lambda>} that returns the value. */
	private Expr lambda() {
		Token t = next();
		Ast.Def params = parameters("<lambda>", ":", t);
		next();
		Expr value = test();
		return new Ast.Lambda(new Ast.Def("<lambda>", params.params(), params.posOnly(), params.kwOnly(), params.varArgs(),
				params.varKeywords(), List.of(new Ast.Return(value, value.line(), value.col())), t.line(), t.col()),
				t.line(), t.col());
	}

	/** {@code class Name:} or {@code class Name(Base):}, with one base at most and no keywords (docs/language/classes.md). */
	private Ast.ClassDef classDef() {
		Token t = next();
		String name = name("expected a class name");
		Expr base = null;
		if (peek().isOp("(")) {
			next();
			if (!peek().isOp(")")) {
				if (peek().kind() == Kind.NAME && peek(1).isOp("=")) {
					throw error(peek(), "metaclasses and class keywords are not supported");
				}
				base = test();
				if (peek().isOp(",")) {
					next();
					if (!peek().isOp(")")) {
						throw error(peek(), "multiple inheritance is not supported");
					}
				}
			}
			expectOp(")", "expected ')' after the base class");
		}
		expectOp(":", "expected ':' after the class name");
		int loops = loopDepth;
		loopDepth = 0;
		List<Stmt> body = block("class definition on line " + t.line());
		loopDepth = loops;
		Set<String> attributes = new HashSet<>();
		for (Stmt statement : body) {
			classStatement(statement, attributes);
		}
		return new Ast.ClassDef(name, base, body, t.line(), t.col());
	}

	/**
	 * Checks a statement of a class body: a method, an assignment to plain names, {@code pass} or a docstring. The values
	 * of a class body are evaluated in the scope around the class, so one may not use a name the body bound before.
	 */
	private void classStatement(Stmt statement, Set<String> attributes) {
		switch (statement) {
			case Ast.Pass ignored -> {
			}
			case Ast.ExprStmt s when s.expr() instanceof Ast.StrLit -> {
			}
			case Ast.Def def -> {
				def.params().forEach(p -> notAnAttribute(p.defaultValue(), attributes));
				special(def, def.name());
				attributes.add(def.name());
			}
			case Ast.Assign assign when assign.targets().stream().allMatch(target -> target instanceof Ast.Name) -> {
				notAnAttribute(assign.value(), attributes);
				for (Expr target : assign.targets()) {
					String attribute = ((Ast.Name) target).id();
					if (attribute.equals("__slots__")) {
						throw unsupportedAt(target, "`__slots__`");
					}
					special(target, attribute);
					attributes.add(attribute);
				}
			}
			default -> throw error(statement, "a class body may only hold methods, attribute assignments, `pass` and a docstring");
		}
	}

	private void special(Ast.Node node, String name) {
		if (name.length() > 4 && name.startsWith("__") && name.endsWith("__") && !name.equals("__init__")) {
			throw error(node, "special methods other than `__init__` are not supported (`" + name + "`)");
		}
	}

	private void notAnAttribute(Expr value, Set<String> attributes) {
		SubsetCheck.Names names = new SubsetCheck.Names();
		names.expr(value);
		for (Ast.Name load : names.loads) {
			if (attributes.contains(load.id())) {
				throw error(load, "a class attribute cannot use another one (`" + load.id() + "`)");
			}
		}
	}

	private CompileError unsupportedAt(Ast.Node node, String construct) {
		return CompileError.unsupported(file, node.line(), node.col(), construct);
	}

	/** The body after a compound statement's ':': an indented block, or simple statements on the same line. */
	private List<Stmt> block(String what) {
		if (peek().kind() != Kind.NEWLINE) {
			return List.copyOf(simpleStatements());
		}
		next();
		if (peek().kind() != Kind.INDENT) {
			throw error(peek(), "expected an indented block after " + what);
		}
		next();
		List<Stmt> body = new ArrayList<>();
		while (peek().kind() != Kind.DEDENT && peek().kind() != Kind.EOF) {
			body.addAll(statement());
		}
		if (peek().kind() == Kind.DEDENT) {
			next();
		}
		return List.copyOf(body);
	}

	private void checkTarget(Expr target) {
		switch (target) {
			case Ast.Name ignored -> {
			}
			case Ast.Attribute ignored -> {
			}
			case Ast.Subscript ignored -> {
			}
			case Ast.TupleLit tuple -> tuple.elements().forEach(this::checkTarget);
			case Ast.ListLit list -> list.elements().forEach(this::checkTarget);
			case Ast.IntLit ignored -> throw error(start(target), "cannot assign to literal");
			case Ast.FloatLit ignored -> throw error(start(target), "cannot assign to literal");
			case Ast.StrLit ignored -> throw error(start(target), "cannot assign to literal");
			case Ast.FString ignored -> throw error(start(target), "cannot assign to literal");
			case Ast.BoolLit ignored -> throw error(start(target), "cannot assign to literal");
			case Ast.NoneLit ignored -> throw error(start(target), "cannot assign to literal");
			case Ast.Call ignored -> throw error(start(target), "cannot assign to function call");
			default -> throw error(start(target), "cannot assign to expression");
		}
	}

	/** The targets of a {@code for}: expressions up to {@code in}, a tuple when there are several. */
	private Expr targetList() {
		Token t = peek();
		List<Expr> targets = new ArrayList<>();
		while (true) {
			if (peek().isOp("*")) {
				throw unsupported(peek(), "`*` unpacking");
			}
			targets.add(bitwiseOr());
			if (!peek().isOp(",")) {
				break;
			}
			next();
			if (peek().isName("in")) {
				break;
			}
		}
		Expr target = targets.size() == 1 && !tokens.get(index - 1).isOp(",")
				? targets.getFirst()
				: new Ast.TupleLit(List.copyOf(targets), t.line(), t.col());
		checkTarget(target);
		return target;
	}

	// Expressions

	/** Expressions separated by commas: a tuple when there is a comma. */
	private Expr testList() {
		Token t = peek();
		Expr first = test();
		if (!peek().isOp(",")) {
			return first;
		}
		List<Expr> elements = new ArrayList<>();
		elements.add(first);
		while (peek().isOp(",")) {
			next();
			if (!startsExpression(peek())) {
				break;
			}
			elements.add(test());
		}
		return new Ast.TupleLit(List.copyOf(elements), t.line(), t.col());
	}

	private Expr test() {
		Token t = peek();
		if (t.isName("lambda")) {
			return lambda();
		}
		if (t.isOp("*")) {
			throw unsupported(t, "`*` unpacking");
		}
		if (t.isOp("**")) {
			throw unsupported(t, "`**` unpacking");
		}
		Expr body = orTest();
		if (!peek().isName("if")) {
			return body;
		}
		Token ifToken = next();
		Expr test = orTest();
		if (!peek().isName("else")) {
			throw unexpected(peek(), "expected 'else' after 'if' expression");
		}
		next();
		Expr orElse = test();
		return new Ast.IfExp(test, body, orElse, ifToken.line(), ifToken.col());
	}

	private Expr orTest() {
		Expr left = andTest();
		while (peek().isName("or")) {
			Token op = next();
			left = new Ast.BoolOp(Ast.BoolOpKind.OR, left, andTest(), op.line(), op.col());
		}
		return left;
	}

	private Expr andTest() {
		Expr left = notTest();
		while (peek().isName("and")) {
			Token op = next();
			left = new Ast.BoolOp(Ast.BoolOpKind.AND, left, notTest(), op.line(), op.col());
		}
		return left;
	}

	private Expr notTest() {
		if (peek().isName("not")) {
			Token t = next();
			return new Ast.Unary(Ast.UnaryOp.NOT, notTest(), t.line(), t.col());
		}
		return comparison();
	}

	private Expr comparison() {
		Expr left = bitwiseOr();
		List<CmpOp> ops = new ArrayList<>();
		List<Expr> comparators = new ArrayList<>();
		Token first = null;
		while (true) {
			Token t = peek();
			CmpOp op;
			if (t.kind() == Kind.OP && COMPARISONS.containsKey(t.text())) {
				op = COMPARISONS.get(t.text());
				next();
			} else if (t.isName("in")) {
				op = CmpOp.IN;
				next();
			} else if (t.isName("not") && peek(1).isName("in")) {
				op = CmpOp.NOT_IN;
				next();
				next();
			} else if (t.isName("is")) {
				next();
				op = CmpOp.IS;
				if (peek().isName("not")) {
					next();
					op = CmpOp.IS_NOT;
				}
			} else {
				break;
			}
			Expr right = bitwiseOr();
			if (op == CmpOp.IS || op == CmpOp.IS_NOT) {
				Expr before = comparators.isEmpty() ? left : comparators.getLast();
				if (!(before instanceof Ast.NoneLit) && !(right instanceof Ast.NoneLit)) {
					throw error(t, "`is` is not supported except with `None`");
				}
			}
			if (first == null) {
				first = t;
			}
			ops.add(op);
			comparators.add(right);
		}
		if (first == null) {
			return left;
		}
		return new Ast.Compare(left, List.copyOf(ops), List.copyOf(comparators), first.line(), first.col());
	}

	/** Arithmetic; the bitwise operators that would bind looser are outside the subset. */
	private Expr bitwiseOr() {
		Expr e = arithmetic();
		Token t = peek();
		if (t.isOp("|") || t.isOp("^") || t.isOp("&") || t.isOp("<<") || t.isOp(">>")) {
			throw unsupported(t, "`" + t.text() + "`");
		}
		return e;
	}

	private Expr arithmetic() {
		Expr left = term();
		while (peek().isOp("+") || peek().isOp("-")) {
			Token op = next();
			left = new Ast.BinOp(op.text().equals("+") ? BinaryOp.ADD : BinaryOp.SUB, left, term(), op.line(), op.col());
		}
		return left;
	}

	private Expr term() {
		Expr left = factor();
		while (true) {
			Token op = peek();
			BinaryOp kind;
			if (op.isOp("*")) {
				kind = BinaryOp.MUL;
			} else if (op.isOp("/")) {
				kind = BinaryOp.DIV;
			} else if (op.isOp("//")) {
				kind = BinaryOp.FLOOR_DIV;
			} else if (op.isOp("%")) {
				kind = BinaryOp.MOD;
			} else if (op.isOp("@")) {
				throw unsupported(op, "`@`");
			} else {
				return left;
			}
			next();
			left = new Ast.BinOp(kind, left, factor(), op.line(), op.col());
		}
	}

	private Expr factor() {
		Token t = peek();
		if (t.isOp("-") || t.isOp("+")) {
			next();
			return new Ast.Unary(t.isOp("-") ? Ast.UnaryOp.NEG : Ast.UnaryOp.POS, factor(), t.line(), t.col());
		}
		if (t.isOp("~")) {
			throw unsupported(t, "`~`");
		}
		return power();
	}

	private Expr power() {
		Expr base = primary();
		if (peek().isOp("**")) {
			Token op = next();
			return new Ast.BinOp(BinaryOp.POW, base, factor(), op.line(), op.col());
		}
		return base;
	}

	private Expr primary() {
		Expr e = atom();
		while (true) {
			Token t = peek();
			if (t.isOp("(")) {
				next();
				e = call(e, t);
			} else if (t.isOp("[")) {
				next();
				Expr index = subscripts();
				expectOp("]", null);
				e = new Ast.Subscript(e, index, t.line(), t.col());
			} else if (t.isOp(".")) {
				next();
				e = new Ast.Attribute(e, name("expected an attribute name after '.'"), t.line(), t.col());
			} else {
				return e;
			}
		}
	}

	private Expr call(Expr func, Token open) {
		List<Expr> args = new ArrayList<>();
		List<Ast.Keyword> keywords = new ArrayList<>();
		Set<String> names = new HashSet<>();
		while (!peek().isOp(")")) {
			Token a = peek();
			boolean unpacked = keywords.stream().anyMatch(k -> k.name() == null);
			if (a.isOp("*")) {
				if (unpacked) {
					throw error(a, "iterable argument unpacking follows keyword argument unpacking");
				}
				next();
				args.add(new Ast.Starred(test(), a.line(), a.col()));
			} else if (a.isOp("**")) {
				next();
				keywords.add(new Ast.Keyword(null, test(), a.line(), a.col()));
			} else if (a.kind() == Kind.NAME && !KEYWORDS.contains(a.text()) && peek(1).isOp("=")) {
				next();
				next();
				if (!names.add(a.text())) {
					throw error(a, "keyword argument repeated: " + a.text());
				}
				keywords.add(new Ast.Keyword(a.text(), test(), a.line(), a.col()));
			} else {
				Expr value = test();
				if (peek().isName("for")) { // a generator expression, the call's only argument
					value = new Ast.ListComp(value, comprehensions(), a.line(), a.col());
					if (!args.isEmpty() || !keywords.isEmpty() || !peek().isOp(")")) {
						throw error(a, "Generator expression must be parenthesized");
					}
				}
				if (!keywords.isEmpty()) {
					throw error(a, "positional argument follows keyword argument" + (unpacked ? " unpacking" : ""));
				}
				args.add(value);
			}
			if (peek().isOp(",")) {
				next();
			} else if (!peek().isOp(")")) {
				throw unexpected(peek());
			}
		}
		next();
		return new Ast.Call(func, List.copyOf(args), List.copyOf(keywords), open.line(), open.col());
	}

	private Expr subscripts() {
		Token t = peek();
		Expr first = subscript();
		if (!peek().isOp(",")) {
			return first;
		}
		List<Expr> items = new ArrayList<>();
		items.add(first);
		while (peek().isOp(",")) {
			next();
			if (peek().isOp("]")) {
				break;
			}
			items.add(subscript());
		}
		return new Ast.TupleLit(List.copyOf(items), t.line(), t.col());
	}

	private Expr subscript() {
		Token t = peek();
		Expr lower = t.isOp(":") ? null : test();
		if (!peek().isOp(":")) {
			return lower;
		}
		next();
		Expr upper = endsSlicePart() ? null : test();
		Expr step = null;
		if (peek().isOp(":")) {
			next();
			step = endsSlicePart() ? null : test();
		}
		return new Ast.Slice(lower, upper, step, t.line(), t.col());
	}

	private boolean endsSlicePart() {
		return peek().isOp(":") || peek().isOp("]") || peek().isOp(",");
	}

	private Expr atom() {
		Token t = peek();
		switch (t.kind()) {
			case NAME -> {
				rejectUnsupportedKeyword(t);
				switch (t.text()) {
					case "True", "False" -> {
						next();
						return new Ast.BoolLit(t.text().equals("True"), t.line(), t.col());
					}
					case "None" -> {
						next();
						return new Ast.NoneLit(t.line(), t.col());
					}
					case "set" -> throw unsupported(t, "`set`");
					default -> {
						if (KEYWORDS.contains(t.text())) {
							throw unexpected(t);
						}
						next();
						return new Ast.Name(t.text(), t.line(), t.col());
					}
				}
			}
			case INT -> {
				next();
				return new Ast.IntLit((Long) t.value(), t.line(), t.col());
			}
			case FLOAT -> {
				next();
				return new Ast.FloatLit((Double) t.value(), t.line(), t.col());
			}
			case STRING, FSTRING -> {
				return strings();
			}
			case OP -> {
				if (t.isOp("(")) {
					return parenthesized();
				}
				if (t.isOp("[")) {
					return list();
				}
				if (t.isOp("{")) {
					return dict();
				}
				throw unexpected(t);
			}
			default -> throw unexpected(t);
		}
	}

	/** Adjacent string literals, joined; an f-string among them makes the whole an f-string. */
	private Expr strings() {
		Token first = peek();
		List<Ast.FString.Part> parts = new ArrayList<>();
		StringBuilder text = new StringBuilder();
		boolean formatted = false;
		while (peek().kind() == Kind.STRING || peek().kind() == Kind.FSTRING) {
			Token t = next();
			if (t.kind() == Kind.STRING) {
				text.append((String) t.value());
				continue;
			}
			formatted = true;
			@SuppressWarnings("unchecked")
			List<Token.FPiece> pieces = (List<Token.FPiece>) t.value();
			for (Token.FPiece piece : pieces) {
				switch (piece) {
					case Token.Text literal -> text.append(literal.text());
					case Token.Field field -> {
						if (!text.isEmpty()) {
							parts.add(new Ast.FString.Text(text.toString()));
							text.setLength(0);
						}
						parts.add(field(field));
					}
				}
			}
		}
		if (!formatted) {
			return new Ast.StrLit(text.toString(), first.line(), first.col());
		}
		if (!text.isEmpty()) {
			parts.add(new Ast.FString.Text(text.toString()));
		}
		return new Ast.FString(List.copyOf(parts), first.line(), first.col());
	}

	private Ast.FString.Value field(Token.Field field) {
		Parser parser = new Parser(file, Tokenizer.tokenizeField(file, field.source(), field.line(), field.col()));
		Expr expr = parser.testList();
		if (parser.peek().kind() != Kind.EOF) {
			throw parser.unexpected(parser.peek());
		}
		List<Ast.FString.Part> spec = null;
		if (field.spec() != null) {
			spec = new ArrayList<>();
			for (Token.FPiece piece : field.spec()) {
				spec.add(switch (piece) {
					case Token.Text text -> new Ast.FString.Text(text.text());
					case Token.Field nested -> field(nested);
				});
			}
			spec = List.copyOf(spec);
		}
		return new Ast.FString.Value(expr, field.conversion(), spec);
	}

	private Expr parenthesized() {
		Token open = next();
		if (peek().isOp(")")) {
			next();
			return new Ast.TupleLit(List.of(), open.line(), open.col());
		}
		Expr first = test();
		if (peek().isName("for")) { // a generator expression
			first = new Ast.ListComp(first, comprehensions(), open.line(), open.col());
			expectOp(")", null);
			return first;
		}
		if (peek().isOp(")")) {
			next();
			return first;
		}
		List<Expr> elements = new ArrayList<>();
		elements.add(first);
		while (peek().isOp(",")) {
			next();
			if (peek().isOp(")")) {
				break;
			}
			elements.add(test());
		}
		expectOp(")", null);
		return new Ast.TupleLit(List.copyOf(elements), open.line(), open.col());
	}

	private Expr list() {
		Token open = next();
		List<Expr> elements = new ArrayList<>();
		if (peek().isOp("]")) {
			next();
			return new Ast.ListLit(List.of(), open.line(), open.col());
		}
		Expr first = test();
		if (peek().isName("for") || peek().isName("async")) {
			Ast.ListComp comprehension = new Ast.ListComp(first, comprehensions(), open.line(), open.col());
			expectOp("]", null);
			return comprehension;
		}
		elements.add(first);
		while (peek().isOp(",")) {
			next();
			if (peek().isOp("]")) {
				break;
			}
			elements.add(test());
		}
		expectOp("]", null);
		return new Ast.ListLit(List.copyOf(elements), open.line(), open.col());
	}

	private List<Ast.Comprehension> comprehensions() {
		List<Ast.Comprehension> generators = new ArrayList<>();
		while (peek().isName("for") || peek().isName("async")) {
			rejectUnsupportedKeyword(peek());
			Token t = next();
			Expr target = targetList();
			if (!peek().isName("in")) {
				throw unexpected(peek(), "expected 'in' after 'for' target");
			}
			next();
			Expr iter = orTest();
			List<Expr> ifs = new ArrayList<>();
			while (peek().isName("if")) {
				next();
				ifs.add(orTest());
			}
			generators.add(new Ast.Comprehension(target, iter, List.copyOf(ifs), t.line(), t.col()));
		}
		return List.copyOf(generators);
	}

	private Expr dict() {
		Token open = next();
		if (peek().isOp("}")) {
			next();
			return new Ast.DictLit(List.of(), List.of(), open.line(), open.col());
		}
		Expr key = test();
		if (!peek().isOp(":")) {
			if (peek().isName("for")) {
				throw error(open, "set comprehensions are not supported");
			}
			throw error(open, "set literals are not supported");
		}
		next();
		Expr value = test();
		if (peek().isName("for")) {
			throw error(open, "dict comprehensions are not supported");
		}
		List<Expr> keys = new ArrayList<>();
		List<Expr> values = new ArrayList<>();
		keys.add(key);
		values.add(value);
		while (peek().isOp(",")) {
			next();
			if (peek().isOp("}")) {
				break;
			}
			keys.add(test());
			expectOp(":", "expected ':' after a dict key");
			values.add(test());
		}
		expectOp("}", null);
		return new Ast.DictLit(List.copyOf(keys), List.copyOf(values), open.line(), open.col());
	}

	// Helpers

	private static boolean startsExpression(Token t) {
		return switch (t.kind()) {
			case NAME -> !KEYWORDS.contains(t.text()) || Set.of("True", "False", "None", "not", "lambda", "yield", "await")
					.contains(t.text());
			case INT, FLOAT, STRING, FSTRING -> true;
			case OP -> Set.of("(", "[", "{", "-", "+", "~", "*", "**", "...").contains(t.text());
			default -> false;
		};
	}

	/** Where an expression's source starts: its leftmost token. */
	private static Ast.Node start(Expr e) {
		return switch (e) {
			case Ast.BinOp b -> start(b.left());
			case Ast.BoolOp b -> start(b.left());
			case Ast.Compare c -> start(c.left());
			case Ast.IfExp i -> start(i.body());
			case Ast.Attribute a -> start(a.value());
			case Ast.Subscript s -> start(s.value());
			case Ast.Call c -> start(c.func());
			default -> e;
		};
	}

	private void rejectUnsupportedKeyword(Token t) {
		if (t.kind() == Kind.NAME && UNSUPPORTED_KEYWORDS.contains(t.text())) {
			throw unsupported(t, "`" + t.text() + "`");
		}
	}

	private String name(String message) {
		Token t = peek();
		if (t.kind() != Kind.NAME || KEYWORDS.contains(t.text())) {
			throw unexpected(t, message);
		}
		next();
		return t.text();
	}

	private void expectOp(String op, String message) {
		if (!peek().isOp(op)) {
			throw unexpected(peek(), message);
		}
		next();
	}

	private Token peek() {
		return tokens.get(index);
	}

	private Token peek(int ahead) {
		return tokens.get(Math.min(index + ahead, tokens.size() - 1));
	}

	private Token next() {
		Token t = tokens.get(index);
		if (t.kind() != Kind.EOF) {
			index++;
		}
		return t;
	}

	private CompileError unexpected(Token t) {
		return unexpected(t, null);
	}

	/** An error at an unexpected token: its own when the token is outside the subset, else {@code message}. */
	private CompileError unexpected(Token t, String message) {
		if (t.kind() == Kind.OP && UNSUPPORTED_OPERATORS.contains(t.text())) {
			return unsupported(t, "`" + t.text() + "`");
		}
		if (t.kind() == Kind.NAME && UNSUPPORTED_KEYWORDS.contains(t.text())) {
			return unsupported(t, "`" + t.text() + "`");
		}
		return error(t, message != null ? message : "invalid syntax");
	}

	private CompileError unsupported(Token t, String construct) {
		return CompileError.unsupported(file, t.line(), t.col(), construct);
	}

	private CompileError error(Token t, String message) {
		return new CompileError(file, t.line(), t.col(), message);
	}

	private CompileError error(Ast.Node node, String message) {
		return new CompileError(file, node.line(), node.col(), message);
	}
}
