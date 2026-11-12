package org.example.stock.service;

import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.example.stock.enums.TypeCompteTresorerie;
import org.example.stock.export.*;
import org.example.stock.model.Montants;
import org.example.stock.model.MouvementCaisse;
import org.example.stock.repository.MouvementCaisseRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Export du journal de trésorerie ({@code /caisse/journal}) en Excel et PDF. Réservé au gérant, comme le journal : la règle
 * de sécurité « /caisse/** » refuse le caissier et le service le revérifie. Les mouvements sont ceux de la boutique du compte connecté,
 * filtrés comme l'historique de l'écran (compte, source, sens, période, recherche) ; l'export ajoute les soldes que l'écran affiche.
 */
@Service
@Transactional(readOnly = true)
public class TresorerieExportService {
    private static final int PAQUET = 1000;
    public static final Set<String> SENS = Set.of("entree", "sortie", "neutre", "inconnu");
    public static final String SOURCE_VIDE = "Non renseignée", SOURCE_INITIALE = "Solde initial";

    private final MouvementCaisseRepository mouvements;
    private final CaisseService caisse;
    private final BoutiqueService boutiques;
    private final LimiteExport limite;

    public TresorerieExportService(MouvementCaisseRepository mouvements, CaisseService caisse, BoutiqueService boutiques, LimiteExport limite) {
        this.mouvements = mouvements; this.caisse = caisse; this.boutiques = boutiques; this.limite = limite;
    }

    /**
     * Filtres du journal.
     *
     * @param compteId identifiant du compte de trésorerie, ou null pour tous les comptes
     * @param source source telle que l'écran l'affiche (« Solde initial », « VENTE », « Non renseignée »…), ou null pour toutes
     * @param sens   {@code entree}, {@code sortie}, {@code neutre} ou {@code inconnu} (montant non renseigné), ou null
     * @param du     premier jour inclus, null sans borne ; avec une borne, les mouvements sans date sont exclus (comme à l'écran)
     * @param au     dernier jour inclus
     */
    public record FiltreJournal(Long compteId, String source, String sens, LocalDate du, LocalDate au) { }

    /** @throws ExportTropVolumineuxException si le filtre (hors recherche) donne plus de {@link LimiteExport#lignes()} mouvements */
    public TableauExport preparer(FiltreJournal filtre, String recherche) {
        DroitsExport.exigerGerant();
        String nomCompte = filtre.compteId() == null ? null : caisse.nomDuCompte(filtre.compteId());
        List<MouvementCaisse> lus = mouvements.findAll(specification(filtre), PageRequest.of(0, limite.lignes() + 1,
                Sort.by(Sort.Order.desc("dateMouvement"), Sort.Order.desc("id")))).getContent();
        limite.verifier(lus.size());
        Map<Long, MouvementCaisse> annulations = annulationsDe(lus);
        String q = recherche == null ? "" : recherche.strip();
        List<MouvementCaisse> retenus = q.isEmpty() ? lus : lus.stream().filter(m -> RechercheListe.correspond(q, texteDeLaLigne(m, annulations.get(m.getId())))).toList();

        TableauExport tableau = TableauExport.titre("Journal de trésorerie").nomFeuille("Trésorerie")
                .filtres(decrireFiltres(filtre, nomCompte, q))
                .colonnes(Colonne.dateHeure("Date et heure"), Colonne.texteCourt("Compte"), Colonne.texteCourt("Sens"), Colonne.montant("Montant"),
                        Colonne.texteCourt("Source"), Colonne.texte("Motif"), Colonne.texte("Utilisateur"), Colonne.texte("Annulation"));
        BigDecimal entrees = Montants.ZERO, sorties = Montants.ZERO;
        for (MouvementCaisse m : retenus) {
            BigDecimal montant = m.getMontant();
            if (montant != null && montant.signum() > 0) entrees = entrees.add(montant);
            if (montant != null && montant.signum() < 0) sorties = sorties.add(montant.negate());
            tableau.ligne(m.getDateMouvement(), m.getCompte().getNom(), sens(m), montant, source(m), motif(m), utilisateur(m), mentionAnnulation(m, annulations.get(m.getId())));
        }
        tableau.totaux(retenus.size() + (retenus.size() > 1 ? " mouvements" : " mouvement"), null, null, entrees.subtract(sorties), null, null, null, null);
        tableau.note("Dans la sélection : entrées " + Textes.montant(entrees) + " GNF · sorties " + Textes.montant(sorties) + " GNF · variation nette "
                + Textes.montant(entrees.subtract(sorties)) + " GNF (transferts et soldes initiaux compris).");
        ajouterSoldesDeLEcran(tableau);
        return tableau;
    }

