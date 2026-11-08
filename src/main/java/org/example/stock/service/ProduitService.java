package org.example.stock.service;

import jakarta.persistence.EntityManager;
import org.example.stock.enums.NatureProduit;
import org.example.stock.model.Produit;
import org.example.stock.model.Montants;
import org.example.stock.repository.ProduitRepository;
import org.example.stock.repository.CategorieRepository;
import org.example.stock.repository.FournisseurRepository;
import org.example.stock.repository.StockLockRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class ProduitService {
    @Autowired private ProduitRepository produitRepository;
    @Autowired private CategorieRepository categorieRepository;
    @Autowired private FournisseurRepository fournisseurRepository;
    @Autowired private StockLockRepository stockLockRepository;
    @Autowired private BoutiqueService boutiqueService;
    @Autowired private org.example.stock.repository.MouvementStockRepository mouvementsStock;
    @Autowired private EntityManager entityManager;

    public List<Produit> listerTous() {
        return produitRepository.findAllByBoutiqueId(boutiqueService.idBoutiqueCourante());
    }

    public Produit trouverParId(Long id) {
        return produitRepository.findByIdAndBoutiqueId(id, boutiqueService.idBoutiqueCourante())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Produit introuvable"));
    }

    @Transactional
    public Produit ajouterProduit(Produit produit) {
        if (produit.getId() != null) throw new IllegalArgumentException("Une création ne peut pas contenir d'identifiant");
        if (produit.getVersion() != null) throw new IllegalArgumentException("Une création ne peut pas contenir de version");
        if (produit.getNature() == null) produit.setNature(NatureProduit.STOCK);
        produit.setQuantite(0L);
        produit.setUnite(normaliserUnite(produit.getUnite()));
        if (produit.estService()) {
            // Un service n'attend aucune entrée de stock : ses prix sont fixés sur la fiche.
            if (produit.getPrixVente() == null) throw new IllegalArgumentException("Le prix de vente du service est obligatoire.");
            BigDecimal cout = Montants.positifOuNul(Montants.ouZero(produit.getCoutMoyen()), "Le coût du service");
            produit.setCoutMoyen(cout);
            produit.setPrixAchat(cout);
            produit.setSeuilAlerte(Produit.SEUIL_ALERTE_PAR_DEFAUT);
            produit.setPrixDefinis(true);
        } else {
            produit.setSeuilAlerte(validerSeuil(produit.getSeuilAlerte()));
            produit.setPrixAchat(Montants.ZERO);
            produit.setPrixVente(Montants.ZERO);
            produit.setCoutMoyen(Montants.ZERO);
            produit.setPrixDefinis(false);
        }
        produit.setBoutique(boutiqueService.boutiqueCourante());
        chargerRelations(produit);
        return produitRepository.save(produit);
    }

    /**
     * Les champs absents valent {@code null} : la nature ne change jamais, le seuil absent est conservé
     * et le coût n'est modifiable que pour un service (celui d'un article en stock vient des achats
     * et la valeur reçue est ignorée ; le contrôleur refuse ce champ pour un article en stock).
     */
    @Transactional
    public Produit modifierProduit(Long id, Produit modifications) {
        if (modifications.getVersion() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Version du produit obligatoire");
        }
        // Ordre global des verrous : la boutique avant le produit (docs/lot-2.md, « Ordre des verrous »).
        stockLockRepository.verrouillerBoutiqueCourante();
        if (!produitRepository.existsByIdAndBoutiqueId(id, boutiqueService.idBoutiqueCourante())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Produit introuvable");
        }
        Produit produit = stockLockRepository.verrouillerProduits(List.of(id)).get(id);
        if (!modifications.getVersion().equals(produit.getVersion())) throw new ProduitModifieException();
        if (modifications.getNature() != null && modifications.getNature() != produit.getNature()) {
            throw new IllegalArgumentException("La nature d'une fiche ne peut pas être changée après sa création. Créez une nouvelle fiche.");
        }
        if (produit.isPrixDefinis() && modifications.getPrixVente() == null) {
            throw new IllegalArgumentException("Le prix de vente est obligatoire pour modifier une fiche dont les tarifs sont déjà définis.");
        }
        if (!produit.isPrixDefinis() && modifications.getPrixVente() != null && modifications.getPrixVente().signum() != 0) {
            throw new IllegalArgumentException("Définissez les prix lors d'un achat ou de l'enregistrement du stock initial.");
        }
        if (!produit.isPrixDefinis()) modifications.setPrixVente(Montants.ZERO);
        chargerRelations(modifications);
        produit.setNom(modifications.getNom());
        produit.setReference(modifications.getReference());
        produit.setUnite(normaliserUnite(modifications.getUnite()));
        if (!produit.estService() && modifications.getSeuilAlerte() != null) {
            produit.setSeuilAlerte(validerSeuil(modifications.getSeuilAlerte()));
        }
        if (produit.isPrixDefinis()) produit.setPrixVente(modifications.getPrixVente());
        if (produit.estService()) {
            BigDecimal cout = Montants.positifOuNul(Montants.ouZero(modifications.getCoutMoyen()), "Le coût du service");
            produit.setCoutMoyen(cout);
            produit.setPrixAchat(cout);
        }
        produit.setCategorie(modifications.getCategorie());
        produit.setFournisseur(modifications.getFournisseur());
        return produitRepository.save(produit);
    }

    /** Compteurs du catalogue : les services n'entrent ni dans le stock, ni dans les alertes. */
    public record ResumeCatalogue(long total, long enStock, long faibles, long ruptures, long services) {
        public static ResumeCatalogue de(List<Produit> produits) {
            long enStock = 0, faibles = 0, ruptures = 0, services = 0;
            for (Produit produit : produits) {
                switch (produit.etatStock()) {
                    case "service" -> services++;
                    case "rupture" -> ruptures++;
                    case "faible" -> { faibles++; enStock++; }
                    case "disponible" -> enStock++;
                    default -> { }
                }
            }
            return new ResumeCatalogue(produits.size(), enStock, faibles, ruptures, services);
        }
    }

    /** L'unité sert seulement à l'affichage : espaces superflus retirés, vide = aucune unité. */
    static String normaliserUnite(String unite) {
        if (unite == null) return null;
        String propre = unite.strip().replaceAll("\\s+", " ");
        if (propre.isEmpty()) return null;
        if (propre.codePointCount(0, propre.length()) > 30) {
            throw new IllegalArgumentException("L'unité accepte au maximum 30 caractères.");
        }
        if (propre.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("L'unité contient des caractères non autorisés.");
        }
        return propre;
    }

    private static Long validerSeuil(Long seuil) {
        if (seuil == null) return Produit.SEUIL_ALERTE_PAR_DEFAUT;
        if (seuil < 0) throw new IllegalArgumentException("Le seuil d'alerte ne peut pas être négatif.");
        if (seuil > 999_999_999L) throw new IllegalArgumentException("Le seuil d'alerte ne peut pas dépasser 999 999 999.");
        return seuil;
    }

    private void chargerRelations(Produit produit) {
        produit.setPrixAchat(Montants.positifOuNul(produit.getPrixAchat(), "Le prix d'achat"));
        produit.setPrixVente(Montants.positifOuNul(produit.getPrixVente(), "Le prix de vente"));
        if (produit.getCategorie() == null || produit.getCategorie().getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Catégorie obligatoire");
        }
        Long boutiqueId = boutiqueService.idBoutiqueCourante();
        produit.setCategorie(categorieRepository.findByIdAndBoutiqueId(produit.getCategorie().getId(), boutiqueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Catégorie introuvable")));
        if (produit.getFournisseur() != null) {
            if (produit.getFournisseur().getId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fournisseur invalide");
            }
            produit.setFournisseur(fournisseurRepository.findByIdAndBoutiqueId(produit.getFournisseur().getId(), boutiqueId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fournisseur introuvable")));
        }
    }

    @Transactional
    public void supprimerProduit(Long id) {
        stockLockRepository.verrouillerBoutiqueCourante();
        Produit produit = stockLockRepository.verrouillerProduits(List.of(id)).get(id);
        if (produit.getQuantite() != null && produit.getQuantite() != 0) {
            throw new IllegalArgumentException("Ce produit possède encore du stock et doit être conservé. Seuls les achats et les ventes peuvent faire varier les quantités.");
        }
        if (mouvementsStock.existsByProduitId(id)) throw new IllegalArgumentException("Ce produit possède un historique d’inventaire et doit être conservé.");
        if (possedeLigne("DetailAchat", id) || possedeLigne("DetailVente", id)) {
            throw new IllegalArgumentException("Ce produit figure dans un achat ou une vente et doit être conservé pour préserver l’historique.");
        }
        if (possedeLigne("DetailDevis", id)) {
            throw new IllegalArgumentException("Ce produit figure dans un devis et doit être conservé pour préserver l’historique.");
        }
        produitRepository.delete(produit);
    }

    private boolean possedeLigne(String entite, Long produitId) {
        return !entityManager.createQuery("select ligne.id from " + entite + " ligne where ligne.produit.id = :produitId", Long.class)
                .setParameter("produitId", produitId).setMaxResults(1).getResultList().isEmpty();
    }
}
