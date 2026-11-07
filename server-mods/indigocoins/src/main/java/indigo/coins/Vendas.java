package indigo.coins;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Venda de coins entre jogadores, sem item físico:
 *   vendedor: /indigocoins vender <comprador> <quantidade> <preço>
 *   comprador: /indigocoins aceitar <vendedor>   (ou recusar)
 * Ao aceitar, o comprador paga em CobbleDollars e as coins passam para ele.
 * A coin só muda de dono se quem RECEBE o dinheiro (vendedor) ainda for o dono dela.
 */
final class Vendas {
    record Oferta(UUID vendedor, String nickVendedor, UUID comprador, List<String> ids, long preco, long expira) {}

    /** comprador -> (vendedor -> oferta) */
    private static final Map<UUID, Map<UUID, Oferta>> ofertas = new HashMap<>();

    private Vendas() {}

    static int oferecer(ServerPlayerEntity vendedor, ServerPlayerEntity comprador, int qtd, long preco) {
        if (vendedor.getUuid().equals(comprador.getUuid())) {
            vendedor.sendMessage(Text.literal("§cVocê não pode vender para si mesmo."));
            return 0;
        }
        List<String> minhas = Moedas.doJogador(vendedor.getUuid());
        if (minhas.size() < qtd) {
            vendedor.sendMessage(Text.literal("§cVocê tem só " + minhas.size() + " Indigo Coin(s)."));
            return 0;
        }
        List<String> ids = new ArrayList<>(minhas.subList(0, qtd));
        Oferta o = new Oferta(vendedor.getUuid(), vendedor.getGameProfile().getName(), comprador.getUuid(), ids, preco,
                System.currentTimeMillis() + Dados.config.segundosOferta * 1000L);
        ofertas.computeIfAbsent(comprador.getUuid(), k -> new HashMap<>()).put(vendedor.getUuid(), o);
        String txt = qtd + " Indigo Coin" + (qtd > 1 ? "s" : "") + " por " + Economia.fmt(preco) + " CobbleDollars";
        vendedor.sendMessage(Text.literal("§aOferta enviada a §f" + comprador.getGameProfile().getName() + "§a: " + txt
                + ". Ela vale por " + Dados.config.segundosOferta + " s."));
        comprador.sendMessage(Text.literal("§6" + vendedor.getGameProfile().getName() + " §equer te vender §f" + txt
                + "§e.\n§7Para comprar: §a/indigocoins aceitar " + vendedor.getGameProfile().getName()
                + "§7  •  para recusar: §c/indigocoins recusar " + vendedor.getGameProfile().getName()));
        return 1;
    }

    static int recusar(ServerPlayerEntity comprador, ServerPlayerEntity vendedor) {
        Map<UUID, Oferta> m = ofertas.get(comprador.getUuid());
        Oferta o = m == null ? null : m.remove(vendedor.getUuid());
        if (o == null) { comprador.sendMessage(Text.literal("§7Nenhuma oferta de " + vendedor.getGameProfile().getName() + ".")); return 0; }
        comprador.sendMessage(Text.literal("§7Oferta recusada."));
        vendedor.sendMessage(Text.literal("§c" + comprador.getGameProfile().getName() + " recusou sua oferta de Indigo Coins."));
        return 1;
    }

    static int aceitar(MinecraftServer server, ServerPlayerEntity comprador, ServerPlayerEntity vendedor) {
        Map<UUID, Oferta> m = ofertas.get(comprador.getUuid());
        Oferta o = m == null ? null : m.remove(vendedor.getUuid());
        if (o == null || o.expira() < System.currentTimeMillis()) {
            comprador.sendMessage(Text.literal("§cNão há oferta válida de " + vendedor.getGameProfile().getName() + " (ela pode ter expirado)."));
            return 0;
        }
        // as coins ainda são do vendedor?
        String uv = o.vendedor().toString();
        for (String id : o.ids()) {
            Dados.Moeda c = Dados.moedas.get(id);
            if (c == null || c.gastaEm != 0 || !uv.equals(c.dono)) {
                comprador.sendMessage(Text.literal("§cA venda foi cancelada: as coins não pertencem mais a " + o.nickVendedor() + "."));
                return 0;
            }
        }
        BigInteger preco = BigInteger.valueOf(o.preco());
        BigInteger saldoC = Economia.saldo(comprador), saldoV = Economia.saldo(vendedor);
        if (saldoC == null || saldoV == null) { comprador.sendMessage(Text.literal("§cCobbleDollars indisponível, tente de novo.")); return 0; }
        if (saldoC.compareTo(preco) < 0) {
            comprador.sendMessage(Text.literal("§cSaldo insuficiente: a oferta custa " + Economia.fmt(o.preco()) + " CobbleDollars."));
            vendedor.sendMessage(Text.literal("§c" + comprador.getGameProfile().getName() + " não tinha saldo para a sua oferta."));
            return 0;
        }
        // 1) pagamento
        if (!Economia.definir(comprador, saldoC.subtract(preco))) { comprador.sendMessage(Text.literal("§cFalha no pagamento.")); return 0; }
        if (!Economia.definir(vendedor, saldoV.add(preco))) {
            Economia.definir(comprador, saldoC);   // devolve
            comprador.sendMessage(Text.literal("§cFalha no pagamento; nada foi cobrado."));
            return 0;
        }
        // 2) transferência: só muda o dono se quem recebeu o dinheiro era o dono
        String nickC = comprador.getGameProfile().getName();
        for (String id : o.ids()) {
            Dados.Moeda c = Dados.moedas.get(id);
            if (uv.equals(c.dono)) {
                c.dono = comprador.getUuidAsString();
                c.nick = nickC;
            }
            Dados.log("VENDA " + id + " de=" + o.nickVendedor() + " para=" + nickC + " preco=" + o.preco());
        }
        Dados.salvarMoedas();
        String txt = o.ids().size() + " Indigo Coin" + (o.ids().size() > 1 ? "s" : "");
        comprador.sendMessage(Text.literal("§aVocê comprou " + txt + " de " + o.nickVendedor() + " por " + Economia.fmt(o.preco()) + " CobbleDollars."));
        vendedor.sendMessage(Text.literal("§a" + nickC + " comprou suas " + txt + ". Você recebeu " + Economia.fmt(o.preco()) + " CobbleDollars."));
        return 1;
    }
}
