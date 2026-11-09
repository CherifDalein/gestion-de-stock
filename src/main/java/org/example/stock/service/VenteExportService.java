package org.example.stock.service;

import org.example.stock.export.*;
import org.example.stock.model.Montants;
import org.example.stock.model.Utilisateur;
import org.example.stock.model.Vente;
import org.example.stock.repository.VenteExportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Export de la liste des ventes (Excel et PDF) : c'est l'exemple de référence du socle d'export (docs/lot-4.md).
 * Les lignes sont celles de {@link VenteService#rechercher(FiltreVentes)}, donc celles de l'écran, de la boutique du compte
 * connecté. Le bénéfice brut (coûts figés) n'est calculé et placé dans le tableau QUE pour le gérant.
 */
@Service
@Transactional(readOnly = true)
public class VenteExportService {
    private static final int PAQUET = 1000;

    private final VenteService ventes;
    private final VenteExportRepository marges;
    private final EcheanceService echeances;
    private final LimiteExport limite;

    public VenteExportService(VenteService ventes, VenteExportRepository marges, EcheanceService echeances, LimiteExport limite) {
        this.ventes = ventes; this.marges = marges; this.echeances = echeances; this.limite = limite;
    }

    /**
     * @param recherche texte de la recherche de l'écran (facultatif) : une vente est gardée si chaque mot figure dans le texte
     *                  de sa ligne (numéro, dates, client, vendeur, montants, statut), sans tenir compte des accents ni de la casse
     * @throws ExportTropVolumineuxException si le filtre donne plus de {@link LimiteExport#lignes()} ventes
     */
    public TableauExport preparer(FiltreVentes filtre, String recherche) {
        boolean gerant = DroitsExport.estGerant();
        LocalDate aujourdHui = echeances.aujourdHui();
        List<Vente> lues = ventes.rechercher(filtre, limite.lignes());
        limite.verifier(lues.size());
        String q = recherche == null ? "" : recherche.strip();
        List<Vente> retenues = q.isEmpty() ? lues : lues.stream().filter(v -> correspond(q, texteDeLaLigne(v, aujourdHui))).toList();

        // Coûts et bénéfices : jamais lus pour un caissier.
        Benefices benefices = gerant ? Benefices.de(marges, retenues) : null;

        List<Colonne> colonnes = new ArrayList<>(List.of(Colonne.texteCourt("Numéro"), Colonne.dateHeure("Date"), Colonne.texte("Client"),
                Colonne.texte("Vendeur"), Colonne.montant("Total"), Colonne.montant("Retours"), Colonne.montant("Versé"),
                Colonne.montant("Avance utilisée"), Colonne.montant("Reste"), Colonne.texteCourt("Statut"), Colonne.date("Échéance")));
        if (gerant) colonnes.add(Colonne.montant("Bénéfice brut"));

        TableauExport tableau = TableauExport.titre("Journal des ventes").nomFeuille("Ventes")
                .filtres(decrireFiltres(filtre, q)).colonnes(colonnes.toArray(Colonne[]::new));
        BigDecimal total = Montants.ZERO, retours = Montants.ZERO, verse = Montants.ZERO, avance = Montants.ZERO, reste = Montants.ZERO,
                benefice = Montants.ZERO;
        for (Vente v : retenues) {
            BigDecimal montant = Montants.ouZero(v.getMontantTotal()), retourne = Montants.ouZero(v.getMontantRetours()),
                    verseVente = Montants.ouZero(v.getMontantVerse()), avanceVente = Montants.ouZero(v.getAvanceUtilisee()),
                    resteVente = v.getResteAPayer();
            total = total.add(montant); retours = retours.add(retourne); verse = verse.add(verseVente);
            avance = avance.add(avanceVente); reste = reste.add(resteVente);
            List<Object> ligne = new ArrayList<>(Arrays.asList(numero(v), v.getDateVente(), client(v), vendeur(v), montant, retourne,
                    verseVente, avanceVente, resteVente, statut(v, aujourdHui), v.getDateEcheance()));
            if (gerant) {
                BigDecimal b = benefices.de(v.getId());
                benefice = benefice.add(b);
                ligne.add(b);
            }
            tableau.ligne(ligne.toArray());
        }
        List<Object> totaux = new ArrayList<>(Arrays.asList(retenues.size() + (retenues.size() > 1 ? " ventes" : " vente"), null, null, null,
                total, retours, verse, avance, reste, null, null));
        if (gerant) totaux.add(benefice);
        tableau.totaux(totaux.toArray());
        if (gerant && benefices.lignesSansCout() > 0) {
            tableau.note("Bénéfice brut : " + benefices.lignesSansCout() + (benefices.lignesSansCout() == 1
                    ? " ligne de vente sans coût connu, comptée à 0." : " lignes de vente sans coût connu, comptées à 0."));
        }
        if (gerant) tableau.note("Bénéfice brut = (prix − coût figé à la vente) × quantité, moins la marge des marchandises retournées.");
        return tableau;
    }

    private String decrireFiltres(FiltreVentes filtre, String recherche) {
        List<String> parties = new ArrayList<>();
        if (filtre.du() != null && filtre.au() != null) parties.add("Période du " + Textes.date(filtre.du()) + " au " + Textes.date(filtre.au()));
        else if (filtre.du() != null) parties.add("Depuis le " + Textes.date(filtre.du()));
        else if (filtre.au() != null) parties.add("Jusqu'au " + Textes.date(filtre.au()));
        if (filtre.vendeurId() != null) {
            parties.add("Vendeur : " + ventes.listerVendeursPossibles().stream().filter(u -> u.getId().equals(filtre.vendeurId()))
                    .map(VenteExportService::nomCompte).findFirst().orElse("inconnu"));
        }
        if (filtre.echues()) parties.add("Ventes échues non soldées");
        if (!recherche.isEmpty()) parties.add("Recherche : « " + recherche + " »");
        return parties.isEmpty() ? "Toutes les ventes" : String.join(" · ", parties);
    }

    private static String nomCompte(Utilisateur u) {
        return u.getNom() == null || u.getNom().isBlank() ? "Compte sans nom" : u.getNom().strip();
    }

    private static String numero(Vente v) { return v.getNumero() != null ? v.getNumero() : "#" + v.getId(); }

    private static String client(Vente v) { return v.getClient() != null ? v.getClient().getNom() : "Client de passage"; }

    private static String vendeur(Vente v) {
        return v.getVendeur() != null && v.getVendeur().getNom() != null && !v.getVendeur().getNom().isBlank()
                ? v.getVendeur().getNom() : "Non renseigné";
    }

    private static String statut(Vente v, LocalDate aujourdHui) {
        if (v.getResteAPayer().signum() <= 0) return "Réglée";
        return v.estEnRetard(aujourdHui) ? "À régler (en retard)" : "À régler";
    }

    // ----- Recherche -----

    private static String texteDeLaLigne(Vente v, LocalDate aujourdHui) {
        return String.join(" ", numero(v), Textes.dateHeure(v.getDateVente()), Textes.date(v.getDateEcheance()), client(v), vendeur(v),
                brut(v.getMontantTotal()), brut(v.getMontantRetours()), brut(v.getMontantVerse()), brut(v.getAvanceUtilisee()),
                brut(v.getResteAPayer()), statut(v, aujourdHui));
    }

    /** Montant en chiffres seuls, avec et sans séparateur de milliers : « 1500000 » et « 1 500 000 » retrouvent la vente. */
    private static String brut(BigDecimal m) {
        return RechercheListe.montantRecherchable(Montants.ouZero(m));
    }

    static boolean correspond(String recherche, String texte) {
        return RechercheListe.correspond(recherche, texte);
    }

    // ----- Bénéfices (gérant) -----

    private record Benefices(Map<Long, BigDecimal> parVente, long lignesSansCout) {
        static Benefices de(VenteExportRepository marges, List<Vente> ventes) {
            Map<Long, BigDecimal> parVente = new HashMap<>();
            long sansCout = 0;
            List<Long> ids = ventes.stream().map(Vente::getId).toList();
            for (int i = 0; i < ids.size(); i += PAQUET) {
                List<Long> paquet = ids.subList(i, Math.min(ids.size(), i + PAQUET));
                for (Object[] l : marges.margesDesVentes(paquet)) {
                    parVente.merge((Long) l[0], (BigDecimal) l[1], BigDecimal::add);
                    sansCout += ((Number) l[2]).longValue();
                }
                for (Object[] l : marges.margesDesRetours(paquet)) parVente.merge((Long) l[0], ((BigDecimal) l[1]).negate(), BigDecimal::add);
            }
            return new Benefices(parVente, sansCout);
        }

        BigDecimal de(Long venteId) { return parVente.getOrDefault(venteId, Montants.ZERO); }
    }
}
