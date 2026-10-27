package io.github.barniebarnie.antcraft.script.lang;

/**
 * A value type the host adds to the language, such as the bot API's {@code Pos}. It names its type, has data
 * attributes and may take part in binary operators; its methods are natives registered under its type name
 * ({@link Natives#registerMethod}). It prints as its {@code toString}, compares with {@code ==} by {@code equals}, and is
 * a dict key by {@code equals} and {@code hashCode}, so a host value used as a key must be immutable.
 */
public interface HostValue {
	/** The type name scripts see, in error messages and for method lookup. */
	String typeName();

	/** The data attribute {@code name}, or null when it has none. */
	default Object attribute(String name) {
		return null;
	}

	/**
	 * {@code this op other}, or {@code other op this} when {@code reflected}; null when the operator does not apply,
	 * which fails the script with Python's message.
	 */
	default Object binary(Ast.BinaryOp op, Object other, boolean reflected) {
		return null;
	}
}
