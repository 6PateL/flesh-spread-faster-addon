package com.example.fleshspread;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Тестові команди (потрібні права оператора): /flesh_spread seed | nearest */
@Mod.EventBusSubscriber(modid = FleshSpread.MOD_ID)
public final class SpreadCommands {
    private SpreadCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("flesh_spread")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("seed").executes(ctx -> seed(ctx.getSource())))
                .then(Commands.literal("nearest").executes(ctx -> nearest(ctx.getSource()))));
    }

    private static int seed(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        BlockPos p = player.blockPosition();
        BlockPos pos = Nests.findSurface(level, p.getX() + 12, p.getZ() + 12);
        if (pos == null) pos = p;
        boolean ok = Nests.spawnNest(level, pos, SpreadConfig.get().patchRadius);
        BlockPos shown = pos;
        src.sendSuccess(() -> Component.literal(ok
                ? "Гніздо створено: " + shown.toShortString()
                : "Не вдалося (чи встановлено Flesh That Hates?)"), true);
        return ok ? 1 : 0;
    }

    private static int nearest(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        BlockPos c = player.blockPosition();
        AABB box = new AABB(c.getX() - 512, level.getMinBuildHeight(), c.getZ() - 512,
                c.getX() + 512, level.getMaxBuildHeight(), c.getZ() + 512);

        List<Entity> list = new ArrayList<>(level.getEntities((Entity) null, box, Nests::isIncubator));
        list.sort(Comparator.comparingDouble(e -> e.distanceToSqr(player)));

        if (list.isEmpty()) {
            src.sendSuccess(() -> Component.literal("Гнізд у завантажених чанках поруч немає"), false);
            return 0;
        }
        for (int i = 0; i < Math.min(5, list.size()); i++) {
            Entity e = list.get(i);
            String kind = e.getType() == Nests.big() ? "великий" : "малий";
            String line = kind + ": " + e.blockPosition().toShortString()
                    + " (" + (int) Math.sqrt(e.distanceToSqr(player)) + " м)";
            src.sendSuccess(() -> Component.literal(line), false);
        }
        return list.size();
    }
}
