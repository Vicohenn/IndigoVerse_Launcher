package indigo.guildtags;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Ponte para o mod de guildas (guild.manager.*) e para o Flan, por reflexão:
 * assim este mod não precisa deles para compilar.
 */
public final class Guildas {
    private static Method gmGet, getGuildOf, getAllGuilds, getName;
    private static Method flanGet, flanClaimAt, flanOwner;
    private static boolean flanAusente;

    private Guildas() {}

    private static void init() throws Exception {
        if (gmGet != null) return;
        Class<?> gm = Class.forName("guild.manager.GuildManager");
        Class<?> g = Class.forName("guild.manager.Guild");
        gmGet = gm.getMethod("get", MinecraftServer.class);
        getGuildOf = gm.getMethod("getGuildOf", UUID.class);
        getAllGuilds = gm.getMethod("getAllGuilds");
        getName = g.getMethod("getName");
    }

    /** Nome da guilda do jogador (ou null). */
    public static String guildaDe(MinecraftServer server, UUID jogador) {
        try {
            init();
            Object guild = getGuildOf.invoke(gmGet.invoke(null, server), jogador);
            return guild == null ? null : (String) getName.invoke(guild);
        } catch (Exception e) {
            GuildTagsMod.LOGGER.warn("Não consegui consultar o mod de guildas: {}", e.toString());
            return null;
        }
    }

    public static List<String> todas(MinecraftServer server) {
        List<String> nomes = new ArrayList<>();
        try {
            init();
            for (Object g : (Collection<?>) getAllGuilds.invoke(gmGet.invoke(null, server))) nomes.add((String) getName.invoke(g));
        } catch (Exception ignored) { }
        return nomes;
    }

    /** A guilda tem a categoria? */
    public static boolean temCategoria(String guilda, String categoria) {
        if (guilda == null) return false;
        List<String> lista = TagsConfig.get().guildas.get(categoria);
        if (lista == null) return false;
        for (String g : lista) if (g.equalsIgnoreCase(guilda)) return true;
        return false;
    }

    /** O jogador pode usar a categoria? (ops passam direto, se configurado) */
    public static boolean podeUsar(ServerPlayerEntity player, String categoria) {
        if (TagsConfig.get().opsIgnoramRestricoes && player.hasPermissionLevel(2)) return true;
        return temCategoria(guildaDe(player.getServer(), player.getUuid()), categoria);
    }

    /** Alguma das categorias da lista pertence à guilda do jogador? */
    public static String algumaCategoria(ServerPlayerEntity player, List<String> categorias) {
        if (TagsConfig.get().opsIgnoramRestricoes && player.hasPermissionLevel(2)) return null;
        String guilda = guildaDe(player.getServer(), player.getUuid());
        if (guilda == null) return null;
        for (String c : categorias) if (temCategoria(guilda, c)) return c;
        return null;
    }

    /** Dono do claim do Flan na posição (ou null se não houver claim / Flan ausente). */
    public static UUID donoDoClaim(ServerWorld world, BlockPos pos) {
        if (flanAusente) return null;
        try {
            if (flanGet == null) {
                Class<?> st = Class.forName("io.github.flemmli97.flan.claim.ClaimStorage");
                flanGet = st.getMethod("get", ServerWorld.class);
                flanClaimAt = st.getMethod("getClaimAt", BlockPos.class);
                flanOwner = Class.forName("io.github.flemmli97.flan.claim.Claim").getMethod("getOwner");
            }
            Object claim = flanClaimAt.invoke(flanGet.invoke(null, world), pos);
            return claim == null ? null : (UUID) flanOwner.invoke(claim);
        } catch (ClassNotFoundException e) {
            flanAusente = true;
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String chave(String guilda) {
        return guilda.toLowerCase(Locale.ROOT);
    }
}
