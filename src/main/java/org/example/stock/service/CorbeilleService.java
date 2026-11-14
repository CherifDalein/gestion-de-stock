package org.example.stock.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.example.stock.config.CorbeilleProperties;
import org.example.stock.export.RechercheListe;
import org.example.stock.model.ElementSupprimable;
import org.example.stock.repository.StockLockRepository;
import org.hibernate.query.NativeQuery;
import org.hibernate.type.StandardBasicTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Corbeille (lot 6) : un client, un fournisseur, un produit ou une catégorie supprimé n'est pas effacé ; il garde ses données avec
 * une date et un auteur de suppression ({@code supprime_le}, {@code supprime_par}) et disparaît de tous les écrans. Il peut être
 * restauré pendant {@code stock.corbeille.retention-jours} jours (30 par défaut), puis une tâche quotidienne l'efface.
 *
 * <p>Les règles qui refusent une suppression ne changent pas : {@link #exigerSansLien} reprend, une à une, les clés étrangères qui
 * la refusaient. Les fiches de la corbeille sont invisibles pour JPA ({@code @SQLRestriction} des entités) ; ce service les lit et les
 * modifie donc par SQL direct, avec des noms de tables tirés de {@link TypeCorbeille}, jamais du navigateur.</p>
 *
 * <p>Verrous : restaurer, effacer et vider prennent d'abord le verrou de la boutique, puis celui de la fiche (ordre global de
 * {@code docs/lot-2.md}). L'effacement automatique fait de même pour chaque fiche, dans sa propre transaction.</p>
 */
@Service
public class CorbeilleService {
    private static final Logger JOURNAL = LoggerFactory.getLogger(CorbeilleService.class);
    /** Les produits d'abord : ils référencent les catégories et les fournisseurs, qui ne peuvent partir qu'ensuite. */
    static final List<TypeCorbeille> ORDRE_EFFACEMENT = List.of(TypeCorbeille.PRODUIT, TypeCorbeille.CLIENT, TypeCorbeille.FOURNISSEUR, TypeCorbeille.CATEGORIE);

    /** Ce que rend une suppression : de quoi composer le message et le lien « Annuler ». */
    public record ElementSupprime(TypeCorbeille type, Long id, String nom) {}

    /** Une ligne de la page Corbeille. {@code joursRestants} : jours entiers avant l'effacement automatique (0 : aujourd'hui). */
    public record Ligne(TypeCorbeille type, Long id, String nom, String detail, LocalDateTime supprimeLe, Long supprimePar, String supprimeParNom,
                        LocalDateTime effaceLe, long joursRestants) {}

    /** Bilan d'un effacement : fiches effacées, et fiches gardées parce qu'une autre fiche s'y réfère encore ou que l'effacement a échoué. */
    public record Bilan(int effaces, int conserves) {}

    @PersistenceContext
    private EntityManager entityManager;
    private final BoutiqueService boutiques;
    private final StockLockRepository verrous;
    private final EcheanceService horloge;
    private final CorbeilleProperties proprietes;
    private final PlatformTransactionManager transactions;

    public CorbeilleService(BoutiqueService boutiques, StockLockRepository verrous, EcheanceService horloge, CorbeilleProperties proprietes,
                            PlatformTransactionManager transactions) {
        this.boutiques = boutiques; this.verrous = verrous; this.horloge = horloge; this.proprietes = proprietes; this.transactions = transactions;
    }

    public int retentionJours() { return proprietes.getRetentionJours(); }

    // ---- Mise à la corbeille (appelée par les services de suppression) ----------------------------------------------------------------

    /**
     * Refuse la suppression si une autre fiche se réfère à celle-ci : mêmes cas que les clés étrangères qui la refusaient avant la
     * corbeille. Les produits déjà à la corbeille ne comptent pas (ils ne s'affichent plus ; leur restauration attendra celle de la catégorie).
     */
    public void exigerSansLien(TypeCorbeille type, Long id) {
        if (aDesLiens(type, id, false)) throw new IllegalArgumentException(type.messageRefus());
    }

