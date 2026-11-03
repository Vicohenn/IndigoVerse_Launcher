package indigo.guard;

import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Provas de duplicação (a cada 30 s, jogadores online sem OP):
 *  - Pokémon com o mesmo ID interno em dois lugares (time/PC do mesmo jogador ou de dois jogadores)
 *  - pilha acima do máximo do item no inventário do jogador ou no baú do End
 *    (só esses: mochilas e baús do Sophisticated Storage podem guardar mais de 64 por slot e não são olhados)
 */
public final class Duplicacao {
    private static int ticks;

    private Duplicacao() {}

    public static void tick(MinecraftServer server) {
        if (!GuardConfig.get().duplicacaoAtivo || ++ticks % 600 != 0) return;
        Map<UUID, List<String>> donos = new HashMap<>();           // id do Pokémon -> onde aparece
        Map<UUID, ServerPlayerEntity> porNick = new HashMap<>();
        Map<UUID, String> especie = new HashMap<>();
        for (ServerPlayerEntity p : new java.util.ArrayList<>(server.getPlayerManager().getPlayerList())) {
            if (Alertas.ehStaffOnline(p)) continue;
            porNick.put(p.getUuid(), p);
            pilhas(server, p);
            for (Object poke : pokemons(p)) {
                try {
                    UUID id = (UUID) poke.getClass().getMethod("getUuid").invoke(poke);
                    donos.computeIfAbsent(id, k -> new ArrayList<>()).add(p.getUuid().toString());
                    Object sp = poke.getClass().getMethod("getSpecies").invoke(poke);
                    especie.putIfAbsent(id, String.valueOf(sp.getClass().getMethod("getName").invoke(sp)));
                } catch (Exception ignored) { }
            }
        }
        for (Map.Entry<UUID, List<String>> e : donos.entrySet()) {
            if (e.getValue().size() < 2) continue;
            for (String dono : new java.util.LinkedHashSet<>(e.getValue())) {
                ServerPlayerEntity p = porNick.get(UUID.fromString(dono));
                if (p == null) continue;
                Alertas.alertar(server, Alertas.Tipo.DUPE_POKEMON, p.getGameProfile().getName(), p.getUuid(), Alertas.local(p), List.of(
                        "Pokémon " + especie.getOrDefault(e.getKey(), "?") + " (ID " + e.getKey() + ") aparece " + e.getValue().size() + " vezes",
                        "Contas com a cópia: " + String.join(", ", nomes(server, e.getValue()))));
            }
        }
    }

    /** Pokémon do time e do PC (Cobblemon, por reflexão). */
    private static List<Object> pokemons(ServerPlayerEntity p) {
        List<Object> lista = new ArrayList<>();
        try {
            Class<?> cobblemon = Class.forName("com.cobblemon.mod.common.Cobblemon");
            Object inst = cobblemon.getField("INSTANCE").get(null);
            Object storage = cobblemon.getMethod("getStorage").invoke(inst);
            for (String metodo : new String[]{"getParty", "getPC"}) {
                Object store = null;
                for (var m : storage.getClass().getMethods()) {
                    if (m.getName().equals(metodo) && m.getParameterCount() == 1
                            && m.getParameterTypes()[0].isAssignableFrom(p.getClass())) { store = m.invoke(storage, p); break; }
                }
                if (store instanceof Iterable<?> it) for (Object poke : it) if (poke != null) lista.add(poke);
            }
        } catch (Throwable t) {
            IndigoGuardMod.LOGGER.debug("IndigoGuard: não consegui ler os Pokémon de {}: {}", p.getGameProfile().getName(), t.toString());
        }
        return lista;
    }

    private static void pilhas(MinecraftServer server, ServerPlayerEntity p) {
        List<String> achados = new ArrayList<>();
        olhar(p.getInventory(), "inventário", achados);
        olhar(p.getEnderChestInventory(), "baú do End", achados);
        if (!achados.isEmpty()) {
            Alertas.alertar(server, Alertas.Tipo.DUPE_PILHA, p.getGameProfile().getName(), p.getUuid(), Alertas.local(p), achados);
        }
    }

    private static void olhar(Inventory inv, String onde, List<String> achados) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack st = inv.getStack(i);
            if (st.isEmpty()) continue;
            if (st.getCount() > st.getMaxCount()) {
                achados.add(Registries.ITEM.getId(st.getItem()) + ": pilha de " + st.getCount()
                        + " (máximo " + st.getMaxCount() + ") no " + onde);
            }
        }
    }

    private static List<String> nomes(MinecraftServer server, List<String> uuids) {
        List<String> r = new ArrayList<>();
        for (String u : new java.util.LinkedHashSet<>(uuids)) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(UUID.fromString(u));
            r.add((p == null ? "?" : p.getGameProfile().getName()) + " (" + u + ")");
        }
        return r;
    }
}
