package indigo.guard;

import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Conta minérios raros quebrados em 10 minutos; acima do limite = alerta no log (sem chamar a staff). */
public final class Mineracao {
    private static final long JANELA_MS = 10 * 60_000L;
    private static final Map<String, Deque<Long>> QUEBRAS = new ConcurrentHashMap<>();

    private Mineracao() {}

    public static void sair(UUID id) {
        QUEBRAS.keySet().removeIf(k -> k.startsWith(id.toString()));
    }

    public static void quebrou(ServerPlayerEntity p, BlockState state) {
        GuardConfig cfg = GuardConfig.get();
        if (!cfg.mineracaoAtivo || Alertas.ehStaffOnline(p) || p.isCreative()) return;
        String id = Registries.BLOCK.getId(state.getBlock()).toString();
        for (Map.Entry<String, List<String>> g : cfg.grupoMinerios.entrySet()) {
            if (!bate(id, g.getValue())) continue;
            String chave = p.getUuid() + "|" + g.getKey();
            Deque<Long> d = QUEBRAS.computeIfAbsent(chave, k -> new ArrayDeque<>());
            long agora = System.currentTimeMillis();
            d.addLast(agora);
            while (!d.isEmpty() && agora - d.peekFirst() > JANELA_MS) d.pollFirst();
            int limite = cfg.limiteMinerios.getOrDefault(g.getKey(), Integer.MAX_VALUE);
            if (d.size() >= limite) {
                Alertas.alertar(p.getServer(), Alertas.Tipo.MINERACAO, p.getGameProfile().getName(), p.getUuid(), Alertas.local(p),
                        List.of(d.size() + " blocos de " + g.getKey() + " em 10 minutos (limite " + limite + ")",
                                "Possível x-ray: confira com o GriefLogger onde ele cavou."));
                d.clear();
            }
            return;
        }
    }

    private static boolean bate(String id, List<String> padroes) {
        for (String p : padroes) {
            if (p.contains("*")) {
                if (Pattern.compile(Pattern.quote(p).replace("*", "\\E.*\\Q")).matcher(id).matches()) return true;
            } else if (p.equals(id)) return true;
        }
        return false;
    }
}
