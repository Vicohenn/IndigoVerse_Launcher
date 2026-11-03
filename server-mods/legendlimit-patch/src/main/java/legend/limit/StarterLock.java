package legend.limit;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.reflect.Method;

/**
 * Bloqueia a escolha de inicial pela tecla C.
 *
 * Todo jogador que entra sem ter escolhido inicial fica com starterLocked = true.
 * O Cobblemon recusa o pedido da tecla C para jogadores travados, mas o comando
 * /openstarterscreen (usado pelo NPC do Easy NPC) destrava antes de abrir a tela.
 *
 * Usa reflexão para não precisar do Cobblemon no classpath de compilação.
 */
public final class StarterLock {
    private StarterLock() {}

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> lock(handler.getPlayer()));
    }

    private static void lock(ServerPlayerEntity player) {
        try {
            Class<?> cobblemon = Class.forName("com.cobblemon.mod.common.Cobblemon");
            Object instance = cobblemon.getField("INSTANCE").get(null);
            Object manager = cobblemon.getMethod("getPlayerDataManager").invoke(instance);
            Object data = find(manager.getClass(), "getGenericData", 1).invoke(manager, player);

            boolean selected = (boolean) data.getClass().getMethod("getStarterSelected").invoke(data);
            boolean locked = (boolean) data.getClass().getMethod("getStarterLocked").invoke(data);
            if (selected || locked) return;

            data.getClass().getMethod("setStarterLocked", boolean.class).invoke(data, true);
            find(data.getClass(), "sendToPlayer", 1).invoke(data, player);

            Class<?> types = Class.forName("com.cobblemon.mod.common.api.storage.player.PlayerInstancedDataStoreTypes");
            Object general = types.getMethod("getGENERAL").invoke(types.getField("INSTANCE").get(null));
            find(manager.getClass(), "saveSingle", 2).invoke(manager, data, general);

            LegendLimitMod.LOGGER.info("Inicial travado para {} (só pelo NPC)", player.getGameProfile().getName());
        } catch (Throwable t) {
            LegendLimitMod.LOGGER.error("Não consegui travar a escolha de inicial", t);
        }
    }

    private static Method find(Class<?> type, String name, int params) throws NoSuchMethodException {
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == params) return m;
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
}
