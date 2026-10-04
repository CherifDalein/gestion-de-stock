package org.example.stock.service;

import org.example.stock.model.Client;
import org.example.stock.repository.ClientRepository;
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

    public List<Client> listerTous() {
        return clientRepository.findAll();
    }

    public Client enregistrerClient(Client client) {
        if (client.getId() != null) throw new IllegalArgumentException("Une création ne peut pas contenir d'identifiant");
        return clientRepository.save(client);
    }

    @Transactional
    public Client modifierClient(Long id, Client modifications) {
        Client client = clientRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Client introuvable"));
        client.setNom(modifications.getNom());
        client.setTelephone(modifications.getTelephone());
        client.setEmail(modifications.getEmail());
        client.setAdresse(modifications.getAdresse());
        return clientRepository.save(client);
    }
    public Client trouverParId(Long id) {
        return clientRepository.findById(id).orElseThrow(() -> new RuntimeException("Client introuvable"));
    }

    public void supprimerClient(Long id) {
        clientRepository.deleteById(id);
    }
}