    /** Les soldes et indicateurs du jour que {@code /caisse/journal} affiche en tête, indépendants des filtres comme à l'écran. */
    private void ajouterSoldesDeLEcran(TableauExport tableau) {
        LocalDate jour = LocalDate.now();
        List<CaisseService.SoldeCompte> parCompte = caisse.getSoldesParCompte();
        List<String> comptes = new ArrayList<>();
        for (CaisseService.SoldeCompte c : parCompte) comptes.add(c.compte().getNom() + " " + Textes.montant(c.solde()) + " GNF");
        // Un type qui compte plusieurs comptes porte aussi son sous-total.
        Map<TypeCompteTresorerie, BigDecimal> parType = caisse.getSoldesParType();
        for (TypeCompteTresorerie type : TypeCompteTresorerie.values()) {
            if (parCompte.stream().filter(c -> c.compte().getType() == type).count() > 1) {
                comptes.add("Total " + type.getLibelle() + " " + Textes.montant(parType.get(type)) + " GNF");
            }
        }
        tableau.note("Soldes actuels de la boutique, indépendants des filtres : " + String.join(" · ", comptes) + " · Total " + Textes.montant(caisse.getSoldeActuel()) + " GNF.");
        tableau.note("Journée du " + Textes.date(jour) + " : solde à l’ouverture " + Textes.montant(caisse.getSoldeOuverture(jour)) + " GNF · entrées "
                + Textes.montant(caisse.getEntreesDuJour(jour)) + " GNF · sorties " + Textes.montant(caisse.getSortiesDuJour(jour)) + " GNF · variation "
                + Textes.montant(caisse.getNetDuJour(jour)) + " GNF (hors transferts et soldes initiaux).");
    }

    private Specification<MouvementCaisse> specification(FiltreJournal filtre) {
        Long boutiqueId = boutiques.idBoutiqueCourante();
        return (m, requete, cb) -> {
            if (requete.getResultType() != Long.class && requete.getResultType() != long.class) {
                m.fetch("utilisateur", JoinType.LEFT);
                m.fetch("annulationDe", JoinType.LEFT);
            }
            List<Predicate> conditions = new ArrayList<>();
            conditions.add(cb.equal(m.get("boutique").get("id"), boutiqueId));
            if (filtre.compteId() != null) conditions.add(cb.equal(m.get("compte").get("id"), filtre.compteId()));
            if (filtre.sens() != null) {
                var montant = m.<BigDecimal>get("montant");
                conditions.add(switch (filtre.sens()) {
                    case "entree" -> cb.greaterThan(montant, BigDecimal.ZERO);
                    case "sortie" -> cb.lessThan(montant, BigDecimal.ZERO);
                    case "neutre" -> cb.equal(montant, BigDecimal.ZERO);
                    default -> cb.isNull(montant);
                });
            }
            if (filtre.source() != null) {
                var source = m.<String>get("source");
                if (SOURCE_INITIALE.equals(filtre.source())) conditions.add(cb.equal(source, "SOLDE_INITIAL"));
                else if (SOURCE_VIDE.equals(filtre.source())) conditions.add(cb.or(cb.isNull(source), cb.equal(cb.trim(source), "")));
                else conditions.add(cb.equal(cb.trim(source), org.example.stock.model.SourceMouvement.depuisLibelle(filtre.source())));
            }
            if (filtre.du() != null) conditions.add(cb.greaterThanOrEqualTo(m.<LocalDateTime>get("dateMouvement"), filtre.du().atStartOfDay()));
            if (filtre.au() != null) conditions.add(cb.lessThan(m.<LocalDateTime>get("dateMouvement"), filtre.au().plusDays(1).atStartOfDay()));
            return cb.and(conditions.toArray(Predicate[]::new));
        };
    }

