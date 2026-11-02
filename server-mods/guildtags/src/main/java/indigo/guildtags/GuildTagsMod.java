package indigo.guildtags;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Guild Tags: liga categorias de produção às guildas do mod "guild".
 *
 *   /guildatag listar
 *   /guildatag adicionar <categoria> <guilda>   (OP)
 *   /guildatag remover <categoria> <guilda>     (OP)
 *   /guildatag recarregar                       (OP)
 */
public final class GuildTagsMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("guildtags");

    private static final SuggestionProvider<ServerCommandSource> CATEGORIAS = (ctx, b) ->
            CommandSource.suggestMatching(TagsConfig.get().categorias.keySet(), b);
    private static final SuggestionProvider<ServerCommandSource> GUILDAS = (ctx, b) ->
            CommandSource.suggestMatching(Guildas.todas(ctx.getSource().getServer()), b);

    @Override
    public void onInitialize() {
        TagsConfig.load();

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (player instanceof ServerPlayerEntity sp && world instanceof ServerWorld sw) {
                return Restricoes.aoUsarBloco(sp, sw, hand, hit);
            }
            return ActionResult.PASS;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(
                literal("guildatag")
                        .executes(GuildTagsMod::listar)
                        .then(literal("listar").executes(GuildTagsMod::listar))
                        .then(literal("adicionar").requires(s -> s.hasPermissionLevel(2))
                                .then(argument("categoria", StringArgumentType.word()).suggests(CATEGORIAS)
                                        .then(argument("guilda", StringArgumentType.word()).suggests(GUILDAS)
                                                .executes(c -> alterar(c, true)))))
                        .then(literal("remover").requires(s -> s.hasPermissionLevel(2))
                                .then(argument("categoria", StringArgumentType.word()).suggests(CATEGORIAS)
                                        .then(argument("guilda", StringArgumentType.word()).suggests(GUILDAS)
                                                .executes(c -> alterar(c, false)))))
                        .then(literal("recarregar").requires(s -> s.hasPermissionLevel(2))
                                .executes(c -> {
                                    TagsConfig.load();
                                    c.getSource().sendFeedback(() -> Text.literal("§aguildtags.json recarregado."), true);
                                    return 1;
                                }))));
        LOGGER.info("Guild Tags carregado: {} categorias.", TagsConfig.get().categorias.size());
    }

    private static int listar(CommandContext<ServerCommandSource> ctx) {
        StringBuilder sb = new StringBuilder("§6§lTags de guilda§r");
        for (Map.Entry<String, TagsConfig.Categoria> e : TagsConfig.get().categorias.entrySet()) {
            List<String> gs = TagsConfig.get().guildas.getOrDefault(e.getKey(), List.of());
            sb.append("\n§e").append(e.getValue().nome).append(" §7(").append(e.getKey()).append(")§f: ")
              .append(gs.isEmpty() ? "§8nenhuma" : String.join(", ", gs))
              .append(" §7[").append(gs.size()).append("/").append(TagsConfig.get().maxGuildasPorCategoria).append("]");
        }
        if (ctx.getSource().getEntity() instanceof ServerPlayerEntity p) {
            String g = Guildas.guildaDe(ctx.getSource().getServer(), p.getUuid());
            sb.append("\n§7Sua guilda: §f").append(g == null ? "nenhuma" : g);
        }
        ctx.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
        return 1;
    }

    private static int alterar(CommandContext<ServerCommandSource> ctx, boolean adicionar) {
        String cat = StringArgumentType.getString(ctx, "categoria").toLowerCase();
        String nome = StringArgumentType.getString(ctx, "guilda");
        TagsConfig cfg = TagsConfig.get();
        if (!cfg.categorias.containsKey(cat)) {
            ctx.getSource().sendError(Text.literal("Categoria inexistente. Use: " + String.join(", ", cfg.categorias.keySet())));
            return 0;
        }
        String real = null;
        for (String g : Guildas.todas(ctx.getSource().getServer())) if (g.equalsIgnoreCase(nome)) real = g;
        List<String> lista = cfg.guildas.computeIfAbsent(cat, k -> new ArrayList<>());

        if (adicionar) {
            if (real == null) { ctx.getSource().sendError(Text.literal("A guilda " + nome + " não existe.")); return 0; }
            if (Guildas.temCategoria(real, cat)) { ctx.getSource().sendError(Text.literal(real + " já tem a tag " + Restricoes.nome(cat) + ".")); return 0; }
            if (lista.size() >= cfg.maxGuildasPorCategoria) {
                ctx.getSource().sendError(Text.literal("A categoria " + Restricoes.nome(cat) + " já tem " + lista.size()
                        + " guildas (máximo " + cfg.maxGuildasPorCategoria + "). Remova uma antes."));
                return 0;
            }
            lista.add(real);
        } else {
            boolean removeu = lista.removeIf(g -> g.equalsIgnoreCase(nome));
            if (!removeu) { ctx.getSource().sendError(Text.literal(nome + " não tem a tag " + Restricoes.nome(cat) + ".")); return 0; }
        }
        TagsConfig.save();
        String alvo = real != null ? real : nome;
        ctx.getSource().sendFeedback(() -> Text.literal("§aGuilda §f" + alvo + (adicionar ? " §aagora tem" : " §enão tem mais")
                + " a tag §6" + Restricoes.nome(cat) + "§a."), true);
        return 1;
    }
}
