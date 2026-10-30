package io.github.barniebarnie.antcraft.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.barniebarnie.antcraft.script.ScriptEdits;
import io.github.barniebarnie.antcraft.script.ScriptLibrary;
import io.github.barniebarnie.antcraft.script.editor.ScriptNames;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the server sends the script editor (docs/features/scripts.md, Editing in game). */
class ScriptNetworkingTest {
	/** Vanilla's limit on a custom payload the server sends (ClientboundCustomPayloadPacket.MAX_PAYLOAD_SIZE). */
	private static final int MAX_PAYLOAD_BYTES = 1_048_576;

	@TempDir
	Path folder;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void theEditorGetsEveryNativeBuiltinAndLibraryScriptName() throws IOException {
		Files.writeString(folder.resolve("miner.py"), "pass\n");
		Files.createDirectories(folder.resolve("lib"));
		Files.writeString(folder.resolve("lib").resolve("trees.py"), "pass\n");
		List<String> names = ScriptNetworking.editorNames(ScriptLibrary.load(List.of(folder)));
		assertTrue(names.containsAll(List.of("print", "range", "goto", "craft", "ValueError", "miner", "lib.trees")), names.toString());
		// Natives are read from their registration, so a new one is sent without more code.
		assertTrue(names.containsAll(ScriptNames.natives()), names.toString());
	}

	@Test
	void theNamesAreCappedAndTheLargestPayloadFitsTheLimit() throws IOException {
		for (int i = 0; i < ScriptNetworking.MAX_NAMES + 10; i++) {
			Files.writeString(folder.resolve("script" + i + ".py"), "pass\n");
		}
		assertEquals(ScriptNetworking.MAX_NAMES, ScriptNetworking.editorNames(ScriptLibrary.load(List.of(folder))).size());

		// Every string at its longest, in three-byte characters.
		String longName = "€".repeat(ScriptEdits.MAX_NAME_LENGTH);
		ScriptNetworking.OpenScriptEditor largest = new ScriptNetworking.OpenScriptEditor(longName, "€".repeat(ScriptEdits.MAX_SOURCE_BYTES),
			Collections.nCopies(20, "€".repeat(200)), true, Collections.nCopies(ScriptNetworking.MAX_NAMES, longName));
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		ScriptNetworking.OpenScriptEditor.STREAM_CODEC.encode(buf, largest);
		assertTrue(buf.readableBytes() < MAX_PAYLOAD_BYTES, buf.readableBytes() + " bytes");
		assertEquals(largest, ScriptNetworking.OpenScriptEditor.STREAM_CODEC.decode(buf));
	}
}