    /** Annulation de chaque mouvement lu, par identifiant du mouvement annulé (lecture par paquets). */
    private Map<Long, MouvementCaisse> annulationsDe(List<MouvementCaisse> lus) {
        Map<Long, MouvementCaisse> resultat = new HashMap<>();
        for (int i = 0; i < lus.size(); i += PAQUET) resultat.putAll(caisse.annulationsDe(lus.subList(i, Math.min(lus.size(), i + PAQUET))));
        return resultat;
    }

    private static String decrireFiltres(FiltreJournal f, String nomCompte, String recherche) {
        List<String> parties = new ArrayList<>();
        if (f.du() != null && f.au() != null) parties.add("Période du " + Textes.date(f.du()) + " au " + Textes.date(f.au()));
        else if (f.du() != null) parties.add("Depuis le " + Textes.date(f.du()));
        else if (f.au() != null) parties.add("Jusqu'au " + Textes.date(f.au()));
        if (nomCompte != null) parties.add("Compte : " + nomCompte);
        if (f.source() != null) parties.add("Source : " + f.source());
        if (f.sens() != null) parties.add("Sens : " + libelleFiltreSens(f.sens()));
        if (!recherche.isEmpty()) parties.add("Recherche : « " + recherche + " »");
        return parties.isEmpty() ? "Journal complet" : String.join(" · ", parties);
    }

    private static String libelleFiltreSens(String sens) {
        return switch (sens) {
            case "entree" -> "entrées";
            case "sortie" -> "sorties";
            case "neutre" -> "sans variation";
            default -> "montant non renseigné";
        };
    }

    private static String sens(MouvementCaisse m) {
        String base = m.getMontant() == null ? "Non renseigné" : (m.getMontant().signum() > 0 ? "Entrée" : (m.getMontant().signum() < 0 ? "Sortie" : "Sans variation"));
        return "CORRECTION_ENTREE".equals(m.getType()) ? base + " (correction)" : base;
    }

    private static String source(MouvementCaisse m) {
        if ("SOLDE_INITIAL".equals(m.getSource())) return SOURCE_INITIALE;
        return m.getSource() != null && !m.getSource().isBlank() ? org.example.stock.model.SourceMouvement.libelle(m.getSource().strip()) : SOURCE_VIDE;
    }

    private static String motif(MouvementCaisse m) {
        String motif = m.getMotif() != null && !m.getMotif().isBlank() ? m.getMotif() : "Motif non renseigné";
        if (m.getOperationTresorerie() == null) return motif;
        return motif + " · " + (m.getOperationTresorerie().getType() == null ? "Opération" : m.getOperationTresorerie().getType().getLibelle()) + " #" + m.getOperationTresorerie().getId();
    }

    private static String utilisateur(MouvementCaisse m) {
        return m.getUtilisateur() != null && m.getUtilisateur().getNom() != null && !m.getUtilisateur().getNom().isBlank() ? m.getUtilisateur().getNom() : "Système";
    }

    /** « Annulé le … · motif : … » sur un mouvement annulé, « Annulation du mouvement du … » sur l'annulation elle-même ; vide sinon. */
    private static String mentionAnnulation(MouvementCaisse m, MouvementCaisse annulation) {
        List<String> mentions = new ArrayList<>();
        if (annulation != null) {
            mentions.add("Annulé le " + (annulation.getDateMouvement() != null ? Textes.dateHeure(annulation.getDateMouvement()) : "date non renseignée")
                    + " · motif : " + Objects.toString(annulation.getMotif(), ""));
        }
        if ("ANNULATION".equals(m.getSource()) && m.getAnnulationDe() != null) {
            mentions.add("Annulation du mouvement du " + (m.getAnnulationDe().getDateMouvement() != null
                    ? Textes.dateHeure(m.getAnnulationDe().getDateMouvement()) : "date non renseignée"));
        }
        return mentions.isEmpty() ? null : String.join(" ; ", mentions);
    }

    private static String texteDeLaLigne(MouvementCaisse m, MouvementCaisse annulation) {
        BigDecimal montant = m.getMontant();
        return String.join(" ", m.getDateMouvement() != null ? Textes.dateHeure(m.getDateMouvement()) + " " + m.getDateMouvement() : "Date non renseignée",
                motif(m), Objects.toString(mentionAnnulation(m, annulation), ""), utilisateur(m), m.getCompte().getNom(), source(m), sens(m),
                montant == null ? "Non renseigné" : RechercheListe.montantRecherchable(montant.abs()), Objects.toString(m.getType(), ""));
    }
}
