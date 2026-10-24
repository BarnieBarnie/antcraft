package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.blueprint.Blueprint;
import io.github.barniebarnie.antcraft.blueprint.Blueprints;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;

public class BlueprintGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		method.invoke(this, helper);
	}

	/** The server's data load fills the blueprint library with the bundled blueprints, each pointing at a structure the server has. */
	@GameTest(maxTicks = 5)
	public void theServerListsTheBundledBlueprints(GameTestHelper helper) {
		for (String name : List.of("starter_house", "cobble_step")) {
			Identifier id = AntCraft.id(name);
			Optional<Blueprint> blueprint = Blueprints.get(id);
			helper.assertTrue(blueprint.isPresent(), "no blueprint " + id + " in " + Blueprints.ids());
			helper.assertTrue(Blueprints.ids().contains(id), id + " not listed in " + Blueprints.ids());
			helper.assertTrue(helper.getLevel().getServer().getStructureTemplateManager().get(blueprint.get().structure()).isPresent(),
				"no structure " + blueprint.get().structure());
		}
		helper.succeed();
	}
}
