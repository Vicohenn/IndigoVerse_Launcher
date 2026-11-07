package indigo.ranked;

import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Acesso ao Cobblemon por reflexão (sem dependência de compilação). */
final class Cobble {
    private static Object storage, builder, formatCompanion;
    private static Method mGetParty, mPvp, mSingles, mDoubles, mCopy, mGetMod, mGetType, mGetGen, mBattleDoJogador;
    private static Method mSpecies, mForm, mHeld, mMoveSet, mMoves, mMoveName, mPkUuid, mSpName, mSpDex, mSpLabels, mFormLabels, mDisplayName;
    private static Method mBattleId, mTurn, mEnded, mStop, mWinners, mLosers, mActorPlayers, mEvBattle, mFledPlayer, mActorUuid;
    static boolean ok;

    /** Dados de um Pokémon da equipe, já lidos. */
    record Pk(UUID uuid, String especie, String nome, int dex, Set<String> rotulos, String item, List<String> golpes) {}

    private Cobble() {}

    static void iniciar(Consumer<Object> aoVencer, Consumer<Object> aoFugir) {
        try {
            Class<?> cob = Class.forName("com.cobblemon.mod.common.Cobblemon");
            storage = cob.getMethod("getStorage").invoke(cob.getField("INSTANCE").get(null));
            for (Method m : storage.getClass().getMethods())
                if (m.getName().equals("getParty") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(ServerPlayerEntity.class)) mGetParty = m;

            Class<?> pk = Class.forName("com.cobblemon.mod.common.pokemon.Pokemon");
            mSpecies = pk.getMethod("getSpecies"); mForm = pk.getMethod("getForm"); mHeld = pk.getMethod("heldItem");
            mMoveSet = pk.getMethod("getMoveSet"); mPkUuid = pk.getMethod("getUuid");
            mDisplayName = pk.getMethod("getDisplayName", boolean.class);
            Class<?> sp = Class.forName("com.cobblemon.mod.common.pokemon.Species");
            mSpName = sp.getMethod("getName"); mSpDex = sp.getMethod("getNationalPokedexNumber"); mSpLabels = sp.getMethod("getLabels");
            mFormLabels = Class.forName("com.cobblemon.mod.common.pokemon.FormData").getMethod("getLabels");
            mMoves = Class.forName("com.cobblemon.mod.common.api.moves.MoveSet").getMethod("getMoves");
            mMoveName = Class.forName("com.cobblemon.mod.common.api.moves.Move").getMethod("getName");

            Class<?> bf = Class.forName("com.cobblemon.mod.common.battles.BattleFormat");
            formatCompanion = bf.getField("Companion").get(null);
            mSingles = formatCompanion.getClass().getMethod("getGEN_9_SINGLES");
            mDoubles = formatCompanion.getClass().getMethod("getGEN_9_DOUBLES");
            Class<?> bt = Class.forName("com.cobblemon.mod.common.battles.BattleType");
            mCopy = bf.getMethod("copy", String.class, bt, Set.class, int.class, int.class);
            mGetMod = bf.getMethod("getMod"); mGetType = bf.getMethod("getBattleType"); mGetGen = bf.getMethod("getGen");

            Class<?> bb = Class.forName("com.cobblemon.mod.common.battles.BattleBuilder");
            builder = bb.getField("INSTANCE").get(null);
            mPvp = bb.getMethod("pvp1v1", ServerPlayerEntity.class, ServerPlayerEntity.class, UUID.class, UUID.class, bf, boolean.class, boolean.class);
            Class<?> reg = Class.forName("com.cobblemon.mod.common.battles.BattleRegistry");
            mBattleDoJogador = reg.getMethod("getBattleByParticipatingPlayer", ServerPlayerEntity.class);

            Class<?> pb = Class.forName("com.cobblemon.mod.common.api.battles.model.PokemonBattle");
            mBattleId = pb.getMethod("getBattleId"); mTurn = pb.getMethod("getTurn"); mEnded = pb.getMethod("getEnded"); mStop = pb.getMethod("stop");
            Class<?> vic = Class.forName("com.cobblemon.mod.common.api.events.battles.BattleVictoryEvent");
            mWinners = vic.getMethod("getWinners"); mLosers = vic.getMethod("getLosers"); mEvBattle = vic.getMethod("getBattle");
            Class<?> fled = Class.forName("com.cobblemon.mod.common.api.events.battles.BattleFledEvent");
            mFledPlayer = fled.getMethod("getPlayer");
            Class<?> actor = Class.forName("com.cobblemon.mod.common.api.battles.model.actor.BattleActor");
            mActorPlayers = actor.getMethod("getPlayerUUIDs"); mActorUuid = actor.getMethod("getUuid");

            Class<?> events = Class.forName("com.cobblemon.mod.common.api.events.CobblemonEvents");
            Class<?> prio = Class.forName("com.cobblemon.mod.common.api.Priority");
            Object normal = prio.getField("NORMAL").get(null);
            Object victory = events.getField("BATTLE_VICTORY").get(null);
            victory.getClass().getMethod("subscribe", prio, Consumer.class).invoke(victory, normal, aoVencer);
            Object fledObs = events.getField("BATTLE_FLED").get(null);
            fledObs.getClass().getMethod("subscribe", prio, Consumer.class).invoke(fledObs, normal, aoFugir);
            ok = true;
        } catch (Exception e) {
            IndigoRankedMod.LOGGER.error("Não consegui ligar a ranqueada ao Cobblemon", e);
        }
    }

