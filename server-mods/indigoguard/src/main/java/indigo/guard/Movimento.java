package indigo.guard;

import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Voo e velocidade. Ignora: OP/staff, criativo/espectador, quem está montado (Pokémon, cavalo, barco...),
 * nadando/na água/lava, elytra, escalando, com levitação/queda lenta, tridente com correnteza,
 * e o tick em que o jogador é teleportado (Waystone, /tp, batalha).
 */
public final class Movimento {
    private static final class Estado {
        Vec3d ultima;
        int ticksNoAr;
        double[] passos = new double[40];
        int idx;
        int validos;
        int graca; // ticks ignorados depois de um teleporte/situação especial
    }

    private static final Map<UUID, Estado> ESTADOS = new ConcurrentHashMap<>();

    private Movimento() {}

    public static void sair(UUID id) { ESTADOS.remove(id); }

    public static void tick(MinecraftServer server) {
        GuardConfig cfg = GuardConfig.get();
        if (!cfg.movimentoAtivo) return;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            Estado e = ESTADOS.computeIfAbsent(p.getUuid(), k -> new Estado());
            Vec3d agora = p.getPos();
            Vec3d antes = e.ultima;
            e.ultima = agora;
            if (antes == null) continue;

            if (ignorado(p)) { zera(e, 10); continue; }

            double dx = agora.x - antes.x, dz = agora.z - antes.z, dy = agora.y - antes.y;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            // salto grande num tick = teleporte; reinicia as contas
            if (horizontal > 8 || Math.abs(dy) > 8) { zera(e, 40); continue; }
            if (e.graca > 0) { e.graca--; continue; }

            // ---------- voo: no ar, sem nada embaixo, sem cair ----------
            boolean noAr = semChao(p);
            if (noAr && dy > -0.05) e.ticksNoAr++;
            else if (!noAr || dy < -0.3) e.ticksNoAr = 0;
            if (e.ticksNoAr > cfg.segundosNoArParaAlerta * 20) {
                Alertas.alertar(server, Alertas.Tipo.VOO, p.getGameProfile().getName(), p.getUuid(), Alertas.local(p),
                        List.of(String.format("No ar por %.1f s sem cair", e.ticksNoAr / 20.0)));
                e.ticksNoAr = 0;
            }

            // ---------- velocidade: média de 2 s ----------
            if (p.isTouchingWater() || noGelo(p) || p.hasStatusEffect(StatusEffects.SPEED) && amplificador(p) >= 2) {
                e.validos = 0;
                continue;
            }
            e.passos[e.idx] = horizontal;
            e.idx = (e.idx + 1) % e.passos.length;
            if (e.validos < e.passos.length) e.validos++;
            if (e.validos == e.passos.length) {
                double soma = 0;
                for (double v : e.passos) soma += v;
                double porSegundo = soma / e.passos.length * 20;
                if (porSegundo > cfg.velocidadeMaxima) {
                    Alertas.alertar(server, Alertas.Tipo.VELOCIDADE, p.getGameProfile().getName(), p.getUuid(), Alertas.local(p),
                            List.of(String.format("%.1f blocos/s (máximo esperado: %.0f)", porSegundo, cfg.velocidadeMaxima)));
                    e.validos = 0;
                }
            }
        }
    }

    private static void zera(Estado e, int graca) {
        e.ticksNoAr = 0;
        e.validos = 0;
        e.graca = Math.max(e.graca, graca);
    }

    private static boolean ignorado(ServerPlayerEntity p) {
        return Alertas.ehStaffOnline(p)
                || p.isCreative() || p.isSpectator()
                || p.getAbilities().allowFlying
                || p.hasVehicle()                 // montado em Pokémon, cavalo, barco...
                || p.isFallFlying()               // elytra
                || p.isTouchingWater() || p.isInLava() || p.isSwimming() || p.isSubmergedInWater()
                || p.isClimbing()
                || p.isUsingRiptide()
                || p.isSleeping()
                || p.hasStatusEffect(StatusEffects.LEVITATION)
                || p.hasStatusEffect(StatusEffects.SLOW_FALLING)
                || p.isDead();
    }

    /** Sem bloco nem entidade sólida até 0,6 bloco abaixo (não confia no "onGround" que o cliente manda). */
    private static boolean semChao(ServerPlayerEntity p) {
        Box caixa = p.getBoundingBox();
        Box abaixo = new Box(caixa.minX - 0.3, caixa.minY - 0.6, caixa.minZ - 0.3, caixa.maxX + 0.3, caixa.minY, caixa.maxZ + 0.3);
        return p.getWorld().isSpaceEmpty(p, abaixo);
    }

    private static boolean noGelo(ServerPlayerEntity p) {
        BlockPos b = p.getBlockPos().down();
        String id = Registries.BLOCK.getId(p.getWorld().getBlockState(b).getBlock()).getPath();
        return id.contains("ice");
    }

    private static int amplificador(ServerPlayerEntity p) {
        var ef = p.getStatusEffect(StatusEffects.SPEED);
        return ef == null ? 0 : ef.getAmplifier() + 1;
    }
}
