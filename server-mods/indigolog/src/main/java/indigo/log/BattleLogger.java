package indigo.log;

import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Registra batalhas contra Líderes de Ginásio, Elite dos 4 e Campeões do Radical Cobblemon Trainers.
 *
 *   Nick iniciou uma batalha contra Líder de Ginásio (Brock) às 05:40 — Resultado: EM ANDAMENTO...
 *   Nick finalizou uma batalha contra Líder de Ginásio (Brock) às 05:48 — Resultado: Vitória
 *
 * Usa os eventos do Cobblemon (BATTLE_STARTED_POST, BATTLE_VICTORY, BATTLE_FLED) por reflexão,
 * para o mod compilar sem o Cobblemon/RCT no classpath (igual ao legendlimit).
 */
public final class BattleLogger {

    private record Batalha(Object battle, List<UUID> jogadores, List<String> nomes, String tipo, String treinador) {}

    private final Map<UUID, Batalha> emAndamento = new ConcurrentHashMap<>();
    private boolean registrado;
    private int ticks;

    public void register() {
        if (registrado || !LogConfig.get().logBatalhas) return;
        try {
            Class<?> events = Class.forName("com.cobblemon.mod.common.api.events.CobblemonEvents");
            subscribe(events, "BATTLE_STARTED_POST", this::onStart);
            subscribe(events, "BATTLE_VICTORY", this::onVictory);
            subscribe(events, "BATTLE_FLED", this::onFled);
            registrado = true;
            IndigoLogMod.LOGGER.info("IndigoLog: monitorando batalhas contra líderes, Elite dos 4 e campeões.");
        } catch (Throwable t) {
            IndigoLogMod.LOGGER.error("IndigoLog: não consegui assinar os eventos de batalha do Cobblemon", t);
        }
    }

    private static void subscribe(Class<?> events, String campo, Consumer<Object> handler) throws Exception {
        Object observable = events.getField(campo).get(null);
        Class<?> priority = Class.forName("com.cobblemon.mod.common.api.Priority");
        Object normal = priority.getField("NORMAL").get(null);
        Method sub = observable.getClass().getMethod("subscribe", priority, Consumer.class);
        sub.invoke(observable, normal, handler);
    }

    // ---------- eventos ----------

    private void onStart(Object event) {
        try {
            Object battle = call(event, "getBattle");
            String tipo = null, treinador = null;
            for (Object actor : (Iterable<?>) call(battle, "getActors")) {
                Entity entidade = entidadeDoAtor(actor);
                if (entidade == null || !isTrainerMob(entidade)) continue;
                String t = tipoDoTreinador(entidade);
                if (t != null) {
                    tipo = t;
                    treinador = entidade.getName().getString();
                    break;
                }
            }
            if (tipo == null) return;   // batalha comum / treinador que não interessa

            List<UUID> uuids = new ArrayList<>();
            List<String> nomes = new ArrayList<>();
            for (Object p : (List<?>) call(battle, "getPlayers")) {
                ServerPlayerEntity sp = (ServerPlayerEntity) p;
                uuids.add(sp.getUuid());
                nomes.add(sp.getGameProfile().getName());
            }
            if (uuids.isEmpty()) return;

            UUID id = (UUID) call(battle, "getBattleId");
            emAndamento.put(id, new Batalha(battle, uuids, nomes, tipo, treinador));
            DiscordWebhook.BATALHAS.enviar("⚔️ `" + DiscordWebhook.agora() + "` **" + String.join(", ", nomes)
                    + "** iniciou uma batalha contra **" + tipo + "** (" + treinador + ") — Resultado: **EM ANDAMENTO...**");
        } catch (Throwable t) {
            IndigoLogMod.LOGGER.warn("IndigoLog: erro no início de batalha: {}", t.toString());
        }
    }

    private void onVictory(Object event) {
        try {
            Object battle = call(event, "getBattle");
            Batalha b = emAndamento.remove((UUID) call(battle, "getBattleId"));
            if (b == null) return;
            boolean venceu = false;
            for (Object actor : (List<?>) call(event, "getWinners")) {
                Object uuid = call(actor, "getUuid");
                if (b.jogadores.contains(uuid)) { venceu = true; break; }
            }
            fim(b, venceu ? "✅ Vitória" : "❌ Derrota");
        } catch (Throwable t) {
            IndigoLogMod.LOGGER.warn("IndigoLog: erro no fim de batalha: {}", t.toString());
        }
    }

    private void onFled(Object event) {
        try {
            Object battle = call(event, "getBattle");
            Batalha b = emAndamento.remove((UUID) call(battle, "getBattleId"));
            if (b != null) fim(b, "❌ Derrota (fugiu da batalha)");
        } catch (Throwable t) {
            IndigoLogMod.LOGGER.warn("IndigoLog: erro em fuga de batalha: {}", t.toString());
        }
    }

    /** A cada 5 s: batalhas que terminaram sem evento de vitória (queda, servidor desligando...). */
    public void tick(MinecraftServer server) {
        if (++ticks % 100 != 0 || emAndamento.isEmpty()) return;
        emAndamento.entrySet().removeIf(e -> {
            try {
                if ((Boolean) call(e.getValue().battle, "getEnded")) {
                    fim(e.getValue(), "⚠️ Encerrada sem resultado (desconexão ou batalha cancelada)");
                    return true;
                }
            } catch (Throwable ignored) { }
            return false;
        });
    }

    private void fim(Batalha b, String resultado) {
        DiscordWebhook.BATALHAS.enviar("🏁 `" + DiscordWebhook.agora() + "` **" + String.join(", ", b.nomes)
                + "** finalizou uma batalha contra **" + b.tipo + "** (" + b.treinador + ") — Resultado: **" + resultado + "**");
    }

    // ---------- RCT ----------

    private static Entity entidadeDoAtor(Object actor) {
        try {
            Object e = actor.getClass().getMethod("getEntity").invoke(actor);
            return e instanceof Entity ent ? ent : null;
        } catch (NoSuchMethodException e) {
            return null;   // ator sem entidade (Pokémon selvagem etc.)
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isTrainerMob(Entity e) {
        return e.getClass().getName().equals("com.gitlab.srcmc.rctmod.world.entities.TrainerMob");
    }

    /** Tipo do treinador no datapack do RCT ("kanto", "kanto_league"...) convertido pelo config. */
    private static String tipoDoTreinador(Entity trainerMob) {
        try {
            Class<?> rct = Class.forName("com.gitlab.srcmc.rctmod.api.RCTMod");
            Object mod = rct.getMethod("getInstance").invoke(null);
            Object manager = mod.getClass().getMethod("getTrainerManager").invoke(mod);
            Method getData = manager.getClass().getMethod("getData", trainerMob.getClass());
            Object data = getData.invoke(manager, trainerMob);
            if (data == null) return null;
            Object type = data.getClass().getMethod("getType").invoke(data);
            String id = (String) type.getClass().getMethod("id").invoke(type);
            return id == null ? null : LogConfig.get().tiposDeTreinador.get(id);
        } catch (Exception e) {
            IndigoLogMod.LOGGER.debug("RCT: {}", e.toString());
            return null;
        }
    }

    private static Object call(Object alvo, String metodo) throws Exception {
        return alvo.getClass().getMethod(metodo).invoke(alvo);
    }
}
