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

    private static final String[] MODES = {"Гравець", "Поселення", "Варвари", "Пасовища", "Промисловість", "ППО"};

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
        tips.add(Component.literal("ПКМ — запустити, Shift+ПКМ — змінити режим").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Mod.EventBusSubscriber(modid = FleshSpread.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Tabs {
        @SubscribeEvent
        public static void tab(BuildCreativeModeTabContentsEvent e) {
            if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) e.accept(PROBE.get());
        }
    }
}
