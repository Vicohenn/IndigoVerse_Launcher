package indigo.coins;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Drop raro de Indigo Coin ao vencer trainers do RCT (trainers, líderes de ginásio e bosses).
 * Usa a API do RCT (rctapi) por reflexão: evento BATTLE_ENDED.
 */
final class RctDrops {
    private static final Set<Object> registrados = Collections.newSetFromMap(new IdentityHashMap<>());

    private RctDrops() {}

    static void registrar(MinecraftServer server) {
        try {
            Class<?> api = Class.forName("com.gitlab.srcmc.rctapi.api.RCTApi");
            Class<?> events = Class.forName("com.gitlab.srcmc.rctapi.api.events.Events");
            Class<?> eventType = Class.forName("com.gitlab.srcmc.rctapi.api.events.EventType");
            Class<?> listenerCls = Class.forName("com.gitlab.srcmc.rctapi.api.events.EventListener");
            Class<?> ctxCls = Class.forName("com.gitlab.srcmc.rctapi.api.events.EventContext");
            Object battleEnded = events.getField("BATTLE_ENDED").get(null);
            Method register = ctxCls.getMethod("register", eventType, listenerCls);
            Object listener = Proxy.newProxyInstance(RctDrops.class.getClassLoader(), new Class<?>[]{listenerCls}, (proxy, m, args) -> {
                if (m.getName().equals("notify") && args != null && args.length == 1) {
                    Object evento = args[0];
                    server.execute(() -> aoTerminar(server, evento));
                    return null;
                }
                if (m.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (m.getName().equals("equals")) return proxy == args[0];
                if (m.getName().equals("toString")) return "IndigoCoinsRctListener";
                return null;
            });
            @SuppressWarnings("unchecked")
            Stream<java.util.Map.Entry<String, Object>> inst = (Stream<java.util.Map.Entry<String, Object>>) api.getMethod("getInstances").invoke(null);
            int n = 0;
            for (java.util.Map.Entry<String, Object> e : inst.toList()) {
                Object ctx = api.getMethod("getEventContext").invoke(e.getValue());
                if (ctx != null && registrados.add(ctx)) { register.invoke(ctx, battleEnded, listener); n++; }
            }
            IndigoCoinsMod.LOGGER.info("Drop de Indigo Coin ligado em {} instância(s) do RCT.", n);
        } catch (ClassNotFoundException e) {
            IndigoCoinsMod.LOGGER.warn("RCT não encontrado: Indigo Coins não cairão de trainers.");
        } catch (Exception e) {
            IndigoCoinsMod.LOGGER.error("Não consegui ligar o drop de Indigo Coin no RCT", e);
        }
    }

    private static void aoTerminar(MinecraftServer server, Object evento) {
        try {
            Object state = evento.getClass().getMethod("getValue").invoke(evento);
            if ((boolean) state.getClass().getMethod("isEndForced").invoke(state)) return;   // fuga/encerrada à força
            List<?> vencedores = (List<?>) state.getClass().getMethod("getWinners").invoke(state);
            List<?> perdedores = (List<?>) state.getClass().getMethod("getLosers").invoke(state);
            boolean contraNpc = perdedores.stream().anyMatch(t -> t.getClass().getSimpleName().equals("TrainerNPC"));
            if (!contraNpc) return;
            for (Object t : vencedores) {
                if (!t.getClass().getSimpleName().equals("TrainerPlayer")) continue;
                ServerPlayerEntity p = (ServerPlayerEntity) t.getClass().getMethod("getPlayer").invoke(t);
                if (p == null || Moedas.RNG.nextDouble() >= Dados.config.chanceDropTrainer) continue;
                String id = Moedas.criar(p.getUuid(), p.getGameProfile().getName(), "drop:trainer", null);
                p.sendMessage(Text.literal("§6§l✦ §eVocê encontrou uma §bIndigo Coin§e! §7(nº " + id + ") §8/indigocoins"));
                server.getPlayerManager().broadcast(Text.literal("§6✦ §f" + p.getGameProfile().getName() + " §6encontrou uma §bIndigo Coin§6 ao vencer um treinador!"), false);
            }
        } catch (Exception e) {
            IndigoCoinsMod.LOGGER.error("Erro no drop de Indigo Coin", e);
        }
    }
}
