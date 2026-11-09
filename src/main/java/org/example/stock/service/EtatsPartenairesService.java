package org.example.stock.service;

import org.example.stock.enums.CompteTresorerie;
import org.example.stock.export.Colonne;
import org.example.stock.export.FormatPapier;
import org.example.stock.export.LimiteExport;
import org.example.stock.export.TableauExport;
import org.example.stock.export.Textes;
import org.example.stock.model.*;
import org.example.stock.repository.ClientRepository;
import org.example.stock.repository.EtatsRepository;
import org.example.stock.repository.FournisseurRepository;
import org.example.stock.repository.SoldePartenaireRepository.Totaux;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * États de créances et de dettes, et relevé d'un partenaire (gérant seulement). Les totaux sont ceux de
 * {@link SoldePartenaireService} (les mêmes que le tableau de bord) ; le détail par facture vient des factures non soldées
 * et est rapproché de ces totaux : en cas d'écart, l'état est refusé plutôt que d'afficher des chiffres qui ne s'additionnent pas.
 */
@Service
@Transactional(readOnly = true)
public class EtatsPartenairesService {
    /** Côté de l'état : les clients qui doivent à la boutique, ou les fournisseurs à qui la boutique doit. */
    public enum Cote {
        CLIENT("client", "Ventes sans client", "créances", "Créances", "Client"),
        FOURNISSEUR("fournisseur", "Achats sans fournisseur", "dettes", "Dettes", "Fournisseur");

        final String partenaire, sansPartenaire, pluriel, titre, colonne;

        Cote(String partenaire, String sansPartenaire, String pluriel, String titre, String colonne) {
            this.partenaire = partenaire; this.sansPartenaire = sansPartenaire; this.pluriel = pluriel; this.titre = titre; this.colonne = colonne;
        }
    }

    private final EtatsRepository etats;
    private final SoldePartenaireService soldes;
    private final ClientRepository clients;
    private final FournisseurRepository fournisseurs;
    private final BoutiqueService boutiques;
    private final EcheanceService echeances;
    private final LimiteExport limite;

    public EtatsPartenairesService(EtatsRepository etats, SoldePartenaireService soldes, ClientRepository clients,
                                   FournisseurRepository fournisseurs, BoutiqueService boutiques, EcheanceService echeances,
                                   LimiteExport limite) {
        this.etats = etats; this.soldes = soldes; this.clients = clients; this.fournisseurs = fournisseurs;
        this.boutiques = boutiques; this.echeances = echeances; this.limite = limite;
    }

    // ==== État des créances / des dettes ======================================================================

    /** Une facture non soldée (vente ou achat), dans une forme commune. */
    private record Facture(Long partenaireId, String numero, LocalDateTime date, LocalDate echeance, BigDecimal totalEffectif,
                           BigDecimal verse, BigDecimal avance, BigDecimal reste) { }

    /** Tout ce qui est dû par ou à un partenaire : factures, reste du solde de départ, avance. */
    private static final class Groupe {
        Long id; String nom;
        final List<Facture> factures = new ArrayList<>();
        SoldePartenaire solde;
        BigDecimal reste = Montants.ZERO, enRetard = Montants.ZERO, avance = Montants.ZERO;
        LocalDate plusAncienne;
    }

