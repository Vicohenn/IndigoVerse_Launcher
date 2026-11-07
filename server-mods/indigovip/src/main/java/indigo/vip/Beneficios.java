package indigo.vip;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Benefícios por nível:
 *   - mensagem de entrada personalizada  (/entrada definir <texto> | remover | ver)
 *   - /anunciar <texto>  (com intervalo mínimo entre anúncios)
 *   - vagas extras quando o servidor lota (fila prioritária)
 */
public final class Beneficios {
    private static final Map<UUID, Long> ultimoAnuncio = new HashMap<>();

    private Beneficios() {}

    /** Nível ativo do jogador ou null. */
    public static VipConfig.Nivel nivelDe(String nick) {
        Apoiadores.Registro r = Apoiadores.de(nick);
        return Apoiadores.ativo(r) ? VipConfig.get().nivel(r.nivel) : null;
    }

    /** Tem o benefício se o nível dele for igual ou acima do nível mínimo configurado. */
    public static boolean temBeneficio(String nick, String nivelMinimo) {
        VipConfig.Nivel n = nivelDe(nick);
        VipConfig.Nivel min = VipConfig.get().nivel(nivelMinimo);
        return n != null && min != null && n.ordem >= min.ordem;
    }

    // ---------------- fila prioritária ----------------

    public static boolean podeUsarVagaExtra(String nick, int online, int max) {
        try {
            VipConfig.Nivel n = nivelDe(nick);
            if (n == null || n.vagasExtras == null || n.vagasExtras <= 0) return false;
            return online < max + n.vagasExtras;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- mensagem de entrada ----------------

    public static void aoEntrar(MinecraftServer server, ServerPlayerEntity p) {
        String nick = p.getGameProfile().getName();
        if (!temBeneficio(nick, VipConfig.get().nivelMensagemEntrada)) return;
        Apoiadores.Registro r = Apoiadores.de(nick);
        String texto = r.mensagemEntrada == null || r.mensagemEntrada.isBlank() ? VipConfig.get().entradaPadrao : r.mensagemEntrada;
        VipConfig.Nivel n = nivelDe(nick);
        String msg = VipConfig.get().formatoEntrada
                .replace("{prefixo}", n.prefixo == null ? "" : n.prefixo)
                .replace("{nick}", nick)
                .replace("{mensagem}", limpar(texto));
        server.getPlayerManager().broadcast(Text.literal(Tags.cor(msg)), false);
    }

    /** Aceita cores (&0-&f) e &l &o &n &m &r; tira &k (texto embaralhado) e limita o tamanho. */
    static String limpar(String s) {
        String t = s.replace('§', '&').replaceAll("(?i)&k", "");
        int max = VipConfig.get().tamanhoMaximoTexto;
        return t.length() > max ? t.substring(0, max) : t;
    }

    static int definirEntrada(CommandContext<ServerCommandSource> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
        String nick = p.getGameProfile().getName();
        if (!temBeneficio(nick, VipConfig.get().nivelMensagemEntrada)) return negar(c, VipConfig.get().nivelMensagemEntrada);
        String texto = limpar(StringArgumentType.getString(c, "texto"));
        Apoiadores.de(nick).mensagemEntrada = texto;
        Apoiadores.save();
        c.getSource().sendFeedback(() -> Text.literal("§aMensagem de entrada salva: §f" + Tags.cor(texto)), false);
        return 1;
    }

    static int removerEntrada(CommandContext<ServerCommandSource> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
        Apoiadores.Registro r = Apoiadores.de(p.getGameProfile().getName());
        if (r != null) { r.mensagemEntrada = null; Apoiadores.save(); }
        c.getSource().sendFeedback(() -> Text.literal("§eMensagem de entrada voltou para a padrão."), false);
        return 1;
    }

    static int verEntrada(CommandContext<ServerCommandSource> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
        String nick = p.getGameProfile().getName();
        if (!temBeneficio(nick, VipConfig.get().nivelMensagemEntrada)) return negar(c, VipConfig.get().nivelMensagemEntrada);
        Apoiadores.Registro r = Apoiadores.de(nick);
        String t = r.mensagemEntrada == null || r.mensagemEntrada.isBlank() ? VipConfig.get().entradaPadrao + " §7(padrão)" : r.mensagemEntrada;
        c.getSource().sendFeedback(() -> Text.literal("§7Sua mensagem de entrada: §f" + Tags.cor(t)
                + "\n§7Mude com §e/entrada definir <texto>§7 (aceita cores com &)."), false);
        return 1;
    }

    // ---------------- /anunciar ----------------

    static int anunciar(CommandContext<ServerCommandSource> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
        String nick = p.getGameProfile().getName();
        boolean staff = p.hasPermissionLevel(2);
        if (!staff && !temBeneficio(nick, VipConfig.get().nivelAnunciar)) return negar(c, VipConfig.get().nivelAnunciar);
        long agora = System.currentTimeMillis();
        long espera = VipConfig.get().anunciarIntervaloMinutos * 60_000L;
        Long antes = ultimoAnuncio.get(p.getUuid());
        if (!staff && antes != null && agora - antes < espera) {
            long falta = (espera - (agora - antes) + 59_999) / 60_000;
            c.getSource().sendError(Text.literal("Você poderá anunciar de novo em " + falta + " min."));
            return 0;
        }
        ultimoAnuncio.put(p.getUuid(), agora);
        VipConfig.Nivel n = nivelDe(nick);
        String msg = VipConfig.get().formatoAnuncio
                .replace("{prefixo}", n == null || n.prefixo == null ? "" : n.prefixo)
                .replace("{nick}", nick)
                .replace("{mensagem}", limpar(StringArgumentType.getString(c, "texto")));
        c.getSource().getServer().getPlayerManager().broadcast(Text.literal(Tags.cor(msg)), false);
        return 1;
    }

    private static int negar(CommandContext<ServerCommandSource> c, String nivelMinimo) {
        VipConfig.Nivel n = VipConfig.get().nivel(nivelMinimo);
        c.getSource().sendError(Text.literal("Benefício do apoio " + (n == null ? nivelMinimo : n.nome) + "."));
        return 0;
    }

    public static void registrar(com.mojang.brigadier.CommandDispatcher<ServerCommandSource> d) {
        d.register(literal("entrada")
                .executes(Beneficios::verEntrada)
                .then(literal("ver").executes(Beneficios::verEntrada))
                .then(literal("definir").then(argument("texto", StringArgumentType.greedyString()).executes(Beneficios::definirEntrada)))
                .then(literal("remover").executes(Beneficios::removerEntrada)));
        d.register(literal("anunciar")
                .then(argument("texto", StringArgumentType.greedyString()).executes(Beneficios::anunciar)));
    }
}
