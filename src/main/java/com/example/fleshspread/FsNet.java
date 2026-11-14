package com.example.fleshspread;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/** Мережа: сервер повідомляє клієнтам про початок рейду зараження (ефект на екрані). */
public final class FsNet {
    private static final String PROTOCOL = "1";
    // клієнт без моду або стара версія теж можуть зайти: канал необов'язковий
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(FleshSpread.MOD_ID, "main"),
            () -> PROTOCOL, v -> true, v -> true);

    private FsNet() {}

    public static void register() {
        CHANNEL.registerMessage(0, RaidFx.class, RaidFx::encode, RaidFx::decode, RaidFx::handle);
    }

    public static void sendRaidFx(ServerPlayer player, boolean strong) {
        sendFx(player, strong ? 1 : 0);
    }

    /** 0 = рейд, 1 = посилений рейд, 2 = пробудження порталу плоті. */
    public static void sendFx(ServerPlayer player, int mode) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new RaidFx(mode));
        } catch (Throwable t) {
            FleshSpread.LOGGER.warn("Не вдалося надіслати ефект рейду: {}", t.toString());
        }
    }

    public record RaidFx(int mode) {
        static void encode(RaidFx m, FriendlyByteBuf buf) {
            buf.writeByte(m.mode);
        }

        static RaidFx decode(FriendlyByteBuf buf) {
            return new RaidFx(buf.readByte());
        }

        static void handle(RaidFx m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> RaidOverlay.start(m.mode)));
            c.setPacketHandled(true);
        }
    }
}