    /**
     * @param reference date de référence : une échéance est en retard quand elle est strictement antérieure. Les restes à payer
     *                  sont toujours ceux du jour de l'édition (aucun historique des restes n'est conservé).
     */
    public Etat creancesOuDettes(Cote cote, LocalDate reference) {
        DroitsEtats.exigerGerant();
        if (reference == null || reference.getYear() < PeriodeEtat.ANNEE_MINIMALE || reference.getYear() > PeriodeEtat.ANNEE_MAXIMALE) {
            throw new IllegalArgumentException("La date de l'état est invalide.");
        }
        Long boutiqueId = boutiques.idBoutiqueCourante();
        LocalDate jour = echeances.aujourdHui();
        boolean client = cote == Cote.CLIENT;

        // Totaux : les calculs du tableau de bord.
        Map<Long, SoldePartenaire> soldesParId = client ? soldes.soldesClients() : soldes.soldesFournisseurs();
        Totaux totauxService = client ? soldes.creancesClients() : soldes.dettesFournisseurs();
        BigDecimal avancesService = client ? soldes.avancesClients() : soldes.avancesFournisseurs();
        Map<Long, String> noms = new HashMap<>();
        if (client) clients.findAllByBoutiqueId(boutiqueId).forEach(c -> noms.put(c.getId(), c.getNom()));
        else fournisseurs.findAllByBoutiqueId(boutiqueId).forEach(f -> noms.put(f.getId(), f.getNom()));

        // Détail : factures non soldées.
        List<Facture> factures = new ArrayList<>();
        if (client) {
            List<Vente> lues = etats.ventesNonSoldees(boutiqueId, PageRequest.of(0, limite.lignes() + 1));
            limite.verifier(lues.size());
            for (Vente v : lues) {
                factures.add(new Facture(v.getClient() == null ? null : v.getClient().getId(), v.getNumero() != null ? v.getNumero() : "Vente n° " + v.getId(),
                        v.getDateVente(), v.getDateEcheance(), v.getTotalEffectif(), Montants.ouZero(v.getMontantVerse()),
                        Montants.ouZero(v.getAvanceUtilisee()), v.getResteAPayer()));
            }
        } else {
            List<Achat> lus = etats.achatsNonSoldes(boutiqueId, PageRequest.of(0, limite.lignes() + 1));
            limite.verifier(lus.size());
            for (Achat a : lus) {
                factures.add(new Facture(a.getFournisseur() == null ? null : a.getFournisseur().getId(), a.getNumero() != null ? a.getNumero() : "Achat n° " + a.getId(),
                        a.getDateAchat(), a.getDateEcheance(), a.getTotalEffectif(), Montants.ouZero(a.getMontantVerse()),
                        Montants.ouZero(a.getAvanceUtilisee()), a.getResteAPayer()));
            }
        }

        Map<Long, Groupe> groupes = new LinkedHashMap<>();
        Groupe sans = new Groupe();
        for (Facture f : factures) {
            Groupe g = f.partenaireId() == null ? sans : groupes.computeIfAbsent(f.partenaireId(), id -> nouveau(id, noms, soldesParId));
            g.factures.add(f);
        }
        for (var e : soldesParId.entrySet()) {
            SoldePartenaire s = e.getValue();
            if (s.resteSoldeDepart().signum() > 0 || s.avanceDisponible().signum() > 0) groupes.computeIfAbsent(e.getKey(), id -> nouveau(id, noms, soldesParId));
        }
        BigDecimal resteFactures = Montants.ZERO, resteDepart = Montants.ZERO, retardTotal = Montants.ZERO, avances = Montants.ZERO;
        for (Groupe g : groupes.values()) { cumuler(g, reference); }
        cumuler(sans, reference);
        for (Groupe g : groupes.values()) {
            resteDepart = resteDepart.add(g.solde == null ? Montants.ZERO : g.solde.resteSoldeDepart());
            resteFactures = resteFactures.add(g.factures.stream().map(Facture::reste).reduce(Montants.ZERO, BigDecimal::add));
            retardTotal = retardTotal.add(g.enRetard);
            avances = avances.add(g.avance);
        }
        resteFactures = resteFactures.add(sans.factures.stream().map(Facture::reste).reduce(Montants.ZERO, BigDecimal::add));
        retardTotal = retardTotal.add(sans.enRetard);
        BigDecimal total = resteFactures.add(resteDepart);

        // Rapprochement avec les totaux du tableau de bord : jamais d'état dont les lignes ne donnent pas le total.
        if (total.compareTo(totauxService.total()) != 0 || avances.compareTo(avancesService) != 0
                || (reference.equals(jour) && retardTotal.compareTo(totauxService.enRetard()) != 0)) {
            throw new IncoherenceAvanceException("Le détail des " + cote.pluriel + " ne concorde pas avec les totaux de la boutique : "
                    + "vérifiez les factures et les règlements de soldes de départ avant d'éditer cet état.");
        }

        List<Groupe> ordre = new ArrayList<>(groupes.values());
        Comparator<LocalDate> ancienneD = Comparator.nullsLast(Comparator.<LocalDate>naturalOrder());
        ordre.sort(Comparator.<Groupe, BigDecimal>comparing(g -> g.enRetard).reversed()
                .thenComparing(Comparator.<Groupe, BigDecimal>comparing(g -> g.reste).reversed())
                .thenComparing(g -> g.plusAncienne, ancienneD)
                .thenComparing(g -> g.nom.toLowerCase(Locale.ROOT)).thenComparing(g -> g.id));

        TableauExport detail = TableauExport.titre("Détail par " + cote.partenaire).nomFeuille("Détail")
                .filtres("Date de référence : " + Textes.date(reference))
                .colonnes(Colonne.texte(cote.colonne), Colonne.texteCourt("Document"), Colonne.date("Date"), Colonne.date("Échéance"),
                        Colonne.montant("Total effectif"), Colonne.montant("Versé"), Colonne.montant("Avance utilisée"),
                        Colonne.montant("Reste"), Colonne.entier("Jours de retard"));
        for (Groupe g : ordre) {
            BigDecimal net = Montants.ZERO;
            List<Facture> triees = new ArrayList<>(g.factures);
            triees.sort(Comparator.comparing(Facture::echeance, ancienneD).thenComparing(Facture::date, Comparator.nullsLast(Comparator.naturalOrder())));
            for (Facture f : triees) {
                detail.ligne(g.nom, f.numero(), f.date(), f.echeance(), f.totalEffectif(), f.verse(), f.avance(), f.reste(), retard(f.echeance(), reference));
                net = net.add(f.reste());
            }
            SoldePartenaire s = g.solde;
            if (s != null && s.resteSoldeDepart().signum() > 0) {
                BigDecimal du = (client ? s.soldeDepart() : s.soldeDepart().negate()).max(BigDecimal.ZERO);
                detail.ligne(g.nom, "Solde de départ", null, s.echeanceSoldeDepart(), du, s.soldeDepartRegle(), null, s.resteSoldeDepart(),
                        retard(s.echeanceSoldeDepart(), reference));
                net = net.add(s.resteSoldeDepart());
            }
            if (g.avance.signum() > 0) {
                detail.ligne(g.nom, client ? "Avance du client" : "Avance chez lui", null, null, null, null, null, g.avance.negate(), null);
                net = net.subtract(g.avance);
            }
            detail.sousTotal("Total " + g.nom, null, null, null, null, null, null, net, null);
        }
        if (!sans.factures.isEmpty()) {
            BigDecimal t = sum(sans.factures, Facture::totalEffectif), v = sum(sans.factures, Facture::verse), a = sum(sans.factures, Facture::avance);
            LocalDate plusAncienne = sans.factures.stream().map(Facture::echeance).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
            int n = sans.factures.size();
            detail.sousTotal(cote.sansPartenaire, n + (n > 1 ? " factures" : " facture"), null, plusAncienne, t, v, a,
                    sum(sans.factures, Facture::reste), retard(plusAncienne, reference));
        }
        detail.totaux("Total des " + cote.pluriel + " (hors avances)", null, null, null, null, null, null, total, null);
        detail.note("Total effectif = total de la facture − retours. Reste = total effectif − versé − avance utilisée, jamais négatif. "
                + "Chaque " + cote.partenaire + " porte son propre solde : l'avance de l'un n'est jamais déduite de la dette d'un autre.");

        BigDecimal enRetard = retardTotal;
        TableauExport recap = TableauExport.titre("Récapitulatif").nomFeuille("Récapitulatif")
                .filtres("Date de référence : " + Textes.date(reference)).colonnes(Colonne.texte("Rubrique"), Colonne.montant("Montant"))
                .ligne("Restes à payer des factures" + (client ? " (ventes sans client comprises)" : " (achats sans fournisseur compris)"), resteFactures)
                .ligne("Restes des soldes de départ", resteDepart)
                .sousTotal("Total des " + cote.pluriel, total)
                .ligne("   dont en retard au " + Textes.date(reference), enRetard)
                .ligne(client ? "Avances disponibles des clients" : "Avances versées aux fournisseurs", avances)
                .totaux(client ? "Solde net (créances − avances des clients)" : "Solde net (dettes − avances chez les fournisseurs)", total.subtract(avances));

        detail.note("Les restes à payer sont ceux du jour de l'édition (" + Textes.date(jour) + "), comme au tableau de bord : l'application ne conserve pas "
                + "l'historique des restes. La date de référence sert à décider si une échéance est en retard (échéance strictement antérieure à cette date) "
                + "et à compter les jours de retard.");
        if (!reference.equals(jour)) {
            detail.note("La date de référence (" + Textes.date(reference) + ") n'est pas le jour de l'édition (" + Textes.date(jour) + ") : les montants restent ceux du "
                    + Textes.date(jour) + ", seuls les retards sont calculés à la date choisie.");
        }
        String titre = client ? "État des créances" : "État des dettes";
        return new Etat(client ? "creances" : "dettes", titre, "Date de référence : " + Textes.date(reference), List.of(),
                List.of(detail, recap), FormatPapier.A4_PAYSAGE, client ? "creances" : "dettes", null, reference);
    }

