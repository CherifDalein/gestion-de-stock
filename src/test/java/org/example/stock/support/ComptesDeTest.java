package org.example.stock.support;

import org.example.stock.enums.TypeCompteTresorerie;
import org.example.stock.model.Boutique;
import org.example.stock.model.CompteTresorerieBoutique;

/** Accès aux comptes de base d'une boutique créée en mémoire par un test (les trois comptes principaux sont créés avec elle). */
public final class ComptesDeTest {
    private ComptesDeTest() {}

    public static CompteTresorerieBoutique de(Boutique boutique, TypeCompteTresorerie type) {
        return boutique.getComptesTresorerie().stream().filter(c -> c.getType() == type && c.isPrincipal()).findFirst().orElseThrow();
    }
}
