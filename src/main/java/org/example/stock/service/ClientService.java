package org.example.stock.service;

import org.example.stock.model.Client;
import org.example.stock.repository.ClientRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class ClientService {
    @Autowired
    private ClientRepository clientRepository;

    public List<Client> listerTous() {
        return clientRepository.findAll();
    }

    public Client enregistrerClient(Client client) {
        return clientRepository.save(client);
    }
    public Client trouverParId(Long id) {
        return clientRepository.findById(id).orElseThrow(() -> new RuntimeException("Client introuvable"));
    }

    public void supprimerClient(Long id) {
        clientRepository.deleteById(id);
    }
}
