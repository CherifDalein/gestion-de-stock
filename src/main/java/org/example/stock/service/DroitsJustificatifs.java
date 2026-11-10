package org.example.stock.service;

/** Contrôle de droits des justificatifs de solde pour le contrôleur : même règle que {@link DroitsEtats} (gérant seulement, 403 sinon). */
public final class DroitsJustificatifs {
    private DroitsJustificatifs() { }

    public static void exigerGerant() { DroitsEtats.exigerGerant(); }
}
