package io.github.barniebarnie.antcraft.script.lang;

import java.util.List;

/**
 * The compiled code of a module or of one function: its instructions, with the source line of each, and the constants,
 * global and attribute names and local variable names they refer to. The first {@code paramCount} locals are the
 * parameters, followed by the {@code *args} tuple when {@code varArgs} and the {@code **kwargs} dict when
 * {@code varKeywords}. Code does not change once compiled.
 */
public record Code(String name, String file, Op[] ops, int[] args, int[] lines, Object[] constants, String[] names,
		String[] locals, int paramCount, int defaultCount, boolean varArgs, boolean varKeywords) {
	/**
	 * The arguments of a call with keywords or {@code *value}s: how many positional ones, the names of the keyword ones
	 * (null for a {@code **value}, whose items they spread into), and the indexes of the positional ones that are
	 * {@code *value}s, whose items they spread into.
	 */
	public record CallShape(int positional, List<String> keywords, List<Integer> starred) {
	}

	/** An f-string field's conversion ({@code 's'}, {@code 'r'}, {@code 'a'}, or {@code '\0'} for none) and format spec. */
	public record FormatField(char conversion, String spec) {
	}

	/** A class statement: the class's name, whether a base is given, and the names of the attributes of its body, in order. */
	public record ClassShape(String name, boolean hasBase, List<String> attributes) {
	}

	public int size() {
		return ops.length;
	}

	/** The instructions, one a line, for tests and debugging. */
	public String disassemble() {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < ops.length; i++) {
			out.append(i).append(' ').append(ops[i]).append(' ').append(args[i]).append(" (line ").append(lines[i])
					.append(")\n");
		}
		return out.toString();
	}
}
