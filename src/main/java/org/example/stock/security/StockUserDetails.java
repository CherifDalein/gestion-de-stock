package org.example.stock.security;

import org.example.stock.model.Utilisateur;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

/** L'empreinte reste disponible après l'effacement du secret d'authentification par Spring. */
public final class StockUserDetails extends User {
    @Serial
    private static final long serialVersionUID = 1L;
    private final Long utilisateurId;
    private final Long boutiqueId;
    private final byte[] empreinteMotDePasse;

    public StockUserDetails(Utilisateur utilisateur, List<String> roles, boolean connexionAutorisee) {
        super(utilisateur.getEmail(), utilisateur.getMotDePasse(), connexionAutorisee, true, true, true,
                roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList());
        utilisateurId = utilisateur.getId();
        boutiqueId = utilisateur.getBoutique() == null ? null : utilisateur.getBoutique().getId();
        empreinteMotDePasse = empreinte(utilisateur.getMotDePasse());
    }

    boolean correspond(Utilisateur utilisateur) {
        return Objects.equals(utilisateurId, utilisateur.getId())
                && Objects.equals(getUsername(), utilisateur.getEmail())
                && utilisateur.getBoutique() != null
                && Objects.equals(boutiqueId, utilisateur.getBoutique().getId())
                && utilisateur.getMotDePasse() != null
                && MessageDigest.isEqual(empreinteMotDePasse, empreinte(utilisateur.getMotDePasse()));
    }

    private static byte[] empreinte(String valeur) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(Objects.requireNonNull(valeur).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Algorithme d'empreinte indisponible", exception);
        }
    }
}
