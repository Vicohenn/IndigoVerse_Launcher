package legend.limit;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Tentativas de invocação no altar, por rodada:
 *   - rodada 1: 5 tentativas (limiteSeNaoCapturado). Se o jogador já tinha o lendário
 *     capturado na Pokédex antes de começar, o limite é 1 (limiteSeCapturado).
 *   - /adicionar libera mais uma rodada de 1 a 5 tentativas (no máximo 2 vezes por jogador e item).
 *   - capturou o lendário durante a rodada: o limite da rodada vira 1 (fica 3/1, 4/1...).
 * Itens sem espécie em "itemSpecies" usam o número fixo de "limits".
 */
public final class Limites {
    private static MinecraftServer server;

    private Limites() {}

    /** null = item não é limitado. */
    public static Integer limite(UUID jogador, String itemId, UsageData data) {
        LimitConfig cfg = LimitConfig.get();
        if (!cfg.limits.containsKey(itemId)) return null;
        if (!temEspecie(itemId)) {
            int extra = data.getLimiteRodada(jogador, itemId);
            return extra > 0 ? extra : cfg.limits.get(itemId);
        }
        if (capturouNaRodada(jogador, itemId, data)) return cfg.limiteSeCapturado;
        int rodada = data.getLimiteRodada(jogador, itemId);
        return rodada > 0 ? rodada : cfg.limiteSeNaoCapturado;
    }

    public static boolean temEspecie(String itemId) {
        List<String> e = LimitConfig.get().itemSpecies.get(itemId);
        return e != null && !e.isEmpty();
    }

    /** Capturou nesta rodada; na primeira rodada também conta quem já tinha na Pokédex. */
    public static boolean capturouNaRodada(UUID jogador, String itemId, UsageData data) {
        if (data.isCapturado(jogador, itemId)) return true;
        return data.getAdicionados(jogador, itemId) == 0 && capturouNaPokedex(jogador, itemId);
    }

    /** A rodada atual acabou (capturou ou gastou as tentativas). */
    public static boolean rodadaEncerrada(UUID jogador, String itemId, UsageData data) {
        Integer lim = limite(jogador, itemId, data);
        return lim != null && data.getUses(jogador, itemId) >= lim;
    }

    // ---------------- Pokédex ----------------

    public static boolean capturouNaPokedex(UUID jogador, String itemId) {
        List<String> especies = LimitConfig.get().itemSpecies.get(itemId);
        if (especies == null) return false;
        try {
            Object dex = gerente().getClass().getMethod("getPokedexData", UUID.class).invoke(gerente(), jogador);
            Method knowledge = null;
            for (Method m : dex.getClass().getMethods()) {
                if (m.getName().equals("getKnowledgeForSpecies") && m.getParameterCount() == 1) { knowledge = m; break; }
            }
            if (knowledge == null) return false;
            for (String esp : especies) {
                Object prog = knowledge.invoke(dex, idEspecie(esp));
                if (prog != null && String.valueOf(prog).equals("OWNED")) return true;
            }
        } catch (Throwable t) {
            LegendLimitMod.LOGGER.warn("[legendlimit] Não consegui ler a Pokédex de {}: {}", jogador, t.toString());
        }
        return false;
    }

    private static Object gerente() throws Exception {
        Class<?> cobblemon = Class.forName("com.cobblemon.mod.common.Cobblemon");
        Object instance = cobblemon.getField("INSTANCE").get(null);
        return cobblemon.getMethod("getPlayerDataManager").invoke(instance);
    }

    private static Identifier idEspecie(String esp) {
        return esp.contains(":") ? Identifier.of(esp.toLowerCase(Locale.ROOT)) : Identifier.of("cobblemon", esp.toLowerCase(Locale.ROOT));
    }

    // ---------------- captura (evento do Cobblemon) ----------------

    /** Marca "capturou nesta rodada" quando o jogador captura o lendário de um item limitado. */
    public static void registrarCaptura() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
        try {
            Class<?> events = Class.forName("com.cobblemon.mod.common.api.events.CobblemonEvents");
            Object observable = events.getField("POKEMON_CAPTURED").get(null);
            Class<?> priority = Class.forName("com.cobblemon.mod.common.api.Priority");
            Object normal = priority.getField("NORMAL").get(null);
            Consumer<Object> handler = Limites::aoCapturar;
            observable.getClass().getMethod("subscribe", priority, Consumer.class).invoke(observable, normal, handler);
        } catch (Throwable t) {
            LegendLimitMod.LOGGER.error("[legendlimit] Não consegui assinar o evento de captura do Cobblemon", t);
        }
    }

    private static void aoCapturar(Object evento) {
        try {
            Object pokemon = evento.getClass().getMethod("getPokemon").invoke(evento);
            Object player = evento.getClass().getMethod("getPlayer").invoke(evento);
            if (!(player instanceof ServerPlayerEntity sp) || server == null) return;
            Object species = pokemon.getClass().getMethod("getSpecies").invoke(pokemon);
            String id = String.valueOf(species.getClass().getMethod("getResourceIdentifier").invoke(species));
            String nome = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            UsageData data = UsageData.get(server);
            for (Map.Entry<String, List<String>> e : LimitConfig.get().itemSpecies.entrySet()) {
                if (!LimitConfig.get().limits.containsKey(e.getKey())) continue;
                for (String esp : e.getValue()) {
                    String alvo = esp.contains(":") ? esp.substring(esp.indexOf(':') + 1) : esp;
                    if (alvo.equalsIgnoreCase(nome) && !data.isCapturado(sp.getUuid(), e.getKey())) {
                        data.setCapturado(sp.getUuid(), e.getKey());
                    }
                }
            }
        } catch (Throwable t) {
            LegendLimitMod.LOGGER.warn("[legendlimit] Erro ao registrar captura: {}", t.toString());
        }
    }

    public static String descricao(UUID jogador, String itemId, UsageData data) {
        if (!temEspecie(itemId)) return "";
        int rodada = data.getAdicionados(jogador, itemId) + 1;
        return " (rodada " + rodada + "/" + (LimitConfig.get().maxAdicionar + 1)
                + (capturouNaRodada(jogador, itemId, data) ? ", capturado" : "") + ")";
    }

    public static void avisar(ServerPlayerEntity p, String msg) {
        p.sendMessage(Text.literal(msg).formatted(Formatting.GOLD), false);
    }
}
