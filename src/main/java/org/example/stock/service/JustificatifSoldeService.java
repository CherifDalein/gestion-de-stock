package org.example.stock.service;

import org.example.stock.enums.CompteTresorerie;
import org.example.stock.export.LimiteExport;
import org.example.stock.export.MontantEnLettres;
import org.example.stock.export.Textes;
import org.example.stock.model.Achat;
import org.example.stock.model.Client;
import org.example.stock.model.Fournisseur;
import org.example.stock.model.Montants;
import org.example.stock.model.Vente;
import org.example.stock.repository.ClientRepository;
import org.example.stock.repository.EtatsRepository;
import org.example.stock.repository.FournisseurRepository;
import org.example.stock.repository.JustificatifSoldeRepository;
import org.example.stock.service.EtatsPartenairesService.Cote;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Justificatifs de solde (lot 4, tâche D) : un document officiel de la boutique, réservé au gérant, qui atteste le solde d'un
 * client, d'un fournisseur ou de la trésorerie à une date.
 *
 * <p>Aucun calcul nouveau : le solde d'un partenaire est {@link SoldePartenaire#soldeNet()} de {@link SoldePartenaireService}
 * (le même que la fiche, le relevé et le tableau de bord) ; celui d'un compte de trésorerie est
 * {@link CaisseService#getSoldeCompte} aujourd'hui et, à une date passée, la somme des mouvements jusqu'à la fin de ce jour
 * ({@link EtatsRepository#soldesAvant}, la requête du bilan de la période). Rien n'est enregistré : pas de compteur, pas de table.</p>
 *
 * <p><b>Date d'arrêté.</b> L'application ne garde pas l'historique des restes à payer ni des avances : le solde d'un partenaire est
 * donc toujours celui du jour de l'édition. Le solde de trésorerie, lui, se déduit exactement des mouvements datés et peut
 * s'arrêter à n'importe quel jour passé.</p>
 */
@Service
@Transactional(readOnly = true)
public class JustificatifSoldeService {
    /** Une facture non soldée du détail. */
    public record Facture(String numero, LocalDate date, LocalDate echeance, BigDecimal reste) { }

    /**
     * Justificatif d'un client ou d'un fournisseur. {@code solde} suit la convention des relevés : positif = le client doit à la boutique
     * (la boutique doit au fournisseur) ; négatif = avance (détenue par la boutique pour le client ; versée par la boutique au fournisseur).
     * {@code solde = resteSoldeDepart + resteFactures − avance}.
     */
    public record JustificatifPartenaire(Cote cote, Long partenaireId, String numero, LocalDate arrete, LocalDateTime edition, String nom,
                                         String telephone, String email, String adresse, BigDecimal resteSoldeDepart, LocalDate echeanceSoldeDepart,
                                         List<Facture> factures, BigDecimal resteFactures, BigDecimal avance, BigDecimal solde) {
        public boolean estClient() { return cote == Cote.CLIENT; }

        public BigDecimal montant() { return solde.abs(); }

        public boolean estNul() { return solde.signum() == 0; }

        public boolean estDette() { return solde.signum() > 0; }

        public String titrePartenaire() { return estClient() ? "Client" : "Fournisseur"; }

        /** Phrase d'attestation, du point de vue de la boutique. */
        public String attestation() {
            if (estNul()) return estClient() ? "Le compte du client est soldé : il ne doit rien à la boutique et la boutique ne détient aucune avance à son nom."
                    : "Le compte du fournisseur est soldé : la boutique ne lui doit rien et n'a aucune avance chez lui.";
            if (estClient()) return estDette() ? "Le client doit à la boutique la somme de" : "La boutique détient une avance au nom du client, d'un montant de";
            return estDette() ? "La boutique doit au fournisseur la somme de" : "La boutique a une avance chez le fournisseur, d'un montant de";
        }

        public String titreSolde() {
            if (estNul()) return "Solde nul";
            if (estClient()) return estDette() ? "Solde dû par le client" : "Avance détenue par la boutique";
            return estDette() ? "Solde dû par la boutique" : "Avance de la boutique chez le fournisseur";
        }

        public String montantEnLettres() { return MontantEnLettres.francsGuineens(montant()); }

        public String arreteEnPhrase() { return "Arrêté à la somme de " + montantEnLettres() + "."; }
    }

    /** Solde d'un compte de trésorerie à la date d'arrêté. */
    public record SoldeCompte(CompteTresorerie compte, BigDecimal solde) { }

    public record JustificatifTresorerie(String numero, LocalDate arrete, boolean aujourdHui, LocalDateTime edition, List<SoldeCompte> comptes, BigDecimal total) {
        public String totalEnLettres() { return MontantEnLettres.francsGuineens(total); }

        public String arreteEnPhrase() { return "Arrêté à la somme de " + totalEnLettres() + "."; }
    }

    private final SoldePartenaireService soldes;
    private final JustificatifSoldeRepository factures;
    private final ClientRepository clients;
    private final FournisseurRepository fournisseurs;
    private final EtatsRepository etats;
    private final CaisseService caisse;
    private final BoutiqueService boutiques;
    private final EcheanceService echeances;
    private final LimiteExport limite;

    public JustificatifSoldeService(SoldePartenaireService soldes, JustificatifSoldeRepository factures, ClientRepository clients,
                                    FournisseurRepository fournisseurs, EtatsRepository etats, CaisseService caisse, BoutiqueService boutiques,
                                    EcheanceService echeances, LimiteExport limite) {
        this.soldes = soldes; this.factures = factures; this.clients = clients; this.fournisseurs = fournisseurs;
        this.etats = etats; this.caisse = caisse; this.boutiques = boutiques; this.echeances = echeances; this.limite = limite;
    }

    /** Numéro stable : « JS-AAAAMMJJ-C12 » (client 12), « JS-AAAAMMJJ-F7 » (fournisseur 7), « JS-AAAAMMJJ-T3 » (trésorerie de la boutique 3). */
    public static String numero(LocalDate arrete, char type, Long identifiant) {
        return "JS-" + arrete.toString().replace("-", "") + "-" + type + identifiant;
    }

    // ---- Client --------------------------------------------------------------------------------------------------

    public JustificatifPartenaire client(Long clientId) {
        DroitsEtats.exigerGerant();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        Client client = clients.findByIdAndBoutiqueId(clientId, boutiqueId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Client introuvable"));
        SoldePartenaire s = soldes.soldeClient(clientId);
        List<Vente> lues = factures.ventesNonSoldeesDuClient(boutiqueId, clientId, PageRequest.of(0, limite.lignes() + 1));
        limite.verifier(lues.size());
        List<Facture> detail = new ArrayList<>();
        for (Vente v : lues) detail.add(new Facture(v.getNumero() != null && !v.getNumero().isBlank() ? v.getNumero() : "Vente n° " + v.getId(),
                v.getDateVente() == null ? null : v.getDateVente().toLocalDate(), v.getDateEcheance(), v.getResteAPayer()));
        LocalDate arrete = echeances.aujourdHui();
        return assembler(Cote.CLIENT, clientId, arrete, client.getNom(), client.getTelephone(), client.getEmail(), client.getAdresse(), s, detail, s.soldeNet());
    }

    // ---- Fournisseur ---------------------------------------------------------------------------------------------

    public JustificatifPartenaire fournisseur(Long fournisseurId) {
        DroitsEtats.exigerGerant();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        Fournisseur f = fournisseurs.findByIdAndBoutiqueId(fournisseurId, boutiqueId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fournisseur introuvable"));
        SoldePartenaire s = soldes.soldeFournisseur(fournisseurId);
        List<Achat> lus = factures.achatsNonSoldesDuFournisseur(boutiqueId, fournisseurId, PageRequest.of(0, limite.lignes() + 1));
        limite.verifier(lus.size());
        List<Facture> detail = new ArrayList<>();
        for (Achat a : lus) detail.add(new Facture(a.getNumero() != null && !a.getNumero().isBlank() ? a.getNumero() : "Achat n° " + a.getId(),
                a.getDateAchat() == null ? null : a.getDateAchat().toLocalDate(), a.getDateEcheance(), a.getResteAPayer()));
        LocalDate arrete = echeances.aujourdHui();
        // La convention du fournisseur est inversée dans SoldePartenaire (positif = avance) ; le justificatif suit celle des relevés (positif = dette).
        return assembler(Cote.FOURNISSEUR, fournisseurId, arrete, f.getNom(), f.getTelephone(), f.getEmail(), f.getAdresse(), s, detail, s.soldeNet().negate());
    }

    private JustificatifPartenaire assembler(Cote cote, Long id, LocalDate arrete, String nom, String telephone, String email, String adresse,
                                             SoldePartenaire s, List<Facture> detail, BigDecimal solde) {
        detail.sort(Comparator.comparing(Facture::echeance, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
                .thenComparing(Facture::date, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder())).thenComparing(Facture::numero));
        BigDecimal somme = detail.stream().map(Facture::reste).reduce(Montants.ZERO, BigDecimal::add);
        // Le détail doit redonner le solde de la fiche : sinon on refuse d'éditer un document officiel qui ne s'additionne pas.
        BigDecimal recompose = s.resteSoldeDepart().add(somme).subtract(s.avanceDisponible());
        if (somme.compareTo(s.resteFactures()) != 0 || recompose.compareTo(solde) != 0) {
            throw new IncoherenceAvanceException("Le détail des factures ne concorde pas avec le solde de la fiche : relancez le justificatif après vérification du compte.");
        }
        String numero = numero(arrete, cote == Cote.CLIENT ? 'C' : 'F', id);
        return new JustificatifPartenaire(cote, id, numero, arrete, echeances.maintenant(), nom, blanc(telephone), blanc(email), blanc(adresse),
                s.resteSoldeDepart(), s.resteSoldeDepart().signum() > 0 ? s.echeanceSoldeDepart() : null, List.copyOf(detail), somme, s.avanceDisponible(), solde);
    }

    private static String blanc(String t) { return t == null || t.isBlank() ? null : Textes.pourXml(t.strip()); }

    // ---- Trésorerie ----------------------------------------------------------------------------------------------

    /**
     * Soldes des comptes à la fin du jour {@code date} (aujourd'hui si elle est vide). Aujourd'hui : {@link CaisseService#getSoldeCompte}, le
     * solde du journal. Jour passé : somme des mouvements dont la date précède le lendemain à 0 h (un mouvement sans date compte comme antérieur),
     * exactement la « trésorerie à la fin de la période » du bilan.
     *
     * @throws IllegalArgumentException date future ou hors des années 2000 à 9998
     */
    public JustificatifTresorerie tresorerie(LocalDate date) {
        DroitsEtats.exigerGerant();
        LocalDate jour = echeances.aujourdHui();
        LocalDate arrete = date == null ? jour : date;
        if (arrete.isAfter(jour)) throw new IllegalArgumentException("La date d'arrêté ne peut pas être dans le futur.");
        if (arrete.getYear() < PeriodeEtat.ANNEE_MINIMALE) throw new IllegalArgumentException("La date d'arrêté est invalide.");
        Long boutiqueId = boutiques.idBoutiqueCourante();
        Map<CompteTresorerie, BigDecimal> parCompte = new EnumMap<>(CompteTresorerie.class);
        boolean aujourdHui = arrete.equals(jour);
        if (!aujourdHui) {
            for (Object[] ligne : etats.soldesAvant(boutiqueId, arrete.plusDays(1).atStartOfDay())) {
                parCompte.put((CompteTresorerie) ligne[0], ligne[1] == null ? Montants.ZERO : (BigDecimal) ligne[1]);
            }
        }
        List<SoldeCompte> comptes = new ArrayList<>();
        BigDecimal total = Montants.ZERO;
        for (CompteTresorerie compte : CompteTresorerie.values()) {
            BigDecimal solde = aujourdHui ? caisse.getSoldeCompte(compte) : parCompte.getOrDefault(compte, Montants.ZERO);
            comptes.add(new SoldeCompte(compte, solde));
            total = total.add(solde);
        }
        return new JustificatifTresorerie(numero(arrete, 'T', boutiqueId), arrete, aujourdHui, echeances.maintenant(), List.copyOf(comptes), total);
    }
}
