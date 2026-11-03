package indigo.guard;

import net.minecraft.server.MinecraftServer;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

/** Ban automático conforme o tipo de infração. Nunca pune OP/staff (essas infrações já ignoram eles). */
public final class Punicoes {
    private Punicoes() {}

    /** Devolve o texto da punição aplicada, ou null se o tipo não pune sozinho. */
    public static String aplicar(MinecraftServer server, Alertas.Tipo tipo, String nick, UUID uuid) {
        GuardConfig cfg = GuardConfig.get();
        if (uuid == null || uuid.getMostSignificantBits() == 0 && uuid.getLeastSignificantBits() == 0) return null;
        Date fim;
        switch (tipo.punicao) {
            case NENHUMA -> { return null; }
            case PERMANENTE -> fim = cfg.dupeBanPermanente ? null : diasAFrente(cfg.banAutomaticoDias);
            default -> {
                if (cfg.banAutomaticoDias <= 0) return null;
                fim = diasAFrente(cfg.banAutomaticoDias);
            }
        }
        if (tipo.punicao == Alertas.Punicao.PERMANENTE && !cfg.dupeBanPermanente && cfg.banAutomaticoDias <= 0) return null;
        Acoes.banir(server, uuid, nick, fim, "IndigoGuard: " + tipo.nome);
        return fim == null ? "ban permanente" : "ban até " + new SimpleDateFormat("dd/MM/yyyy HH:mm").format(fim) + " (aguardando revisão da staff)";
    }

    private static Date diasAFrente(int dias) {
        return new Date(System.currentTimeMillis() + dias * 86_400_000L);
    }
}
