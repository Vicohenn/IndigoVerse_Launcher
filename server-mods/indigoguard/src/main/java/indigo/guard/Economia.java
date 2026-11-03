package indigo.guard;

import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dinheiro (CobbleDollars) e itens raros: compara o que o jogador tem agora com o mínimo dos últimos
 * 5 minutos. Ganho acima do limite = alerta no log (duplicação/spawn). Ignora OP e staff.
 */
public final class Economia {
    private static final long JANELA_MS = 5 * 60_000L;

    private record Amostra(long t, BigInteger dinheiro, Map<String, Integer> itens) {}

    private static final Map<UUID, Deque<Amostra>> HIST = new ConcurrentHashMap<>();
    private static Method getDinheiro;
    private static boolean semCobbleDollars;
    private static int ticks;

    private Economia() {}

    public static void sair(UUID id) { HIST.remove(id); }

    public static void tick(MinecraftServer server) {
        if (!GuardConfig.get().economiaAtivo || ++ticks % 100 != 0) return; // a cada 5 s
        long agora = System.currentTimeMillis();
        for (ServerPlayerEntity p : new java.util.ArrayList<>(server.getPlayerManager().getPlayerList())) {
            if (Alertas.ehStaffOnline(p) || p.isCreative()) { HIST.remove(p.getUuid()); continue; }
            Amostra a = new Amostra(agora, dinheiro(p), contarItens(p));
            Deque<Amostra> h = HIST.computeIfAbsent(p.getUuid(), k -> new ArrayDeque<>());
            while (!h.isEmpty() && agora - h.peekFirst().t > JANELA_MS) h.pollFirst();
            if (!h.isEmpty()) verificar(server, p, h, a);
            h.addLast(a);
        }
    }

    private static void verificar(MinecraftServer server, ServerPlayerEntity p, Deque<Amostra> h, Amostra a) {
        GuardConfig cfg = GuardConfig.get();
        // dinheiro
        if (a.dinheiro != null) {
            BigInteger minimo = null;
            for (Amostra x : h) if (x.dinheiro != null && (minimo == null || x.dinheiro.compareTo(minimo) < 0)) minimo = x.dinheiro;
            if (minimo != null) {
                BigInteger ganho = a.dinheiro.subtract(minimo);
                if (ganho.compareTo(BigInteger.valueOf(cfg.maxGanhoDinheiro5Min)) > 0) {
                    Alertas.alertar(server, Alertas.Tipo.DINHEIRO, p.getGameProfile().getName(), p.getUuid(), Alertas.local(p), List.of(
                            "Dinheiro obtido em até 5 min: " + fmt(ganho) + " CobbleDollars (limite previsto: " + fmt(BigInteger.valueOf(cfg.maxGanhoDinheiro5Min)) + ")",
                            "Saldo agora: " + fmt(a.dinheiro),
                            "Pode ser venda grande no GTS — confira antes de punir."));
                    h.clear();
                    return;
                }
            }
        }
        // itens raros
        List<String> achados = new ArrayList<>();
        for (Map.Entry<String, Integer> lim : cfg.itensRaros.entrySet()) {
            int agoraQtd = a.itens.getOrDefault(lim.getKey(), 0);
            if (agoraQtd == 0) continue;
            int minimo = agoraQtd;
            for (Amostra x : h) minimo = Math.min(minimo, x.itens.getOrDefault(lim.getKey(), 0));
            int ganho = agoraQtd - minimo;
            if (ganho >= lim.getValue()) achados.add(lim.getKey() + ": +" + ganho + " em até 5 min (tem " + agoraQtd + ")");
        }
        if (!achados.isEmpty()) {
            achados.add("Pode ter tirado de um baú — confira antes de punir.");
            Alertas.alertar(server, Alertas.Tipo.ITENS, p.getGameProfile().getName(), p.getUuid(), Alertas.local(p), achados);
            h.clear();
        }
    }

    private static Map<String, Integer> contarItens(ServerPlayerEntity p) {
        Map<String, Integer> m = new HashMap<>();
        for (String id : GuardConfig.get().itensRaros.keySet()) {
            Identifier ident = Identifier.tryParse(id);
            if (ident == null || !Registries.ITEM.containsId(ident)) continue;
            Item item = Registries.ITEM.get(ident);
            int qtd = p.getInventory().count(item) + p.getEnderChestInventory().count(item);
            if (qtd > 0) m.put(id, qtd);
        }
        return m;
    }

    /** Saldo do CobbleDollars (método que o mod injeta no jogador). */
    private static BigInteger dinheiro(ServerPlayerEntity p) {
        if (semCobbleDollars) return null;
        try {
            if (getDinheiro == null) getDinheiro = p.getClass().getMethod("cobbleDollars$getCobbleDollars");
            Object v = getDinheiro.invoke(p);
            return v instanceof BigInteger b ? b : null;
        } catch (NoSuchMethodException e) {
            semCobbleDollars = true;
            IndigoGuardMod.LOGGER.warn("IndigoGuard: CobbleDollars não encontrado; verificação de dinheiro desligada.");
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String fmt(BigInteger b) {
        return String.format("%,d", b).replace(',', '.');
    }
}
