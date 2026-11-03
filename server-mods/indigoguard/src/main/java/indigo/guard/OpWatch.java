package indigo.guard;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Avisa quando alguém que não é da staff dá ou recebe OP. */
public final class OpWatch {
    private static final Set<String> JA_AVISADOS = new HashSet<>();
    private static int ticks;

    private OpWatch() {}

    /** Primeira vez: se a lista de staff estiver vazia, usa os OPs atuais. */
    public static void iniciar(MinecraftServer server) {
        GuardConfig cfg = GuardConfig.get();
        if (cfg.staff.isEmpty()) {
            for (String nome : server.getPlayerManager().getOpList().getNames()) cfg.staff.add(nome);
            GuardConfig.save();
            IndigoGuardMod.LOGGER.info("IndigoGuard: staff inicial = OPs atuais {}", cfg.staff);
        }
    }

    /** A cada 2 s confere a lista de OPs (pega OP dado pelo console ou editando o ops.json). */
    public static void tick(MinecraftServer server) {
        if (++ticks % 40 != 0) return;
        for (String nome : server.getPlayerManager().getOpList().getNames()) {
            Optional<GameProfile> perfil = server.getUserCache() == null ? Optional.empty() : server.getUserCache().findByName(nome);
            UUID uuid = perfil.map(GameProfile::getId).orElse(null);
            if (GuardConfig.get().ehStaff(nome, uuid)) continue;
            if (!JA_AVISADOS.add(nome.toLowerCase())) continue;
            ServerPlayerEntity online = server.getPlayerManager().getPlayer(nome);
            Alertas.alertar(server, Alertas.Tipo.OP, nome, uuid == null ? new UUID(0, 0) : uuid,
                    online == null ? "offline" : Alertas.local(online),
                    List.of("Está na lista de OP e não é da staff."));
        }
    }

    /** Chamado pelo mixin do comando /op. */
    public static void comandoOp(ServerCommandSource source, Collection<GameProfile> alvos) {
        MinecraftServer server = source.getServer();
        GuardConfig cfg = GuardConfig.get();
        ServerPlayerEntity quemDeu = source.getPlayer();
        String nomeQuemDeu = quemDeu == null ? "Console" : quemDeu.getGameProfile().getName();
        StringBuilder nomes = new StringBuilder();
        for (GameProfile g : alvos) nomes.append(nomes.length() == 0 ? "" : ", ").append(g.getName());

        if (quemDeu != null && !cfg.ehStaff(nomeQuemDeu, quemDeu.getUuid())) {
            Alertas.alertar(server, Alertas.Tipo.OP, nomeQuemDeu, quemDeu.getUuid(), Alertas.local(quemDeu),
                    List.of("Deu OP sem ser da staff para: " + nomes));
        }
        for (GameProfile g : alvos) {
            if (cfg.ehStaff(g.getName(), g.getId())) continue;
            JA_AVISADOS.add(g.getName().toLowerCase());
            ServerPlayerEntity online = server.getPlayerManager().getPlayer(g.getId());
            Alertas.alertar(server, Alertas.Tipo.OP, g.getName(), g.getId(), online == null ? "offline" : Alertas.local(online),
                    List.of("Recebeu OP de " + nomeQuemDeu + " e não é da staff."));
        }
    }
}
