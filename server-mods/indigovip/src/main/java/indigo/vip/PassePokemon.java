package indigo.vip;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Recompensas de Pokémon do Trainer Pass com IVs perfeitos.
 * config/indigovip/passe-pokemon.json — chave "especie" ou "especie:shiny".
 * O Pokémon vai direto para o time (ou PC se o time estiver cheio) em vez de aparecer no mundo.
 */
public final class PassePokemon {
    private PassePokemon() {}
    private static final Logger LOG = LoggerFactory.getLogger("IndigoVIP");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class Regra {
        public int ivsPerfeitosMin = 1;
        public int ivsPerfeitosMax = 3;
    }

    public static final class Arquivo {
        public boolean ativo = true;
        public Map<String, Regra> recompensas = new LinkedHashMap<>();
    }

    private static Arquivo cfg;
    private static long lido;

    private static Path arquivo() { return VipConfig.pasta().resolve("passe-pokemon.json"); }

    private static Regra regra(int min, int max) { Regra r = new Regra(); r.ivsPerfeitosMin = min; r.ivsPerfeitosMax = max; return r; }

    static synchronized Arquivo config() {
        try {
            Path p = arquivo();
            if (!Files.exists(p)) {
                Arquivo a = new Arquivo();
                a.recompensas.put("kangaskhan", regra(1, 3));
                a.recompensas.put("gastly", regra(1, 3));
                a.recompensas.put("scyther:shiny", regra(1, 5));
                a.recompensas.put("dratini:shiny", regra(1, 5));
                Files.createDirectories(p.getParent());
                Files.writeString(p, GSON.toJson(a), StandardCharsets.UTF_8);
            }
            long m = Files.getLastModifiedTime(p).toMillis();
            if (cfg == null || m != lido) {
                cfg = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), Arquivo.class);
                if (cfg.recompensas == null) cfg.recompensas = new LinkedHashMap<>();
                lido = m;
            }
        } catch (Exception e) {
            LOG.error("[IndigoVIP] passe-pokemon.json inválido", e);
            if (cfg == null) cfg = new Arquivo();
        }
        return cfg;
    }

    private static String limpa(String s) {
        return s == null ? "" : s.toLowerCase().replace("cobblemon:", "").replaceAll("[^a-z0-9_]", "");
    }

    /** true = entregue por aqui (o Trainer Pass não faz o spawn). */
    public static boolean entregar(ServerPlayerEntity player, String especie, int nivel, boolean shiny) {
        try {
            Arquivo a = config();
            if (!a.ativo) return false;
            String sp = limpa(especie);
            Regra r = a.recompensas.get(shiny ? sp + ":shiny" : sp);
            if (r == null || sp.isEmpty()) return false;
            int min = Math.max(0, Math.min(6, r.ivsPerfeitosMin));
            int max = Math.max(min, Math.min(6, r.ivsPerfeitosMax));
            int ivs = ThreadLocalRandom.current().nextInt(min, max + 1);
            int lv = Math.max(1, Math.min(100, nivel));
            String nome = player.getGameProfile().getName();
            String cmd = "givepokemonother " + nome + " " + sp + (shiny ? " shiny" : "") + " level=" + lv
                    + (ivs > 0 ? " min_perfect_ivs=" + ivs : "");
            MinecraftServer server = player.getServer();
            int ok = server.getCommandManager().getDispatcher().execute(cmd, server.getCommandSource().withLevel(4).withSilent());
            if (ok <= 0) return false;
            String bonito = sp.substring(0, 1).toUpperCase() + sp.substring(1);
            player.sendMessage(Text.literal("§a✦ Trainer Pass: você recebeu " + (shiny ? "§6Shiny " : "§e") + bonito
                    + " §7(Nv. " + lv + ", §b" + ivs + " IV" + (ivs == 1 ? "" : "s") + " perfeito" + (ivs == 1 ? "" : "s") + "§7)§a! Confira seu time ou o PC."), false);
            LOG.info("[IndigoVIP] Trainer Pass: {} recebeu {}{} nv{} com {} IVs perfeitos", nome, shiny ? "shiny " : "", sp, lv, ivs);
            return true;
        } catch (Exception e) {
            LOG.error("[IndigoVIP] Falha ao entregar Pokémon do Trainer Pass; usando o padrão do mod", e);
            return false;
        }
    }
}
