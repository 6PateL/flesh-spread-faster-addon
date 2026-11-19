package com.example.fleshspread;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

/** Дебаг-інструмент: Shift+ПКМ перемикає режим, ПКМ запускає рейд / ППО негайно. */
public class ProbeItem extends Item {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, FleshSpread.MOD_ID);
    public static final RegistryObject<Item> PROBE = ITEMS.register("infection_probe",
            () -> new ProbeItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    private static final String[] MODES = {"Гравець", "Поселення", "Варвари", "Пасовища", "Промисловість", "ППО",
            "Посилений рейд (поселення)", "Пришвидшити рейд за розкладом (−5 хв)", "Відкласти рейд за розкладом (+5 хв)", "Вісник: Утроба і 3 розломи поруч", "Бій з Утробою тут (арена довкола вас)"};
    private static final long STEP = 6000L; // чверть дня

    public ProbeItem(Properties p) {
        super(p);
    }

    private static int mode(ItemStack s) {
        CompoundTag t = s.getTag();
        return t == null ? 0 : Math.floorMod(t.getInt("mode"), MODES.length);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide || !(player instanceof ServerPlayer sp)) return InteractionResultHolder.success(stack);
        int m = mode(stack);
        if (player.isShiftKeyDown()) {
            m = (m + 1) % MODES.length;
            stack.getOrCreateTag().putInt("mode", m);
            player.displayClientMessage(Component.literal("Режим: " + MODES[m]).withStyle(ChatFormatting.YELLOW), true);
            return InteractionResultHolder.success(stack);
        }
        ServerLevel sl = sp.serverLevel();
        SpreadConfig cfg = SpreadConfig.get();
        String msg;
        if (m == 5) {
            int n = AirDefense.spawn(sl, cfg, sp.blockPosition());
            msg = n > 0 ? "ППО: юнітів " + n : "ППО не створено (немає Flesh That Hates?)";
        } else if (m == 6) {
            int n = InfectedRaids.launchType(sl, cfg, RaidTargets.VILLAGE, sp, true);
            msg = n > 0 ? "Посилений рейд: нападів " + n : "Посилений рейд: цілей не знайдено";
        } else if (m == 9) {
            net.minecraft.core.BlockPos at = FleshRifts.herald(sl, sp.blockPosition(), "зонд, " + sp.getName().getString(), true);
            msg = at != null ? "Утроба виринає на " + at.toShortString() + " (" + (int) Math.sqrt(at.distSqr(sp.blockPosition())) + " блоків)"
                    : FleshDim.isFlesh(sl) ? "У вимірі плоті вісника не буває" : FleshData.defeated(sl.getServer())
                    ? "Інфекцію вже переможено" : "Довкола немає жодної завантаженої поверхні";
        } else if (m == 10) {
            if (FleshBoss.near(sl, sp.blockPosition(), 150.0D).isEmpty()) {
                FleshDim.startFight(sl, sp.blockPosition(), 60, "зонд, " + sp.getName().getString());
                msg = "Бій з Утробою почато (у креативі вона вас не бачить)";
            } else {
                msg = "Утроба вже поруч";
            }
        } else if (m == 7 || m == 8) {
            long s = InfectedRaids.shift(sl.getServer().overworld(), m == 7 ? -STEP : STEP);
            msg = (m == 7 ? "Рейд пришвидшено" : "Рейд відкладено") + ": залишилось "
                    + (s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) : String.format("%d:%02d", s / 60, s % 60));
        } else {
            int n = InfectedRaids.launchType(sl, cfg, m, sp);
            msg = n > 0 ? "Рейд (" + MODES[m] + "): інфікованих " + n : "Рейд (" + MODES[m] + "): цілей не знайдено";
        }
        player.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.RED), true);
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tips, net.minecraft.world.item.TooltipFlag f) {
        tips.add(Component.literal("Режим: " + MODES[mode(stack)]).withStyle(ChatFormatting.GRAY));
        tips.add(Component.literal("ПКМ — застосувати, Shift+ПКМ — змінити режим").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Mod.EventBusSubscriber(modid = FleshSpread.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Tabs {
        @SubscribeEvent
        public static void tab(BuildCreativeModeTabContentsEvent e) {
            if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
                e.accept(PROBE.get());
                e.accept(FleshDim.FRAME_ITEM.get());
                e.accept(FleshDim.BLOOD_COMPASS.get());
                e.accept(FleshDim.HEART_ITEM.get());
                e.accept(FleshDim.HARDENED_ITEM.get());
                e.accept(FleshDim.HARDENED_STAIRS_ITEM.get());
            }
        }
    }
}
