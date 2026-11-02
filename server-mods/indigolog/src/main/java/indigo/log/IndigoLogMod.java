package indigo.log;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IndigoLog: envia para um canal do Discord (via webhook) o que a Staff precisa acompanhar:
 *  - itens retirados de baús/containers (com o dono do claim, se houver);
 *  - batalhas contra Líderes de Ginásio, Elite dos 4 e Campeões (início e resultado).
 *
 * Roda só no servidor. Não precisa estar instalado nos jogadores.
 */
public final class IndigoLogMod implements ModInitializer {
    public static final String MOD_ID = "indigolog";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LogConfig.load();
        DiscordWebhook.start();

        ContainerLogger containers = new ContainerLogger();
        ServerTickEvents.END_SERVER_TICK.register(containers::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> containers.onDisconnect(handler.getPlayer()));

        BattleLogger battles = new BattleLogger();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> battles.register());
        ServerTickEvents.END_SERVER_TICK.register(battles::tick);

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> DiscordWebhook.flushNow());
        LOGGER.info("IndigoLog carregado (webhook {}).", LogConfig.get().webhookUrl.isBlank() ? "NÃO configurado" : "configurado");
    }
}
