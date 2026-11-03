package indigo.guard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Contas no mesmo IP. Guarda IP -> (UUID -> nick) em config/indigoguard-contas.json.
 * Só avisa; quem decide é a staff (botões no Discord).
 */
public final class Contas {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, Map<String, String>> porIp = new LinkedHashMap<>();
    /** combinações já avisadas (ip + conjunto de contas), para não repetir a cada login */
    private static final Set<String> AVISADOS = new HashSet<>();

    private Contas() {}

    private static Path arquivo() { return FabricLoader.getInstance().getConfigDir().resolve("indigoguard-contas.json"); }

    public static void carregar() {
        try {
            if (Files.exists(arquivo())) {
                try (Reader r = Files.newBufferedReader(arquivo(), StandardCharsets.UTF_8)) {
                    Map<String, Map<String, String>> m = GSON.fromJson(r, new TypeToken<Map<String, Map<String, String>>>() {}.getType());
                    if (m != null) porIp = m;
                }
            }
        } catch (Exception e) {
            IndigoGuardMod.LOGGER.error("IndigoGuard: erro lendo indigoguard-contas.json", e);
        }
    }

    private static void salvar() {
        try (Writer w = Files.newBufferedWriter(arquivo(), StandardCharsets.UTF_8)) {
            GSON.toJson(porIp, w);
        } catch (Exception e) {
            IndigoGuardMod.LOGGER.error("IndigoGuard: erro salvando indigoguard-contas.json", e);
        }
    }

    public static void entrou(MinecraftServer server, ServerPlayerEntity p) {
        if (!GuardConfig.get().contasAtivo) return;
        String ip = p.getIp();
        if (ip == null || ip.isBlank()) return;
        String uuid = p.getUuid().toString();
        String nick = p.getGameProfile().getName();
        Map<String, String> contas = porIp.computeIfAbsent(ip, k -> new LinkedHashMap<>());
        if (!nick.equals(contas.put(uuid, nick))) salvar();
        if (contas.size() < 2 || Alertas.ehStaffOnline(p)) return;

        String chave = ip + "|" + String.join(",", new java.util.TreeSet<>(contas.keySet()));
        if (!AVISADOS.add(chave)) return;

        List<String> extras = new ArrayList<>();
        extras.add("Contas no mesmo IP (" + contas.size() + "):");
        for (Map.Entry<String, String> c : contas.entrySet()) {
            extras.add("  " + c.getValue() + " — " + c.getKey() + (c.getKey().equals(uuid) ? " (entrou agora)" : ""));
        }
        Alertas.alertar(server, Alertas.Tipo.CONTAS, nick, p.getUuid(), Alertas.local(p), extras);
    }
}
