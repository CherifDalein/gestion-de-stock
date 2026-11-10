package org.example.stock.service;

import org.example.stock.export.ContexteDocument;
import org.example.stock.export.DocumentPdfService;
import org.example.stock.export.FormatPapier;
import org.example.stock.export.Textes;
import org.example.stock.service.JustificatifSoldeService.JustificatifPartenaire;
import org.example.stock.service.JustificatifSoldeService.JustificatifTresorerie;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** Met un justificatif de solde en PDF A4 portrait (gabarits {@code pdf/justificatif-partenaire} et {@code pdf/justificatif-tresorerie}). */
@Service
public class JustificatifSoldePdfService {
    /** Mises en forme exposées aux gabarits sous le nom {@code fmt} (les montants s'écrivent comme dans tous les documents). */
    public static final class Formats {
        public String montant(BigDecimal valeur) { return Textes.montant(valeur); }

        public String date(LocalDate date) { return Textes.date(date); }
    }

    private final DocumentPdfService pdf;

    public JustificatifSoldePdfService(DocumentPdfService pdf) { this.pdf = pdf; }

    public byte[] rendre(JustificatifPartenaire j, ContexteDocument contexte) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("titre", "Justificatif de solde " + j.numero());
        variables.put("j", j);
        variables.put("fmt", new Formats());
        return pdf.rendre("pdf/justificatif-partenaire", variables, contexte, FormatPapier.A4_PORTRAIT);
    }

    public byte[] rendre(JustificatifTresorerie j, ContexteDocument contexte) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("titre", "Justificatif de solde de trésorerie " + j.numero());
        variables.put("j", j);
        variables.put("fmt", new Formats());
        return pdf.rendre("pdf/justificatif-tresorerie", variables, contexte, FormatPapier.A4_PORTRAIT);
    }
}
