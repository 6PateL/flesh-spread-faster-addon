package com.example.fleshspread;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Клієнт: коли починається рейд зараження, екран плавно затягує «кров і плоть» (червона пульсуюча пелена з темними
 * краями), чутно серцебиття, потім усе так само плавно зникає.
 */
@Mod.EventBusSubscriber(modid = FleshSpread.MOD_ID, value = Dist.CLIENT)
public final class RaidOverlay {
    private static long FADE_IN = 2500L;
    private static long HOLD = 5000L;
    private static long FADE_OUT = 6000L;

    // розворот гравця кривавим компасом
    private static long turnStart = -1L;
    private static float turnFrom, turnTo, pitchFrom;
    private static final long TURN_MS = 900L;

    /** Кривавий компас: короткий кривавий спалах і плавний розворот у бік лігва. */
    public static void compass(float yaw, boolean turn) {
        started = System.currentTimeMillis();
        mode = 3;
        strong = true;
        lastBeat = 0L;
        FADE_IN = 250L;
        HOLD = 900L;
        FADE_OUT = 1800L;
        Minecraft mc = Minecraft.getInstance();
        if (turn && mc.player != null) {
            turnStart = started;
            turnFrom = mc.player.getYRot();
            turnTo = turnFrom + net.minecraft.util.Mth.wrapDegrees(yaw - turnFrom);
            pitchFrom = mc.player.getXRot();
        }
    }

    /** Кожен кадр: поки триває розворот, камера сама повертається до цілі. */
    @SubscribeEvent
    public static void onFrame(net.minecraftforge.event.TickEvent.RenderTickEvent event) {
        if (turnStart < 0L || event.phase != net.minecraftforge.event.TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            turnStart = -1L;
            return;
        }
        float f = Math.min(1.0F, (System.currentTimeMillis() - turnStart) / (float) TURN_MS);
        float k = f * f * (3.0F - 2.0F * f);
        float yaw = turnFrom + (turnTo - turnFrom) * k;
        float pitch = pitchFrom + (0.0F - pitchFrom) * k;
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
        mc.player.setYHeadRot(yaw);
        if (f >= 1.0F) turnStart = -1L;
    }

    private static long started = -1L;
    private static boolean strong;
    private static long lastBeat;

    private RaidOverlay() {}

    private static int mode;

    /** 0 = рейд, 1 = посилений рейд, 2 = пробудження порталу плоті. */
    public static void start(int fxMode) {
        started = System.currentTimeMillis();
        mode = fxMode;
        strong = fxMode != 0;
        if (fxMode == 6) { // вхід у портал: щупальця плоті повзуть від країв до центру
            FADE_IN = 1100L;
            HOLD = 600L;
            FADE_OUT = 1300L;
            return;
        }
        FADE_IN = fxMode == 4 ? 300L : 2500L;       // 4 = крик Утроби: короткий червоний спалах
        HOLD = fxMode == 4 ? 1500L : 5000L;
        FADE_OUT = fxMode == 4 ? 2200L : 6000L;
        lastBeat = 0L;
    }

