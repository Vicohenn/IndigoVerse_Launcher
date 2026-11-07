package indigo.ranked;

import net.minecraft.entity.player.PlayerEntity;

import java.lang.reflect.Method;
import java.math.BigInteger;

/** Saldo do CobbleDollars por reflexão (sem dependência de compilação). */
final class Economia {
    private static Method get, set;

    private Economia() {}

    private static void init() throws Exception {
        if (get != null) return;
        Class<?> k = Class.forName("fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt");
        get = k.getMethod("getCobbleDollars", PlayerEntity.class);
        set = k.getMethod("setCobbleDollars", PlayerEntity.class, BigInteger.class);
    }

    /** Saldo ou null se o CobbleDollars não respondeu. */
    static BigInteger saldo(PlayerEntity p) {
        try {
            init();
            return (BigInteger) get.invoke(null, p);
        } catch (Exception e) {
            IndigoRankedMod.LOGGER.error("CobbleDollars indisponível: {}", e.toString());
            return null;
        }
    }

    static boolean definir(PlayerEntity p, BigInteger valor) {
        try {
            init();
            set.invoke(null, p, valor);
            return true;
        } catch (Exception e) {
            IndigoRankedMod.LOGGER.error("Falha ao mudar saldo de {}: {}", p.getName().getString(), e.toString());
            return false;
        }
    }

    static String fmt(long v) { return String.format("%,d", v).replace(',', '.'); }
}
