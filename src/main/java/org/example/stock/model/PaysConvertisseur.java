package org.example.stock.model;

import jakarta.persistence.AttributeConverter;
import org.example.stock.pays.Pays;

/** Stocke le pays d'une boutique par son code ISO sur deux lettres (« GN », « SN »…). */
public class PaysConvertisseur implements AttributeConverter<Pays, String> {
    @Override
    public String convertToDatabaseColumn(Pays pays) { return pays == null ? null : pays.getCode(); }

    @Override
    public Pays convertToEntityAttribute(String code) {
        if (code == null) return null;
        Pays pays = Pays.depuisCode(code);
        if (pays == null) throw new IllegalStateException("Pays inconnu en base : " + code);
        return pays;
    }
}
