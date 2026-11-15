package org.example.stock.compta;

import org.example.stock.compta.DonneesComptables.Achat;
import org.example.stock.compta.DonneesComptables.Ajustement;
import org.example.stock.compta.DonneesComptables.Mouvement;
import org.example.stock.compta.DonneesComptables.Operation;
import org.example.stock.compta.DonneesComptables.Partenaire;
import org.example.stock.compta.DonneesComptables.Retour;
import org.example.stock.compta.DonneesComptables.Vente;
import org.example.stock.compta.Ecriture.Ligne;
import org.example.stock.compta.Ecriture.TypeTiers;
import org.example.stock.model.SourceMouvement;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Calcul pur des écritures : à partir des opérations enregistrées ({@link DonneesComptables}) et de la table de correspondance, produit
 * les écritures en partie double. Rien n'est lu en base ni conservé : le même enregistrement donne toujours les mêmes écritures.
 *
 * <p>Conventions : les créances et dettes de tiers sont tenues en 4111 / 4011, les avances en 4191 / 4091 (trop-perçu d'un règlement,
 * avoir d'un retour) ; l'annulation d'un mouvement est la contre-passation exacte de son écriture ; le stock est tenu en inventaire
 * intermittent (les achats vont en charges, le stock est régularisé à la date demandée).</p>
 */
public final class ConstructeurEcritures {
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final DonneesComptables d;
    private final Correspondance c;
    private final Map<Long, Vente> ventes = new HashMap<>();
    private final Map<Long, Achat> achats = new HashMap<>();
    private final Map<Long, Retour> retours = new HashMap<>();
    private final Map<Long, String> clients = new HashMap<>();
    private final Map<Long, String> fournisseurs = new HashMap<>();
    private final Map<Long, BigDecimal> surplus = new HashMap<>();
    private final Map<Long, Ecriture> parMouvement = new HashMap<>();
    private final List<Ecriture> resultat = new ArrayList<>();

    public ConstructeurEcritures(DonneesComptables donnees, Correspondance correspondance) {
        this.d = donnees;
        this.c = correspondance;
    }

    public List<Ecriture> construire() {
        d.ventes().forEach(v -> ventes.put(v.id(), v));
        d.achats().forEach(a -> achats.put(a.id(), a));
        d.retours().forEach(r -> retours.put(r.id(), r));
        d.clients().forEach(p -> clients.put(p.id(), p.nom()));
        d.fournisseurs().forEach(p -> fournisseurs.put(p.id(), p.nom()));
        d.ventes().forEach(v -> surplus.put(-v.id(), positif(v.verse().subtract(v.total()))));
        d.achats().forEach(a -> surplus.put(a.id(), positif(a.verse().subtract(a.total()))));

        reports();
        d.ventes().forEach(this::vente);
        d.achats().forEach(this::achat);
        d.retours().forEach(this::retour);
        mouvements();
        regularisationStock();
        List<Ecriture> tri = new ArrayList<>(resultat);
        tri.sort(Comparator.comparing(Ecriture::date).thenComparing(e -> e.journal().ordinal()).thenComparing(Ecriture::piece));
        return tri;
    }

    // ---- Reports -------------------------------------------------------------------------------------------------

    private void reports() {
        for (Partenaire p : d.clients()) {
            BigDecimal depart = soldeDepart(p, true);
            if (depart.signum() != 0) reportTiers(TypeTiers.CLIENT, p, depart, true);
        }
        for (Partenaire p : d.fournisseurs()) {
            BigDecimal depart = soldeDepart(p, false);
            if (depart.signum() != 0) reportTiers(TypeTiers.FOURNISSEUR, p, depart, false);
        }
        for (Ajustement a : d.ajustements()) {
            BigDecimal delta = a.nouveauSolde().subtract(a.ancienSolde());
            if (delta.signum() == 0) continue;
            boolean client = a.clientId() != null;
            Partenaire p = new Partenaire(client ? a.clientId() : a.fournisseurId(), client ? clients.get(a.clientId()) : fournisseurs.get(a.fournisseurId()), ZERO);
            LocalDateTime date = a.date();
            reportTiers(client ? TypeTiers.CLIENT : TypeTiers.FOURNISSEUR, p, delta, client, date, "Ajustement du solde de départ de " + p.nom(), "AJ-" + a.id());
        }
        if (d.stockDepart().signum() != 0) {
            List<Ligne> l = new ArrayList<>();
            l.add(debit(c.compte(CleCompta.STOCK), d.stockDepart()));
            l.add(credit(c.compte(CleCompta.REPORT_A_NOUVEAU), d.stockDepart()));
            ajouter(d.ouverture(), Journal.OD, "OUV-STOCK", "Stock de départ", l, "STOCK_DEPART", false);
        }
    }

    /** Solde de départ d'origine : celui d'avant le premier ajustement s'il y en a, sinon le solde actuel (positif : le tiers doit). */
    private BigDecimal soldeDepart(Partenaire p, boolean client) {
        return d.ajustements().stream().filter(a -> client ? p.id().equals(a.clientId()) : p.id().equals(a.fournisseurId()))
                .min(Comparator.comparing(Ajustement::date)).map(Ajustement::ancienSolde).orElse(p.soldeInitial());
    }

    private void reportTiers(TypeTiers type, Partenaire p, BigDecimal montant, boolean client) {
        reportTiers(type, p, montant, client, d.ouverture(), "Solde de départ de " + p.nom(), "OUV-" + (client ? "C" : "F") + p.id());
    }

    /** {@code montant} signé : positif, le tiers doit à la boutique ; négatif, la boutique lui doit. */
    private void reportTiers(TypeTiers type, Partenaire p, BigDecimal montant, boolean client, LocalDateTime date, String libelle, String piece) {
        boolean tiersDoit = montant.signum() > 0;
        BigDecimal m = montant.abs();
        String report = c.compte(CleCompta.REPORT_A_NOUVEAU);
        List<Ligne> l = new ArrayList<>();
        if (client) {
            // Client qui doit : créance en 4111 ; la boutique lui doit : avance en 4191.
            l.add(tiersDoit ? debit(c.compte(CleCompta.CREANCES_CLIENTS), m, type, p) : credit(c.compte(CleCompta.AVANCES_CLIENTS), m, type, p));
        } else {
            // Fournisseur qui doit à la boutique : avance en 4091 ; la boutique lui doit : dette en 4011.
            l.add(tiersDoit ? debit(c.compte(CleCompta.AVANCES_FOURNISSEURS), m, type, p) : credit(c.compte(CleCompta.DETTES_FOURNISSEURS), m, type, p));
        }
        l.add(tiersDoit ? credit(report, m) : debit(report, m));
        // La ligne du tiers est du côté opposé au report : on remet les lignes dans l'ordre débit puis crédit.
        l.sort(Comparator.comparing((Ligne x) -> x.debit().signum() == 0));
        ajouter(date, Journal.OD, piece, libelle, l, "REPORT:" + piece, false);
    }

    // ---- Ventes, achats, retours ---------------------------------------------------------------------------------

    private void vente(Vente v) {
        BigDecimal services = v.services().min(v.total());
        List<Ligne> l = new ArrayList<>();
        BigDecimal creance = positif(v.total().subtract(v.avanceUtilisee()));
        BigDecimal avance = v.total().min(v.avanceUtilisee());
        if (creance.signum() > 0) l.add(debit(c.compte(CleCompta.CREANCES_CLIENTS), creance, TypeTiers.CLIENT, v.clientId(), v.clientNom()));
        if (avance.signum() > 0) l.add(debit(c.compte(CleCompta.AVANCES_CLIENTS), avance, TypeTiers.CLIENT, v.clientId(), v.clientNom()));
        ajouterSiMontant(l, false, c.compte(CleCompta.VENTES_MARCHANDISES), v.total().subtract(services), null, null, null);
        ajouterSiMontant(l, false, c.compte(CleCompta.VENTES_SERVICES), services, null, null, null);
        ajouter(v.date(), Journal.VE, v.numero(), "Vente " + v.numero() + nomClient(v.clientNom()), l, "VENTE:" + v.id(), false);
    }

    private void achat(Achat a) {
        List<Ligne> l = new ArrayList<>();
        l.add(debit(c.compte(CleCompta.ACHATS_MARCHANDISES), a.total()));
        BigDecimal dette = positif(a.total().subtract(a.avanceUtilisee()));
        BigDecimal avance = a.total().min(a.avanceUtilisee());
        if (dette.signum() > 0) l.add(credit(c.compte(CleCompta.DETTES_FOURNISSEURS), dette, TypeTiers.FOURNISSEUR, a.fournisseurId(), a.fournisseurNom()));
        if (avance.signum() > 0) l.add(credit(c.compte(CleCompta.AVANCES_FOURNISSEURS), avance, TypeTiers.FOURNISSEUR, a.fournisseurId(), a.fournisseurNom()));
        ajouter(a.date(), Journal.AC, a.numero(), "Achat " + a.numero() + nomClient(a.fournisseurNom()), l, "ACHAT:" + a.id(), false);
    }

    private void retour(Retour r) {
        List<Ligne> l = new ArrayList<>();
        BigDecimal avoir = r.avoir().min(r.total());
        BigDecimal reste = r.total().subtract(avoir);
        if (r.vente()) {
            BigDecimal services = r.services().min(r.total());
            ajouterSiMontant(l, true, c.compte(CleCompta.RETOURS_VENTES), r.total().subtract(services), null, null, null);
            ajouterSiMontant(l, true, c.compte(CleCompta.VENTES_SERVICES), services, null, null, null);
            ajouterSiMontant(l, false, c.compte(CleCompta.CREANCES_CLIENTS), reste, TypeTiers.CLIENT, r.tiersId(), r.tiersNom());
            ajouterSiMontant(l, false, c.compte(CleCompta.AVANCES_CLIENTS), avoir, TypeTiers.CLIENT, r.tiersId(), r.tiersNom());
            ajouter(r.date(), Journal.VE, r.numero(), "Retour de vente " + r.numero() + nomClient(r.tiersNom()), l, "RETOUR:" + r.id(), false);
        } else {
            ajouterSiMontant(l, true, c.compte(CleCompta.DETTES_FOURNISSEURS), reste, TypeTiers.FOURNISSEUR, r.tiersId(), r.tiersNom());
            ajouterSiMontant(l, true, c.compte(CleCompta.AVANCES_FOURNISSEURS), avoir, TypeTiers.FOURNISSEUR, r.tiersId(), r.tiersNom());
            ajouterSiMontant(l, false, c.compte(CleCompta.RETOURS_ACHATS), r.total(), null, null, null);
            ajouter(r.date(), Journal.AC, r.numero(), "Retour d'achat " + r.numero() + nomClient(r.tiersNom()), l, "RETOUR:" + r.id(), false);
        }
    }

    // ---- Mouvements de trésorerie --------------------------------------------------------------------------------

    private void mouvements() {
        List<Mouvement> tous = new ArrayList<>(d.mouvements());
        tous.sort(Comparator.comparing(Mouvement::date).thenComparing(Mouvement::id));
        Map<Long, List<Mouvement>> transferts = new LinkedHashMap<>();
        Set<Long> fait = new HashSet<>();
        for (Mouvement m : tous) {
            if (SourceMouvement.ANNULATION.equals(m.source())) continue;
            Operation op = m.operationId() == null ? null : d.operations().get(m.operationId());
            if ("TRANSFERT".equals(m.source()) && op != null) { transferts.computeIfAbsent(op.id(), k -> new ArrayList<>()).add(m); continue; }
            Ecriture e = mouvement(m, op);
            if (e != null) parMouvement.put(m.id(), e);
            fait.add(m.id());
        }
        transferts.values().forEach(this::transfert);
        // Les annulations viennent en dernier : elles reprennent l'écriture annulée, quel que soit l'ordre des dates.
        for (Mouvement m : tous) {
            if (!SourceMouvement.ANNULATION.equals(m.source())) continue;
            Ecriture origine = m.annulationDeId() == null ? null : parMouvement.get(m.annulationDeId());
            List<Ligne> l = new ArrayList<>();
            if (origine == null) {
                // Origine inconnue (par exemple un transfert) : l'annulation reste équilibrée avec le compte d'attente.
                String t = c.compteTresorerie(m.compteId());
                l.add(m.montant().signum() >= 0 ? debit(t, m.montant()) : credit(t, m.montant().abs()));
                l.add(m.montant().signum() >= 0 ? credit(c.compte(CleCompta.ATTENTE), m.montant()) : debit(c.compte(CleCompta.ATTENTE), m.montant().abs()));
                ajouter(m.date(), Journal.TR, "MV-" + m.id(), "Annulation : " + texte(m.motif()), l, "MOUVEMENT:" + m.id(), true);
                continue;
            }
            for (Ligne x : origine.lignes()) l.add(new Ligne(x.compte(), x.credit(), x.debit(), x.tiersType(), x.tiersId(), x.tiersNom()));
            ajouter(m.date(), origine.journal(), origine.piece(), "Annulation : " + origine.libelle(), l, "MOUVEMENT:" + m.id(), true);
        }
    }

    private void transfert(List<Mouvement> deux) {
        Mouvement ref = deux.get(0);
        Operation op = d.operations().get(ref.operationId());
        List<Ligne> l = new ArrayList<>();
        String virement = c.compte(CleCompta.VIREMENTS_INTERNES);
        for (Mouvement m : deux) {
            String t = c.compteTresorerie(m.compteId());
            BigDecimal a = m.montant().abs();
            if (m.montant().signum() >= 0) { l.add(debit(t, a)); l.add(credit(virement, a)); }
            else { l.add(debit(virement, a)); l.add(credit(t, a)); }
        }
        Ecriture e = ajouter(ref.date(), Journal.TR, "OP-" + op.id(), "Transfert : " + texte(op.motif()), l, "OPERATION:" + op.id(), false);
        deux.forEach(m -> parMouvement.put(m.id(), e));
    }

    private Ecriture mouvement(Mouvement m, Operation op) {
        String t = c.compteTresorerie(m.compteId());
        BigDecimal a = m.montant().abs();
        if (a.signum() == 0) return null;
        boolean entree = m.montant().signum() > 0;
        String source = m.source() == null ? "" : m.source();
        String piece = "MV-" + m.id();
        String libelle = texte(m.motif());
        Journal journal = Journal.TR;
        List<Ligne> l = new ArrayList<>();
        // Ligne de trésorerie, puis contrepartie du côté opposé.
        Ligne tres = entree ? debit(t, a) : credit(t, a);
        Ligne contre;
        switch (source) {
            case SourceMouvement.VENTE, SourceMouvement.REGLEMENT_VENTE, SourceMouvement.SOLDE_CLIENT -> {
                Long venteId = m.venteId();
                Long clientId = m.clientId() != null ? m.clientId() : venteId == null || ventes.get(venteId) == null ? null : ventes.get(venteId).clientId();
                String nom = clientId == null ? null : clients.get(clientId);
                if (venteId != null && ventes.get(venteId) != null) piece = ventes.get(venteId).numero();
                BigDecimal avance = SourceMouvement.VENTE.equals(source) && venteId != null ? prendreSurplus(-venteId, a) : ZERO;
                l.add(tres);
                if (avance.signum() > 0) l.add(credit(c.compte(CleCompta.AVANCES_CLIENTS), avance, TypeTiers.CLIENT, clientId, nom));
                contre = a.subtract(avance).signum() > 0 ? credit(c.compte(CleCompta.CREANCES_CLIENTS), a.subtract(avance), TypeTiers.CLIENT, clientId, nom) : null;
                if (libelle.isEmpty()) libelle = "Encaissement client" + nomClient(nom);
            }
            case SourceMouvement.ACHAT, SourceMouvement.REGLEMENT_ACHAT, SourceMouvement.SOLDE_FOURNISSEUR -> {
                Long achatId = m.achatId();
                Long fournisseurId = m.fournisseurId() != null ? m.fournisseurId() : achatId == null || achats.get(achatId) == null ? null : achats.get(achatId).fournisseurId();
                String nom = fournisseurId == null ? null : fournisseurs.get(fournisseurId);
                if (achatId != null && achats.get(achatId) != null) piece = achats.get(achatId).numero();
                BigDecimal avance = SourceMouvement.ACHAT.equals(source) && achatId != null ? prendreSurplus(achatId, a) : ZERO;
                l.add(tres);
                if (avance.signum() > 0) l.add(debit(c.compte(CleCompta.AVANCES_FOURNISSEURS), avance, TypeTiers.FOURNISSEUR, fournisseurId, nom));
                contre = a.subtract(avance).signum() > 0 ? debit(c.compte(CleCompta.DETTES_FOURNISSEURS), a.subtract(avance), TypeTiers.FOURNISSEUR, fournisseurId, nom) : null;
                if (libelle.isEmpty()) libelle = "Règlement fournisseur" + nomClient(nom);
            }
            case "RETOUR_VENTE" -> {
                Retour r = m.retourId() == null ? null : retours.get(m.retourId());
                if (r != null) piece = r.numero();
                l.add(tres);
                contre = debit(c.compte(CleCompta.CREANCES_CLIENTS), a, TypeTiers.CLIENT, r == null ? null : r.tiersId(), r == null ? null : r.tiersNom());
            }
            case "RETOUR_ACHAT" -> {
                Retour r = m.retourId() == null ? null : retours.get(m.retourId());
                if (r != null) piece = r.numero();
                l.add(tres);
                contre = credit(c.compte(CleCompta.DETTES_FOURNISSEURS), a, TypeTiers.FOURNISSEUR, r == null ? null : r.tiersId(), r == null ? null : r.tiersNom());
            }
            case "SOLDE_INITIAL" -> {
                journal = Journal.OD;
                l.add(tres);
                contre = entree ? credit(c.compte(CleCompta.REPORT_A_NOUVEAU), a) : debit(c.compte(CleCompta.REPORT_A_NOUVEAU), a);
                if (libelle.isEmpty()) libelle = "Solde de départ du compte";
            }
            case "DEPENSE" -> {
                l.add(tres);
                contre = entree ? credit(c.compteDepense(op == null ? libelle : op.motif()), a) : debit(c.compteDepense(op == null ? libelle : op.motif()), a);
                if (op != null) piece = "OP-" + op.id();
            }
            default -> {
                l.add(tres);
                contre = contrepartieFinanciere(source, entree, a, m, op);
                if (op != null) piece = "OP-" + op.id();
            }
        }
        if (contre != null) l.add(contre);
        else if (l.size() == 1) l.add(entree ? credit(c.compte(CleCompta.ATTENTE), a) : debit(c.compte(CleCompta.ATTENTE), a));
        if (libelle.isEmpty()) libelle = SourceMouvement.libelle(source);
        return ajouter(m.date(), journal, piece, libelle, l, "MOUVEMENT:" + m.id(), false);
    }

    /** Contrepartie d'un financement, d'un écart de caisse ou d'un produit ou une charge saisis (compte d'attente si la nature est inconnue). */
    private Ligne contrepartieFinanciere(String source, boolean entree, BigDecimal a, Mouvement m, Operation op) {
        CleCompta cle = switch (source) {
            case SourceMouvement.APPORT_CAPITAL, SourceMouvement.RETRAIT_CAPITAL -> CleCompta.EXPLOITANT;
            case SourceMouvement.APPORT_COMPTE_COURANT, SourceMouvement.RETRAIT_COMPTE_COURANT -> CleCompta.ASSOCIES_COMPTE_COURANT;
            case SourceMouvement.DEBLOCAGE_EMPRUNT, SourceMouvement.REMBOURSEMENT_EMPRUNT -> CleCompta.EMPRUNTS;
            case SourceMouvement.DEBLOCAGE_PRET, SourceMouvement.REMBOURSEMENT_PRET -> CleCompta.PRETS;
            case SourceMouvement.INTERETS_EMPRUNT -> CleCompta.INTERETS_EMPRUNTS;
            case SourceMouvement.INTERETS_PRET -> CleCompta.INTERETS_PRETS;
            case SourceMouvement.FRAIS_DOSSIER -> CleCompta.FRAIS_DOSSIER;
            case SourceMouvement.PRODUIT_FINANCIER -> CleCompta.PRODUITS_FINANCIERS;
            case SourceMouvement.CHARGE_FINANCIERE -> CleCompta.FRAIS_BANCAIRES;
            case SourceMouvement.AUTRE_PRODUIT -> m.comptageId() != null ? CleCompta.ECART_EXCEDENT : CleCompta.AUTRES_PRODUITS;
            case SourceMouvement.AUTRE_CHARGE -> m.comptageId() != null ? CleCompta.ECART_MANQUE : CleCompta.AUTRES_CHARGES;
            default -> CleCompta.ATTENTE;
        };
        boolean partenaire = cle == CleCompta.ASSOCIES_COMPTE_COURANT;
        String compte = c.compte(cle);
        TypeTiers type = partenaire && op != null && op.partenaireId() != null ? TypeTiers.PARTENAIRE : null;
        Long id = type == null ? null : op.partenaireId();
        String nom = type == null ? null : op.partenaireNom();
        return entree ? credit(compte, a, type, id, nom) : debit(compte, a, type, id, nom);
    }

    // ---- Stock ----------------------------------------------------------------------------------------------------

    /** Régularisation du stock (inventaire intermittent) : le stock passe de sa valeur de départ à sa valeur à la date demandée. */
    private void regularisationStock() {
        for (DonneesComptables.ValeurStock v : d.valeursStock()) {
            BigDecimal ecart = v.valeur().subtract(d.stockDepart());
            if (ecart.signum() == 0) continue;
            List<Ligne> l = new ArrayList<>();
            if (ecart.signum() > 0) { l.add(debit(c.compte(CleCompta.STOCK), ecart)); l.add(credit(c.compte(CleCompta.VARIATION_STOCK), ecart)); }
            else { l.add(debit(c.compte(CleCompta.VARIATION_STOCK), ecart.abs())); l.add(credit(c.compte(CleCompta.STOCK), ecart.abs())); }
            ajouter(v.date(), Journal.OD, "INV", "Régularisation du stock à la date d'inventaire", l, "INVENTAIRE", false);
        }
    }

    // ---- Outils ---------------------------------------------------------------------------------------------------

    private BigDecimal prendreSurplus(long cle, BigDecimal montant) {
        BigDecimal reste = surplus.getOrDefault(cle, ZERO);
        BigDecimal pris = reste.min(montant);
        if (pris.signum() > 0) surplus.put(cle, reste.subtract(pris));
        return pris.signum() > 0 ? pris : ZERO;
    }

    private Ecriture ajouter(LocalDateTime date, Journal journal, String piece, String libelle, List<Ligne> lignes, String origine, boolean contre) {
        Ecriture e = new Ecriture(date, journal, piece, libelle, lignes, origine, contre);
        resultat.add(e);
        return e;
    }

    private void ajouterSiMontant(List<Ligne> l, boolean debit, String compte, BigDecimal montant, TypeTiers type, Long id, String nom) {
        if (montant == null || montant.signum() <= 0) return;
        l.add(debit ? debit(compte, montant, type, id, nom) : credit(compte, montant, type, id, nom));
    }

    private static BigDecimal positif(BigDecimal x) { return x.signum() > 0 ? x : ZERO; }

    private static String texte(String s) { return s == null ? "" : s.strip(); }

    private static String nomClient(String nom) { return nom == null || nom.isBlank() ? "" : " - " + nom; }

    private static Ligne debit(String compte, BigDecimal m) { return new Ligne(compte, m, ZERO, null, null, null); }

    private static Ligne credit(String compte, BigDecimal m) { return new Ligne(compte, ZERO, m, null, null, null); }

    private static Ligne debit(String compte, BigDecimal m, TypeTiers t, Long id, String nom) { return new Ligne(compte, m, ZERO, id == null ? null : t, id, id == null ? null : nom); }

    private static Ligne credit(String compte, BigDecimal m, TypeTiers t, Long id, String nom) { return new Ligne(compte, ZERO, m, id == null ? null : t, id, id == null ? null : nom); }

    private static Ligne debit(String compte, BigDecimal m, TypeTiers t, Partenaire p) { return debit(compte, m, t, p.id(), p.nom()); }

    private static Ligne credit(String compte, BigDecimal m, TypeTiers t, Partenaire p) { return credit(compte, m, t, p.id(), p.nom()); }
}