    /** Place la fiche dans la corbeille : date du serveur, compte connecté. La fiche doit être gérée par JPA, qui écrit la modification à la fin de la transaction. */
    public ElementSupprime placer(TypeCorbeille type, ElementSupprimable fiche) {
        fiche.mettreALaCorbeille(horloge.maintenant(), boutiques.idUtilisateurCourant());
        return new ElementSupprime(type, fiche.getId(), fiche.getNom());
    }

    /** « Client X placé dans la corbeille pour 30 jours. » */
    public String messagePlacement(ElementSupprime e) {
        int jours = retentionJours();
        return e.type().libelle() + " " + e.nom() + " " + e.type().placeDansLaCorbeille() + " dans la corbeille pour " + jours + (jours == 1 ? " jour." : " jours.");
    }

    /** Message de succès et lien « Annuler » (restauration immédiate) de la page qui suit la suppression. */
    public void annoncer(RedirectAttributes redirection, ElementSupprime supprime) {
        redirection.addFlashAttribute("success", messagePlacement(supprime));
        redirection.addFlashAttribute("annulerCorbeille", Map.of("type", supprime.type().code(), "id", supprime.id()));
    }

    // ---- Lecture -------------------------------------------------------------------------------------------------------------------------

    /** Contenu de la corbeille de la boutique courante, le plus récent d'abord, filtré par type et par recherche (mots, sans accents ni casse). */
    @Transactional(readOnly = true)
    public List<Ligne> lister(TypeCorbeille filtre, String recherche) {
        exigerGerant();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        LocalDateTime maintenant = horloge.maintenant();
        var brutes = new ArrayList<Ligne>();
        for (TypeCorbeille type : TypeCorbeille.values()) {
            if (filtre != null && filtre != type) continue;
            brutes.addAll(lignes(type, boutiqueId, maintenant));
        }
        var noms = nomsDesAuteurs(brutes, boutiqueId);
        var resultat = new ArrayList<Ligne>();
        for (Ligne l : brutes) {
            String auteur = l.supprimePar() == null ? null : noms.get(l.supprimePar());
            var avecAuteur = new Ligne(l.type(), l.id(), l.nom(), l.detail(), l.supprimeLe(), l.supprimePar(), auteur, l.effaceLe(), l.joursRestants());
            if (recherche == null || recherche.isBlank() || RechercheListe.correspond(recherche,
                    String.join(" ", l.type().libelle(), l.nom() == null ? "" : l.nom(), l.detail() == null ? "" : l.detail(), auteur == null ? "" : auteur))) resultat.add(avecAuteur);
        }
        resultat.sort(Comparator.comparing(Ligne::supprimeLe, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(l -> l.type().ordinal()).thenComparing(Ligne::id));
        return resultat;
    }

    /** Nombre de fiches à la corbeille par type (pour les compteurs du filtre). */
    @Transactional(readOnly = true)
    public Map<TypeCorbeille, Integer> compter() {
        exigerGerant();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        var comptes = new EnumMap<TypeCorbeille, Integer>(TypeCorbeille.class);
        for (TypeCorbeille type : TypeCorbeille.values()) {
            Number n = (Number) entityManager.createNativeQuery("select count(*) from " + type.table() + " where boutique_id = :boutique and supprime_le is not null")
                    .setParameter("boutique", boutiqueId).getSingleResult();
            comptes.put(type, n.intValue());
        }
        return comptes;
    }

    // ---- Restauration ------------------------------------------------------------------------------------------------------------------

    @Transactional
    public ElementSupprime restaurer(TypeCorbeille type, Long id) {
        exigerGerant();
        verrous.verrouillerBoutiqueCourante();
        String nom = verrous.verrouillerElementDeLaCorbeille(type, id);
        Long boutiqueId = boutiques.idBoutiqueCourante();
        if (type == TypeCorbeille.CATEGORIE) {
            if (nombre("select count(*) from categorie where boutique_id = :boutique and supprime_le is null and nom = :nom", Map.of("boutique", boutiqueId, "nom", nom)) > 0)
                throw conflitDeNom(nom);
        } else if (type == TypeCorbeille.PRODUIT) {
            exigerParentActif("categorie", "categorie_id", id, "sa catégorie", "cette catégorie");
            exigerParentActif("fournisseur", "fournisseur_id", id, "son fournisseur", "ce fournisseur");
        }
        String sql = "update " + type.table() + " set supprime_le = null, supprime_par = null"
                + (type == TypeCorbeille.CATEGORIE ? ", nom_cle = nom" : "") + (type == TypeCorbeille.PRODUIT ? ", version = version + 1" : "")
                + " where id = :id and boutique_id = :boutique and supprime_le is not null";
        try {
            entityManager.createNativeQuery(sql).setParameter("id", id).setParameter("boutique", boutiqueId).executeUpdate();
            entityManager.flush();
        } catch (DataIntegrityViolationException e) {
            // Une catégorie du même nom a été créée entre la vérification et l'écriture : la clé d'unicité l'arrête.
            if (type == TypeCorbeille.CATEGORIE) throw conflitDeNom(nom);
            throw e;
        }
        return new ElementSupprime(type, id, nom);
    }

    private static IllegalArgumentException conflitDeNom(String nom) {
        return new IllegalArgumentException("Impossible de restaurer la catégorie « " + nom + " » : une catégorie active porte déjà ce nom. "
                + "Renommez d’abord la catégorie active, puis restaurez celle de la corbeille.");
    }

    /** Un produit ne revient pas à l'écran avec une catégorie ou un fournisseur toujours à la corbeille. */
    private void exigerParentActif(String table, String colonne, Long produitId, String sonParent, String ceParent) {
        var noms = entityManager.createNativeQuery("select t.nom from " + table + " t join produit p on p." + colonne + " = t.id where p.id = :id and t.supprime_le is not null")
                .setParameter("id", produitId).getResultList();
        if (!noms.isEmpty()) throw new IllegalArgumentException("Impossible de restaurer ce produit : " + sonParent + " « " + noms.get(0)
                + " » est dans la corbeille. Restaurez d’abord " + ceParent + ".");
    }

    // ---- Effacement définitif ------------------------------------------------------------------------------------------------------

    @Transactional
    public ElementSupprime supprimerDefinitivement(TypeCorbeille type, Long id) {
        exigerGerant();
        verrous.verrouillerBoutiqueCourante();
        String nom = verrous.verrouillerElementDeLaCorbeille(type, id);
        if (aDesLiens(type, id, true)) throw new IllegalArgumentException(type.libelle() + " « " + nom + " » est encore utilisé" + (type.feminin() ? "e" : "")
                + " par d’autres fiches, par exemple des produits de la corbeille : effacez-les d’abord.");
        effacer(type, id, boutiques.idBoutiqueCourante());
        return new ElementSupprime(type, id, nom);
    }

    /** Efface tout le contenu de la corbeille de la boutique courante ; les fiches encore référencées sont gardées et comptées. */
    @Transactional
    public Bilan vider() {
        exigerGerant();
        verrous.verrouillerBoutiqueCourante();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        int effaces = 0, conserves = 0;
        for (TypeCorbeille type : ORDRE_EFFACEMENT) {
            for (Long id : identifiants(type, boutiqueId, null)) {
                verrous.verrouillerElementDeLaCorbeille(type, id);
                if (aDesLiens(type, id, true)) { conserves++; continue; }
                effacer(type, id, boutiqueId);
                effaces++;
            }
        }
        return new Bilan(effaces, conserves);
    }

    // ---- Effacement automatique ----------------------------------------------------------------------------------------------------

    public Bilan purgerExpires() { return purgerExpires(horloge.maintenant()); }

    /**
     * Efface définitivement, toutes boutiques confondues, les fiches à la corbeille depuis plus de {@code retention-jours} jours à l'instant
     * donné. Chaque fiche a sa transaction : boutique verrouillée, puis fiche. Une fiche qu'on ne peut pas effacer (une autre ligne s'y
     * réfère encore, ou une erreur) est journalisée sans son nom, puis la tâche passe à la suivante.
     */
    public Bilan purgerExpires(LocalDateTime maintenant) {
        LocalDateTime limite = maintenant.minusDays(proprietes.getRetentionJours());
        var lecture = new TransactionTemplate(transactions); lecture.setReadOnly(true);
        var ecriture = new TransactionTemplate(transactions);
        int effaces = 0, conserves = 0;
        for (TypeCorbeille type : ORDRE_EFFACEMENT) {
            List<long[]> cibles = lecture.execute(s -> {
                var resultat = new ArrayList<long[]>();
                for (Object ligne : entityManager.createNativeQuery("select boutique_id, id from " + type.table() + " where supprime_le is not null and supprime_le < :limite order by boutique_id, id")
                        .setParameter("limite", limite).getResultList()) {
                    Object[] c = (Object[]) ligne;
                    resultat.add(new long[]{((Number) c[0]).longValue(), ((Number) c[1]).longValue()});
                }
                return resultat;
            });
            for (long[] cible : cibles == null ? List.<long[]>of() : cibles) {
                try {
                    Integer issue = ecriture.execute(s -> effacerSiExpire(type, cible[0], cible[1], limite));
                    if (issue != null && issue == EFFACE) effaces++; else if (issue != null && issue == GARDE) conserves++;
                } catch (RuntimeException e) {
                    conserves++;
                    JOURNAL.warn("Corbeille : {} n° {} de la boutique {} n'a pas pu être effacé définitivement ({}) ; il reste à la corbeille.",
                            type.table(), cible[1], cible[0], e.getClass().getSimpleName());
                }
            }
        }
        return new Bilan(effaces, conserves);
    }

    private static final int EFFACE = 1, GARDE = 0, DEJA_PARTI = -1;

    private int effacerSiExpire(TypeCorbeille type, long boutiqueId, long id, LocalDateTime limite) {
        // Même ordre que partout : la boutique d'abord, puis la fiche.
        entityManager.createNativeQuery("select id from boutique where id = :id for update").setParameter("id", boutiqueId).getResultList();
        var lignes = entityManager.createNativeQuery("select supprime_le from " + type.table() + " where id = :id and boutique_id = :boutique and supprime_le is not null and supprime_le < :limite for update")
                .setParameter("id", id).setParameter("boutique", boutiqueId).setParameter("limite", limite).getResultList();
        // Restaurée ou déjà effacée entre la lecture et le verrou : rien à faire, et ce n'est pas un échec.
        if (lignes.isEmpty()) return DEJA_PARTI;
        if (aDesLiens(type, id, true)) {
            JOURNAL.warn("Corbeille : {} n° {} de la boutique {} est encore référencé par une autre fiche ; il reste à la corbeille.", type.table(), id, boutiqueId);
            return GARDE;
        }
        effacer(type, id, boutiqueId);
        return EFFACE;
    }

    // ---- Détails ---------------------------------------------------------------------------------------------------------------------------

    private void effacer(TypeCorbeille type, Long id, Long boutiqueId) {
        entityManager.createNativeQuery("delete from " + type.table() + " where id = :id and boutique_id = :boutique and supprime_le is not null")
                .setParameter("id", id).setParameter("boutique", boutiqueId).executeUpdate();
        entityManager.flush();
    }

    /** Une autre ligne se réfère-t-elle à la fiche ? {@code avecCorbeille} : compter aussi les lignes qui sont elles-mêmes à la corbeille. */
    boolean aDesLiens(TypeCorbeille type, Long id, boolean avecCorbeille) {
        for (TypeCorbeille.Lien lien : type.liens()) {
            String sql = "select count(*) from " + lien.table() + " where " + lien.colonne() + " = :id" + (lien.corbeille() && !avecCorbeille ? " and supprime_le is null" : "");
            if (nombre(sql, Map.of("id", id)) > 0) return true;
        }
        return false;
    }

    private long nombre(String sql, Map<String, Object> parametres) {
        var requete = entityManager.createNativeQuery(sql);
        parametres.forEach(requete::setParameter);
        return ((Number) requete.getSingleResult()).longValue();
    }

    private List<Long> identifiants(TypeCorbeille type, Long boutiqueId, LocalDateTime avant) {
        var requete = entityManager.createNativeQuery("select id from " + type.table() + " where boutique_id = :boutique and supprime_le is not null"
                + (avant == null ? "" : " and supprime_le < :limite") + " order by id").setParameter("boutique", boutiqueId);
        if (avant != null) requete.setParameter("limite", avant);
        var ids = new ArrayList<Long>();
        for (Object o : requete.getResultList()) ids.add(((Number) o).longValue());
        return ids;
    }

    private List<Ligne> lignes(TypeCorbeille type, Long boutiqueId, LocalDateTime maintenant) {
        boolean produit = type == TypeCorbeille.PRODUIT;
        @SuppressWarnings("unchecked")
        NativeQuery<Object[]> requete = entityManager.createNativeQuery("select id, nom, " + (produit ? "reference, " : "") + "supprime_le, supprime_par from " + type.table()
                        + " where boutique_id = :boutique and supprime_le is not null").unwrap(NativeQuery.class);
        requete.addScalar("id", StandardBasicTypes.LONG).addScalar("nom", StandardBasicTypes.STRING);
        if (produit) requete.addScalar("reference", StandardBasicTypes.STRING);
        requete.addScalar("supprime_le", StandardBasicTypes.LOCAL_DATE_TIME).addScalar("supprime_par", StandardBasicTypes.LONG);
        requete.setParameter("boutique", boutiqueId);
        int decalage = produit ? 1 : 0;
        var lignes = new ArrayList<Ligne>();
        for (Object[] c : requete.getResultList()) {
            LocalDateTime supprimeLe = (LocalDateTime) c[2 + decalage];
            LocalDateTime effaceLe = supprimeLe == null ? null : supprimeLe.plusDays(proprietes.getRetentionJours());
            long jours = effaceLe == null ? 0 : Math.max(0, (Duration.between(maintenant, effaceLe).toSeconds() + 86_399) / 86_400);
            lignes.add(new Ligne(type, (Long) c[0], (String) c[1], produit ? (String) c[2] : null, supprimeLe, (Long) c[3 + decalage], null, effaceLe, jours));
        }
        return lignes;
    }

    private Map<Long, String> nomsDesAuteurs(List<Ligne> lignes, Long boutiqueId) {
        var ids = new HashSet<Long>();
        for (Ligne l : lignes) if (l.supprimePar() != null) ids.add(l.supprimePar());
        var noms = new HashMap<Long, String>();
        if (ids.isEmpty()) return noms;
        for (Object[] c : entityManager.createQuery("select u.id, u.nom from Utilisateur u where u.id in :ids and u.boutique.id = :boutique", Object[].class)
                .setParameter("ids", ids).setParameter("boutique", boutiqueId).getResultList()) noms.put((Long) c[0], (String) c[1]);
        return noms;
    }

    /** Seul le gérant voit et manipule la corbeille (la configuration de sécurité l'impose aussi à l'adresse). */
    private static void exigerGerant() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Réservé au gérant");
    }
}
