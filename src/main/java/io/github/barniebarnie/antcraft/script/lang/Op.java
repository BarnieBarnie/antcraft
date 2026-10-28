package io.github.barniebarnie.antcraft.script.lang;

/**
 * The VM's instructions. Each has one int argument (unused by some). "Pops" and "pushes" are on the operand stack of
 * the running frame; TOS is its top, TOS1 the value under it.
 */
public enum Op {
	/** Pushes {@code constants[arg]}. */
	LOAD_CONST,
	/** Pushes local {@code arg}; an error if it was not set yet. */
	LOAD_LOCAL,
	/** Pops into local {@code arg}. */
	STORE_LOCAL,
	/** Pushes the global {@code names[arg]}, else the native of that name; an error if there is neither. */
	LOAD_GLOBAL,
	/** Pops into the global {@code names[arg]}. */
	STORE_GLOBAL,
	POP,
	/** Pushes TOS again. */
	DUP,
	/** Pushes TOS1 and TOS again: {@code a b -> a b a b}. */
	DUP2,
	/** Swaps TOS and TOS1. */
	ROT2,
	/** Moves TOS under the two below it: {@code a b c -> c a b}. */
	ROT3,
	/** Applies {@code Ast.UnaryOp.values()[arg]} to TOS. */
	UNARY,
	/** Pops b, a and pushes {@code a op b} for {@code Ast.BinaryOp.values()[arg]}. */
	BINARY,
	/** As {@link #BINARY}, for {@code a op= b}. */
	INPLACE,
	/** Pops b, a and pushes {@code a op b} for {@code Ast.CmpOp.values()[arg]}. */
	COMPARE,
	/** Jumps to instruction {@code arg}. */
	JUMP,
	/** Pops TOS and jumps when it is false. */
	JUMP_IF_FALSE,
	/** Pops TOS and jumps when it is true. */
	JUMP_IF_TRUE,
	/** Jumps, keeping TOS, when it is false; pops it otherwise. */
	JUMP_IF_FALSE_OR_POP,
	/** Jumps, keeping TOS, when it is true; pops it otherwise. */
	JUMP_IF_TRUE_OR_POP,
	/** Pops {@code arg} values and pushes a list of them. */
	BUILD_LIST,
	/** Pops {@code arg} values and pushes a tuple of them. */
	BUILD_TUPLE,
	/** Pops {@code arg} key and value pairs and pushes a dict of them. */
	BUILD_DICT,
	/** Pops step, upper and lower and pushes a {@link Values.Slice}. */
	BUILD_SLICE,
	/** Pops index and container and pushes {@code container[index]}. */
	GET_ITEM,
	/** Pops index, container and value and sets {@code container[index] = value}. */
	SET_ITEM,
	/** Replaces TOS with its attribute {@code names[arg]}. */
	LOAD_ATTR,
	/** Pops the object and the value and sets the attribute {@code names[arg]}. */
	STORE_ATTR,
	/** Pops an iterable of exactly {@code arg} items and pushes them, the first on top. */
	UNPACK,
	/** Replaces TOS with an iterator over it. */
	GET_ITER,
	/** Pushes the next item of the iterator at TOS, or pops the iterator and jumps to {@code arg} when it is done. */
	FOR_ITER,
	/** Pops the defaults of the {@link Code} in {@code constants[arg]} and pushes a function of it. */
	MAKE_FUNCTION,
	/** Calls with {@code arg} positional arguments: pops them and the function, pushes the result. */
	CALL,
	/**
	 * Calls with keyword arguments: {@code constants[arg]} is a {@link Code.CallShape} giving how many positional
	 * arguments and the keyword names, whose values follow them on the stack.
	 */
	CALL_KW,
	/** Pops the result and returns from the frame. */
	RETURN,
	/** Fails the script with "assertion failed", followed by the popped message when {@code arg} is 1. */
	ASSERT_FAIL,
	/**
	 * Replaces TOS with its text in an f-string: converted and formatted as the {@link Code.FormatField} in
	 * {@code constants[arg]} says.
	 */
	FORMAT,
	/**
	 * Pops a format spec, then replaces TOS with its text in an f-string: converted by {@code arg} (a conversion
	 * character, or 0 for none) and formatted with that spec.
	 */
	FORMAT_SPEC,
	/** Pops {@code arg} strings and pushes them joined. */
	BUILD_STRING,
	/** Pops TOS and appends it to the list {@code arg} values below it (under a comprehension's iterators). */
	LIST_APPEND
}
