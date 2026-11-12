package org.example.stock.service;

import org.example.stock.model.Client;
import org.example.stock.model.Fournisseur;
import org.example.stock.model.Montants;
import org.example.stock.repository.ClientRepository;
import org.example.stock.repository.FournisseurRepository;
import org.example.stock.repository.SoldePartenaireRepository;
import org.example.stock.repository.SoldePartenaireRepository.Factures;
import org.example.stock.repository.SoldePartenaireRepository.Partenaire;
import org.example.stock.repository.SoldePartenaireRepository.Reglements;
import org.example.stock.repository.SoldePartenaireRepository.RetardPartenaire;
import org.example.stock.repository.SoldePartenaireRepository.Totaux;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Solde global d'un client ou d'un fournisseur de la boutique courante.
 *
 * <p>Convention de signe, du point de vue de la boutique : positif = le partenaire doit de l'argent à la boutique ;
 * négatif = la boutique lui en doit. Un client dont le solde est négatif a une avance ; un fournisseur dont le
 * solde est positif a reçu une avance de la boutique.</p>
 *
 * <ul>
 *   <li>avance disponible d'un client = max(0, −solde de départ) + Σ max(0, versé − total d'origine) + Σ avoirs de ses
 *       retours de vente − Σ avance utilisée de ses ventes ; celle d'un fournisseur = max(0, solde de départ) + Σ trop-payés + Σ avoirs de ses
 *       retours d'achat − Σ avance utilisée de ses achats ;</li>
 *   <li>reste du solde de départ = montant dû (client : solde positif ; fournisseur : solde négatif) − Σ règlements + Σ leurs annulations.</li>
 * </ul>
 *
 * <p>Les lectures d'affichage utilisent des requêtes agrégées. Les méthodes « verrouillée » relisent les lignes sous
 * verrou et ne s'utilisent qu'après le verrou de la fiche du partenaire ({@code StockLockRepository}).</p>
 */
@Service
public class SoldePartenaireService {
    private final SoldePartenaireRepository soldes;
    private final ClientRepository clients;
    private final FournisseurRepository fournisseurs;
    private final BoutiqueService boutiques;
    private final EcheanceService echeances;

    public SoldePartenaireService(SoldePartenaireRepository soldes, ClientRepository clients, FournisseurRepository fournisseurs,
                                  BoutiqueService boutiques, EcheanceService echeances) {
        this.soldes = soldes;
        this.clients = clients;
        this.fournisseurs = fournisseurs;
        this.boutiques = boutiques;
        this.echeances = echeances;
    }