    private static Groupe nouveau(Long id, Map<Long, String> noms, Map<Long, SoldePartenaire> soldesParId) {
        Groupe g = new Groupe();
        g.id = id; g.nom = noms.getOrDefault(id, "Partenaire n° " + id); g.solde = soldesParId.get(id);
        return g;
    }

    private static void cumuler(Groupe g, LocalDate reference) {
        for (Facture f : g.factures) {
            g.reste = g.reste.add(f.reste());
            if (f.echeance() != null && f.echeance().isBefore(reference)) g.enRetard = g.enRetard.add(f.reste());
            g.plusAncienne = plusTot(g.plusAncienne, f.echeance());
        }
        if (g.solde != null) {
            g.avance = g.solde.avanceDisponible();
            if (g.solde.resteSoldeDepart().signum() > 0) {
                g.reste = g.reste.add(g.solde.resteSoldeDepart());
                LocalDate e = g.solde.echeanceSoldeDepart();
                if (e != null && e.isBefore(reference)) g.enRetard = g.enRetard.add(g.solde.resteSoldeDepart());
                g.plusAncienne = plusTot(g.plusAncienne, e);
            }
        }
    }

    private static LocalDate plusTot(LocalDate a, LocalDate b) {
        return a == null ? b : (b == null || a.isBefore(b) ? a : b);
    }

