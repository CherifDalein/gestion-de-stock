package org.example.stock.config;

import org.example.stock.enums.TypeCompteTresorerie;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Lit le type d'un compte de trésorerie envoyé par un formulaire, avec les anciennes valeurs : {@code CAISSE} devient {@code ESPECES}
 * et {@code ORANGE_MONEY} devient {@code MOBILE_MONEY} (formulaires ouverts avant la mise à jour). Une valeur inconnue reste une erreur
 * de liaison, comme pour toute énumération.
 */
@Component
public class TypeCompteTresorerieConvertisseur implements Converter<String, TypeCompteTresorerie> {
    @Override
    public TypeCompteTresorerie convert(String source) {
        if (source == null || source.isBlank()) return null;
        TypeCompteTresorerie type = TypeCompteTresorerie.depuisSaisie(source);
        if (type == null) throw new IllegalArgumentException("Type de compte inconnu : " + source.strip());
        return type;
    }
}