    // ---- Lectures d'affichage -------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public SoldePartenaire soldeClient(Long clientId) {
        Long boutiqueId = boutiques.idBoutiqueCourante();
        Client client = clients.findByIdAndBoutiqueId(clientId, boutiqueId).orElseThrow(() -> introuvable("Client"));
        return construire(Partenaire.CLIENT, client.getSoldeInitial(), client.getEcheanceSoldeInitial(),
                soldes.reglementsSoldeDepart(Partenaire.CLIENT, boutiqueId, clientId), soldes.factures(Partenaire.CLIENT, boutiqueId, clientId));
    }

    @Transactional(readOnly = true)
    public SoldePartenaire soldeFournisseur(Long fournisseurId) {
        Long boutiqueId = boutiques.idBoutiqueCourante();
        Fournisseur fournisseur = fournisseurs.findByIdAndBoutiqueId(fournisseurId, boutiqueId).orElseThrow(() -> introuvable("Fournisseur"));
        return construire(Partenaire.FOURNISSEUR, fournisseur.getSoldeInitial(), fournisseur.getEcheanceSoldeInitial(),
                soldes.reglementsSoldeDepart(Partenaire.FOURNISSEUR, boutiqueId, fournisseurId), soldes.factures(Partenaire.FOURNISSEUR, boutiqueId, fournisseurId));
    }

    /** Soldes de tous les clients de la boutique, par identifiant, avec trois requêtes agrégées. */
    @Transactional(readOnly = true)
    public Map<Long, SoldePartenaire> soldesClients() {
        Long boutiqueId = boutiques.idBoutiqueCourante();
        var factures = soldes.factures(Partenaire.CLIENT, boutiqueId);
        var reglements = soldes.reglementsSoldeDepart(Partenaire.CLIENT, boutiqueId);
        Map<Long, SoldePartenaire> resultat = new LinkedHashMap<>();
        for (Client client : clients.findAllByBoutiqueId(boutiqueId)) {
            resultat.put(client.getId(), construire(Partenaire.CLIENT, client.getSoldeInitial(), client.getEcheanceSoldeInitial(),
                    reglements.getOrDefault(client.getId(), Reglements.AUCUN), factures.getOrDefault(client.getId(), Factures.VIDE)));
        }
        return resultat;
    }

    @Transactional(readOnly = true)
    public Map<Long, SoldePartenaire> soldesFournisseurs() {
        Long boutiqueId = boutiques.idBoutiqueCourante();
        var factures = soldes.factures(Partenaire.FOURNISSEUR, boutiqueId);
        var reglements = soldes.reglementsSoldeDepart(Partenaire.FOURNISSEUR, boutiqueId);
        Map<Long, SoldePartenaire> resultat = new LinkedHashMap<>();
        for (Fournisseur fournisseur : fournisseurs.findAllByBoutiqueId(boutiqueId)) {
            resultat.put(fournisseur.getId(), construire(Partenaire.FOURNISSEUR, fournisseur.getSoldeInitial(), fournisseur.getEcheanceSoldeInitial(),
                    reglements.getOrDefault(fournisseur.getId(), Reglements.AUCUN), factures.getOrDefault(fournisseur.getId(), Factures.VIDE)));
        }
        return resultat;
    }

    /** Avance disponible d'un client de la boutique courante (404 pour un client d'une autre boutique). */
    @Transactional(readOnly = true)
    public BigDecimal avanceClient(Long clientId) {
        return soldeClient(clientId).avanceDisponible();
    }

    @Transactional(readOnly = true)
    public BigDecimal avanceFournisseur(Long fournisseurId) {
        return soldeFournisseur(fournisseurId).avanceDisponible();
    }

    // ---- Agrégats de la boutique (préparent le tableau de bord des créances et dettes) ----------------------

    /**
     * Créances sur les clients : restes à payer de toutes les ventes de la boutique (clients de passage compris) et
     * restes des soldes de départ « il me doit ». La part en retard compte les factures dont l'échéance est strictement
     * antérieure à aujourd'hui (même convention que {@link EcheanceService}) et les soldes de départ échus.
     */
    @Transactional(readOnly = true)
    public Totaux creancesClients() {
        return totaux(Partenaire.CLIENT);
    }

    /** Dettes envers les fournisseurs : restes à payer des achats et restes des soldes de départ « je lui dois ». */
    @Transactional(readOnly = true)
    public Totaux dettesFournisseurs() {
        return totaux(Partenaire.FOURNISSEUR);
    }

    /** Avances disponibles de tous les clients de la boutique (argent reçu d'avance), chaque solde restant distinct. */
    @Transactional(readOnly = true)
    public BigDecimal avancesClients() {
        return soldesClients().values().stream().map(SoldePartenaire::avanceDisponible).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Avances de la boutique chez ses fournisseurs. */
    @Transactional(readOnly = true)
    public BigDecimal avancesFournisseurs() {
        return soldesFournisseurs().values().stream().map(SoldePartenaire::avanceDisponible).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Clients en retard, du montant en retard le plus grand au plus petit (au plus {@code limite}). */
    @Transactional(readOnly = true)
    public Retards retardsClients(int limite) {
        return retards(Partenaire.CLIENT, limite);
    }

    /** Fournisseurs à payer en retard, du montant le plus grand au plus petit (au plus {@code limite}). */
    @Transactional(readOnly = true)
    public Retards retardsFournisseurs(int limite) {
        return retards(Partenaire.FOURNISSEUR, limite);
    }

    private Retards retards(Partenaire type, int limite) {
        Long boutiqueId = boutiques.idBoutiqueCourante();
        LocalDate jour = echeances.aujourdHui();
        var tous = soldes.retardsParPartenaire(type, boutiqueId, jour).stream()
                .sorted(Comparator.comparing(RetardPartenaire::montant).reversed()
                        .thenComparing(RetardPartenaire::plusAncienneEcheance)
                        .thenComparing(RetardPartenaire::nom, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(RetardPartenaire::id))
                .toList();
        return new Retards(tous.stream().limit(Math.max(0, limite)).toList(), tous.size(),
                soldes.retardFacturesSansPartenaire(type, boutiqueId, jour));
    }

    /**
     * Partenaires en retard (liste limitée, du plus grand montant au plus petit), nombre total de partenaires en retard
     * et montant en retard des factures sans partenaire nommé (ventes sans client), compté dans les totaux mais absent
     * de la liste.
     */
    public record Retards(List<RetardPartenaire> partenaires, int total, BigDecimal sansPartenaire) {
        public int autres() { return Math.max(0, total - partenaires.size()); }
    }

    private Totaux totaux(Partenaire type) {
        Long boutiqueId = boutiques.idBoutiqueCourante();
        LocalDate jour = echeances.aujourdHui();
        Totaux factures = soldes.restesFactures(type, boutiqueId, jour);
        Totaux depart = soldes.restesSoldesDepart(type, boutiqueId, jour);
        if (depart.total().signum() < 0 || depart.enRetard().signum() < 0) {
            throw new IncoherenceAvanceException("Un reste de solde de départ est négatif : vérifiez les règlements des soldes de départ.");
        }
        return new Totaux(factures.total().add(depart.total()), factures.enRetard().add(depart.enRetard()));
    }

    // ---- Calculs sous verrou --------------------------------------------------------------------------------

    /** Avance du client, relue sous verrou. Le client doit déjà être verrouillé par {@code verrouillerClient}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal avanceDisponibleVerrouillee(Client client) {
        Factures factures = soldes.facturesVerrouillees(Partenaire.CLIENT, boutiques.idBoutiqueCourante(), client.getId());
        BigDecimal exces = exces(Partenaire.CLIENT, client.getSoldeInitial(), reglementsVerrouilles(Partenaire.CLIENT, client.getId()).net());
        return avance(client.getSoldeInitial().negate(), factures, exces);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal avanceDisponibleVerrouillee(Fournisseur fournisseur) {
        Factures factures = soldes.facturesVerrouillees(Partenaire.FOURNISSEUR, boutiques.idBoutiqueCourante(), fournisseur.getId());
        BigDecimal exces = exces(Partenaire.FOURNISSEUR, fournisseur.getSoldeInitial(), reglementsVerrouilles(Partenaire.FOURNISSEUR, fournisseur.getId()).net());
        return avance(fournisseur.getSoldeInitial(), factures, exces);
    }

    /** Avance d'un solde de départ hypothétique (modification de la fiche), relue sous verrou. */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal avanceDisponibleAvecSolde(Partenaire type, Long partenaireId, BigDecimal nouveauSolde) {
        Factures factures = soldes.facturesVerrouillees(type, boutiques.idBoutiqueCourante(), partenaireId);
        BigDecimal exces = exces(type, nouveauSolde, reglementsVerrouilles(type, partenaireId).net());
        return avanceBrute(type == Partenaire.CLIENT ? nouveauSolde.negate() : nouveauSolde, factures).add(exces);
    }

    /**
     * Avance qui resterait après l'annulation d'un règlement du solde de départ (fiche déjà verrouillée) : si le solde a été
     * ajusté sous ce qui avait été réglé, le trop-réglé est une avance, et l'annuler la retire.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal avanceApresAnnulationReglement(Partenaire type, Long partenaireId, BigDecimal soldeInitial, BigDecimal montantAnnule) {
        Factures factures = soldes.facturesVerrouillees(type, boutiques.idBoutiqueCourante(), partenaireId);
        BigDecimal regle = reglementsVerrouilles(type, partenaireId).net().subtract(montantAnnule);
        return avanceBrute(type == Partenaire.CLIENT ? soldeInitial.negate() : soldeInitial, factures).add(exces(type, soldeInitial, regle));
    }

    /**
     * Part des règlements du solde de départ qui dépasse ce qui est dû : cela n'arrive qu'après un ajustement du solde de départ
     * sous le montant déjà réglé. Ce trop-réglé est une avance du partenaire (client) ou de la boutique (fournisseur).
     */
    static BigDecimal exces(Partenaire type, BigDecimal soldeInitial, BigDecimal regle) {
        BigDecimal solde = Montants.ouZero(soldeInitial);
        BigDecimal du = (type == Partenaire.CLIENT ? solde : solde.negate()).max(BigDecimal.ZERO);
        return regle.subtract(du).max(BigDecimal.ZERO);
    }

    /** Règlements du solde de départ (nets de leurs annulations, et bruts), relus sous verrou en valeur absolue. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Reglements reglementsVerrouilles(Partenaire type, Long partenaireId) {
        return soldes.reglementsVerrouilles(type, boutiques.idBoutiqueCourante(), partenaireId);
    }

    /** Une facture de ce partenaire a-t-elle consommé de l'avance ? Relu sous verrou, fiche déjà verrouillée. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean avanceUtiliseeVerrouillee(Partenaire type, Long partenaireId) {
        return soldes.avanceUtiliseeVerrouillee(type, boutiques.idBoutiqueCourante(), partenaireId);
    }

    /** Avance disponible, ou {@link IncoherenceAvanceException} si le calcul donne une valeur négative. */
    private static BigDecimal avance(BigDecimal partieSolde, Factures factures, BigDecimal exces) {
        BigDecimal avance = avanceBrute(partieSolde, factures).add(exces);
        if (avance.signum() < 0) {
            throw new IncoherenceAvanceException("L'avance de ce partenaire est négative (" + avance.toPlainString()
                    + "). Les données sont incohérentes : l'opération est annulée, vérifiez ses factures et son solde de départ.");
        }
        return avance;
    }

    private static BigDecimal avanceBrute(BigDecimal partieSolde, Factures factures) {
        return partieSolde.max(BigDecimal.ZERO).add(factures.surplus()).add(factures.avoirs()).subtract(factures.avanceUtilisee());
    }

    private SoldePartenaire construire(Partenaire type, BigDecimal soldeInitial, LocalDate echeance, Reglements reglements, Factures factures) {
        soldeInitial = Montants.ouZero(soldeInitial);
        BigDecimal regle = reglements.net();
        boolean client = type == Partenaire.CLIENT;
        BigDecimal du = (client ? soldeInitial : soldeInitial.negate()).max(BigDecimal.ZERO);
        // Le trop-réglé (solde ajusté sous ce qui a été payé) devient une avance : le reste ne descend jamais sous zéro.
        BigDecimal reste = du.subtract(regle).max(BigDecimal.ZERO);
        BigDecimal avance = avance(client ? soldeInitial.negate() : soldeInitial, factures, exces(type, soldeInitial, regle));
        BigDecimal creances = reste.add(factures.reste());
        BigDecimal net = client ? creances.subtract(avance) : avance.subtract(creances);
        return new SoldePartenaire(soldeInitial, regle, reste, reste.signum() > 0 ? echeance : null, factures.reste(), avance, net, factures.avanceUtilisee(), reglements.brut());
    }

    private static ResponseStatusException introuvable(String libelle) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, libelle + " introuvable");
    }
}
