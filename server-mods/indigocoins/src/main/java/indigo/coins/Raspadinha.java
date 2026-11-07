package indigo.coins;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Raspadinha: item físico (papel com marcação) comprado na loja de NPC, por /raspadinha comprar ou dado aos VIPs.
 * O NPC "Conferir raspadinhas" roda /raspadinha conferir <jogador>: cada raspadinha do inventário é lida,
 * recebe um número de 12 dígitos e tem 1 chance em "umEm" de ser o número premiado da rodada.
 * A rodada troca de número a cada 24 h ou assim que sai um bilhete premiado.
 */
final class Raspadinha {
    static final String MARCA = "indigo_raspadinha";

    private Raspadinha() {}

    // ---------------- item ----------------

    static ItemStack criarItem(int qtd) {
        ItemStack st = new ItemStack(Items.PAPER, qtd);
        st.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Raspadinha IndigoVerse").styled(s -> s.withItalic(false).withColor(Formatting.GOLD)));
        st.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("Leve ao Sr. Fujiro Nakombi e clique em").styled(s -> s.withItalic(false).withColor(Formatting.GRAY)),
                Text.literal("\"Conferir raspadinhas\" para raspar.").styled(s -> s.withItalic(false).withColor(Formatting.GRAY)),
                Text.literal("Prêmio: de 1 a 5 Indigo Coins").styled(s -> s.withItalic(false).withColor(Formatting.AQUA)))));
        NbtCompound tag = new NbtCompound();
        tag.putBoolean(MARCA, true);
        st.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        st.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return st;
    }

    static boolean ehRaspadinha(ItemStack st) {
        if (st.isEmpty() || !st.isOf(Items.PAPER)) return false;
        NbtComponent c = st.get(DataComponentTypes.CUSTOM_DATA);
        return c != null && c.copyNbt().getBoolean(MARCA);
    }

    static void dar(ServerPlayerEntity p, int qtd) {
        while (qtd > 0) {
            int n = Math.min(64, qtd);
            ItemStack st = criarItem(n);
            if (!p.getInventory().insertStack(st)) p.dropItem(st, false);
            qtd -= n;
        }
    }

    // ---------------- rodada ----------------

    static void garantirRodada(MinecraftServer server) {
        long agora = System.currentTimeMillis();
        if (Dados.rodada == null || Dados.rodada.numero == null) {
            Dados.rodada = new Dados.Rodada();
            Dados.rodada.rodada = Dados.historico.isEmpty() ? 1 : Dados.historico.get(Dados.historico.size() - 1).rodada + 1;
            Dados.rodada.numero = Moedas.numero12();
            Dados.rodada.inicio = agora;
            Dados.salvarRodada();
            Dados.log("RODADA " + Dados.rodada.rodada + " iniciada");
            relatorio();
            return;
        }
        if (agora - Dados.rodada.inicio >= Dados.config.horasPorRodada * 3_600_000L) {
            fecharRodada("24h", null, null, 0, List.of());
        }
    }

    private static void fecharRodada(String motivo, String nick, String uuid, int recompensa, List<String> ids) {
        Dados.Resultado r = new Dados.Resultado();
        r.rodada = Dados.rodada.rodada;
        r.numero = Dados.rodada.numero;
        r.inicio = Dados.rodada.inicio;
        r.fim = System.currentTimeMillis();
        r.bilhetesLidos = Dados.rodada.bilhetesLidos;
        r.motivo = motivo;
        if (nick != null) { r.vencedor = nick; r.vencedorUuid = uuid; }
        r.recompensa = recompensa;
        r.moedas = new ArrayList<>(ids);
        Dados.historico.add(r);
        Dados.salvarHistorico();
        Dados.log("RODADA " + r.rodada + " encerrada (" + motivo + ") numero=" + r.numero + (nick != null ? " vencedor=" + nick + " moedas=" + recompensa : ""));
        Dados.rodada = null;
        garantirRodada(null);
    }

    // ---------------- conferir ----------------

    /** Raspa todas as raspadinhas do inventário do jogador. Devolve quantas foram lidas. */
    static int conferir(MinecraftServer server, ServerPlayerEntity p) {
        garantirRodada(server);
        PlayerInventory inv = p.getInventory();
        int lidas = 0, ganhas = 0;
        List<String> numeros = new ArrayList<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack st = inv.getStack(i);
            while (ehRaspadinha(st) && st.getCount() > 0) {
                st.decrement(1);
                lidas++;
                Dados.rodada.bilhetesLidos++;
                boolean premiada = Moedas.RNG.nextInt(Math.max(1, Dados.config.umEm)) == 0;
                String numero;
                if (premiada) {
                    numero = Dados.rodada.numero;
                } else {
                    do { numero = Moedas.numero12(); } while (numero.equals(Dados.rodada.numero));
                }
                if (!premiada) { numeros.add(numero); continue; }

                int qtd = Moedas.quantidadePremio();
                List<String> ids = new ArrayList<>();
                for (int k = 0; k < qtd; k++)
                    ids.add(Moedas.criar(p.getUuid(), p.getGameProfile().getName(), "raspadinha:" + Dados.rodada.rodada, k == 0 ? numero : null));
                ganhas += qtd;
                p.sendMessage(Text.literal("§6§l★ RASPADINHA PREMIADA! ★ §r§eBilhete nº §f" + numero
                        + "§e: você ganhou §b" + qtd + " Indigo Coin" + (qtd > 1 ? "s" : "") + "§e!"));
                if (Dados.config.anunciarVencedor)
                    server.getPlayerManager().broadcast(Text.literal("§6✦ §f" + p.getGameProfile().getName()
                            + " §6raspou o bilhete premiado nº §f" + numero + "§6 e ganhou §b" + qtd + " Indigo Coin" + (qtd > 1 ? "s" : "") + "§6!"), false);
                fecharRodada("premiada", p.getGameProfile().getName(), p.getUuidAsString(), qtd, ids);
            }
        }
        if (lidas == 0) {
            p.sendMessage(Text.literal("§7Você não tem raspadinhas para conferir."));
            return 0;
        }
        Dados.salvarRodada();
        if (!numeros.isEmpty()) {
            String lista = numeros.size() <= 5 ? String.join(", ", numeros) : String.join(", ", numeros.subList(0, 5)) + " …";
            p.sendMessage(Text.literal("§7" + numeros.size() + " raspadinha" + (numeros.size() > 1 ? "s" : "")
                    + " sem prêmio (§f" + lista + "§7). Não foi dessa vez!"));
        }
        Dados.log("CONFERIR " + p.getGameProfile().getName() + " lidas=" + lidas + " moedas=" + ganhas);
        relatorio();
        return lidas;
    }

    // ---------------- relatório para testes (abre no Excel) ----------------

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("America/Sao_Paulo"));

    static void relatorio() {
        if (!Dados.config.relatorioAtivo) return;
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("Rodada;Número da raspadinha premiada;Player com esse bilhete;Início;Fim;Situação;Bilhetes conferidos;IDs das coins;Recompensa da raspadinha (moedas)\r\n");
        for (Dados.Resultado r : Dados.historico) {
            sb.append(r.rodada).append(';').append("=\"").append(r.numero).append("\";")
              .append(r.vencedor == null || r.vencedor.isEmpty() ? "(ninguém)" : r.vencedor).append(';')
              .append(DT.format(Instant.ofEpochMilli(r.inicio))).append(';').append(DT.format(Instant.ofEpochMilli(r.fim))).append(';')
              .append("premiada".equals(r.motivo) ? "Premiada" : "Expirou (24h)").append(';')
              .append(r.bilhetesLidos).append(';').append(String.join(" ", r.moedas)).append(';')
              .append(r.recompensa).append("\r\n");
        }
        if (Dados.rodada != null) {
            sb.append(Dados.rodada.rodada).append(';').append("=\"").append(Dados.rodada.numero).append("\";")
              .append("(ninguém ainda)").append(';').append(DT.format(Instant.ofEpochMilli(Dados.rodada.inicio))).append(';')
              .append(';').append("Em andamento").append(';').append(Dados.rodada.bilhetesLidos).append(';').append(';').append(0).append("\r\n");
        }
        try {
            Path f = Path.of(Dados.config.relatorioArquivo);
            Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // o Excel trava o arquivo enquanto está aberto: tenta de novo na próxima atualização
            IndigoCoinsMod.LOGGER.warn("Não consegui atualizar {} (está aberto no Excel?)", Dados.config.relatorioArquivo);
        }
    }
}
