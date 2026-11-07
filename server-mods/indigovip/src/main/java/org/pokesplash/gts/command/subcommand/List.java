package org.pokesplash.gts.command.subcommand;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.ServerCommandSource;

/** Só para compilar o mixin do GTS (fica fora do .jar). */
public class List {
    public int run(CommandContext<ServerCommandSource> c) { return new org.pokesplash.gts.config.Config().getMaxListingsPerPlayer(); }
    public int runPokemon(CommandContext<ServerCommandSource> c) { return new org.pokesplash.gts.config.Config().getMaxListingsPerPlayer(); }
    public int runItem(CommandContext<ServerCommandSource> c) { return new org.pokesplash.gts.config.Config().getMaxListingsPerPlayer(); }
}
