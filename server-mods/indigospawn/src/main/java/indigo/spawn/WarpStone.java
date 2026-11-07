package indigo.spawn;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Desgaste da Warp Stone (Waystones): cada teleporte feito com ela conta um uso, guardado no próprio item.
 * Até 75% da vida útil ela não quebra; na zona de risco (últimos 25%) cada uso tem 5% de chance de quebrar,
 * e no último uso ela quebra de qualquer jeito. Ouve o evento WaystoneTeleportEntityEvent.Post por reflexão.
 */
final class WarpStone {
    static final String CHAVE = "indigo_warp_usos";
    private static Method mCtx, mEnt, mWarpItem, mCtxEnt;

    private WarpStone() {}

    static void registrar() {
        try {
            Class<?> post = Class.forName("net.blay09.mods.waystones.api.event.WaystoneTeleportEntityEvent$Post");
            Class<?> base = Class.forName("net.blay09.mods.waystones.api.event.WaystoneTeleportEntityEvent");
            Class<?> ctx = Class.forName("net.blay09.mods.waystones.api.WaystoneTeleportContext");
            mCtx = base.getMethod("getContext");
            mEnt = base.getMethod("getEntity");
            mWarpItem = ctx.getMethod("getWarpItem");
            mCtxEnt = ctx.getMethod("getEntity");
            Object events = Class.forName("net.blay09.mods.balm.api.Balm").getMethod("getEvents").invoke(null);
            Consumer<Object> ouvinte = ev -> {
                try { aoTeleportar(ev); } catch (Exception e) { IndigoSpawnMod.LOGGER.error("Warp Stone: erro no desgaste", e); }
            };
            events.getClass().getMethod("onEvent", Class.class, Consumer.class).invoke(events, post, ouvinte);
            IndigoSpawnMod.LOGGER.info("Desgaste da Warp Stone ligado ({} usos).", SpawnConfig.get().warpStoneUsosMax);
        } catch (ClassNotFoundException e) {
            IndigoSpawnMod.LOGGER.warn("Waystones não encontrado: desgaste da Warp Stone desligado.");
        } catch (Exception e) {
            IndigoSpawnMod.LOGGER.error("Não consegui ligar o desgaste da Warp Stone", e);
        }
    }

    private static boolean ehWarpStone(ItemStack st) {
        return st != null && !st.isEmpty() && "waystones:warp_stone".equals(Registries.ITEM.getId(st.getItem()).toString());
    }

    static int usos(ItemStack st) {
        NbtComponent c = st.get(DataComponentTypes.CUSTOM_DATA);
        return c == null ? 0 : c.copyNbt().getInt(CHAVE);
    }

    private static void aoTeleportar(Object ev) throws Exception {
        SpawnConfig cfg = SpawnConfig.get();
        if (!Boolean.TRUE.equals(cfg.warpStoneDesgaste)) return;
        Object ctx = mCtx.invoke(ev);
        // o evento sai uma vez por entidade teleportada (jogador, montaria, pets): conta só o jogador que usou
        if (mEnt.invoke(ev) != mCtxEnt.invoke(ctx)) return;
        if (!(mEnt.invoke(ev) instanceof ServerPlayerEntity p)) return;
        ItemStack usado = (ItemStack) mWarpItem.invoke(ctx);
        if (!ehWarpStone(usado)) return;
        ItemStack st = acharNoJogador(p, usado);
        if (st == null) return;

        int max = Math.max(1, cfg.warpStoneUsosMax);
        int n = usos(st) + 1;
        int inicioRisco = (int) Math.round(max * (1 - cfg.warpStoneVidaRisco));
        boolean quebra = n >= max || (n > inicioRisco && ThreadLocalRandom.current().nextDouble() < cfg.warpStoneChanceQuebra);
        if (quebra) {
            st.decrement(1);
            p.getServerWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_ITEM_BREAK, SoundCategory.PLAYERS, 1f, 0.8f);
            p.sendMessage(Text.literal("§c✖ Sua Warp Stone se partiu depois de " + n + " usos."));
            IndigoSpawnMod.LOGGER.info("Warp Stone de {} quebrou com {} usos", p.getGameProfile().getName(), n);
            return;
        }
        gravar(st, n, max, inicioRisco);
        if (n == inicioRisco + 1)
            p.sendMessage(Text.literal("§eSua Warp Stone começou a rachar: a partir de agora cada uso pode quebrá-la."));
    }

    /** A pilha de verdade no inventário (o evento pode trazer uma cópia). */
    private static ItemStack acharNoJogador(ServerPlayerEntity p, ItemStack usado) {
        for (Hand h : Hand.values()) {
            ItemStack s = p.getStackInHand(h);
            if (s == usado) return s;
        }
        for (Hand h : Hand.values()) {
            ItemStack s = p.getStackInHand(h);
            if (ehWarpStone(s)) return s;
        }
        var inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) if (inv.getStack(i) == usado) return inv.getStack(i);
        return ehWarpStone(usado) ? usado : null;
    }

    private static void gravar(ItemStack st, int n, int max, int inicioRisco) {
        NbtComponent atual = st.get(DataComponentTypes.CUSTOM_DATA);
        NbtCompound tag = atual == null ? new NbtCompound() : atual.copyNbt();
        tag.putInt(CHAVE, n);
        st.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        int vida = (int) Math.ceil(100.0 * (max - n) / max);
        Formatting cor = n > inicioRisco ? Formatting.RED : vida <= 50 ? Formatting.YELLOW : Formatting.GREEN;
        String estado = n > inicioRisco ? " — rachada, pode quebrar a qualquer uso" : "";
        st.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("Vida útil: " + vida + "% (" + n + " / " + max + " usos)" + estado)
                        .styled(s -> s.withItalic(false).withColor(cor)))));
    }
}
