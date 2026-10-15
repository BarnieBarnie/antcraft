package io.github.barniebarnie.antcraft.registry;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.block.ColonyCoreBlock;
import io.github.barniebarnie.antcraft.block.ColonyCoreBlockEntity;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

public final class ModBlocks {
	public static final ResourceKey<Block> COLONY_CORE_KEY = ResourceKey.create(Registries.BLOCK, AntCraft.id("colony_core"));

	public static final Block COLONY_CORE = Registry.register(
		BuiltInRegistries.BLOCK,
		COLONY_CORE_KEY,
		new ColonyCoreBlock(BlockBehaviour.Properties.of().setId(COLONY_CORE_KEY).mapColor(MapColor.METAL).strength(3.0F, 6.0F).sound(SoundType.METAL))
	);

	public static final BlockEntityType<ColonyCoreBlockEntity> COLONY_CORE_ENTITY = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE,
		AntCraft.id("colony_core"),
		new BlockEntityType<>(ColonyCoreBlockEntity::new, Set.of(COLONY_CORE))
	);

	private ModBlocks() {
	}

	public static void register() {
	}
}
