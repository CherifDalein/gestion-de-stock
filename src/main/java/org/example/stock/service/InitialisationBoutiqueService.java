package org.example.stock.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.example.stock.enums.Role;
import org.example.stock.model.Boutique;
import org.example.stock.model.Emails;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Un seul démarrage par boutique, sérialisé avec toutes ses opérations commerciales. */
@Service
@Transactional(readOnly = true)
public class InitialisationBoutiqueService {
    private final BoutiqueService boutiques;
    private final UtilisateurRepository utilisateurs;
    private final EntityManager em;

    public InitialisationBoutiqueService(BoutiqueService boutiques, UtilisateurRepository utilisateurs, EntityManager em) {
        this.boutiques = boutiques;
        this.utilisateurs = utilisateurs;
        this.em = em;
    }

    public boolean estOuverte() {
        Boutique boutique = boutiques.boutiqueCourante();
        return !boutique.isInitialisationTerminee() && !possedeActivite(boutique.getId(), false);
    }

    /** L'utilisateur est verrouillé avant la boutique, comme pour les saisies initiales. */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void terminer() {
        Utilisateur acteur = administrateur();
        em.refresh(acteur, LockModeType.PESSIMISTIC_WRITE);
        if (!acteur.isActif() || acteur.getRole() != Role.ADMIN) throw interdit();
        boutiques.exigerAppartenance(acteur.getBoutique());
        verrouillerBoutiqueCourante().setInitialisationTerminee(true);
    }

    /** Appeler avant tout verrou produit ou document. Le verrou est conservé jusqu'au commit. */
    @Transactional
    public Boutique verrouillerEtExigerOuverte() {
        Boutique boutique = verrouillerBoutiqueCourante();
        if (boutique.isInitialisationTerminee() || possedeActivite(boutique.getId(), true)) {
            throw new IllegalArgumentException("Le stock initial et le solde initial sont réservés au démarrage de la boutique, avant le premier achat, la première vente ou le premier mouvement de caisse. Utilisez un achat pour entrer des marchandises.");
        }
        return boutique;
    }

    /** Une opération échouée annule aussi la clôture dans la même transaction. */
    @Transactional
    public Boutique verrouillerEtClorePourActivite() {
        Boutique courante = boutiques.boutiqueCourante();
        // La clôture est monotone : après le démarrage, les caissiers gardent seulement
        // les verrous de leur document et de leurs produits, sans verrou global permanent.
        if (courante.isInitialisationTerminee()) return courante;
        Boutique boutique = verrouillerBoutiqueCourante();
        boutique.setInitialisationTerminee(true);
        return boutique;
    }

    private Boutique verrouillerBoutiqueCourante() {
        Boutique boutique = boutiques.boutiqueCourante();
        // Un appel CaisseService imbriqué ne doit pas rafraîchir et perdre le flag encore non flushé.
        if (em.getLockMode(boutique) != LockModeType.PESSIMISTIC_WRITE) {
            em.refresh(boutique, LockModeType.PESSIMISTIC_WRITE);
        }
        boutiques.exigerAppartenance(boutique);
        return boutique;
    }

    private boolean possedeActivite(Long boutiqueId, boolean lectureCourante) {
        return existe("select a.id from Achat a where a.boutique.id = :boutique", boutiqueId, lectureCourante)
                || existe("select v.id from Vente v where v.boutique.id = :boutique", boutiqueId, lectureCourante)
                || existe("select m.id from MouvementCaisse m where m.boutique.id = :boutique and (m.source is null or m.source <> 'SOLDE_INITIAL' or m.type is null or m.type <> 'OUVERTURE')", boutiqueId, lectureCourante)
                || existe("select m.id from MouvementStock m where m.boutique.id = :boutique and m.type = org.example.stock.enums.TypeMouvementStock.AJUSTEMENT", boutiqueId, lectureCourante)
                || existe("select p.id from Produit p where p.boutique.id = :boutique and p.quantite > 0 and not exists (select m.id from MouvementStock m where m.produit.id = p.id and m.boutique.id = :boutique and m.type = org.example.stock.enums.TypeMouvementStock.INITIAL)", boutiqueId, lectureCourante);
    }

    private boolean existe(String jpql, Long boutiqueId, boolean lectureCourante) {
        var requete = em.createQuery(jpql, Long.class).setParameter("boutique", boutiqueId).setMaxResults(1);
        // Une lecture verrouillée voit aussi une activité créée après le snapshot REPEATABLE READ.
        if (lectureCourante) requete.setLockMode(LockModeType.PESSIMISTIC_READ);
        return !requete.getResultList().isEmpty();
    }

    private Utilisateur administrateur() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) throw interdit();
        Utilisateur acteur = utilisateurs.findByEmail(Emails.normaliser(auth.getName())).orElseThrow(InitialisationBoutiqueService::interdit);
        if (!acteur.isActif() || acteur.getRole() != Role.ADMIN) throw interdit();
        boutiques.exigerAppartenance(acteur.getBoutique());
        return acteur;
    }

    private static ResponseStatusException interdit() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Fin du démarrage réservée au gérant de la boutique");
    }
}
