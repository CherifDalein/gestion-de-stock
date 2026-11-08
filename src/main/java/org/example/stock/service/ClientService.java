package org.example.stock.service;

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
    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private BoutiqueService boutiqueService;

    @Autowired
    private SoldeDepartService soldeDepart;

    @Autowired
    private StockLockRepository verrous;

    public List<Client> listerTous() {
        return clientRepository.findAllByBoutiqueId(boutiqueService.idBoutiqueCourante());
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

    @Transactional
    public void supprimerClient(Long id) {
        // La suppression contrôle les factures du client : la boutique d'abord (docs/lot-2.md, « Ordre des verrous »).
        verrous.verrouillerBoutiqueCourante();
        clientRepository.delete(trouverParId(id));
    }
}
