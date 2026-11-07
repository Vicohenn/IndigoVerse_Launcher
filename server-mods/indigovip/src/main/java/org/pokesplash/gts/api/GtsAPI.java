package org.pokesplash.gts.api;

import java.util.UUID;

/** Só para compilar o mixin do GTS (fica fora do .jar). */
public class GtsAPI {
    private static boolean transferFunds(UUID a, UUID b, double c) { return new org.pokesplash.gts.config.Config().getTaxRate() > 0; }
    private static void revertFundTransfer(UUID a, UUID b, double c) { new org.pokesplash.gts.config.Config().getTaxRate(); }
}
