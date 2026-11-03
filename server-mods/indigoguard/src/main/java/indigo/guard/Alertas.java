package indigo.guard;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monta e envia os alertas.
 * Duplicação/dinheiro e mineração: só log no Discord.
 * O resto (voo, velocidade, contas no mesmo IP, OP): log + @staff + 4 botões + aviso para a staff no jogo.
 */
public final class Alertas {
    public enum Tipo {
        VOO("Voo (ficou no ar sem estar montado, nadando ou de elytra)", true),
        VELOCIDADE("Velocidade acima do possível", true),
        CONTAS("Contas no mesmo IP", true),
        OP("OP fora da staff", true),
        DINHEIRO("Dinheiro acima do previsto (duplicação/spawn?)", false),
        ITENS("Itens raros duplicados/spawnados?", false),
        MINERACAO("Mineração suspeita", false);

        public final String nome;
        public final boolean chamaStaff;

        Tipo(String nome, boolean chamaStaff) {
            this.nome = nome;
            this.chamaStaff = chamaStaff;
        }
    }

    private static final Map<String, Long> ULTIMO = new ConcurrentHashMap<>();

    private Alertas() {}

    /** Ignora OP (nível 2+) e a lista de staff. */
    public static boolean ehStaffOnline(ServerPlayerEntity p) {
        return p.hasPermissionLevel(2) || GuardConfig.get().ehStaff(p.getGameProfile().getName(), p.getUuid());
    }

    public static void alertar(MinecraftServer server, Tipo tipo, String nick, UUID uuid, String local, List<String> extras) {
        String chave = tipo + ":" + uuid;
        long agora = System.currentTimeMillis();
        Long antes = ULTIMO.get(chave);
        if (antes != null && agora - antes < GuardConfig.get().cooldownSegundos * 1000L) return;
        ULTIMO.put(chave, agora);

        StringBuilder sb = new StringBuilder();
        sb.append("***SUSPEITO***\n");
        sb.append("**Nick:** ").append(nick).append('\n');
        sb.append("**UUID:** ").append(uuid).append('\n');
        sb.append("**Tipo de infração:** ").append(tipo.nome).append('\n');
        sb.append("**Local:** ").append(local == null ? "—" : local);
        for (String e : extras) sb.append("\n• ").append(e);

        IndigoGuardMod.LOGGER.warn("[IndigoGuard] {} | {} ({}) | {} | {}", tipo.nome, nick, uuid, local, String.join(" | ", extras));
        Discord.enviarAlerta(sb.toString(), tipo.chamaStaff, uuid);

        if (tipo.chamaStaff) {
            String msg = "§8[§cIndigoGuard§8] §c" + nick + " §f— " + tipo.nome + (local == null ? "" : " §7em " + local)
                    + (extras.isEmpty() ? "" : "\n§7" + String.join("\n§7", extras));
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                if (ehStaffOnline(p)) p.sendMessage(Text.literal(msg), false);
            }
        }
    }

    public static String local(ServerPlayerEntity p) {
        String dim = p.getWorld().getRegistryKey().getValue().getPath();
        return String.format("%d %d %d (%s)", p.getBlockX(), p.getBlockY(), p.getBlockZ(), dim);
    }
}
