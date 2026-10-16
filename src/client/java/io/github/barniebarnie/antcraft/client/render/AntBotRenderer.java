package io.github.barniebarnie.antcraft.client.render;

import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotSkins;
import java.util.HashMap;
import java.util.Map;
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

/** Renders bots with the player model, in the skin of their role (see {@link BotSkins}). */
public class AntBotRenderer extends HumanoidMobRenderer<AntBot, AvatarRenderState, PlayerModel> {
	private static final Map<Identifier, PlayerSkin> SKINS = new HashMap<>();

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
		state.skin = SKINS.computeIfAbsent(BotSkins.texture(entity.getRole(), entity.getUUID()),
			texture -> new PlayerSkin(new ClientAsset.ResourceTexture(texture), null, null, PlayerModelType.WIDE, true));
		state.showCape = false;
	}

	@Override
	public Identifier getTextureLocation(AvatarRenderState state) {
		return state.skin.body().texturePath();
	}
}
