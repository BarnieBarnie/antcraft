package io.github.barniebarnie.antcraft.script.lang;

/**
 * A function written in Java that scripts call, such as {@code print} or a bot action. It returns a script value (see
 * {@link Values}), or {@link NativeCall#suspend()} to pause the script until the host resumes it with the call's result
 * ({@link Vm#resume}). It throws a {@link ScriptError} to fail the script at the line of the call.
 */
@FunctionalInterface
public interface NativeFunction {
	Object call(NativeCall call);
}
