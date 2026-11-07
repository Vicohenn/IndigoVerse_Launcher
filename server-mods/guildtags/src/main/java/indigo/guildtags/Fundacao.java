package indigo.guildtags;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * /guilda fundar <nome>             mostra o preço
 * /guilda fundar <nome> confirmar   paga em CobbleDollars e cria a guilda (o jogador vira líder)
 * O subcomando entra no /guilda do mod de guildas (o Brigadier junta os dois).
 * Apoiadores a partir de "nivelDesconto" (IndigoVIP) pagam com desconto.
 */
public final class Fundacao {
    private Fundacao() {}

    public static void registrar(CommandDispatcher<ServerCommandSource> d) {
        d.register(literal("guilda").then(literal("fundar")
                .then(argument("nome", StringArgumentType.word())
                        .executes(c -> fundar(c, false))
                        .then(literal("confirmar").executes(c -> fundar(c, true))))));
    }

    private static int fundar(CommandContext<ServerCommandSource> c, boolean confirmar) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
        TagsConfig.Fundacao f = TagsConfig.get().fundacao;
        String nome = StringArgumentType.getString(c, "nome");
        if (f == null || f.custo <= 0) {
            c.getSource().sendError(Text.literal("A fundação de guildas é feita pela staff. Fale com um administrador."));
            return 0;
        }
        if (Guildas.guildaDe(p.getServer(), p.getUuid()) != null) {
            c.getSource().sendError(Text.literal("Você já pertence a uma guilda."));
            return 0;
        }
        boolean comDesconto = temNivel(p.getGameProfile().getName(), f.nivelDesconto);
        long preco = comDesconto ? Math.round(f.custo * (1.0 - f.desconto)) : f.custo;
        String precoTxt = String.format("%,d", preco).replace(',', '.');
        String extra = comDesconto ? " §a(" + Math.round(f.desconto * 100) + "% de desconto de apoiador)" : "";

        if (!confirmar) {
            c.getSource().sendFeedback(() -> Text.literal("§6Fundar a guilda §f" + nome + "§6 custa §e" + precoTxt + " CobbleDollars" + extra
                    + "\n§7Para confirmar: §e/guilda fundar " + nome + " confirmar"), false);
            return 1;
        }
        BigInteger saldo = saldo(p);
        if (saldo == null) { c.getSource().sendError(Text.literal("Não consegui consultar seu saldo de CobbleDollars.")); return 0; }
        if (saldo.compareTo(BigInteger.valueOf(preco)) < 0) {
            c.getSource().sendError(Text.literal("Saldo insuficiente: a fundação custa " + precoTxt + " CobbleDollars."));
            return 0;
        }
        try {
            criarGuilda(p.getServer(), nome, p.getUuid(), p.getGameProfile().getName());
        } catch (InvocationTargetException e) {
            c.getSource().sendError(Text.literal(e.getCause() == null ? "Erro ao criar a guilda." : e.getCause().getMessage()));
            return 0;
        } catch (Exception e) {
            GuildTagsMod.LOGGER.error("Erro criando guilda {}", nome, e);
            c.getSource().sendError(Text.literal("Erro ao criar a guilda."));
            return 0;
        }
        definirSaldo(p, saldo.subtract(BigInteger.valueOf(preco)));
        GuildTagsMod.LOGGER.info("{} fundou a guilda {} por {} CobbleDollars", p.getGameProfile().getName(), nome, preco);
        c.getSource().getServer().getPlayerManager().broadcast(
                Text.literal("§6✦ §f" + p.getGameProfile().getName() + "§6 fundou a guilda §e" + nome + "§6!"), false);
        return 1;
    }

    // ---------- pontes por reflexão ----------

    private static void criarGuilda(MinecraftServer server, String nome, UUID lider, String nick) throws Exception {
        Class<?> gm = Class.forName("guild.manager.GuildManager");
        Object m = gm.getMethod("get", MinecraftServer.class).invoke(null, server);
        gm.getMethod("createGuild", String.class, UUID.class, String.class).invoke(m, nome, lider, nick);
    }

    private static Method getCD, setCD;

    private static BigInteger saldo(PlayerEntity p) {
        try {
            if (getCD == null) {
                Class<?> k = Class.forName("fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt");
                getCD = k.getMethod("getCobbleDollars", PlayerEntity.class);
                setCD = k.getMethod("setCobbleDollars", PlayerEntity.class, BigInteger.class);
            }
            return (BigInteger) getCD.invoke(null, p);
        } catch (Exception e) {
            GuildTagsMod.LOGGER.error("CobbleDollars indisponível: {}", e.toString());
            return null;
        }
    }

    private static void definirSaldo(PlayerEntity p, BigInteger valor) {
        try {
            setCD.invoke(null, p, valor);
        } catch (Exception e) {
            GuildTagsMod.LOGGER.error("Falha ao cobrar a fundação de {}: {}", p.getName().getString(), e.toString());
        }
    }

    private static boolean temNivel(String nick, String nivelMinimo) {
        try {
            return (boolean) Class.forName("indigo.vip.Beneficios").getMethod("temBeneficio", String.class, String.class)
                    .invoke(null, nick, nivelMinimo);
        } catch (Exception e) {
            return false;
        }
    }
}