    // ---------- equipe ----------

    static List<Pk> equipe(ServerPlayerEntity p) {
        List<Pk> out = new ArrayList<>();
        try {
            Object party = mGetParty.invoke(storage, p);
            for (Object pk : (Iterable<?>) party) {
                if (pk == null) continue;
                Object sp = mSpecies.invoke(pk);
                Set<String> rot = new HashSet<>();
                for (Object o : (Iterable<?>) mSpLabels.invoke(sp)) rot.add(String.valueOf(o).toLowerCase());
                try { for (Object o : (Iterable<?>) mFormLabels.invoke(mForm.invoke(pk))) rot.add(String.valueOf(o).toLowerCase()); } catch (Exception ignored) {}
                ItemStack held = (ItemStack) mHeld.invoke(pk);
                String item = held == null || held.isEmpty() ? "" : Registries.ITEM.getId(held.getItem()).toString();
                List<String> golpes = new ArrayList<>();
                for (Object mv : (Iterable<?>) mMoves.invoke(mMoveSet.invoke(pk)))
                    if (mv != null) golpes.add(String.valueOf(mMoveName.invoke(mv)).toLowerCase());
                String nome;
                try { nome = ((net.minecraft.text.Text) mDisplayName.invoke(pk, false)).getString(); } catch (Exception e) { nome = String.valueOf(mSpName.invoke(sp)); }
                out.add(new Pk((UUID) mPkUuid.invoke(pk), String.valueOf(mSpName.invoke(sp)).toLowerCase(), nome,
                        (Integer) mSpDex.invoke(sp), rot, item, golpes));
            }
        } catch (Exception e) {
            IndigoRankedMod.LOGGER.error("Erro lendo a equipe de {}", p.getGameProfile().getName(), e);
        }
        return out;
    }

    // ---------- batalha ----------

    static boolean emBatalha(ServerPlayerEntity p) {
        try { return mBattleDoJogador.invoke(null, p) != null; } catch (Exception e) { return false; }
    }

    /** Começa a batalha (equipes clonadas, curadas e no nível do regulamento). Devolve a batalha ou o erro (String). */
    static Object iniciarBatalha(ServerPlayerEntity a, ServerPlayerEntity b, Regulamento reg, int nivel) {
        try {
            Object base = (reg.duplas() ? mDoubles : mSingles).invoke(formatCompanion);
            Set<String> regras = new HashSet<>(reg.regrasShowdown == null ? List.of() : reg.regrasShowdown);
            Object formato = mCopy.invoke(base, mGetMod.invoke(base), mGetType.invoke(base), regras, mGetGen.invoke(base), nivel);
            Object res = mPvp.invoke(builder, a, b, null, null, formato, true, true);
            try {
                return res.getClass().getMethod("getBattle").invoke(res);
            } catch (NoSuchMethodException e) {
                return "o Cobblemon recusou a batalha (" + res.getClass().getSimpleName() + ")";
            }
        } catch (Exception e) {
            IndigoRankedMod.LOGGER.error("Erro iniciando batalha ranqueada", e);
            return "erro ao iniciar a batalha: " + e;
        }
    }

    static UUID idBatalha(Object battle) {
        try { return (UUID) mBattleId.invoke(battle); } catch (Exception e) { return null; }
    }
    static int turno(Object battle) {
        try { return (Integer) mTurn.invoke(battle); } catch (Exception e) { return 0; }
    }
    static boolean terminou(Object battle) {
        try { return (Boolean) mEnded.invoke(battle); } catch (Exception e) { return true; }
    }
    static void parar(Object battle) {
        try { mStop.invoke(battle); } catch (Exception e) { IndigoRankedMod.LOGGER.warn("Não consegui parar a batalha: {}", e.toString()); }
    }

    // ---------- eventos ----------

    static Object batalhaDoEvento(Object ev) {
        try { return ev.getClass().getMethod("getBattle").invoke(ev); } catch (Exception e) { return null; }
    }

    /** UUIDs dos jogadores vencedores de um BattleVictoryEvent. */
    static Set<UUID> vencedores(Object ev) { return jogadoresDe(ev, mWinners); }
    static Set<UUID> perdedores(Object ev) { return jogadoresDe(ev, mLosers); }

    private static Set<UUID> jogadoresDe(Object ev, Method lista) {
        Set<UUID> out = new HashSet<>();
        try {
            for (Object actor : (Iterable<?>) lista.invoke(ev))
                for (Object u : (Iterable<?>) mActorPlayers.invoke(actor)) out.add((UUID) u);
        } catch (Exception e) {
            IndigoRankedMod.LOGGER.error("Erro lendo resultado da batalha", e);
        }
        return out;
    }

    static UUID quemFugiu(Object ev) {
        try { return (UUID) mActorUuid.invoke(mFledPlayer.invoke(ev)); } catch (Exception e) { return null; }
    }
}
