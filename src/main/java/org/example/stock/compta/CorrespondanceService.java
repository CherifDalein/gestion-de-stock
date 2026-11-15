package org.example.stock.compta;

import org.example.stock.model.Boutique;
import org.example.stock.model.CorrespondanceCompta;
import org.example.stock.repository.CorrespondanceComptaRepository;
import org.example.stock.service.BoutiqueService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Table de correspondance d'une boutique : seules les valeurs que le gérant a changées sont enregistrées, les autres viennent des valeurs
 * par défaut de {@link CleCompta}. « Rétablir les valeurs par défaut » supprime donc simplement les lignes de la boutique.
 */
@Service
public class CorrespondanceService {
    private final CorrespondanceComptaRepository repo;
    private final PlanComptesService plan;
    private final BoutiqueService boutiques;

    public CorrespondanceService(CorrespondanceComptaRepository repo, PlanComptesService plan, BoutiqueService boutiques) {
        this.repo = repo;
        this.plan = plan;
        this.boutiques = boutiques;
    }

    /** Une ligne affichée : la clé, le compte en vigueur, les mots-clés en vigueur et si l'un des deux a été changé. */
    public record LigneVue(CleCompta cle, String compte, String intituleCompte, String motsCles, boolean modifie) {}

    /** Correspondance en vigueur : valeurs par défaut remplacées par les lignes de la boutique, trésorerie par sous-comptes. */
    @Transactional
    public Correspondance effective(Long boutiqueId) {
        plan.synchroniserTresorerie(boutiqueId);
        Map<CleCompta, String> comptes = new EnumMap<>(CleCompta.class);
        Map<CleCompta, String> mots = new EnumMap<>(CleCompta.class);
        for (CorrespondanceCompta l : repo.findByBoutiqueId(boutiqueId)) {
            CleCompta cle = CleCompta.depuisCode(l.getCle());
            if (cle == null) continue;
            if (l.getCompteNumero() != null && !l.getCompteNumero().isBlank()) comptes.put(cle, l.getCompteNumero());
            if (cle.estDepense() && l.getMotsCles() != null) mots.put(cle, l.getMotsCles());
        }
        return Correspondance.avec(comptes, mots, plan.comptesTresorerie(boutiqueId));
    }

    @Transactional
    public List<LigneVue> lignes(Long boutiqueId) {
        Correspondance eff = effective(boutiqueId);
        Map<String, String> intitules = plan.intitules(boutiqueId);
        Map<CleCompta, CorrespondanceCompta> stock = new HashMap<>();
        for (CorrespondanceCompta l : repo.findByBoutiqueId(boutiqueId)) { CleCompta k = CleCompta.depuisCode(l.getCle()); if (k != null) stock.put(k, l); }
        java.util.ArrayList<LigneVue> r = new java.util.ArrayList<>();
        for (CleCompta cle : CleCompta.values()) {
            String compte = eff.compte(cle);
            String mots = cle.estDepense() ? String.join(", ", Correspondance.decouperMotsCles(
                    stock.containsKey(cle) && stock.get(cle).getMotsCles() != null ? stock.get(cle).getMotsCles() : cle.getMotsClesParDefaut())) : "";
            r.add(new LigneVue(cle, compte, intitules.getOrDefault(compte, ""), mots, stock.containsKey(cle)));
        }
        return r;
    }

    /** Enregistre le compte (et les mots-clés d'une catégorie de dépense) d'une clé ; une valeur identique au défaut supprime la ligne. */
    @Transactional
    public void modifier(Long boutiqueId, String code, String compte, String motsCles) {
        CleCompta cle = CleCompta.depuisCode(code);
        if (cle == null) throw new IllegalArgumentException("Nature d'opération inconnue.");
        String c = compte == null ? "" : compte.strip();
        if (!plan.existe(boutiqueId, c)) throw new IllegalArgumentException("Le compte " + (c.isEmpty() ? "saisi" : c) + " n'existe ni dans le plan officiel ni parmi les sous-comptes de la boutique.");
        String mots = null;
        if (cle.estDepense()) {
            mots = String.join(", ", Correspondance.decouperMotsCles(motsCles));
            if (mots.length() > CorrespondanceCompta.MOTS_CLES_MAX) throw new IllegalArgumentException("La liste de mots-clés est trop longue.");
            if (cle != CleCompta.DEPENSE_AUTRE && mots.isEmpty()) throw new IllegalArgumentException("Saisissez au moins un mot-clé pour cette catégorie de dépense.");
        }
        boolean defaut = c.equals(cle.getCompteParDefaut())
                && (!cle.estDepense() || String.join(", ", Correspondance.decouperMotsCles(cle.getMotsClesParDefaut())).equals(mots));
        CorrespondanceCompta ligne = repo.findByBoutiqueIdAndCle(boutiqueId, cle.name()).orElse(null);
        if (defaut) { if (ligne != null) repo.delete(ligne); return; }
        if (ligne == null) {
            Boutique boutique = boutiques.boutiqueCourante();
            if (!boutique.getId().equals(boutiqueId)) throw new IllegalArgumentException("Boutique inconnue.");
            ligne = new CorrespondanceCompta(boutique, cle.name(), c, mots);
        } else ligne.modifier(c, mots);
        repo.save(ligne);
    }

    @Transactional
    public void retablirDefauts(Long boutiqueId) { repo.supprimerDeLaBoutique(boutiqueId); }
}
