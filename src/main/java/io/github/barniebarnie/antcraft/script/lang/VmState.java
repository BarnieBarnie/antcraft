package io.github.barniebarnie.antcraft.script.lang;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Saves a running {@link Vm} as bytes and loads it again (docs/scripting.md, Save and resume): its frames with their
 * code position, locals and operand stack, every module with its globals, the builtin values (such as {@code memory}),
 * how many instructions it ran, and the native call it is suspended in, which a loaded VM makes again ({@link Vm#recall}).
 *
 * <p>Values are saved as a graph, so a list two variables share, or one that contains itself, comes back as one list.
 * Code is saved by reference, not as code: by the module it was compiled from and its place among that module's nested
 * constants, with a fingerprint of each module's compiled code. A load compiles the modules again and refuses to go on
 * when one of them changed ({@link Unloadable}), so the script restarts from the top instead. Values the language does
 * not know (the host's, such as a {@code Pos}) are saved as plain values by a {@link HostCodec}; a value it cannot save
 * keeps the VM from being saved at all ({@link NotSaveable}).
 */
public final class VmState {
	private static final int MAGIC = 0x41435653; // ACVS
	private static final int VERSION = 1;

	// Values
	private static final byte NULL = 0;
	private static final byte NONE = 1;
	private static final byte TRUE = 2;
	private static final byte FALSE = 3;
	private static final byte INT = 4;
	private static final byte FLOAT = 5;
	private static final byte STR = 6;
	private static final byte REF = 7;

	// Objects, by kind
	private static final byte LIST = 1;
	private static final byte TUPLE = 2;
	private static final byte DICT = 3;
	private static final byte MODULE = 4;
	private static final byte FUNCTION = 5;
	private static final byte CODE = 6;
	private static final byte NATIVE = 7;
	private static final byte METHOD = 8;
	private static final byte RANGE = 9;
	private static final byte VIEW = 10;
	private static final byte SLICE = 11;
	private static final byte HOST = 12;
	private static final byte ITER_LIST = 20;
	private static final byte ITER_TUPLE = 21;
	private static final byte ITER_STRING = 22;
	private static final byte ITER_DICT = 23;
	private static final byte ITER_ITEMS = 24;
	private static final byte ITER_ENUMERATE = 25;
	private static final byte ITER_ZIP = 26;
	private static final byte ITER_RANGE = 27;

	private VmState() {
	}

	/** Saves the host's own value types as plain values (numbers, strings, tuples and the like) and makes them again. */
	public interface HostCodec {
		/** A codec that saves no host values. */
		HostCodec NONE = new HostCodec() {
			@Override
			public @Nullable Object save(Object value) {
				return null;
			}

			@Override
			public Object load(Object saved) {
				throw new IllegalArgumentException("no host values");
			}
		};

		/** {@code value} as a value of the language, or null when it cannot be saved. */
		@Nullable Object save(Object value);

		/** The host value {@link #save} made {@code saved} of. */
		Object load(Object saved);
	}

	/** The VM holds a value that cannot be saved. */
	public static final class NotSaveable extends Exception {
		NotSaveable(String message) {
			super(message);
		}
	}

	/** A saved VM that cannot be loaded: its code changed since the save, or the save is broken. */
	public static final class Unloadable extends Exception {
		Unloadable(String message) {
			super(message);
		}
	}

	/** A reference to a saved object, by its index in the save. */
	private record Ref(int id) {
	}

	/** A saved object as read: its kind and its fields, values with {@link Ref}s to other objects. */
	private record Raw(byte kind, Object[] fields) {
	}

	/** Where a code object was compiled: the module and the indexes of the constants that lead to it. */
	private record CodeRef(String module, String path) {
	}

	// Saving

	/** Saves {@code vm}, which must be running or suspended in a native call. */
	public static byte[] save(Vm vm, HostCodec host) throws NotSaveable {
		if (vm.status != Vm.Status.RUNNING && vm.status != Vm.Status.SUSPENDED) {
			throw new IllegalStateException("the script is " + vm.status());
		}
		Writer writer = new Writer(vm, host);
		try {
			return writer.write();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static final class Writer {
		private final Vm vm;
		private final HostCodec host;
		private final Map<Code, CodeRef> codes = new IdentityHashMap<>();
		private final Map<String, Long> fingerprints = new LinkedHashMap<>();
		private final Map<Object, Integer> ids = new IdentityHashMap<>();
		private final List<Object> objects = new ArrayList<>();

		Writer(Vm vm, HostCodec host) {
			this.vm = vm;
			this.host = host;
			for (PyModule module : vm.modules.values()) {
				if (module.code() != null) {
					index(module.name(), module.code());
				}
			}
			index(Natives.BUILTINS_FILE, Builtins.PRELUDE_CODE);
		}

		private void index(String module, Code code) {
			fingerprints.put(module, fingerprint(code));
			index(module, code, "");
		}

		private void index(String module, Code code, String path) {
			codes.putIfAbsent(code, new CodeRef(module, path));
			Object[] constants = code.constants();
			for (int i = 0; i < constants.length; i++) {
				if (constants[i] instanceof Code nested) {
					index(module, nested, path.isEmpty() ? String.valueOf(i) : path + "." + i);
				}
			}
		}

		byte[] write() throws IOException, NotSaveable {
			ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
			DataOutputStream body = new DataOutputStream(bodyBytes);
			List<Object> fields = new ArrayList<>();
			fields.add(vm.main.name());
			fields.add(vm.status.name());
			fields.add(vm.executed);
			fields.add(vm.debt);
			fields.add((long) fingerprints.size());
			fingerprints.forEach((name, fingerprint) -> {
				fields.add(name);
				fields.add(fingerprint);
			});
			fields.add((long) vm.modules.size());
			fields.addAll(vm.modules.values());
			fields.add((long) vm.builtins.size());
			vm.builtins.forEach((name, value) -> {
				fields.add(name);
				fields.add(value);
			});
			fields.add((long) vm.frames.size());
			for (Vm.Frame frame : vm.frames) {
				CodeRef code = code(frame.code);
				fields.add(code.module());
				fields.add(code.path());
				fields.add(frame.module);
				fields.add(frame.imports);
				fields.add((long) frame.pc);
				fields.add((long) frame.locals.length);
				fields.addAll(Arrays.asList(frame.locals));
				fields.add((long) frame.sp);
				fields.addAll(Arrays.asList(frame.stack).subList(0, frame.sp));
			}
			Vm.PendingCall pending = vm.status == Vm.Status.SUSPENDED ? vm.pending : null;
			fields.add(pending != null);
			if (pending != null) {
				fields.add(pending.callee());
				fields.add((long) pending.args().size());
				fields.addAll(pending.args());
				fields.add((long) pending.kwargs().size());
				pending.kwargs().forEach((name, value) -> {
					fields.add(name);
					fields.add(value);
				});
			}
			writeFields(body, fields);

			ByteArrayOutputStream objectBytes = new ByteArrayOutputStream();
			DataOutputStream out = new DataOutputStream(objectBytes);
			for (int i = 0; i < objects.size(); i++) { // writing an object can add more
				writeObject(out, objects.get(i));
			}

			ByteArrayOutputStream all = new ByteArrayOutputStream();
			DataOutputStream data = new DataOutputStream(all);
			data.writeInt(MAGIC);
			data.writeInt(VERSION);
			data.writeInt(objects.size());
			objectBytes.writeTo(data);
			bodyBytes.writeTo(data);
			data.flush();
			return all.toByteArray();
		}

		private CodeRef code(Code code) throws NotSaveable {
			CodeRef ref = codes.get(code);
			if (ref == null) {
				throw new NotSaveable("the code of " + code.name() + " in " + code.file() + " is not of a module of the script");
			}
			return ref;
		}

		private void writeFields(DataOutputStream out, List<Object> fields) throws IOException, NotSaveable {
			out.writeInt(fields.size());
			for (Object field : fields) {
				writeValue(out, field);
			}
		}

		private void writeValue(DataOutputStream out, @Nullable Object value) throws IOException, NotSaveable {
			switch (value) {
				case null -> out.writeByte(NULL);
				case PyNone n -> out.writeByte(NONE);
				case Boolean b -> out.writeByte(b ? TRUE : FALSE);
				case Long l -> {
					out.writeByte(INT);
					out.writeLong(l);
				}
				case Double d -> {
					out.writeByte(FLOAT);
					out.writeDouble(d);
				}
				case String s -> {
					out.writeByte(STR);
					writeString(out, s);
				}
				default -> {
					out.writeByte(REF);
					out.writeInt(id(value));
				}
			}
		}

		private int id(Object value) throws NotSaveable {
			Integer id = ids.get(value);
			if (id == null) {
				if (!(value instanceof PyList || value instanceof PyTuple || value instanceof PyDict || value instanceof PyModule
						|| value instanceof PyFunction || value instanceof Code || value instanceof PyNative
						|| value instanceof PyMethod || value instanceof PyRange || value instanceof PyDictView
						|| value instanceof Values.Slice || value instanceof Iter)) {
					Object saved = value instanceof HostValue ? host.save(value) : null;
					if (saved == null) {
						throw new NotSaveable("a '" + Values.typeName(value) + "' value cannot be saved");
					}
				}
				id = objects.size();
				ids.put(value, id);
				objects.add(value);
			}
			return id;
		}

		private void writeObject(DataOutputStream out, Object value) throws IOException, NotSaveable {
			List<Object> fields = new ArrayList<>();
			byte kind = switch (value) {
				case PyList list -> {
					fields.addAll(list.items);
					yield LIST;
				}
				case PyTuple tuple -> {
					fields.addAll(tuple.items());
					yield TUPLE;
				}
				case PyDict dict -> {
					fields.add(dict.checked());
					dict.items().forEach(entry -> {
						fields.add(entry.getKey());
						fields.add(entry.getValue());
					});
					yield DICT;
				}
				case PyModule module -> {
					fields.add(module.name());
					fields.add(module.loaded());
					fields.add(module.code() != null);
					module.globals().forEach((name, global) -> {
						fields.add(name);
						fields.add(global);
					});
					yield MODULE;
				}
				case PyFunction function -> {
					CodeRef code = code(function.code());
					fields.add(code.module());
					fields.add(code.path());
					fields.add(function.module());
					fields.addAll(function.defaults());
					yield FUNCTION;
				}
				case Code code -> {
					CodeRef ref = code(code);
					fields.add(ref.module());
					fields.add(ref.path());
					yield CODE;
				}
				case PyNative n -> {
					fields.add(n.name());
					yield NATIVE;
				}
				case PyMethod m -> {
					fields.add(m.self());
					fields.add(m.type());
					fields.add(m.name());
					yield METHOD;
				}
				case PyRange r -> {
					fields.addAll(List.of(r.start(), r.stop(), r.step()));
					yield RANGE;
				}
				case PyDictView v -> {
					fields.add(v.dict());
					fields.add(v.kind().name());
					yield VIEW;
				}
				case Values.Slice s -> {
					fields.addAll(Arrays.asList(s.lower(), s.upper(), s.step()));
					yield SLICE;
				}
				case Iter.OfList i -> {
					fields.addAll(List.of(i.list, (long) i.index));
					yield ITER_LIST;
				}
				case Iter.OfTuple i -> {
					fields.addAll(List.of(i.tuple, (long) i.index));
					yield ITER_TUPLE;
				}
				case Iter.OfString i -> {
					fields.addAll(List.of(i.string, (long) i.index));
					yield ITER_STRING;
				}
				case Iter.OfDict i -> {
					fields.addAll(List.of(i.dict, i.kind.name(), (long) i.index));
					fields.addAll(i.keys);
					yield ITER_DICT;
				}
				case Iter.OfItems i -> {
					fields.addAll(List.of(i.typeName, (long) i.index));
					fields.addAll(i.items);
					yield ITER_ITEMS;
				}
				case Iter.OfEnumerate i -> {
					fields.addAll(List.of(i.items, i.count));
					yield ITER_ENUMERATE;
				}
				case Iter.OfZip i -> {
					fields.addAll(i.iters);
					yield ITER_ZIP;
				}
				case Iter.OfRange i -> {
					fields.addAll(List.of(i.range, i.index));
					yield ITER_RANGE;
				}
				default -> {
					fields.add(host.save(value));
					yield HOST;
				}
			};
			out.writeByte(kind);
			writeFields(out, fields);
		}
	}

	private static void writeString(DataOutputStream out, String s) throws IOException {
		byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
		out.writeInt(bytes.length);
		out.write(bytes);
	}

	/**
	 * A fingerprint of compiled code, nested code included: what a load compares to tell whether a module changed. Code
	 * compiled from the same source by the same compiler has the same one.
	 */
	static long fingerprint(Code code) {
		StringBuilder text = new StringBuilder();
		describe(code, text);
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
			long fingerprint = 0;
			for (int i = 0; i < 8; i++) {
				fingerprint = fingerprint << 8 | (hash[i] & 0xff);
			}
			return fingerprint;
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void describe(Code code, StringBuilder text) {
		text.append("code(").append(code.name()).append('|').append(code.file()).append('|')
				.append(Arrays.toString(code.ops())).append(Arrays.toString(code.args())).append(Arrays.toString(code.lines()))
				.append(Arrays.toString(code.names())).append(Arrays.toString(code.locals())).append(code.paramCount())
				.append(',').append(code.defaultCount()).append("|[");
		for (Object constant : code.constants()) {
			if (constant instanceof Code nested) {
				describe(nested, text);
			} else {
				text.append(constant == null ? "null" : constant.getClass().getSimpleName()).append(':')
						.append(constant instanceof String s ? Values.repr(s) : String.valueOf(constant));
			}
			text.append(',');
		}
		text.append("])");
	}

	// Loading

	/**
	 * Loads a VM {@link #save saved} by a script whose main module is now compiled to {@code main}, with its other modules
	 * from {@code loader}. Dicts that checked what was stored into them, such as {@code memory}, come from
	 * {@code checkedDict}. A VM that was suspended in a native call is loaded suspended; the host makes the call again
	 * with {@link Vm#recall}.
	 *
	 * @throws Unloadable when a module of the script changed or is gone, or the save is broken
	 */
	public static Vm load(byte[] saved, Code main, Natives natives, ModuleLoader loader, int maxDepth, HostCodec host,
			Supplier<PyDict> checkedDict) throws Unloadable {
		List<Raw> raws = new ArrayList<>();
		Object[] body = read(saved, raws);
		try {
			return new Reader(raws, body, main, natives, loader, maxDepth, host, checkedDict).read();
		} catch (RuntimeException e) { // a save that does not fit the code, such as one written by hand
			throw new Unloadable("the save cannot be read: " + e);
		}
	}

	/**
	 * The builtin {@code name} of a VM {@link #save saved}, such as {@code memory}, read without the script's code, so
	 * also when the script changed since; null if the VM had none. It must hold plain data only: no functions or code.
	 *
	 * @throws Unloadable when the save is broken or the builtin holds code
	 */
	public static @Nullable Object loadBuiltin(byte[] saved, String name, HostCodec host, Supplier<PyDict> checkedDict) throws Unloadable {
		List<Raw> raws = new ArrayList<>();
		Object[] body = read(saved, raws);
		try {
			return new Reader(raws, body, null, null, null, 0, host, checkedDict).builtin(name);
		} catch (RuntimeException e) {
			throw new Unloadable("the save cannot be read: " + e);
		}
	}

	/** The body of {@code saved}, with its objects added to {@code raws}. */
	private static Object[] read(byte[] saved, List<Raw> raws) throws Unloadable {
		try {
			DataInputStream in = new DataInputStream(new ByteArrayInputStream(saved));
			if (in.readInt() != MAGIC) {
				throw new IOException("not a saved script");
			}
			int version = in.readInt();
			if (version != VERSION) {
				throw new IOException("saved by version " + version + ", this is " + VERSION);
			}
			int count = in.readInt();
			for (int i = 0; i < count; i++) {
				byte kind = in.readByte();
				raws.add(new Raw(kind, readFields(in)));
			}
			return readFields(in);
		} catch (IOException | RuntimeException e) {
			throw new Unloadable("the save cannot be read: " + e.getMessage());
		}
	}

	private static Object[] readFields(DataInputStream in) throws IOException {
		int count = in.readInt();
		if (count < 0 || count > in.available()) {
			throw new IOException("bad length " + count);
		}
		Object[] fields = new Object[count];
		for (int i = 0; i < count; i++) {
			fields[i] = readValue(in);
		}
		return fields;
	}

	private static @Nullable Object readValue(DataInputStream in) throws IOException {
		byte tag = in.readByte();
		return switch (tag) {
			case NULL -> null;
			case NONE -> PyNone.NONE;
			case TRUE -> true;
			case FALSE -> false;
			case INT -> in.readLong();
			case FLOAT -> in.readDouble();
			case STR -> {
				int length = in.readInt();
				if (length < 0 || length > in.available()) {
					throw new IOException("bad length " + length);
				}
				yield new String(in.readNBytes(length), StandardCharsets.UTF_8);
			}
			case REF -> new Ref(in.readInt());
			default -> throw new IOException("unknown value tag " + tag);
		};
	}

	private static final class Reader {
		private final List<Raw> raws;
		private final Object[] body;
		private final Code mainCode;
		private final Natives natives;
		private final ModuleLoader loader;
		private final int maxDepth;
		private final HostCodec host;
		private final Supplier<PyDict> checkedDict;
		private final Object[] built;
		private final Map<String, Code> modules = new HashMap<>();
		private int at;

		Reader(List<Raw> raws, Object[] body, Code mainCode, Natives natives, ModuleLoader loader, int maxDepth,
				HostCodec host, Supplier<PyDict> checkedDict) {
			this.raws = raws;
			this.body = body;
			this.mainCode = mainCode;
			this.natives = natives;
			this.loader = loader;
			this.maxDepth = maxDepth;
			this.host = host;
			this.checkedDict = checkedDict;
			this.built = new Object[raws.size()];
		}

		private Object next() {
			return body[at++];
		}

		private int nextInt() {
			return Math.toIntExact((Long) next());
		}

		/** Only the builtin {@code name}, skipping the rest; its lists and dicts are made as they are reached. */
		@Nullable Object builtin(String name) {
			at = 4; // main module, status, executed, debt
			int fingerprints = nextInt();
			at += 2 * fingerprints;
			int moduleCount = nextInt();
			at += moduleCount;
			for (int i = nextInt(); i > 0; i--) {
				if (next().equals(name)) {
					return value(next());
				}
				at++;
			}
			return null;
		}

		Vm read() throws Unloadable {
			String mainName = (String) next();
			Vm.Status status = Vm.Status.valueOf((String) next());
			long executed = (Long) next();
			long debt = (Long) next();
			modules.put(mainName, mainCode);
			for (int i = nextInt(); i > 0; i--) {
				String name = (String) next();
				long fingerprint = (Long) next();
				Code code = module(name);
				if (fingerprint(code) != fingerprint) {
					throw new Unloadable(code.file() + " changed since the save");
				}
			}
			// The mutable objects first, empty, so that the values that refer to them can be made.
			for (int id = 0; id < raws.size(); id++) {
				Raw raw = raws.get(id);
				built[id] = switch (raw.kind()) {
					case LIST -> new PyList();
					case DICT -> (Boolean) raw.fields()[0] ? checkedDict.get() : new PyDict();
					case MODULE -> {
						PyModule module = new PyModule((String) raw.fields()[0]);
						if ((Boolean) raw.fields()[1]) {
							module.setLoaded();
						}
						if ((Boolean) raw.fields()[2]) {
							module.setCode(module(module.name()));
						}
						yield module;
					}
					default -> null;
				};
			}
			for (int id = 0; id < raws.size(); id++) {
				Raw raw = raws.get(id);
				Object[] f = raw.fields();
				switch (built[id]) {
					case PyList list -> {
						for (Object item : f) {
							list.items.add(value(item));
						}
					}
					case PyDict dict -> {
						for (int i = 1; i < f.length; i += 2) {
							dict.putUnchecked(value(f[i]), value(f[i + 1]));
						}
					}
					case PyModule module -> {
						for (int i = 3; i < f.length; i += 2) {
							module.globals().put((String) f[i], value(f[i + 1]));
						}
					}
					case null, default -> value(new Ref(id));
				}
			}

			int moduleCount = nextInt();
			List<PyModule> runModules = new ArrayList<>();
			for (int i = 0; i < moduleCount; i++) {
				runModules.add((PyModule) value(next()));
			}
			Vm vm = new Vm(runModules.getFirst(), natives, loader, maxDepth);
			runModules.forEach(module -> vm.modules.put(module.name(), module));
			for (int i = nextInt(); i > 0; i--) {
				String name = (String) next();
				vm.builtins.put(name, value(next()));
			}
			for (int i = nextInt(); i > 0; i--) {
				Code code = code((String) next(), (String) next());
				PyModule module = (PyModule) value(next());
				boolean imports = (Boolean) next();
				int pc = nextInt();
				Object[] locals = new Object[nextInt()];
				for (int j = 0; j < locals.length; j++) {
					locals[j] = value(next());
				}
				Vm.Frame frame = new Vm.Frame(code, locals, module, imports);
				frame.pc = pc;
				int sp = nextInt();
				frame.stack = new Object[Math.max(8, sp)];
				for (int j = 0; j < sp; j++) {
					frame.stack[j] = value(next());
				}
				frame.sp = sp;
				vm.frames.add(frame);
			}
			if ((Boolean) next()) {
				Object callee = value(next());
				List<Object> args = new ArrayList<>();
				for (int i = nextInt(); i > 0; i--) {
					args.add(value(next()));
				}
				Map<String, Object> kwargs = new LinkedHashMap<>();
				for (int i = nextInt(); i > 0; i--) {
					String name = (String) next();
					kwargs.put(name, value(next()));
				}
				vm.pending = new Vm.PendingCall(callee, List.copyOf(args), Collections.unmodifiableMap(kwargs));
			} else if (status == Vm.Status.SUSPENDED) {
				throw new IllegalArgumentException("suspended without a native call");
			}
			if (vm.frames.isEmpty()) {
				throw new IllegalArgumentException("no frames");
			}
			vm.status = status;
			vm.executed = executed;
			vm.debt = debt;
			return vm;
		}

		/** The compiled top level of module {@code name} as it is now. */
		private Code module(String name) throws Unloadable {
			Code code = modules.get(name);
			if (code == null) {
				if (name.equals(Natives.BUILTINS_FILE)) {
					code = Builtins.PRELUDE_CODE;
				} else {
					try {
						code = loader.load(name);
					} catch (CompileError e) {
						throw new Unloadable(e.getMessage());
					}
					if (code == null) {
						throw new Unloadable("module " + name + " is gone");
					}
				}
				modules.put(name, code);
			}
			return code;
		}

		private Code code(String module, String path) throws Unloadable {
			Code code = module(module);
			if (!path.isEmpty()) {
				for (String index : path.split("\\.")) {
					code = (Code) code.constants()[Integer.parseInt(index)];
				}
			}
			return code;
		}

		private @Nullable Object value(@Nullable Object field) {
			if (!(field instanceof Ref(int id))) {
				return field;
			}
			if (built[id] != null) {
				return built[id];
			}
			Object[] f = raws.get(id).fields();
			// Lists and dicts are made up front by read, and only here by builtin: made first, so a loop through them ends.
			switch (raws.get(id).kind()) {
				case LIST -> {
					PyList list = new PyList();
					built[id] = list;
					for (Object item : f) {
						list.items.add(value(item));
					}
					return list;
				}
				case DICT -> {
					PyDict dict = (Boolean) f[0] ? checkedDict.get() : new PyDict();
					built[id] = dict;
					for (int i = 1; i < f.length; i += 2) {
						dict.putUnchecked(value(f[i]), value(f[i + 1]));
					}
					return dict;
				}
				default -> {
				}
			}
			Object value;
			try {
				value = switch (raws.get(id).kind()) {
					case TUPLE -> new PyTuple(values(f, 0));
					case FUNCTION -> new PyFunction(code((String) f[0], (String) f[1]), List.copyOf(values(f, 3)),
							(PyModule) value(f[2]));
					case CODE -> code((String) f[0], (String) f[1]);
					case NATIVE -> new PyNative((String) f[0]);
					case METHOD -> new PyMethod(value(f[0]), (String) f[1], (String) f[2]);
					case RANGE -> new PyRange((Long) f[0], (Long) f[1], (Long) f[2]);
					case VIEW -> new PyDictView((PyDict) value(f[0]), PyDictView.Kind.valueOf((String) f[1]));
					case SLICE -> new Values.Slice(value(f[0]), value(f[1]), value(f[2]));
					case HOST -> host.load(value(f[0]));
					case ITER_LIST -> {
						Iter.OfList iter = new Iter.OfList((PyList) value(f[0]));
						iter.index = Math.toIntExact((Long) f[1]);
						yield iter;
					}
					case ITER_TUPLE -> {
						Iter.OfTuple iter = new Iter.OfTuple((PyTuple) value(f[0]));
						iter.index = Math.toIntExact((Long) f[1]);
						yield iter;
					}
					case ITER_STRING -> {
						Iter.OfString iter = new Iter.OfString((String) f[0]);
						iter.index = Math.toIntExact((Long) f[1]);
						yield iter;
					}
					case ITER_DICT -> {
						Iter.OfDict iter = new Iter.OfDict((PyDict) value(f[0]), PyDictView.Kind.valueOf((String) f[1]),
								values(f, 3));
						iter.index = Math.toIntExact((Long) f[2]);
						yield iter;
					}
					case ITER_ITEMS -> {
						Iter.OfItems iter = new Iter.OfItems((String) f[0], values(f, 2));
						iter.index = Math.toIntExact((Long) f[1]);
						yield iter;
					}
					case ITER_ENUMERATE -> new Iter.OfEnumerate((Iter) value(f[0]), (Long) f[1]);
					case ITER_ZIP -> {
						List<Iter> iters = new ArrayList<>();
						for (Object iter : values(f, 0)) {
							iters.add((Iter) iter);
						}
						yield new Iter.OfZip(iters);
					}
					case ITER_RANGE -> {
						Iter.OfRange iter = new Iter.OfRange((PyRange) value(f[0]));
						iter.index = (Long) f[1];
						yield iter;
					}
					default -> throw new IllegalArgumentException("unknown object kind " + raws.get(id).kind());
				};
			} catch (Unloadable e) {
				throw new IllegalArgumentException(e.getMessage());
			}
			built[id] = value;
			return value;
		}

		private List<Object> values(Object[] fields, int from) {
			List<Object> values = new ArrayList<>(fields.length - from);
			for (int i = from; i < fields.length; i++) {
				values.add(value(fields[i]));
			}
			return values;
		}
	}
}