    @SubscribeEvent
    public static void onRender(RenderGuiEvent.Post event) {
        if (started < 0L) return;
        long t = System.currentTimeMillis() - started;
        long total = FADE_IN + HOLD + FADE_OUT;
        if (t >= total) {
            started = -1L;
            return;
        }
        // плавний перехід: поява, утримання, згасання
        float a;
        if (t < FADE_IN) a = t / (float) FADE_IN;
        else if (t < FADE_IN + HOLD) a = 1.0F;
        else a = 1.0F - (t - FADE_IN - HOLD) / (float) FADE_OUT;
        a = a * a * (3.0F - 2.0F * a); // згладжування
        // пульс, як серцебиття
        float pulse = 0.82F + 0.18F * (float) Math.sin(t / 1000.0D * Math.PI * 2.0D * 1.15D);
        float k = a * pulse * (strong ? 1.0F : 0.8F);

        GuiGraphics g = event.getGuiGraphics();
        int w = event.getWindow().getGuiScaledWidth();
        int h = event.getWindow().getGuiScaledHeight();
        if (mode == 6) {
            tentacles(g, w, h, t, a);
            return;
        }

        // кривава пелена на весь екран
        g.fill(0, 0, w, h, argb((int) (70 * k), 0x7A, 0x08, 0x08));
        // темна «плоть» по краях: згори і знизу градієнт, з боків смуги
        int edge = argb((int) (215 * k), 0x30, 0x00, 0x02);
        int clear = argb(0, 0x30, 0x00, 0x02);
        int band = h / 3;
        g.fillGradient(0, 0, w, band, edge, clear);
        g.fillGradient(0, h - band, w, h, clear, edge);
        int strips = 24;
        int sw = Math.max(1, w / 3 / strips);
        for (int i = 0; i < strips; i++) {
            float f = 1.0F - i / (float) strips;
            int c = argb((int) (215 * k * f * f), 0x30, 0x00, 0x02);
            g.fill(i * sw, 0, (i + 1) * sw, h, c);
            g.fill(w - (i + 1) * sw, 0, w - i * sw, h, c);
        }
        // напис на початку
        if (t < FADE_IN + HOLD && mode != 3 && mode != 4) {
            Minecraft mc = Minecraft.getInstance();
            Component text = Component.literal(mode == 5 ? "\u2623 УТРОБА ПРОКИНУЛАСЬ \u2623" : mode == 2 ? "\u2623 ВРАТА ПЛОТІ ВІДЧИНЕНО \u2623"
                    : mode == 1 ? "\u2623 ПОСИЛЕНИЙ РЕЙД ЗАРАЖЕННЯ \u2623" : "\u2623 ЗАРАЖЕННЯ НАСТУПАЄ \u2623");
            int alpha = Math.max(8, (int) (255 * a));
            g.pose().pushPose();
            g.pose().translate(w / 2.0F, h / 4.0F, 0.0F);
            g.pose().scale(2.0F, 2.0F, 1.0F);
            g.drawCenteredString(mc.font, text, 0, 0, (alpha << 24) | 0xE01818);
            g.pose().popPose();
        }
        // серцебиття
        if (t - lastBeat >= 870L || lastBeat == 0L) {
            lastBeat = Math.max(1L, t);
            Minecraft mc = Minecraft.getInstance();
            SoundEvent beat = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("minecraft", "entity.warden.heartbeat"));
            if (mc.player != null && beat != null) {
                mc.player.playSound(beat, 1.2F * a + 0.2F, strong ? 0.8F : 1.0F);
            }
        }
    }

    /**
     * Щупальця плоті: дванадцять м'ясистих відростків виростають від країв екрана, звиваючись, і майже змикаються в
     * центрі; під ними темніє, потім усе розсмоктується.
     */
    private static void tentacles(GuiGraphics g, int w, int h, long t, float a) {
        float grow = Math.min(1.0F, t / (float) FADE_IN);
        grow = 1.0F - (1.0F - grow) * (1.0F - grow);
        float cx = w / 2.0F, cy = h / 2.0F;
        g.fill(0, 0, w, h, argb((int) (150 * a * grow), 0x1A, 0x00, 0x02));
        int n = 12;
        for (int i = 0; i < n; i++) {
            double ang = i * Math.PI * 2.0D / n + 0.13D * i;
            // старт на краю екрана
            double ex = Math.cos(ang), ey = Math.sin(ang);
            double scale = Math.min(Math.abs(cx / (ex == 0 ? 1e-6 : ex)), Math.abs(cy / (ey == 0 ? 1e-6 : ey)));
            float sx = (float) (cx + ex * scale), sy = (float) (cy + ey * scale);
            float len = (float) (scale * (0.88D - (i % 3) * 0.07D)) * grow;
            float base = Math.max(6.0F, Math.min(w, h) * 0.07F) * (1.0F - (i % 2) * 0.25F);
            int steps = (int) Math.max(2, len / 1.5F);
            for (int k = 0; k <= steps; k++) {
                float f = k / (float) steps;
                float d = f * len;
                // звивання: синус уздовж щупальця, що «пливе» в часі
                double wob = Math.sin(f * 7.0D + t / 180.0D + i) * (10.0D + 14.0D * f) * grow;
                float px = (float) (sx - ex * d - ey * wob);
                float py = (float) (sy - ey * d + ex * wob);
                float r = base * (1.0F - f * 0.85F);
                int shade = (int) (110 + 60 * Math.sin(f * 20.0D + i));
                int alpha = (int) (235 * a);
                g.fill((int) (px - r), (int) (py - r), (int) (px + r), (int) (py + r), argb(alpha, Math.min(255, shade + 40), 0x10, 0x16));
                if (k % 6 == 0) { // темні жили й присоски
                    float rr = r * 0.45F;
                    g.fill((int) (px - rr), (int) (py - rr), (int) (px + rr), (int) (py + rr), argb(alpha, 0x4A, 0x02, 0x08));
                }
            }
        }
    }

    private static int argb(int a, int r, int g, int b) {
        return (Math.max(0, Math.min(255, a)) << 24) | (r << 16) | (g << 8) | b;
    }
}
