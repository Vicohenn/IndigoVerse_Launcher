package indigo.coins;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Lucky Egg com desgaste: todo Lucky Egg segurado por um Pokémon guarda (no próprio item) quanto XP
 * o Pokémon já ganhou com ele. Ao chegar no limite (250k), o ovo quebra e some.
 * O progresso aparece no item (descrição), numa barra no topo da tela e em /luckyegg.
 * Usa a API do Cobblemon por reflexão (evento EXPERIENCE_GAINED_EVENT_POST).
 */
public final class LuckyEgg {
    static final String CHAVE = "indigo_lucky_xp";
    private static final Map<UUID, ServerBossBar> barras = new HashMap<>();
    private static final Map<UUID, Long> barraAte = new HashMap<>();
    private static long segundos;

    private static Method mPokemon, mXp, mSource, mIsBattle, mHeldNoCopy, mRemoveHeld, mOwner, mDisplayName, mGetParty, mStorage;
    private static Object cobblemon;

    private LuckyEgg() {}

    // ---------------- registro no Cobblemon ----------------

    static void registrar() {
        try {
            Class<?> events = Class.forName("com.cobblemon.mod.common.api.events.CobblemonEvents");
            Object observable = events.getField("EXPERIENCE_GAINED_EVENT_POST").get(null);
            Class<?> prio = Class.forName("com.cobblemon.mod.common.api.Priority");
            Object normal = prio.getField("NORMAL").get(null);
            Class<?> post = Class.forName("com.cobblemon.mod.common.api.events.pokemon.ExperienceGainedEvent$Post");
            mPokemon = post.getMethod("getPokemon");
            mXp = post.getMethod("getExperience");
            mSource = post.getMethod("getSource");
            mIsBattle = Class.forName("com.cobblemon.mod.common.api.pokemon.experience.ExperienceSource").getMethod("isBattle");
            Class<?> pk = Class.forName("com.cobblemon.mod.common.pokemon.Pokemon");
            mHeldNoCopy = pk.getMethod("heldItemNoCopy$common");
            mRemoveHeld = pk.getMethod("removeHeldItem");
            mOwner = pk.getMethod("getOwnerPlayer");
            mDisplayName = pk.getMethod("getDisplayName", boolean.class);
            Class<?> cob = Class.forName("com.cobblemon.mod.common.Cobblemon");
            cobblemon = cob.getField("INSTANCE").get(null);
            mStorage = cob.getMethod("getStorage");
            for (Method m : mStorage.getReturnType().getMethods())
                if (m.getName().equals("getParty") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(ServerPlayerEntity.class)) mGetParty = m;

            Consumer<Object> ouvinte = ev -> {
                try { aoGanharXp(ev); } catch (Exception e) { IndigoCoinsMod.LOGGER.error("Lucky Egg: erro ao contar XP", e); }
            };
            observable.getClass().getMethod("subscribe", prio, Consumer.class).invoke(observable, normal, ouvinte);
            IndigoCoinsMod.LOGGER.info("Lucky Egg com desgaste ligado (limite {} XP).", Dados.config.luckyEggLimiteXp);
        } catch (ClassNotFoundException e) {
            IndigoCoinsMod.LOGGER.warn("Cobblemon não encontrado: desgaste do Lucky Egg desligado.");
        } catch (Exception e) {
            IndigoCoinsMod.LOGGER.error("Não consegui ligar o desgaste do Lucky Egg", e);
        }
    }

    // ---------------- item ----------------

    private static Item item() {
        Item i = Registries.ITEM.get(Identifier.of(Dados.config.luckyEggItem));
        return i == Items.AIR ? null : i;
    }

    static boolean ehOvo(ItemStack st) {
        Item i = item();
        return i != null && !st.isEmpty() && st.isOf(i);
    }

    static long xpDo(ItemStack st) {
        NbtComponent c = st.get(DataComponentTypes.CUSTOM_DATA);
        return c == null ? 0 : c.copyNbt().getLong(CHAVE);
    }

