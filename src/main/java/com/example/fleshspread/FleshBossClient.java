package com.example.fleshspread;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/** Клієнт: модель і рендер Утроби (GeckoLib), збільшені в 4 рази. */
@Mod.EventBusSubscriber(modid = FleshSpread.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class FleshBossClient {
    private FleshBossClient() {}

    public static class Model extends GeoModel<FleshBoss> {
        private static final ResourceLocation GEO = new ResourceLocation(FleshSpread.MOD_ID, "geo/flesh_heart_boss.geo.json");
        private static final ResourceLocation TEX = new ResourceLocation(FleshSpread.MOD_ID, "textures/entity/flesh_heart_boss.png");
        private static final ResourceLocation ANIM = new ResourceLocation(FleshSpread.MOD_ID, "animations/flesh_heart_boss.animation.json");

        @Override
        public ResourceLocation getModelResource(FleshBoss boss) {
            return GEO;
        }

        @Override
        public ResourceLocation getTextureResource(FleshBoss boss) {
            return TEX;
        }

        @Override
        public ResourceLocation getAnimationResource(FleshBoss boss) {
            return ANIM;
        }
    }

    public static class Renderer extends GeoEntityRenderer<FleshBoss> {
        public Renderer(EntityRendererProvider.Context ctx) {
            super(ctx, new Model());
            this.shadowRadius = 0.0F;
            this.withScale(FleshBoss.SCALE);
        }

        @Override
        public boolean shouldRender(FleshBoss boss, net.minecraft.client.renderer.culling.Frustum frustum, double x, double y, double z) {
            return true; // 50 блоків завдовжки: звичайне відсікання за хітбоксом ховало б його
        }
    }

    @SubscribeEvent
    public static void onRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(FleshDim.BOSS.get(), Renderer::new);
    }
}
