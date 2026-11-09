package org.example.stock.export;

import org.example.stock.model.Boutique;
import org.example.stock.model.Emails;
import org.example.stock.repository.LogoBoutiqueRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.example.stock.service.BoutiqueService;
import org.example.stock.service.EcheanceService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;

/**
 * Construit l'en-tête de tout document : boutique du compte connecté (nom, coordonnées, logo), nom de l'éditeur, heure
 * du serveur. Le nom de la plateforme n'y figure jamais.
 */
@Service
@Transactional(readOnly = true)
public class ContexteDocumentService {
    private final BoutiqueService boutiques;
    private final LogoBoutiqueRepository logos;
    private final UtilisateurRepository utilisateurs;
    private final EcheanceService horloge;

    public ContexteDocumentService(BoutiqueService boutiques, LogoBoutiqueRepository logos, UtilisateurRepository utilisateurs,
                                   EcheanceService horloge) {
        this.boutiques = boutiques; this.logos = logos; this.utilisateurs = utilisateurs; this.horloge = horloge;
    }

    public ContexteDocument courant() {
        Boutique boutique = boutiques.boutiqueCourante();
        String logo = logos.findById(boutique.getId())
                .map(l -> "data:image/png;base64," + Base64.getEncoder().encodeToString(l.getContenu())).orElse(null);
        String principal = vide(boutique.getTelephone()), secondaire = vide(boutique.getTelephoneSecondaire());
        String telephones = principal == null && secondaire == null ? null
                : (principal != null && secondaire != null ? "Téléphones : " + principal + " · " + secondaire
                : "Téléphone : " + (principal != null ? principal : secondaire));
        String email = vide(boutique.getEmail()) == null ? null : "Email : " + vide(boutique.getEmail());
        return new ContexteDocument(
                nettoyer(boutique.getNom(), "Boutique"), nettoyer(boutique.getAdresse(), null), telephones == null ? null : Textes.pourXml(telephones),
                email == null ? null : Textes.pourXml(email), logo, nomEditeur(), horloge.maintenant());
    }

    private String nomEditeur() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return "";
        return utilisateurs.findByEmail(Emails.normaliser(auth.getName()))
                .map(u -> u.getNom() != null && !u.getNom().isBlank() ? u.getNom().strip() : u.getEmail())
                .map(Textes::pourXml).orElse("");
    }

    private static String vide(String texte) {
        return texte == null || texte.isBlank() ? null : texte.strip();
    }

    private static String nettoyer(String texte, String defaut) {
        return texte == null || texte.isBlank() ? defaut : Textes.pourXml(texte.strip());
    }
}
