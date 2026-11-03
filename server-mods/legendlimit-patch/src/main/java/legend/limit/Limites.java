package legend.limit;

import net.minecraft.util.Identifier;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/**
 * Quantas vezes o jogador pode usar um item de invocação no altar:
 *   - lendário já CAPTURADO (registrado como capturado na Pokédex): limiteSeCapturado (1)
 *   - ainda não capturado: limiteSeNaoCapturado (5)
 *   - mais os usos extras liberados por doação (UsageData.extras)
 * Itens sem espécie em "itemSpecies" usam o número fixo de "limits".
 */
public final class Limites {
    private Limites() {}

    /** null = item não é limitado. */
    public static Integer limite(UUID jogador, String itemId, UsageData data) {
        LimitConfig cfg = LimitConfig.get();
        if (!cfg.limits.containsKey(itemId)) return null;
        int base;
        List<String> especies = cfg.itemSpecies.get(itemId);
        if (especies != null && !especies.isEmpty()) {
            base = capturou(jogador, especies) ? cfg.limiteSeCapturado : cfg.limiteSeNaoCapturado;
        } else {
            base = cfg.limits.get(itemId);
        }
        return base + data.getExtras(jogador, itemId);
    }

    /** true se alguma das espécies está como capturada (OWNED) na Pokédex do jogador. */
    public static boolean capturou(UUID jogador, List<String> especies) {
        try {
            Class<?> cobblemon = Class.forName("com.cobblemon.mod.common.Cobblemon");
            Object instance = cobblemon.getField("INSTANCE").get(null);
            Object manager = cobblemon.getMethod("getPlayerDataManager").invoke(instance);
            Object dex = manager.getClass().getMethod("getPokedexData", UUID.class).invoke(manager, jogador);
            Method knowledge = null;
            for (Method m : dex.getClass().getMethods()) {
                if (m.getName().equals("getKnowledgeForSpecies") && m.getParameterCount() == 1) { knowledge = m; break; }
            }
            if (knowledge == null) return false;
            for (String esp : especies) {
                Identifier id = esp.contains(":") ? Identifier.of(esp) : Identifier.of("cobblemon", esp);
                Object prog = knowledge.invoke(dex, id);
                if (prog != null && String.valueOf(prog).equals("OWNED")) return true;
            }
        } catch (Throwable t) {
            LegendLimitMod.LOGGER.warn("[legendlimit] Não consegui ler a Pokédex de {}: {}", jogador, t.toString());
        }
        return false;
    }

    public static boolean capturouItem(UUID jogador, String itemId) {
        List<String> especies = LimitConfig.get().itemSpecies.get(itemId);
        return especies != null && capturou(jogador, especies);
    }
}
