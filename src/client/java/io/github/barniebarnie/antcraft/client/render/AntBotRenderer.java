package io.github.barniebarnie.antcraft.client.render;

import io.github.barniebarnie.antcraft.entity.AntBot;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/** Renders bots with the player model and one of the default (wide-arm) player skins. */
public class AntBotRenderer extends HumanoidMobRenderer<AntBot, AvatarRenderState, PlayerModel> {
	private static final String[] SKIN_NAMES = {"alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri"};
	private static final PlayerSkin[] SKINS = new PlayerSkin[SKIN_NAMES.length];

	static {
		for (int i = 0; i < SKIN_NAMES.length; i++) {
			ClientAsset.ResourceTexture body = new ClientAsset.ResourceTexture(Identifier.withDefaultNamespace("entity/player/wide/" + SKIN_NAMES[i]));
			SKINS[i] = new PlayerSkin(body, null, null, PlayerModelType.WIDE, true);
		}
	}

	public AntBotRenderer(EntityRendererProvider.Context context) {
		super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
		this.addLayer(new HumanoidArmorLayer<>(
			this,
			ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR, context.getModelSet(), part -> new PlayerModel(part, false)),
			context.getEquipmentRenderer()
		));
	}

	@Override
	public AvatarRenderState createRenderState() {
		return new AvatarRenderState();
	}

	@Override
	public void extractRenderState(AntBot entity, AvatarRenderState state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		// Based on the UUID so a bot keeps its skin across reloads.
		state.skin = SKINS[Math.floorMod(entity.getUUID().hashCode(), SKINS.length)];
		state.showCape = false;
	}

	@Override
	public Identifier getTextureLocation(AvatarRenderState state) {
		return state.skin.body().texturePath();
	}
}