    private static void gravar(ItemStack st, long xp) {
        NbtComponent atual = st.get(DataComponentTypes.CUSTOM_DATA);
        NbtCompound tag = atual == null ? new NbtCompound() : atual.copyNbt();
        tag.putLong(CHAVE, xp);
        st.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        long lim = limite();
        int pct = (int) Math.min(100, xp * 100 / lim);
        Formatting cor = pct >= 90 ? Formatting.RED : pct >= 60 ? Formatting.YELLOW : Formatting.GREEN;
        st.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("Desgaste: " + Economia.fmt(xp) + " / " + Economia.fmt(lim) + " XP (" + pct + "%)")
                        .styled(s -> s.withItalic(false).withColor(cor)),
                Text.literal("Quebra ao chegar em " + Economia.fmt(lim) + " de XP" + (Dados.config.luckyEggSoBatalha ? " de batalha." : "."))
                        .styled(s -> s.withItalic(false).withColor(Formatting.DARK_GRAY)))));
    }

    static ItemStack criarItem() {
        Item i = item();
        if (i == null) return ItemStack.EMPTY;
        ItemStack st = new ItemStack(i, 1);
        gravar(st, 0);
        return st;
    }

    static int dar(ServerPlayerEntity p, int qtd) {
        int dados = 0;
        for (int k = 0; k < qtd; k++) {
            ItemStack st = criarItem();
            if (st.isEmpty()) break;
            if (!p.getInventory().insertStack(st)) p.dropItem(st, false);
            dados++;
        }
        return dados;
    }

    static long limite() { return Math.max(1, Dados.config.luckyEggLimiteXp); }

    // ---------------- desgaste ----------------

    private static void aoGanharXp(Object ev) throws Exception {
        if (!Dados.config.luckyEggAtivo) return;
        int xp = (Integer) mXp.invoke(ev);
        if (xp <= 0) return;
        if (Dados.config.luckyEggSoBatalha) {
            Object src = mSource.invoke(ev);
            if (src == null || !(Boolean) mIsBattle.invoke(src)) return;
        }
        Object pokemon = mPokemon.invoke(ev);
        ItemStack ovo = (ItemStack) mHeldNoCopy.invoke(pokemon);
        if (ovo == null || !ehOvo(ovo)) return;

        long lim = limite();
        long antes = xpDo(ovo);
        long agora = antes + xp;
        ServerPlayerEntity dono = mOwner.invoke(pokemon) instanceof ServerPlayerEntity sp ? sp : null;
        String nome = nomeDe(pokemon);

        if (agora >= lim) {
            mRemoveHeld.invoke(pokemon);
            if (dono != null) {
                dono.sendMessage(Text.literal("§c✖ O Lucky Egg de §f" + nome + "§c quebrou depois de "
                        + Economia.fmt(lim) + " de XP. Compre outro no Sr. Fujiro Nakombi."));
                dono.getServerWorld().playSound(null, dono.getBlockPos(), SoundEvents.ENTITY_ITEM_BREAK, SoundCategory.PLAYERS, 1f, 1f);
                mostrar(dono, "§c✖ Lucky Egg de " + nome + " quebrou", 0f, BossBar.Color.RED);
                Dados.log("LUCKYEGG quebrou: " + dono.getGameProfile().getName() + " / " + nome);
            }
            return;
        }
        gravar(ovo, agora);
        if (dono != null) {
            if (antes < lim * 9 / 10 && agora >= lim * 9 / 10)
                dono.sendMessage(Text.literal("§eO Lucky Egg de §f" + nome + "§e está quase quebrando: §f"
                        + Economia.fmt(lim - agora) + "§e de XP restante."));
            float pct = (float) agora / lim;
            mostrar(dono, "§a🥚 Lucky Egg de §f" + nome + " §7" + Economia.fmt(agora) + " / " + Economia.fmt(lim) + " XP",
                    1f - pct, pct >= 0.9f ? BossBar.Color.RED : pct >= 0.6f ? BossBar.Color.YELLOW : BossBar.Color.GREEN);
        }
    }

    private static String nomeDe(Object pokemon) {
        try { return ((Text) mDisplayName.invoke(pokemon, false)).getString(); } catch (Exception e) { return "seu Pokémon"; }
    }

    // ---------------- barra no topo da tela ----------------

    /** Barra mostra quanto falta (cheia = ovo novo). Some depois de alguns segundos sem ganhar XP. */
    private static void mostrar(ServerPlayerEntity p, String texto, float resto, BossBar.Color cor) {
        if (!Dados.config.luckyEggBarraNaTela) return;
        ServerBossBar b = barras.computeIfAbsent(p.getUuid(),
                k -> new ServerBossBar(Text.empty(), BossBar.Color.GREEN, BossBar.Style.NOTCHED_10));
        b.setName(Text.literal(texto));
        b.setPercent(Math.max(0f, Math.min(1f, resto)));
        b.setColor(cor);
        if (!b.getPlayers().contains(p)) b.addPlayer(p);
        barraAte.put(p.getUuid(), segundos + Math.max(2, Dados.config.luckyEggSegundosNaTela));
    }

    static void removerBarra(UUID u) {
        barraAte.remove(u);
        ServerBossBar b = barras.remove(u);
        if (b != null) b.clearPlayers();
    }

    /** A cada segundo: esconde as barras vencidas. */
    static void tick(MinecraftServer server) {
        segundos++;
        if (barraAte.isEmpty()) return;
        for (UUID u : new ArrayList<>(barraAte.keySet()))
            if (barraAte.get(u) <= segundos) removerBarra(u);
    }

    // ---------------- /luckyegg ----------------

    /** Linhas com o desgaste de cada Lucky Egg da equipe do jogador. */
    static List<String> daEquipe(ServerPlayerEntity p) {
        List<String> out = new ArrayList<>();
        try {
            if (mGetParty == null) return out;
            Object party = mGetParty.invoke(mStorage.invoke(cobblemon), p);
            long lim = limite();
            for (Object pokemon : (Iterable<?>) party) {
                if (pokemon == null) continue;
                ItemStack st = (ItemStack) mHeldNoCopy.invoke(pokemon);
                if (st == null || !ehOvo(st)) continue;
                long xp = xpDo(st);
                int pct = (int) Math.min(100, xp * 100 / lim);
                String cor = pct >= 90 ? "§c" : pct >= 60 ? "§e" : "§a";
                out.add("§f" + nomeDe(pokemon) + "§7: " + cor + Economia.fmt(xp) + " / " + Economia.fmt(lim)
                        + " XP (" + pct + "%) §7— faltam " + Economia.fmt(lim - xp));
            }
        } catch (Exception e) {
            IndigoCoinsMod.LOGGER.error("Lucky Egg: erro lendo a equipe", e);
        }
        return out;
    }
}