    private static Integer retard(LocalDate echeance, LocalDate reference) {
        return echeance != null && echeance.isBefore(reference) ? (int) ChronoUnit.DAYS.between(echeance, reference) : null;
    }

    private static BigDecimal sum(List<Facture> factures, java.util.function.Function<Facture, BigDecimal> champ) {
        return factures.stream().map(champ).reduce(Montants.ZERO, BigDecimal::add);
    }

    // ==== Relevé d'un partenaire ===============================================================================

    /** Ligne du livre : effet signé sur ce que le partenaire doit à la boutique (client) ou ce que la boutique doit (fournisseur). */
    private record Evenement(LocalDateTime date, int ordre, long id, String document, String libelle, CompteTresorerie compte, BigDecimal effet) { }

    private static final int FACTURE = 0, VERSEMENT = 1, RETOUR = 2, REMBOURSEMENT = 3, AVOIR = 4, REGLEMENT = 5, ANNULATION = 6;

    /**
     * Relevé d'un client (créances) ou d'un fournisseur (dettes) : toutes les opérations de la période avec le solde progressif.
     * Solde = ce que le partenaire doit à la boutique (client) ou ce que la boutique lui doit (fournisseur) ; négatif : avance.
     * Le solde de départ de la fiche et les opérations antérieures à la période sont reportés en tête.
     */
    public Etat releve(Cote cote, Long partenaireId, PeriodeEtat periode) {
        DroitsEtats.exigerGerant();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        boolean client = cote == Cote.CLIENT;
        String nom; BigDecimal soldeDepart; BigDecimal soldeFiche;
        if (client) {
            Client c = clients.findByIdAndBoutiqueId(partenaireId, boutiqueId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Client introuvable"));
            nom = c.getNom(); soldeDepart = Montants.ouZero(c.getSoldeInitial()); soldeFiche = soldes.soldeClient(partenaireId).soldeNet();
        } else {
            Fournisseur f = fournisseurs.findByIdAndBoutiqueId(partenaireId, boutiqueId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fournisseur introuvable"));
            nom = f.getNom(); soldeDepart = Montants.ouZero(f.getSoldeInitial()).negate(); soldeFiche = soldes.soldeFournisseur(partenaireId).soldeNet().negate();
        }
        var max = PageRequest.of(0, limite.lignes() + 1);
        List<Evenement> evenements = new ArrayList<>();
        List<MouvementCaisse> mouvements = client ? etats.mouvementsDuClient(boutiqueId, partenaireId, max) : etats.mouvementsDuFournisseur(boutiqueId, partenaireId, max);
        limite.verifier(mouvements.size());
        List<Retour> retours = client ? etats.retoursDuClient(boutiqueId, partenaireId, max) : etats.retoursDuFournisseur(boutiqueId, partenaireId, max);
        limite.verifier(retours.size());

        // Règlements ultérieurs nets d'annulation par facture, et compte du paiement saisi à la création.
        Map<Long, BigDecimal> reglementsNets = new HashMap<>();
        Map<Long, CompteTresorerie> compteCreation = new HashMap<>();
        for (MouvementCaisse m : mouvements) {
            Long documentId = documentId(m, client);
            BigDecimal valeur = m.getMontant() == null ? Montants.ZERO : m.getMontant().abs();
            if (documentId == null) continue;
            switch (String.valueOf(m.getSource())) {
                case SourceMouvement.REGLEMENT_VENTE, SourceMouvement.REGLEMENT_ACHAT -> reglementsNets.merge(documentId, valeur, BigDecimal::add);
                case SourceMouvement.ANNULATION -> reglementsNets.merge(documentId, valeur.negate(), BigDecimal::add);
                case SourceMouvement.VENTE, SourceMouvement.ACHAT -> compteCreation.putIfAbsent(documentId, m.getCompte());
                default -> { }
            }
        }
        record Doc(Long id, String numero, LocalDateTime date, BigDecimal total, BigDecimal verse) { }
        List<Doc> documents = new ArrayList<>();
        if (client) {
            List<Vente> ventes = etats.ventesDuClient(boutiqueId, partenaireId, max);
            limite.verifier(ventes.size());
            for (Vente v : ventes) documents.add(new Doc(v.getId(), numero(v.getNumero(), "Vente", v.getId()), v.getDateVente(),
                    Montants.ouZero(v.getMontantTotal()), Montants.ouZero(v.getMontantVerse())));
        } else {
            List<Achat> achats = etats.achatsDuFournisseur(boutiqueId, partenaireId, max);
            limite.verifier(achats.size());
            for (Achat a : achats) documents.add(new Doc(a.getId(), numero(a.getNumero(), "Achat", a.getId()), a.getDateAchat(),
                    Montants.ouZero(a.getMontantTotal()), Montants.ouZero(a.getMontantVerse())));
        }
        Map<Long, String> numeros = new HashMap<>();
        for (Doc d : documents) {
            numeros.put(d.id(), d.numero());
            evenements.add(new Evenement(d.date(), FACTURE, d.id(), d.numero(), client ? "Facture de vente" : "Facture d'achat", null, d.total()));
            BigDecimal creation = d.verse().subtract(reglementsNets.getOrDefault(d.id(), Montants.ZERO));
            if (creation.signum() != 0) {
                evenements.add(new Evenement(d.date(), VERSEMENT, d.id(), d.numero(), client ? "Versement à la création de la vente" : "Versement à la création de l'achat",
                        compteCreation.get(d.id()), creation.negate()));
            }
        }
        for (MouvementCaisse m : mouvements) {
            BigDecimal valeur = m.getMontant() == null ? Montants.ZERO : m.getMontant().abs();
            Long documentId = documentId(m, client);
            String source = String.valueOf(m.getSource());
            switch (source) {
                case SourceMouvement.REGLEMENT_VENTE, SourceMouvement.REGLEMENT_ACHAT ->
                        evenements.add(new Evenement(m.getDateMouvement(), REGLEMENT, m.getId(), numeros.getOrDefault(documentId, ""), "Règlement", m.getCompte(), valeur.negate()));
                case SourceMouvement.SOLDE_CLIENT, SourceMouvement.SOLDE_FOURNISSEUR ->
                        evenements.add(new Evenement(m.getDateMouvement(), REGLEMENT, m.getId(), "", "Règlement du solde de départ", m.getCompte(), valeur.negate()));
                case SourceMouvement.ANNULATION -> evenements.add(new Evenement(m.getDateMouvement(), ANNULATION, m.getId(),
                        documentId == null ? "" : numeros.getOrDefault(documentId, ""),
                        (documentId == null ? "Annulation du règlement du solde de départ" : "Annulation d'un règlement")
                                + (m.getMotif() == null || m.getMotif().isBlank() ? "" : " (" + m.getMotif().strip() + ")"), m.getCompte(), valeur));
                default -> { }
            }
        }
        for (Retour r : retours) {
            BigDecimal total = Montants.ouZero(r.getMontantTotal());
            String facture = client ? (r.getVente() == null ? "" : numero(r.getVente().getNumero(), "Vente", r.getVente().getId()))
                    : (r.getAchat() == null ? "" : numero(r.getAchat().getNumero(), "Achat", r.getAchat().getId()));
            evenements.add(new Evenement(r.getDateRetour(), RETOUR, r.getId(), r.getNumero(),
                    (client ? "Retour de marchandises" : "Retour au fournisseur") + (facture.isEmpty() ? "" : " (facture " + facture + ")"), null, total.negate()));
            if (Montants.ouZero(r.getMontantRembourse()).signum() > 0) {
                evenements.add(new Evenement(r.getDateRetour(), REMBOURSEMENT, r.getId(), r.getNumero(),
                        client ? "Remboursement au client" : "Remboursement reçu du fournisseur", r.getCompteTresorerie(), r.getMontantRembourse()));
            }
            if (Montants.ouZero(r.getMontantAvoir()).signum() > 0) {
                evenements.add(new Evenement(r.getDateRetour(), AVOIR, r.getId(), r.getNumero(),
                        "Avoir de " + Textes.montant(r.getMontantAvoir()) + " conservé en avance (sans mouvement d'argent)", null, Montants.ZERO));
            }
        }
        limite.verifier(evenements.size());
        evenements.sort(Comparator.comparing(Evenement::date, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingInt(Evenement::ordre).thenComparingLong(Evenement::id));

        LocalDateTime debut = periode.debut(), fin = periode.fin();
        BigDecimal courant = soldeDepart, anterieur = Montants.ZERO, apres = Montants.ZERO;
        int nombreAnterieur = 0;
        List<Evenement> dansLaPeriode = new ArrayList<>();
        for (Evenement e : evenements) {
            if (e.date() == null || e.date().isBefore(debut)) { anterieur = anterieur.add(e.effet()); nombreAnterieur++; }
            else if (e.date().isBefore(fin)) dansLaPeriode.add(e);
            else apres = apres.add(e.effet());
        }

        String titre = (client ? "Relevé des créances du client " : "Relevé des dettes du fournisseur ") + nom;
        TableauExport t = TableauExport.titre(titre).nomFeuille(client ? "Relevé client" : "Relevé fournisseur").filtres(periode.texte())
                .colonnes(Colonne.date("Date"), Colonne.texteCourt("Document"), Colonne.texte("Opération"), Colonne.texteCourt("Compte"),
                        Colonne.montant(client ? "Augmente la créance" : "Augmente la dette"), Colonne.montant(client ? "Diminue la créance" : "Diminue la dette"),
                        Colonne.montant(client ? "Solde (il vous doit)" : "Solde (vous lui devez)"));
        courant = soldeDepart;
        t.ligne(null, "", "Solde de départ de la fiche", "", null, null, courant);
        if (nombreAnterieur > 0) {
            courant = courant.add(anterieur);
            t.ligne(null, "", "Opérations antérieures au " + Textes.date(periode.du()) + " (" + nombreAnterieur + (nombreAnterieur > 1 ? " opérations)" : " opération)"),
                    "", null, null, courant);
        }
        BigDecimal augmente = Montants.ZERO, diminue = Montants.ZERO;
        for (Evenement e : dansLaPeriode) {
            courant = courant.add(e.effet());
            BigDecimal plus = e.effet().signum() > 0 ? e.effet() : null, moins = e.effet().signum() < 0 ? e.effet().negate() : null;
            if (plus != null) augmente = augmente.add(plus);
            if (moins != null) diminue = diminue.add(moins);
            t.ligne(e.date(), e.document(), e.libelle(), e.compte() == null ? "" : e.compte().getLibelle(), plus, moins, courant);
        }
        t.totaux("Solde au " + Textes.date(periode.au()), null, null, null, augmente, diminue, courant);

        BigDecimal soldeTous = soldeDepart.add(anterieur).add(sumEffets(dansLaPeriode)).add(apres);
        t.note(client ? "Solde positif : le client doit cette somme à la boutique ; négatif : le client a une avance."
                : "Solde positif : la boutique doit cette somme au fournisseur ; négatif : la boutique a une avance chez lui.");
        t.note("Un retour réduit la dette sur la facture ; la part déjà payée est remboursée (ligne « Remboursement ») ou gardée en avoir (ligne « Avoir », sans mouvement d'argent, "
                + "le solde négatif reste en avance). Une annulation remet le règlement d'origine. L'avance utilisée à la création d'une facture ne s'écrit pas : "
                + "elle est déjà comprise dans le solde.");
        if (soldeTous.compareTo(soldeFiche) == 0) {
            t.note("Concordance : le solde de toutes les opérations (" + Textes.montant(soldeTous) + " GNF) est celui de la fiche et du tableau de bord.");
        } else {
            t.note("ATTENTION : le solde de toutes les opérations (" + Textes.montant(soldeTous) + " GNF) diffère du solde de la fiche (" + Textes.montant(soldeFiche)
                    + " GNF). Vérifiez ce partenaire avant de vous fier à ce relevé.");
        }
        if (apres.signum() != 0 || evenements.stream().anyMatch(e -> e.date() != null && !e.date().isBefore(fin))) {
            t.note("Des opérations postérieures au " + Textes.date(periode.au()) + " existent : elles ne figurent pas ici, mais comptent dans le solde de la fiche.");
        }
        return new Etat(client ? "releve-client" : "releve-fournisseur", titre, periode.texte(), List.of(), List.of(t), FormatPapier.A4_PAYSAGE,
                (client ? "releve-client-" : "releve-fournisseur-") + partenaireId, periode.du(), periode.au());
    }

    /** Solde de départ, opérations antérieures et opérations de la période, pour les contrôles des tests. */
    private static BigDecimal sumEffets(List<Evenement> evenements) {
        return evenements.stream().map(Evenement::effet).reduce(Montants.ZERO, BigDecimal::add);
    }

    private static Long documentId(MouvementCaisse m, boolean client) {
        if (client) return m.getVente() == null ? null : m.getVente().getId();
        return m.getAchat() == null ? null : m.getAchat().getId();
    }

    private static String numero(String numero, String type, Long id) {
        return numero != null && !numero.isBlank() ? numero : type + " n° " + id;
    }
}
