package org.example.stock.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Validator;
import org.example.stock.enums.Role;
import org.example.stock.enums.TypeMouvementStock;
import org.example.stock.form.AjustementStockForm;
import org.example.stock.form.StockInitialForm;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class InventaireService {
    private final ProduitService produits;
    private final BoutiqueService boutiques;
    private final UtilisateurRepository utilisateurs;
    private final StockLockRepository verrous;
    private final MouvementStockRepository mouvements;
    private final EntityManager em;
    private final Validator validator;
    private final Clock clock;
    private final InitialisationBoutiqueService initialisation;
    public InventaireService(ProduitService produits, BoutiqueService boutiques, UtilisateurRepository utilisateurs,
                            StockLockRepository verrous, MouvementStockRepository mouvements, EntityManager em,
                            Validator validator, @Qualifier("recoveryClock") Clock clock, InitialisationBoutiqueService initialisation) {
        this.produits=produits; this.boutiques=boutiques; this.utilisateurs=utilisateurs; this.verrous=verrous;
        this.mouvements=mouvements; this.em=em; this.validator=validator; this.clock=clock;
        this.initialisation=initialisation;
    }
    public record VueInventaire(Produit produit, boolean initialAutorise, List<MouvementStock> historique,
                                int page, boolean pagePrecedente, boolean pageSuivante) {}
    public VueInventaire consulter(Long id, int page) {
        administrateur();
        if (page < 0 || page > 100_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page invalide");
        Produit produit=produits.trouverParId(id);
        var historique=mouvements.findByBoutiqueIdAndProduitIdOrderByCreeLeDescIdDesc(boutiques.idBoutiqueCourante(),id,PageRequest.of(page,20));
        return new VueInventaire(produit, initialAutorise(produit), historique.getContent(), page, historique.hasPrevious(), historique.hasNext());
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void initialiser(Long id, StockInitialForm formulaire) {
        Utilisateur acteur=verrouillerAdministrateur(); valider(formulaire);
        initialisation.verrouillerEtExigerOuverte();
        Produit produit=verrous.verrouillerProduits(List.of(id)).get(id);
        verifierVersion(produit,formulaire.getVersion());
        if (!initialAutorise(produit)) throw new IllegalArgumentException("Le stock initial est réservé au démarrage de la boutique et à un produit sans stock ni opération précédente. Utilisez un achat.");
        var cout=Montants.positifOuNul(formulaire.getCoutUnitaire(),"Le coût unitaire");
        var prixVente=Montants.positifOuNul(formulaire.getPrixVente(),"Le prix de vente");
        produit.setPrixAchat(cout); produit.setPrixVente(prixVente); produit.setPrixDefinis(true);
        ajouter(produit,acteur,TypeMouvementStock.INITIAL,formulaire.getQuantite(),cout,prixVente,formulaire.getMotif());
    }
    @Transactional
    public void ajuster(Long id, AjustementStockForm formulaire) {
        administrateur();
        produits.trouverParId(id);
        throw new IllegalArgumentException("La modification manuelle du stock est désactivée. Après le démarrage de la boutique, seuls les achats et les ventes font varier les quantités.");
    }
    private boolean initialAutorise(Produit produit) {
        return initialisation.estOuverte() && produit.getQuantite() != null && produit.getQuantite() == 0
                && !mouvements.existsByProduitId(produit.getId())
                && em.createQuery("select count(d) from DetailAchat d where d.produit.id=:id",Long.class).setParameter("id",produit.getId()).getSingleResult()==0
                && em.createQuery("select count(d) from DetailVente d where d.produit.id=:id",Long.class).setParameter("id",produit.getId()).getSingleResult()==0;
    }
    private void ajouter(Produit produit, Utilisateur acteur, TypeMouvementStock type, Long apres, java.math.BigDecimal cout, java.math.BigDecimal prixVente, String motif) {
        MouvementStock mouvement=new MouvementStock();
        mouvement.setBoutique(produit.getBoutique()); mouvement.setProduit(produit); mouvement.setActeurId(acteur.getId());
        String nom=acteur.getNom()==null||acteur.getNom().isBlank()?"Gérant":acteur.getNom().strip();
        mouvement.setActeurNom(nom.codePointCount(0,nom.length())>255?nom.substring(0,nom.offsetByCodePoints(0,255)):nom);
        mouvement.setType(type); mouvement.setCreeLe(LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
        mouvement.setQuantiteAvant(produit.getQuantite()); mouvement.setQuantiteApres(apres); mouvement.setCoutUnitaire(cout); mouvement.setPrixVente(prixVente); mouvement.setMotif(motif);
        produit.setQuantite(apres); mouvements.saveAndFlush(mouvement);
    }
    private Utilisateur administrateur() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!auth.isAuthenticated()||auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_ADMIN"))) throw interdit();
        Utilisateur compte=utilisateurs.findByEmail(Emails.normaliser(auth.getName())).orElseThrow(InventaireService::interdit);
        if(!compte.isActif()||compte.getRole()!=Role.ADMIN) throw interdit();
        boutiques.exigerAppartenance(compte.getBoutique()); return compte;
    }
    private Utilisateur verrouillerAdministrateur() {
        Utilisateur acteur=administrateur(); em.refresh(acteur,LockModeType.PESSIMISTIC_WRITE);
        if(!acteur.isActif()||acteur.getRole()!=Role.ADMIN) throw interdit();
        boutiques.exigerAppartenance(acteur.getBoutique()); return acteur;
    }
    private void verifierVersion(Produit produit, Long version) {
        if(!produit.getVersion().equals(version)) throw new ProduitModifieException();
    }
    private void valider(Object formulaire) {
        if(formulaire==null) throw new IllegalArgumentException("Formulaire obligatoire");
        var violations=validator.validate(formulaire);
        if(!violations.isEmpty()) throw new IllegalArgumentException(violations.stream().sorted(java.util.Comparator.comparing(v->v.getPropertyPath().toString())).findFirst().orElseThrow().getMessage());
    }
    private static ResponseStatusException interdit(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"Inventaire réservé au gérant de la boutique");}
}
