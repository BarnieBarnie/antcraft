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

/**
 * Runs compiled scripts a number of instructions at a time. A stack machine with explicit frames: a script calling a
 * function pushes a frame rather than recursing in Java, so the VM can stop after any instruction and continue later
 * from exactly there. Its whole state (frames with their code position, locals and operand stack; the globals; whether
 * it waits for a native) is plain data, so a running script can be saved.
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

	/** A running call of a function, or of the module's top level. */
	static final class Frame {
		final Code code;
		int pc;
		/** Null where a local is not set yet. */
		final Object[] locals;
		Object[] stack = new Object[8];
		int sp;

		Frame(Code code, Object[] locals) {
			this.code = code;
			this.locals = locals;
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
	private final Map<String, Object> globals = new LinkedHashMap<>();
	private final List<Frame> frames = new ArrayList<>();
	private Status status = Status.RUNNING;
	private ScriptError error;
	private long executed;

	public Vm(Code module, Natives natives) {
		this(module, natives, DEFAULT_MAX_DEPTH);
	}

	public Vm(Code module, Natives natives, int maxDepth) {
		this.natives = natives;
		this.maxDepth = maxDepth;
		frames.add(new Frame(module, new Object[module.locals().length]));
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

	/** The module's globals, by name; read-only. */
	public Map<String, Object> globals() {
		return Collections.unmodifiableMap(globals);
	}

	/** The source line about to run, or 0 once the script has finished. */
	public int line() {
		if (frames.isEmpty()) {
			return 0;
		}
		Frame frame = frames.getLast();
		return frame.code.lines()[Math.min(frame.pc, frame.code.size() - 1)];
	}

	/**
	 * Runs at most {@code budget} instructions and reports where the script is: still running (out of budget),
	 * suspended in a native call, finished or failed. A script that is not running is left as it is.
	 */
	public Status run(long budget) {
		for (long left = budget; left > 0 && status == Status.RUNNING; left--) {
			Frame frame = frames.getLast();
			Code code = frame.code;
			int pc = frame.pc++;
			executed++;
			try {
				execute(frame, code.ops()[pc], code.args()[pc]);
			} catch (ScriptError e) {
				error = e.file() == null ? e.at(code.file(), code.lines()[pc]) : e;
				status = Status.FAILED;
			}
		}
		return status;
	}

	/** Continues a suspended script: {@code value} becomes the result of the native call it waits on. */
	public void resume(Object value) {
		if (status != Status.SUSPENDED) {
			throw new IllegalStateException("the script is " + status + ", not suspended");
		}
		frames.getLast().push(value);
		status = Status.RUNNING;
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
			case LOAD_GLOBAL -> f.push(global(code.names()[arg]));
			case STORE_GLOBAL -> globals.put(code.names()[arg], f.pop());
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
				throw new ScriptError("'" + Values.typeName(target) + "' object has no attribute '" + code.names()[arg]
						+ "'");
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
				f.push(new PyFunction(function, List.copyOf(f.pop(function.defaultCount()))));
			}
			case CALL -> {
				List<Object> args = f.pop(arg);
				call(f.pop(), args, Map.of());
			}
			case CALL_KW -> {
				Code.CallShape shape = (Code.CallShape) code.constants()[arg];
				List<Object> values = f.pop(shape.keywords().size());
				Map<String, Object> kwargs = new LinkedHashMap<>();
				for (int i = 0; i < values.size(); i++) {
					kwargs.put(shape.keywords().get(i), values.get(i));
				}
				List<Object> args = f.pop(shape.positional());
				call(f.pop(), args, kwargs);
			}
			case RETURN -> {
				Object result = f.pop();
				frames.removeLast();
				if (frames.isEmpty()) {
					status = Status.FINISHED;
				} else {
					frames.getLast().push(result);
				}
			}
			case ASSERT_FAIL -> throw new ScriptError(arg == 1 ? "assertion failed: " + Values.str(f.pop())
					: "assertion failed");
		}
	}

	private Object global(String name) {
		Object value = globals.get(name);
		if (value != null) {
			return value;
		}
		if (natives.function(name) != null) {
			return new PyNative(name);
		}
		throw new ScriptError("name '" + name + "' is not defined");
	}

	private Object attribute(Object value, String name) {
		String type = Values.typeName(value);
		if (natives.method(type, name) != null) {
			return new PyMethod(value, type, name);
		}
		throw new ScriptError("'" + type + "' object has no attribute '" + name + "'");
	}

	private void call(Object callee, List<Object> args, Map<String, Object> kwargs) {
		switch (callee) {
			case PyFunction function -> {
				if (frames.size() >= maxDepth) {
					throw new ScriptError("maximum recursion depth exceeded");
				}
				frames.add(new Frame(function.code(), bind(function, args, kwargs)));
			}
			case PyNative n -> {
				NativeFunction function = natives.function(n.name());
				if (function == null) {
					throw new ScriptError("name '" + n.name() + "' is not defined");
				}
				callNative(function, n.name(), args, kwargs);
			}
			case PyMethod m -> {
				NativeFunction function = natives.method(m.type(), m.name());
				if (function == null) {
					throw new ScriptError("'" + m.type() + "' object has no attribute '" + m.name() + "'");
				}
				List<Object> withSelf = new ArrayList<>(args.size() + 1);
				withSelf.add(m.self());
				withSelf.addAll(args);
				callNative(function, m.name(), withSelf, kwargs);
			}
			default -> throw new ScriptError("'" + Values.typeName(callee) + "' object is not callable");
		}
	}

	private void callNative(NativeFunction function, String name, List<Object> args, Map<String, Object> kwargs) {
		Object result = function.call(new NativeCall(name, Collections.unmodifiableList(args),
				Collections.unmodifiableMap(kwargs)));
		if (result == NativeCall.SUSPEND) {
			status = Status.SUSPENDED;
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
		int params = code.paramCount();
		int defaults = function.defaults().size();
		Object[] locals = new Object[code.locals().length];
		if (args.size() > params) {
			String takes = defaults == 0 ? params + " positional argument" + (params == 1 ? "" : "s")
					: "from " + (params - defaults) + " to " + params + " positional arguments";
			throw new ScriptError(name + "() takes " + takes + " but " + args.size()
					+ (args.size() == 1 ? " was" : " were") + " given");
		}
		for (int i = 0; i < args.size(); i++) {
			locals[i] = args.get(i);
		}
		for (Map.Entry<String, Object> kwarg : kwargs.entrySet()) {
			int index = Arrays.asList(code.locals()).subList(0, params).indexOf(kwarg.getKey());
			if (index < 0) {
				throw new ScriptError(name + "() got an unexpected keyword argument '" + kwarg.getKey() + "'");
			}
			if (locals[index] != null) {
				throw new ScriptError(name + "() got multiple values for argument '" + kwarg.getKey() + "'");
			}
			locals[index] = kwarg.getValue();
		}
		List<String> missing = new ArrayList<>();
		for (int i = 0; i < params; i++) {
			if (locals[i] == null) {
				if (i >= params - defaults) {
					locals[i] = function.defaults().get(i - (params - defaults));
				} else {
					missing.add("'" + code.locals()[i] + "'");
				}
			}
		}
		if (!missing.isEmpty()) {
			String list = missing.size() == 1 ? missing.getFirst()
					: missing.size() == 2 ? missing.get(0) + " and " + missing.get(1)
							: String.join(", ", missing.subList(0, missing.size() - 1)) + ", and " + missing.getLast();
			throw new ScriptError(name + "() missing " + missing.size() + " required positional argument"
					+ (missing.size() == 1 ? "" : "s") + ": " + list);
		}
		return locals;
	}
}
