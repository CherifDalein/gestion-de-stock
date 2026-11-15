package org.example.stock.service;

import java.time.LocalDateTime;
import org.example.stock.model.Client;
import org.example.stock.model.Montants;
import org.example.stock.repository.ClientRepository;
import org.example.stock.repository.StockLockRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;


@Service
public class ClientService {
    @org.springframework.beans.factory.annotation.Autowired private org.example.stock.compta.VerrouExercice verrouExercice;
    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private BoutiqueService boutiqueService;

    @Autowired
    private SoldeDepartService soldeDepart;

    @Autowired
    private StockLockRepository verrous;

    @Autowired
    private CorbeilleService corbeille;

    public List<Client> listerTous() {
        return clientRepository.findAllByBoutiqueId(boutiqueService.idBoutiqueCourante());
    }

    /** Comme {@link #listerTous()} mais lit au plus {@code max + 1} fiches (export : plus de {@code max} signale un dépassement). */
    public List<Client> listerPourExport(int max) {
        return clientRepository.findAllByBoutiqueId(boutiqueService.idBoutiqueCourante(), org.springframework.data.domain.PageRequest.of(0, max + 1, org.springframework.data.domain.Sort.by("id")));
    }

    @Transactional
    public Client enregistrerClient(Client client) {
        if (client.getId() != null) throw new IllegalArgumentException("Une création ne peut pas contenir d'identifiant");
        client.setBoutique(boutiqueService.boutiqueCourante());
        // Le solde enregistré ne vient jamais du navigateur : il est recalculé, pour le gérant seulement.
        client.setSoldeInitial(Montants.ZERO);
        client.setEcheanceSoldeInitial(client.isSoldeDepartSoumis() ? client.getEcheanceSoldeInitial() : null);
        soldeDepart.appliquerALaCreation(client);
        return clientRepository.save(client);
    }

    @Transactional
    public Client modifierClient(Long id, Client modifications) {
        // Un caissier ne peut ni saisir ni modifier le solde de départ ; le gérant le modifie sous le verrou de la fiche.
        soldeDepart.appliquerALaModification(id, modifications);
        Client client = trouverParId(id);
        client.setNom(modifications.getNom());
        client.setTelephone(modifications.getTelephone());
        client.setEmail(modifications.getEmail());
        client.setAdresse(modifications.getAdresse());
        return clientRepository.save(client);
    }
    public Client trouverParId(Long id) {
        return clientRepository.findByIdAndBoutiqueId(id, boutiqueService.idBoutiqueCourante())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Client introuvable"));
    }

    /**
     * Place le client dans la corbeille (lot 6). Les règles de suppression sont celles d'avant la corbeille : un client qui a des
     * ventes, devis, retours, règlements ou ajustements de solde est refusé ({@link IllegalArgumentException}).
     */
    @Transactional
    public CorbeilleService.ElementSupprime supprimerClient(Long id) {
        // La suppression contrôle les factures du client : la boutique d'abord, puis la fiche (docs/lot-2.md, « Ordre des verrous »).
        verrous.verrouillerBoutiqueCourante();
        verrouExercice.exigerOuvert(LocalDateTime.now());
        Client client = verrous.verrouillerClient(id);
        corbeille.exigerSansLien(TypeCorbeille.CLIENT, id);
        var supprime = corbeille.placer(TypeCorbeille.CLIENT, client);
        clientRepository.saveAndFlush(client);
        return supprime;
    }
}
