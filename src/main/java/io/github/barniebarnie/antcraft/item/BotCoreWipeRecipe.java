package io.github.barniebarnie.antcraft.item;

import com.mojang.serialization.MapCodec;
import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.registry.ModComponents;
import io.github.barniebarnie.antcraft.registry.ModItems;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * A Bot Core alone in the grid crafts into a blank one, if it remembers a bot or a script (#916, #924;
 * docs/features/bots.md). A special recipe, so the recipe book does not show "bot core -> bot core".
 */
public final class BotCoreWipeRecipe extends CustomRecipe {
	public static final BotCoreWipeRecipe INSTANCE = new BotCoreWipeRecipe();
	public static final RecipeSerializer<BotCoreWipeRecipe> SERIALIZER = Registry.register(
		BuiltInRegistries.RECIPE_SERIALIZER,
		AntCraft.id("bot_core_wipe"),
		new RecipeSerializer<>(MapCodec.unit(INSTANCE), StreamCodec.unit(INSTANCE))
	);

	private BotCoreWipeRecipe() {
	}

	@Override
	public boolean matches(CraftingInput input, Level level) {
		if (input.ingredientCount() != 1) {
			return false;
		}
		ItemStack core = input.items().stream().filter(stack -> !stack.isEmpty()).findFirst().orElseThrow();
		return core.is(ModItems.BOT_CORE) && (core.has(ModComponents.REMEMBERED_BOT) || core.has(ModComponents.CORE_SCRIPT));
	}

	@Override
	public ItemStack assemble(CraftingInput input) {
		return new ItemStack(ModItems.BOT_CORE);
	}

	@Override
	public RecipeSerializer<BotCoreWipeRecipe> getSerializer() {
		return SERIALIZER;
	}

	public static void register() {
		// Loading the class registers the serializer.
	}
}
