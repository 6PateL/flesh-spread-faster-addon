package com.example.fleshspread;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

import java.util.List;

/**
 * Кривавий компас. Працює тільки у вимірі плоті: раз на 30 секунд випускає рев, «сканує» місцевість і показує, у який
 * бік іти до Серця плоті. Кожен рев чує рій: якщо заражених поруч немає, вони приходять; якщо є — кидаються на гравця.
 */
public class BloodCompassItem extends Item {
    private static final String[] SIDES = {"схід", "південний схід", "південь", "південний захід", "захід", "північний захід",
            "північ", "північний схід"};

    public BloodCompassItem(Properties p) {
        super(p);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level lvl, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (lvl.isClientSide || !(player instanceof ServerPlayer sp) || !(lvl instanceof ServerLevel level)) {
            return InteractionResultHolder.success(stack);
        }
        if (!FleshDim.isFlesh(level)) {
            sp.displayClientMessage(Component.literal("Стрілка мертва. Компас оживає лише у вимірі плоті.").withStyle(ChatFormatting.GRAY), true);
            return InteractionResultHolder.fail(stack);
        }
        SpreadConfig cfg = SpreadConfig.get();
        FleshData data = FleshData.get(level);
        sp.getCooldowns().addCooldown(this, Math.max(1, cfg.compassCooldownSeconds) * 20);
        FleshDim.sound(level, sp.blockPosition(), "flesh_that_hates:entity.call", 5.0F, 0.75F);

        if (data.boss == null) {
            sp.displayClientMessage(Component.literal("Компас мовчить: лігво ще не визначено (зайдіть у вимір через портал).")
                    .withStyle(ChatFormatting.DARK_RED), true);
            FsNet.sendCompass(sp, sp.getYRot(), false);
        } else {
            double dx = data.boss.getX() + 0.5D - sp.getX();
            double dz = data.boss.getZ() + 0.5D - sp.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            double nx = dist < 0.01D ? 0.0D : dx / dist, nz = dist < 0.01D ? 0.0D : dz / dist;
            // кривавий слід у потрібний бік
            DustParticleOptions blood = new DustParticleOptions(new Vector3f(0.75F, 0.02F, 0.02F), 1.6F);
            for (int i = 2; i <= 36; i++) {
                double f = i * 0.5D;
                level.sendParticles(sp, blood, true, sp.getX() + nx * f, sp.getEyeY() - 0.3D + Math.sin(i * 0.5D) * 0.15D,
                        sp.getZ() + nz * f, 2, 0.05D, 0.05D, 0.05D, 0.0D);
            }
            // кров на екрані, а самого гравця розвертає обличчям до лігва
            FsNet.sendCompass(sp, (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D), dist > 3.0D);
            int side = (int) Math.floorMod(Math.round(Math.atan2(dz, dx) / (Math.PI / 4.0D)), 8L);
            String range = dist < 48.0D ? "зовсім поруч" : dist < 200.0D ? "близько" : dist < 600.0D ? "далеко" : "дуже далеко";
            sp.displayClientMessage(Component.literal("Серце плоті: " + SIDES[side] + ", " + range).withStyle(ChatFormatting.DARK_RED), true);
        }

        // рев чує рій
        List<Mob> near = FleshDim.infectedNear(level, sp, 40.0D);
        int called = 0;
        if (near.isEmpty()) {
            called = FleshDim.summon(level, sp.blockPosition(), cfg.compassSummon, 10, 18, sp);
        } else if (!sp.isCreative()) {
            for (Mob m : near) m.setTarget(sp);
        }
        FleshSpread.LOGGER.info("Кривавий компас: {} на {}, лігво {}, заражених поруч {}, прикликано {}", sp.getName().getString(),
                sp.blockPosition().toShortString(), data.boss == null ? "?" : data.boss.toShortString(), near.size(), called);
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tips, TooltipFlag flag) {
        tips.add(Component.literal("Працює лише у вимірі плоті").withStyle(ChatFormatting.GRAY));
        tips.add(Component.literal("ПКМ — рев, що вказує шлях до Серця плоті (раз на 30 с)").withStyle(ChatFormatting.DARK_GRAY));
        tips.add(Component.literal("Рій чує кожен рев").withStyle(ChatFormatting.DARK_RED));
    }
}
