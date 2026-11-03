package indigo.guard;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * IndigoGuard: alertas para a staff (nunca pune sozinho).
 *   /indigoguard recarregar
 *   /indigoguard staff adicionar|remover <nick>
 *   /indigoguard staff listar
 *   /indigoguard teste   (manda um alerta de teste para o Discord)
 */
public final class IndigoGuardMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("indigoguard");

    @Override
    public void onInitialize() {
        GuardConfig.load();
        Contas.carregar();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            OpWatch.iniciar(server);
            Discord.iniciar(server);
            LOGGER.info("IndigoGuard ativo. Staff: {}", GuardConfig.get().staff);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> Discord.parar());

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            try { Movimento.tick(server); } catch (Throwable t) { LOGGER.debug("movimento: {}", t.toString()); }
            try { Economia.tick(server); } catch (Throwable t) { LOGGER.debug("economia: {}", t.toString()); }
            try { Duplicacao.tick(server); } catch (Throwable t) { LOGGER.debug("duplicação: {}", t.toString()); }
            try { OpWatch.tick(server); } catch (Throwable t) { LOGGER.debug("op: {}", t.toString()); }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            try { Contas.entrou(server, handler.getPlayer()); } catch (Throwable t) { LOGGER.warn("contas: {}", t.toString()); }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var id = handler.getPlayer().getUuid();
            Movimento.sair(id);
            Economia.sair(id);
            Mineracao.sair(id);
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldP, newP, alive) -> Movimento.sair(newP.getUuid()));

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            if (player instanceof ServerPlayerEntity sp) {
                try { Mineracao.quebrou(sp, state); } catch (Throwable t) { LOGGER.debug("mineração: {}", t.toString()); }
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(
                literal("indigoguard").requires(s -> s.hasPermissionLevel(3))
                        .then(literal("recarregar").executes(c -> {
                            GuardConfig.load();
                            c.getSource().sendFeedback(() -> Text.literal("§aindigoguard.json recarregado."), true);
                            return 1;
                        }))
                        .then(literal("teste").executes(c -> {
                            ServerPlayerEntity p = c.getSource().getPlayer();
                            Alertas.alertar(c.getSource().getServer(), Alertas.Tipo.VOO,
                                    p == null ? "Console" : p.getGameProfile().getName(),
                                    p == null ? new java.util.UUID(0, 0) : p.getUuid(),
                                    p == null ? "—" : Alertas.local(p), List.of("ALERTA DE TESTE — pode clicar em Ignorar."));
                            c.getSource().sendFeedback(() -> Text.literal("§aAlerta de teste enviado."), false);
                            return 1;
                        }))
                        .then(literal("staff")
                                .then(literal("listar").executes(c -> {
                                    c.getSource().sendFeedback(() -> Text.literal("§6Staff: §f" + String.join(", ", GuardConfig.get().staff)), false);
                                    return 1;
                                }))
                                .then(literal("adicionar").then(argument("nick", StringArgumentType.word()).executes(c -> {
                                    String n = StringArgumentType.getString(c, "nick");
                                    if (GuardConfig.get().staff.stream().noneMatch(s -> s.equalsIgnoreCase(n))) GuardConfig.get().staff.add(n);
                                    GuardConfig.save();
                                    c.getSource().sendFeedback(() -> Text.literal("§a" + n + " agora é staff no IndigoGuard."), true);
                                    return 1;
                                })))
                                .then(literal("remover").then(argument("nick", StringArgumentType.word()).executes(c -> {
                                    String n = StringArgumentType.getString(c, "nick");
                                    GuardConfig.get().staff.removeIf(s -> s.equalsIgnoreCase(n));
                                    GuardConfig.save();
                                    c.getSource().sendFeedback(() -> Text.literal("§e" + n + " removido da staff do IndigoGuard."), true);
                                    return 1;
                                }))))));
        LOGGER.info("IndigoGuard carregado.");
    }
}
