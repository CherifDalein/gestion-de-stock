package org.example.stock.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.example.stock.enums.TypeOperationCreation;
import org.example.stock.model.*;
import org.example.stock.repository.OperationCreationRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class OperationCreationService {
    private final OperationCreationRepository operations;
    private final UtilisateurRepository utilisateurs;
    private final AchatService achats;
    private final VenteService ventes;
    private final EntityManager entityManager;
    private final BoutiqueService boutiques;

    public OperationCreationService(OperationCreationRepository operations, UtilisateurRepository utilisateurs,
                                    AchatService achats, VenteService ventes, EntityManager entityManager, BoutiqueService boutiques) {
        this.operations = operations;
        this.utilisateurs = utilisateurs;
        this.achats = achats;
        this.ventes = ventes;
        this.entityManager = entityManager;
        this.boutiques = boutiques;
    }

    public record ResultatCreation(Long documentId, boolean dejaEnregistre) {}

    @Transactional
    public String ouvrir(TypeOperationCreation type) {
        OperationCreation operation = new OperationCreation();
        operation.setBoutique(boutiques.boutiqueCourante());
        operation.setJeton(UUID.randomUUID().toString());
        operation.setUtilisateur(utilisateurActuel());
        operation.setType(type);
        operation.setDateCreation(Instant.now());
        return operations.save(operation).getJeton();
    }

    @Transactional
    public ResultatCreation creerAchat(String jeton, Achat achat) {
        OperationCreation operation = verrouiller(jeton, TypeOperationCreation.ACHAT);
        String empreinte = empreinte(achat, null);
        if (operation.getAchat() != null) {
            verifierResultat(Achat.class, operation.getAchat().getId());
            verifierCopie(operation, empreinte);
            return new ResultatCreation(operation.getAchat().getId(), true);
        }
        verifierValidite(operation);
        Achat enregistre = achats.enregistrerAchat(achat);
        operation.setAchat(enregistre);
        operation.setEmpreinte(empreinte);
        return new ResultatCreation(enregistre.getId(), false);
    }

    @Transactional
    public ResultatCreation creerVente(String jeton, Vente vente) {
        OperationCreation operation = verrouiller(jeton, TypeOperationCreation.VENTE);
        String empreinte = empreinte(null, vente);
        if (operation.getVente() != null) {
            verifierResultat(Vente.class, operation.getVente().getId());
            verifierCopie(operation, empreinte);
            return new ResultatCreation(operation.getVente().getId(), true);
        }
        verifierValidite(operation);
        Vente enregistree = ventes.effectuerVente(vente);
        operation.setVente(enregistree);
        operation.setEmpreinte(empreinte);
        return new ResultatCreation(enregistree.getId(), false);
    }

    private OperationCreation verrouiller(String jeton, TypeOperationCreation type) {
        try {
            if (jeton == null || !UUID.fromString(jeton).toString().equals(jeton)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new CreationOperationException(HttpStatus.BAD_REQUEST, "L'identifiant du formulaire est invalide. Ouvrez un nouveau formulaire.");
        }
        Long utilisateurId = utilisateurActuel().getId();
        OperationCreation operation = operations.verrouiller(jeton, boutiques.idBoutiqueCourante())
                .orElseThrow(OperationCreationService::formulaireInvalide);
        // Relire aussi un jeton déjà chargé dans le contexte JPA avant l'attente du verrou.
        entityManager.refresh(operation, LockModeType.PESSIMISTIC_WRITE);
        boutiques.exigerAppartenance(operation.getBoutique());
        if (operation.getType() != type || !operation.getUtilisateur().getId().equals(utilisateurId)) throw formulaireInvalide();
        return operation;
    }

    private Utilisateur utilisateurActuel() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) throw new AccessDeniedException("Connexion requise");
        Utilisateur utilisateur = utilisateurs.findByEmail(authentication.getName())
                .orElseThrow(() -> new AccessDeniedException("Reconnectez-vous avant de créer une opération."));
        boutiques.exigerAppartenance(utilisateur.getBoutique());
        return utilisateur;
    }

    private <T> void verifierResultat(Class<T> type, Long id) {
        // Lecture courante sous verrou : avec REPEATABLE READ, le document peut
        // avoir été créé après le snapshot initial d'une requête concurrente.
        if (entityManager.createQuery("select document from " + type.getSimpleName()
                        + " document where document.id = :id and document.boutique.id = :boutiqueId", type)
                .setParameter("id", id).setParameter("boutiqueId", boutiques.idBoutiqueCourante())
                .setLockMode(LockModeType.PESSIMISTIC_READ).getResultList().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable");
        }
    }

    private void verifierValidite(OperationCreation operation) {
        if (operation.getDateCreation().plus(Duration.ofHours(24)).isBefore(Instant.now())) throw formulaireInvalide();
    }

    private void verifierCopie(OperationCreation operation, String empreinte) {
        if (!empreinte.equals(operation.getEmpreinte())) {
            throw new CreationOperationException(HttpStatus.CONFLICT,
                    "Ce formulaire a déjà enregistré une opération avec un autre contenu. Ouvrez un nouveau formulaire.");
        }
    }

    private static CreationOperationException formulaireInvalide() {
        return new CreationOperationException(HttpStatus.CONFLICT, "Ce formulaire n'est plus valide. Ouvrez un nouveau formulaire.");
    }

    /** Empreinte des saisies, avant toute mutation métier ; aucun prix de vente courant n'y entre. */
    private static String empreinte(Achat achat, Vente vente) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeUTF(achat == null ? "VENTE" : "ACHAT");
                identifiant(out, achat != null ? (achat.getFournisseur() == null ? null : achat.getFournisseur().getId())
                        : (vente.getClient() == null ? null : vente.getClient().getId()));
                decimal(out, achat != null ? achat.getMontantVerse() : vente.getMontantVerse());
                if (achat != null) {
                    out.writeInt(achat.getLignes() == null ? -1 : achat.getLignes().size());
                    if (achat.getLignes() != null) for (DetailAchat ligne : achat.getLignes()) {
                        out.writeBoolean(ligne != null);
                        if (ligne == null) continue;
                        identifiant(out, ligne.getProduit() == null ? null : ligne.getProduit().getId());
                        identifiant(out, ligne.getQuantite() == null ? null : ligne.getQuantite().longValue());
                        decimal(out, ligne.getPrixAchatUnitaire());
                    }
                } else {
                    out.writeInt(vente.getLignes() == null ? -1 : vente.getLignes().size());
                    if (vente.getLignes() != null) for (DetailVente ligne : vente.getLignes()) {
                        out.writeBoolean(ligne != null);
                        if (ligne == null) continue;
                        identifiant(out, ligne.getProduit() == null ? null : ligne.getProduit().getId());
                        identifiant(out, ligne.getQuantite() == null ? null : ligne.getQuantite().longValue());
                    }
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Impossible de vérifier le contenu du formulaire", e);
        }
    }

    private static void identifiant(DataOutputStream out, Long valeur) throws IOException {
        out.writeBoolean(valeur != null);
        if (valeur != null) out.writeLong(valeur);
    }

    private static void decimal(DataOutputStream out, BigDecimal valeur) throws IOException {
        out.writeBoolean(valeur != null);
        if (valeur != null) out.writeUTF(valeur.stripTrailingZeros().toString());
    }
}
