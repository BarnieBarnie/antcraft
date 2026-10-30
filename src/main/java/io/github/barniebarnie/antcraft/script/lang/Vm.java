package io.github.barniebarnie.antcraft.script.lang;

import io.github.barniebarnie.antcraft.script.lang.Ast.BinaryOp;
import io.github.barniebarnie.antcraft.script.lang.Ast.CmpOp;
import io.github.barniebarnie.antcraft.script.lang.Ast.UnaryOp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Runs compiled scripts a number of instructions at a time. A stack machine with explicit frames: a script calling a
 * function pushes a frame rather than recursing in Java, so the VM can stop after any instruction and continue later
 * from exactly there. Its whole state (frames with their code position, locals and operand stack; the modules with their
 * globals, by name; whether it waits for a native) is plain data, so a running script can be saved.
 *
 * <p>The script is the main module; {@code import} runs the top level of another module of the {@link ModuleLoader} in a
 * frame of its own, once per run, and every module has its own globals. A function sees the globals of the module that
 * defined it. A name a module does not define is looked up in the {@link #setBuiltin builtin values}, then the natives,
 * which every module shares.
 *
 * <p>A native function can suspend the script ({@link NativeCall#suspend()}); the host then resumes it with the call's
 * result ({@link #resume}). Bot actions work this way.
 */
public final class Vm {
	/** How deep script functions may call each other before the script fails. */
	public static final int DEFAULT_MAX_DEPTH = 200;

	private static final BinaryOp[] BINARY_OPS = BinaryOp.values();
	private static final CmpOp[] CMP_OPS = CmpOp.values();
	private static final UnaryOp[] UNARY_OPS = UnaryOp.values();

	public enum Status {
		/** Has instructions left to run. */
		RUNNING,
		/** Waits for the host to {@link #resume} it with the result of a native call. */
		SUSPENDED,
		/** Ran to its end. */
		FINISHED,
		/** Stopped with an {@link #error()}. */
		FAILED
	}

	/** A running call of a function, or of a module's top level. */
	static final class Frame {
		final Code code;
		/** The module whose globals the code sees. */
		final PyModule module;
		/** Whether this runs the top level of an imported module, which is imported once the frame returns. */
		final boolean imports;
		/** Whether this runs the {@code __init__} of a new instance, which is the call's result once the frame returns. */
		boolean init;
		int pc;
		/** Null where a local is not set yet. */
		final Object[] locals;
		Object[] stack = new Object[8];
		int sp;
		/** The {@code try} blocks the frame is in, innermost last: for each its handler's pc, then the stack height. */
		int[] blocks = new int[0];
		int blockCount;

		Frame(Code code, Object[] locals, PyModule module, boolean imports) {
			this.code = code;
			this.locals = locals;
			this.module = module;
			this.imports = imports;
		}

		void push(Object value) {
			if (sp == stack.length) {
				stack = Arrays.copyOf(stack, sp * 2);
			}
			stack[sp++] = value;
		}

		Object pop() {
			Object value = stack[--sp];
			stack[sp] = null;
			return value;
		}

		Object peek() {
			return stack[sp - 1];
		}

		void pushBlock(int handler) {
			if (2 * blockCount == blocks.length) {
				blocks = Arrays.copyOf(blocks, Math.max(4, 2 * blocks.length));
			}
			blocks[2 * blockCount] = handler;
			blocks[2 * blockCount + 1] = sp;
			blockCount++;
		}

		/** Pops the top {@code n} values, in the order they were pushed. */
		List<Object> pop(int n) {
			List<Object> values = new ArrayList<>(Arrays.asList(stack).subList(sp - n, sp));
			Arrays.fill(stack, sp - n, sp, null);
			sp -= n;
			return values;
		}
	}

	private final Natives natives;
	private final int maxDepth;
	private final ModuleLoader loader;
	/** Every module of the run, by name, in the order their import started; the main module first. */
	final Map<String, PyModule> modules = new LinkedHashMap<>();
	final PyModule main;
	/** Values every module sees, such as {@code memory}; a module's own global of the same name hides one. */
	final Map<String, Object> builtins = new LinkedHashMap<>();
	final List<Frame> frames = new ArrayList<>();
	Status status = Status.RUNNING;
	private ScriptError error;
	long executed;
	/** Instructions {@link #charge charged} but not yet taken from a budget. */
	long debt;
	/** The native call a suspended script waits on, so a loaded script can make it again ({@link #recall}). */
	@Nullable PendingCall pending;

	/** A call of a native: the function or method called, and its arguments as the script gave them. */
	record PendingCall(Object callee, List<Object> args, Map<String, Object> kwargs) {
	}

	/** The name of the main module of a VM made without one. */
	public static final String MAIN = "__main__";

	/** A VM without modules to import. */
	public Vm(Code module, Natives natives) {
		this(module, natives, DEFAULT_MAX_DEPTH);
	}

	public Vm(Code module, Natives natives, int maxDepth) {
		this(MAIN, module, natives, ModuleLoader.NONE, maxDepth);
	}

	/** A VM that runs {@code module} as the module {@code name}, importing modules from {@code loader}. */
	public Vm(String name, Code module, Natives natives, ModuleLoader loader) {
		this(name, module, natives, loader, DEFAULT_MAX_DEPTH);
	}

	public Vm(String name, Code module, Natives natives, ModuleLoader loader, int maxDepth) {
		this.natives = natives;
		this.maxDepth = maxDepth;
		this.loader = loader;
		this.main = new PyModule(name);
		main.setCode(module);
		modules.put(name, main);
		frames.add(new Frame(module, new Object[module.locals().length], main, false));
	}

	/** A VM without frames whose main module is {@code main}, which {@link VmState} fills as it loads a saved one. */
	Vm(PyModule main, Natives natives, ModuleLoader loader, int maxDepth) {
		this.natives = natives;
		this.maxDepth = maxDepth;
		this.loader = loader;
		this.main = main;
	}

	public Status status() {
		return status;
	}

	/** Why the script failed, or null while it has not. */
	public ScriptError error() {
		return error;
	}

	/** How many instructions ran so far, over every {@link #run}. */
	public long executed() {
		return executed;
	}

	/** The main module's globals, by name; read-only. */
	public Map<String, Object> globals() {
		return Collections.unmodifiableMap(main.globals());
	}

	/** Sets the main module's global {@code name}. */
	public void setGlobal(String name, Object value) {
		main.globals().put(name, value);
	}

	/** Sets a value every module sees under {@code name}, as it sees the natives, such as {@code memory}. */
	public void setBuiltin(String name, Object value) {
		builtins.put(name, value);
	}

	/** The value {@link #setBuiltin} set under {@code name}, or null. */
	public @Nullable Object builtin(String name) {
		return builtins.get(name);
	}

	/** The modules of the run, by name, in the order their import started: the main module first; read-only. */
	public Map<String, PyModule> modules() {
		return Collections.unmodifiableMap(modules);
	}

	/**
	 * The source line about to run, or 0 once the script has finished. Inside a builtin written in the script language,
	 * the line of the script that called it.
	 */
	public int line() {
		if (frames.isEmpty()) {
			return 0;
		}
		Frame frame = frames.getLast();
		for (int i = frames.size() - 2; i >= 0 && frame.code.file().equals(Natives.BUILTINS_FILE); i--) {
			frame = frames.get(i);
			if (!frame.code.file().equals(Natives.BUILTINS_FILE)) {
				return frame.code.lines()[frame.pc - 1];
			}
		}
		return frame.code.lines()[Math.min(frame.pc, frame.code.size() - 1)];
	}

	/**
	 * Runs at most {@code budget} instructions and reports where the script is: still running (out of budget),
	 * suspended in a native call, finished or failed. A script that is not running is left as it is.
	 */
	public Status run(long budget) {
		long left = budget - payDebt(budget);
		while (left > 0 && status == Status.RUNNING) {
			left--;
			int depth = frames.size() - 1;
			Frame frame = frames.get(depth);
			Code code = frame.code;
			int pc = frame.pc++;
			executed++;
			try {
				execute(frame, code.ops()[pc], code.args()[pc]);
			} catch (ScriptError e) {
				fail(e.file() == null ? place(e, depth, pc) : e);
			}
			left -= payDebt(left);
		}
		return status;
	}

	/**
	 * Counts {@code instructions} more against the script, as if it had run them: a native that does a lot of work,
	 * such as reading many blocks, charges for it. They use up what is left of the current {@link #run}'s budget, and
	 * what they exceed it by comes off the budgets of the next runs.
	 */
	public void charge(long instructions) {
		executed += instructions;
		debt += instructions;
	}

	/** Pays off as much of the charged instructions as {@code left} allows; gives how much was paid. */
	private long payDebt(long left) {
		long paid = Math.min(debt, Math.max(0, left));
		debt -= paid;
		return paid;
	}

	/**
	 * Places an error at the instruction of frame {@code depth} that raised it; an error in a builtin written in the
	 * script language ({@link Natives#BUILTINS_FILE}) is placed at the script's call of that builtin instead.
	 */
	private ScriptError place(ScriptError e, int depth, int pc) {
		if (depth >= frames.size()) { // the frame returned, as an __init__ does before its result is checked
			depth = frames.size() - 1;
			pc = frames.get(depth).pc - 1;
		}
		for (int i = depth; i >= 0; i--) {
			Frame frame = frames.get(i);
			if (!frame.code.file().equals(Natives.BUILTINS_FILE) || i == 0) {
				return e.at(frame.code.file(), frame.code.lines()[i == depth ? pc : frame.pc - 1]);
			}
		}
		throw new IllegalStateException("no frames");
	}

	/** Continues a suspended script: {@code value} becomes the result of the native call it waits on. */
	public void resume(Object value) {
		if (status != Status.SUSPENDED) {
			throw new IllegalStateException("the script is " + status + ", not suspended");
		}
		frames.getLast().push(value);
		status = Status.RUNNING;
		pending = null;
	}

	/**
	 * Makes again the native call a {@link VmState loaded} script was suspended in when it was saved, with the same
	 * arguments, as if the script had just reached it: the native starts its action again, suspending the script, or
	 * returns its result at once. A native that fails fails the script at the call's line.
	 */
	public Status recall() {
		if (status != Status.SUSPENDED || pending == null) {
			throw new IllegalStateException("the script is " + status + ", not suspended in a native call");
		}
		PendingCall call = pending;
		pending = null;
		status = Status.RUNNING;
		try {
			call(call.callee(), call.args(), call.kwargs());
		} catch (ScriptError e) {
			Frame frame = frames.getLast();
			fail(e.file() == null ? place(e, frames.size() - 1, frame.pc - 1) : e);
		}
		return status;
	}

	/**
	 * Hands a placed error to the innermost {@code try} block it was raised in (docs/language/errors.md, Catching
	 * errors): drops the frames above that block's, cuts the operand stack back, pushes the exception and jumps to the
	 * handler. Without one the script fails with it.
	 */
	private void fail(ScriptError e) {
		for (int i = frames.size() - 1; i >= 0; i--) {
			Frame frame = frames.get(i);
			if (frame.blockCount > 0) {
				while (frames.size() > i + 1) {
					Frame dropped = frames.removeLast();
					if (dropped.imports) { // its import failed, so a later one starts again
						modules.remove(dropped.module.name());
					}
				}
				frame.blockCount--;
				int sp = frame.blocks[2 * frame.blockCount + 1];
				Arrays.fill(frame.stack, sp, frame.sp, null);
				frame.sp = sp;
				frame.push(e.exception());
				frame.pc = frame.blocks[2 * frame.blockCount];
				return;
			}
		}
		error = e;
		status = Status.FAILED;
	}

	private void execute(Frame f, Op op, int arg) {
		Code code = f.code;
		switch (op) {
			case LOAD_CONST -> f.push(code.constants()[arg]);
			case LOAD_LOCAL -> {
				Object value = f.locals[arg];
				if (value == null) {
					throw new ScriptError("cannot access local variable '" + code.locals()[arg]
							+ "' where it is not associated with a value");
				}
				f.push(value);
			}
			case STORE_LOCAL -> f.locals[arg] = f.pop();
			case LOAD_GLOBAL -> f.push(global(f.module, code.names()[arg]));
			case STORE_GLOBAL -> f.module.globals().put(code.names()[arg], f.pop());
			case POP -> f.pop();
			case DUP -> f.push(f.peek());
			case DUP2 -> {
				Object b = f.stack[f.sp - 1];
				Object a = f.stack[f.sp - 2];
				f.push(a);
				f.push(b);
			}
			case ROT2 -> {
				Object b = f.pop();
				Object a = f.pop();
				f.push(b);
				f.push(a);
			}
			case ROT3 -> {
				Object c = f.pop();
				Object b = f.pop();
				Object a = f.pop();
				f.push(c);
				f.push(a);
				f.push(b);
			}
			case UNARY -> f.push(Values.unary(UNARY_OPS[arg], f.pop()));
			case BINARY, INPLACE -> {
				Object b = f.pop();
				Object a = f.pop();
				f.push(Values.binary(BINARY_OPS[arg], a, b, op == Op.INPLACE));
			}
			case COMPARE -> {
				Object b = f.pop();
				Object a = f.pop();
				f.push(Values.compare(CMP_OPS[arg], a, b));
			}
			case JUMP -> f.pc = arg;
			case JUMP_IF_FALSE -> {
				if (!Values.truthy(f.pop())) {
					f.pc = arg;
				}
			}
			case JUMP_IF_TRUE -> {
				if (Values.truthy(f.pop())) {
					f.pc = arg;
				}
			}
			case JUMP_IF_FALSE_OR_POP -> {
				if (Values.truthy(f.peek())) {
					f.pop();
				} else {
					f.pc = arg;
				}
			}
			case JUMP_IF_TRUE_OR_POP -> {
				if (Values.truthy(f.peek())) {
					f.pc = arg;
				} else {
					f.pop();
				}
			}
			case BUILD_LIST -> f.push(new PyList(f.pop(arg)));
			case BUILD_TUPLE -> f.push(new PyTuple(f.pop(arg)));
			case BUILD_DICT -> {
				List<Object> pairs = f.pop(2 * arg);
				PyDict dict = new PyDict();
				for (int i = 0; i < pairs.size(); i += 2) {
					dict.put(pairs.get(i), pairs.get(i + 1));
				}
				f.push(dict);
			}
			case BUILD_SLICE -> {
				Object step = f.pop();
				Object upper = f.pop();
				Object lower = f.pop();
				f.push(new Values.Slice(lower, upper, step));
			}
			case GET_ITEM -> {
				Object index = f.pop();
				Object container = f.pop();
				f.push(Values.getItem(container, index));
			}
			case SET_ITEM -> {
				Object index = f.pop();
				Object container = f.pop();
				Values.setItem(container, index, f.pop());
			}
			case LOAD_ATTR -> f.push(attribute(f.pop(), code.names()[arg]));
			case STORE_ATTR -> {
				Object target = f.pop();
				Object value = f.pop();
				switch (target) {
					case PyInstance instance -> instance.attributes.put(code.names()[arg], value);
					case PyClass c -> c.attributes.put(code.names()[arg], value);
					default -> throw new ScriptError("'" + Values.typeName(target) + "' object has no attribute '"
							+ code.names()[arg] + "'");
				}
			}
			case UNPACK -> {
				List<Object> items = Values.elements(f.pop());
				if (items.size() < arg) {
					throw new ScriptError("not enough values to unpack (expected " + arg + ", got " + items.size() + ")");
				}
				if (items.size() > arg) {
					throw new ScriptError("too many values to unpack (expected " + arg + ", got " + items.size() + ")");
				}
				for (int i = items.size() - 1; i >= 0; i--) {
					f.push(items.get(i));
				}
			}
			case GET_ITER -> f.push(Values.iter(f.pop()));
			case FOR_ITER -> {
				Object next = ((Iter) f.peek()).next();
				if (next == null) {
					f.pop();
					f.pc = arg;
				} else {
					f.push(next);
				}
			}
			case MAKE_FUNCTION -> {
				Code function = (Code) code.constants()[arg];
				f.push(new PyFunction(function, List.copyOf(f.pop(function.defaultCount())), f.module));
			}
			case CALL -> {
				List<Object> args = f.pop(arg);
				call(f.pop(), args, Map.of());
			}
			case CALL_KW -> {
				Code.CallShape shape = (Code.CallShape) code.constants()[arg];
				List<Object> values = f.pop(shape.keywords().size());
				List<Object> args = f.pop(shape.positional());
				Object callee = f.pop();
				call(callee, spread(callee, args, shape.starred()), keywords(callee, shape.keywords(), values));
			}
			case RETURN -> {
				Object result = f.pop();
				frames.removeLast();
				if (frames.isEmpty()) {
					status = Status.FINISHED;
				} else if (f.imports) {
					imported(f.module); // the IMPORT that started it runs again and finds it
				} else if (f.init) {
					if (result != PyNone.NONE) {
						throw new ScriptError("__init__() should return None, not '" + Values.typeName(result) + "'");
					}
					// ponytail: self as the frame left it; a script that assigns to self in __init__ gets that back
					frames.getLast().push(f.locals[0]);
				} else {
					frames.getLast().push(result);
				}
			}
			case ASSERT_FAIL -> throw new ScriptError(arg == 1 ? "assertion failed: " + Values.str(f.pop())
					: "assertion failed");
			case FORMAT -> {
				Code.FormatField field = (Code.FormatField) code.constants()[arg];
				f.push(Format.field(f.pop(), field.conversion(), field.spec()));
			}
			case FORMAT_SPEC -> {
				String spec = (String) f.pop();
				f.push(Format.field(f.pop(), (char) arg, spec));
			}
			case BUILD_STRING -> {
				StringBuilder text = new StringBuilder();
				f.pop(arg).forEach(part -> text.append((String) part));
				f.push(text.toString());
			}
			case LIST_APPEND -> {
				Object item = f.pop();
				((PyList) f.stack[f.sp - 1 - arg]).items.add(item);
			}
			case IMPORT -> {
				PyModule module = importModule(code.names()[arg]);
				if (module == null) {
					f.pc--;
				} else {
					f.push(module);
				}
			}
			case SETUP_TRY -> f.pushBlock(arg);
			case POP_BLOCK -> f.blockCount--;
			case RAISE -> throw new ScriptError(switch (f.pop()) {
				case PyException e -> e;
				case PyNative n when PyException.isType(n.name()) -> PyException.of(n.name(), "");
				case PyInstance i when i.type.exceptionType() != null -> PyException.of(i);
				case PyClass c when c.exceptionType() != null -> {
					if (c.lookup("__init__") != null) { // its __init__ would need a frame of its own
						throw ScriptError.of("TypeError", "raise " + c.name + "(...): the class has an __init__");
					}
					yield PyException.of(newInstance(c, List.of()));
				}
				default -> throw ScriptError.of("TypeError", "exceptions must derive from BaseException");
			});
			case EXC_MATCH -> {
				Object type = f.pop();
				PyException e = (PyException) f.pop();
				boolean matches = false;
				for (Object t : type instanceof PyTuple tuple ? tuple.items() : List.of(type)) {
					if (!(t instanceof PyNative n && PyException.isType(n.name())
							|| t instanceof PyClass c && c.exceptionType() != null)) {
						throw ScriptError.of("TypeError", "catching classes that do not inherit from BaseException is not allowed");
					}
					matches |= e.isA(t);
				}
				f.push(matches);
			}
			case EXC_VALUE -> {
				if (f.peek() instanceof PyException e && e.instance() != null) {
					f.pop();
					f.push(e.instance());
				}
			}
			case BUILD_CLASS -> {
				Code.ClassShape shape = (Code.ClassShape) code.constants()[arg];
				List<Object> values = f.pop(shape.attributes().size());
				Object base = !shape.hasBase() ? null : switch (f.pop()) {
					case PyClass c -> c;
					case PyNative n when PyException.isType(n.name()) -> n.name();
					case Object other -> throw new ScriptError("a class can only derive from another class or an exception type, not '"
							+ Values.typeName(other) + "'");
				};
				PyClass c = new PyClass(shape.name(), base);
				for (int i = 0; i < values.size(); i++) {
					Object value = values.get(i);
					c.attributes.put(shape.attributes().get(i), value instanceof PyFunction fn
							? new PyFunction(fn.code(), fn.defaults(), fn.module(), c) : value);
				}
				f.push(c);
			}
			case SUPER -> {
				Object self = f.pop();
				f.push(new PySuper((PyClass) f.pop(), self));
			}
			case IMPORT_FROM -> {
				PyModule module = (PyModule) f.peek();
				String name = code.names()[arg];
				Object value = module.globals().get(name);
				if (value == null) {
					String submodule = module.name() + "." + name;
					if (!modules.containsKey(submodule) && !loader.isPackage(submodule) && load(submodule) == null) {
						throw new ScriptError("cannot import name '" + name + "' from '" + module.name() + "'");
					}
					value = importModule(submodule);
				}
				if (value == null) {
					f.pc--;
				} else {
					f.push(value);
				}
			}
		}
	}

	/**
	 * The module {@code name}, once it and every package above it are imported. Imports the first of them that is not
	 * imported yet: a package without a file of its own at once, a module by pushing a frame for its top level, in which
	 * case this gives null and the caller runs its instruction again once that frame returned.
	 */
	private PyModule importModule(String name) {
		int dot = -1;
		while (true) {
			dot = name.indexOf('.', dot + 1);
			String prefix = dot < 0 ? name : name.substring(0, dot);
			PyModule module = modules.get(prefix);
			if (module == null) {
				Code code = load(prefix);
				if (code == null && !loader.isPackage(prefix)) {
					throw new ScriptError("No module named '" + prefix + "'");
				}
				if (code != null && frames.size() >= maxDepth) {
					throw new ScriptError("maximum recursion depth exceeded");
				}
				module = new PyModule(prefix);
				module.setCode(code);
				modules.put(prefix, module);
				if (code != null) {
					frames.add(new Frame(code, new Object[code.locals().length], module, true));
					return null;
				}
				imported(module);
			} else if (!module.loaded()) {
				throw circular(prefix);
			}
			if (dot < 0) {
				return module;
			}
		}
	}

	/** The top level of module {@code name}, or null when the loader has none; its compile error is placed in its file. */
	private Code load(String name) {
		try {
			return loader.load(name);
		} catch (CompileError e) {
			throw new ScriptError(new PyException("SyntaxError", e.detail(), e.detail(), e.file(), e.line()));
		}
	}

	/** Marks {@code module} imported, and binds it in its package, as {@code import lib.trees} makes {@code lib.trees}. */
	private void imported(PyModule module) {
		module.setLoaded();
		int dot = module.name().lastIndexOf('.');
		if (dot >= 0) {
			modules.get(module.name().substring(0, dot)).globals().put(module.name().substring(dot + 1), module);
		}
	}

	/**
	 * The error for importing {@code name} while its top level runs, naming the modules of the cycle. The modules whose
	 * top level runs are the main module and those not imported yet, in the order they started.
	 */
	private ScriptError circular(String name) {
		List<String> running = modules.values().stream().filter(m -> !m.loaded()).map(PyModule::name).toList();
		List<String> cycle = new ArrayList<>(running.subList(running.indexOf(name), running.size()));
		cycle.add(name);
		return new ScriptError("circular import: " + String.join(" -> ", cycle));
	}

	private Object global(PyModule module, String name) {
		Object value = module.globals().get(name);
		if (value == null) {
			value = builtins.get(name);
		}
		if (value != null) {
			return value;
		}
		if (natives.function(name) != null || PyException.isType(name)) {
			return new PyNative(name);
		}
		throw new ScriptError("name '" + name + "' is not defined");
	}

	private Object attribute(Object value, String name) {
		switch (value) {
			case PyInstance instance -> {
				Object attribute = instance.attributes.get(name);
				if (attribute == null) {
					attribute = bound(instance, instance.type.lookup(name));
				}
				if (attribute == null) {
					throw new ScriptError("'" + instance.type.name + "' object has no attribute '" + name + "'");
				}
				return attribute;
			}
			case PyClass c -> {
				Object attribute = name.equals("__name__") ? c.name : c.lookup(name);
				if (attribute == null) {
					throw new ScriptError("type object '" + c.name + "' has no attribute '" + name + "'");
				}
				return attribute;
			}
			case PySuper s -> {
				Object attribute = s.type().base instanceof PyClass base ? bound(s.self(), base.lookup(name)) : null;
				if (attribute == null && name.equals("__init__")) { // of object, or of a builtin exception type
					attribute = new PyBoundMethod(s.self(), new PyNative(Builtins.INIT));
				}
				if (attribute == null) {
					throw new ScriptError("'super' object has no attribute '" + name + "'");
				}
				return attribute;
			}
			default -> {
			}
		}
		if (value instanceof HostValue host) {
			Object attribute = host.attribute(name);
			if (attribute != null) {
				return attribute;
			}
		}
		String type = Values.typeName(value);
		if (natives.method(type, name) != null) {
			return new PyMethod(value, type, name);
		}
		if (value instanceof PyModule module) {
			throw new ScriptError("module '" + module.name() + "' has no attribute '" + name + "'");
		}
		throw new ScriptError("'" + type + "' object has no attribute '" + name + "'");
	}

	/** A class attribute as an instance sees it: a method bound to {@code self}, else the attribute (null for none). */
	private static @Nullable Object bound(Object self, @Nullable Object attribute) {
		return attribute instanceof PyFunction function ? new PyBoundMethod(self, function) : attribute;
	}

	/** A new instance of {@code c}; an exception keeps its arguments in {@code args}, whatever its __init__ does. */
	private static PyInstance newInstance(PyClass c, List<Object> args) {
		PyInstance instance = new PyInstance(c);
		if (c.exceptionType() != null) {
			instance.attributes.put("args", new PyTuple(new ArrayList<>(args)));
		}
		return instance;
	}

	/** Calls class {@code c}: makes an instance and runs its {@code __init__} in a frame whose result is the instance. */
	private void construct(PyClass c, List<Object> args, Map<String, Object> kwargs) {
		PyInstance instance = newInstance(c, args);
		Object init = c.lookup("__init__");
		if (init == null) {
			if (!kwargs.isEmpty() || !args.isEmpty() && c.exceptionType() == null) {
				throw new ScriptError(c.name + "() takes no " + (kwargs.isEmpty() ? "" : "keyword ") + "arguments");
			}
			frames.getLast().push(instance);
			return;
		}
		if (!(init instanceof PyFunction function)) {
			throw new ScriptError("__init__ of " + c.name + " is not a method");
		}
		List<Object> withSelf = new ArrayList<>(args.size() + 1);
		withSelf.add(instance);
		withSelf.addAll(args);
		call(function, withSelf, kwargs);
		frames.getLast().init = true;
	}

	/** {@code args} with the items of each one at an index in {@code starred} in its place (docs/language/functions.md). */
	private static List<Object> spread(Object callee, List<Object> args, List<Integer> starred) {
		if (starred.isEmpty()) {
			return args;
		}
		List<Object> out = new ArrayList<>();
		for (int i = 0; i < args.size(); i++) {
			Object arg = args.get(i);
			if (!starred.contains(i)) {
				out.add(arg);
				continue;
			}
			Iter iter;
			try {
				iter = Values.iter(arg);
			} catch (ScriptError e) {
				throw new ScriptError(calleeName(callee) + "() argument after * must be an iterable, not "
						+ Values.typeName(arg));
			}
			for (Object next = iter.next(); next != null; next = iter.next()) {
				out.add(next);
			}
		}
		return out;
	}

	/**
	 * The keyword arguments named {@code names} with {@code values}, with the items of each value whose name is null (a
	 * {@code **value}) in its place (docs/language/functions.md).
	 */
	private static Map<String, Object> keywords(Object callee, List<String> names, List<Object> values) {
		Map<String, Object> kwargs = new LinkedHashMap<>();
		for (int i = 0; i < values.size(); i++) {
			Object value = values.get(i);
			if (names.get(i) != null) {
				keyword(callee, kwargs, names.get(i), value);
				continue;
			}
			if (!(value instanceof PyDict dict)) {
				throw new ScriptError(calleeName(callee) + "() argument after ** must be a mapping, not "
						+ Values.typeName(value));
			}
			for (Map.Entry<Object, Object> item : dict.items()) {
				if (!(item.getKey() instanceof String key)) {
					throw new ScriptError("keywords must be strings");
				}
				keyword(callee, kwargs, key, item.getValue());
			}
		}
		return kwargs;
	}

	private static void keyword(Object callee, Map<String, Object> kwargs, String name, Object value) {
		if (kwargs.putIfAbsent(name, value) != null) {
			throw new ScriptError(calleeName(callee) + "() got multiple values for keyword argument '" + name + "'");
		}
	}

	private static String calleeName(Object callee) {
		return switch (callee) {
			case PyFunction f -> f.code().name();
			case PyBoundMethod m -> calleeName(m.function());
			case PyNative n -> n.name();
			case PyMethod m -> m.name();
			case PyClass c -> c.name();
			default -> Values.typeName(callee);
		};
	}

	private void call(Object callee, List<Object> args, Map<String, Object> kwargs) {
		switch (callee) {
			case PyClass c -> construct(c, args, kwargs);
			case PyBoundMethod m -> {
				List<Object> withSelf = new ArrayList<>(args.size() + 1);
				withSelf.add(m.self());
				withSelf.addAll(args);
				call(m.function(), withSelf, kwargs);
			}
			case PyFunction function -> {
				if (frames.size() >= maxDepth) {
					throw new ScriptError("maximum recursion depth exceeded");
				}
				PyModule module = function.module() == null ? main : function.module();
				frames.add(new Frame(function.code(), bind(function, args, kwargs), module, false));
			}
			case PyNative n -> {
				NativeFunction function = natives.function(n.name());
				if (function == null && PyException.isType(n.name())) {
					frames.getLast().push(PyException.call(n.name(), args, kwargs));
					return;
				}
				if (function == null) {
					throw new ScriptError("name '" + n.name() + "' is not defined");
				}
				charge(natives.cost(n.name()));
				callNative(callee, function, n.name(), args, kwargs);
			}
			case PyMethod m -> {
				NativeFunction function = natives.method(m.type(), m.name());
				if (function == null) {
					throw new ScriptError("'" + m.type() + "' object has no attribute '" + m.name() + "'");
				}
				List<Object> withSelf = new ArrayList<>(args.size() + 1);
				withSelf.add(m.self());
				withSelf.addAll(args);
				callNative(callee, function, m.name(), withSelf, kwargs, args);
			}
			default -> throw new ScriptError("'" + Values.typeName(callee) + "' object is not callable");
		}
	}

	private void callNative(Object callee, NativeFunction function, String name, List<Object> args, Map<String, Object> kwargs) {
		callNative(callee, function, name, args, kwargs, args);
	}

	/** Calls {@code function} with {@code args}; {@code given} are the arguments the script gave {@code callee}. */
	private void callNative(Object callee, NativeFunction function, String name, List<Object> args, Map<String, Object> kwargs,
			List<Object> given) {
		Object result = function.call(new NativeCall(name, Collections.unmodifiableList(args),
				Collections.unmodifiableMap(kwargs)));
		if (result == NativeCall.SUSPEND) {
			status = Status.SUSPENDED;
			pending = new PendingCall(callee, List.copyOf(given), Collections.unmodifiableMap(new LinkedHashMap<>(kwargs)));
		} else if (result instanceof NativeCall.TailCall tail) {
			call(tail.callee(), tail.args(), Map.of());
		} else {
			frames.getLast().push(switch (result) {
				case null -> PyNone.NONE;
				case Integer i -> (long) i;
				case Float x -> (double) x;
				default -> result;
			});
		}
	}

	/** The locals of a new frame of {@code function}: the arguments bound to its parameters, by Python's rules. */
	private static Object[] bind(PyFunction function, List<Object> args, Map<String, Object> kwargs) {
		Code code = function.code();
		String name = code.name();
		List<String> localNames = Arrays.asList(code.locals());
		int params = code.paramCount();
		int named = params + code.kwOnlyCount();
		List<String> kwDefaults = Arrays.asList(code.kwDefaults());
		int defaults = code.defaultCount() - kwDefaults.size(); // those of the positional parameters
		Object[] locals = new Object[code.locals().length];
		for (int i = 0; i < Math.min(args.size(), params); i++) {
			locals[i] = args.get(i);
		}
		int slot = named; // the next hidden local: *args, **kwargs, then the method's class
		if (code.varArgs()) {
			locals[slot++] = new PyTuple(args.size() > params ? new ArrayList<>(args.subList(params, args.size())) : List.of());
		}
		PyDict rest = code.varKeywords() ? new PyDict() : null;
		if (rest != null) {
			locals[slot++] = rest;
		}
		List<String> posOnly = localNames.subList(0, code.posOnlyCount());
		for (Map.Entry<String, Object> kwarg : kwargs.entrySet()) {
			int index = localNames.subList(0, named).indexOf(kwarg.getKey());
			if (index < code.posOnlyCount() && rest != null) {
				rest.put(kwarg.getKey(), kwarg.getValue());
				continue;
			}
			if (index < code.posOnlyCount()) {
				List<String> passed = kwargs.keySet().stream().filter(posOnly::contains).toList();
				if (!passed.isEmpty()) {
					throw new ScriptError(name + "() got some positional-only arguments passed as keyword arguments: '"
							+ String.join(", ", passed) + "'");
				}
				throw new ScriptError(name + "() got an unexpected keyword argument '" + kwarg.getKey() + "'");
			}
			if (locals[index] != null) {
				throw new ScriptError(name + "() got multiple values for argument '" + kwarg.getKey() + "'");
			}
			locals[index] = kwarg.getValue();
		}
		if (!code.varArgs() && args.size() > params) {
			long kwOnlyGiven = Arrays.stream(locals, params, named).filter(java.util.Objects::nonNull).count();
			String takes = defaults == 0 ? params + " positional argument" + (params == 1 ? "" : "s")
					: "from " + (params - defaults) + " to " + params + " positional arguments";
			String given = kwOnlyGiven == 0 ? "" : " positional argument" + (args.size() == 1 ? "" : "s") + " (and "
					+ kwOnlyGiven + " keyword-only argument" + (kwOnlyGiven == 1 ? "" : "s") + ")";
			throw new ScriptError(name + "() takes " + takes + " but " + args.size() + given
					+ (args.size() == 1 && kwOnlyGiven == 0 ? " was" : " were") + " given");
		}
		List<String> missing = new ArrayList<>();
		for (int i = 0; i < params; i++) {
			if (locals[i] == null) {
				if (i >= params - defaults) {
					locals[i] = function.defaults().get(i - (params - defaults));
				} else {
					missing.add(code.locals()[i]);
				}
			}
		}
		missing(name, missing, "positional");
		for (int i = params; i < named; i++) {
			int d = kwDefaults.indexOf(code.locals()[i]);
			if (locals[i] == null && d >= 0) {
				locals[i] = function.defaults().get(defaults + d);
			} else if (locals[i] == null) {
				missing.add(code.locals()[i]);
			}
		}
		missing(name, missing, "keyword-only");
		if (function.owner() != null && slot < locals.length && code.locals()[slot].equals(Compiler.CLASS_SLOT)) {
			locals[slot] = function.owner(); // for the method's super()
		}
		return locals;
	}

	/** Throws Python's error for the {@code kind} arguments a call of {@code name} is {@code missing}, if any. */
	private static void missing(String name, List<String> missing, String kind) {
		if (missing.isEmpty()) {
			return;
		}
		List<String> quoted = missing.stream().map(m -> "'" + m + "'").toList();
		String list = quoted.size() == 1 ? quoted.getFirst()
				: quoted.size() == 2 ? quoted.get(0) + " and " + quoted.get(1)
						: String.join(", ", quoted.subList(0, quoted.size() - 1)) + ", and " + quoted.getLast();
		throw new ScriptError(name + "() missing " + quoted.size() + " required " + kind + " argument"
				+ (quoted.size() == 1 ? "" : "s") + ": " + list);
	}
}
