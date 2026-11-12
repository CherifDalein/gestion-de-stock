package org.example.stock.service;

import org.example.stock.export.ContexteDocument;
import org.example.stock.export.DocumentPdfService;
import org.example.stock.export.FormatPapier;
import org.example.stock.export.Textes;
import org.example.stock.model.ComptageCaisse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/** Procès-verbal PDF A4 portrait d'un comptage de caisse (gabarit {@code pdf/comptage-caisse}) : billetage, décision, signatures du caissier et du gérant. */
@Service
public class ComptageCaissePdfService {
    /** Mises en forme exposées au gabarit sous le nom {@code fmt}. */
    public static final class Formats {
        public String montant(Number valeur) { return Textes.montant(valeur); }

        /** Écart signé : « + 5 000 » pour un excédent, « − 5 000 » pour un manque. */
        public String ecart(BigDecimal valeur) {
            if (valeur == null || valeur.signum() == 0) return "0";
            return (valeur.signum() > 0 ? "+ " : "− ") + Textes.montant(valeur.abs());
        }
    }

    private final DocumentPdfService pdf;

    public ComptageCaissePdfService(DocumentPdfService pdf) { this.pdf = pdf; }

    public static String numero(ComptageCaisse comptage) { return "CC-" + comptage.getId(); }

    public byte[] rendre(ComptageCaisse comptage, ContexteDocument contexte) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("titre", "Comptage de caisse " + numero(comptage));
        variables.put("c", comptage);
        variables.put("numero", numero(comptage));
        variables.put("fmt", new Formats());
        return pdf.rendre("pdf/comptage-caisse", variables, contexte, FormatPapier.A4_PORTRAIT);
    }
}
