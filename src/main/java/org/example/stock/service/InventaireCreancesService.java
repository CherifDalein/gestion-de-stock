package org.example.stock.service;

import org.example.stock.enums.SensSolde;
import org.example.stock.enums.TypeLigneInventaire;
import org.example.stock.model.Client;
import org.example.stock.model.Emails;
import org.example.stock.model.Fournisseur;
import org.example.stock.model.InventaireCreances;
import org.example.stock.model.LigneInventaire;
import org.example.stock.model.Montants;
import org.example.stock.model.PartenaireFinancier;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.ClientRepository;
import org.example.stock.repository.FournisseurRepository;
import org.example.stock.repository.InventaireCreancesRepository;
import org.example.stock.repository.PartenaireFinancierRepository;
import org.example.stock.repository.StockLockRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Inventaire des créances et dettes (étape 3b2, gérant seulement) : à une date donnée, le gérant saisit le solde que chaque client,
 * fournisseur ou autre partenaire (3b1) lui confirme ; l'application garde son propre solde du moment, l'écart et une raison.
 *
 * <p><b>Aucun effet sur les soldes</b> : l'inventaire n'écrit ni mouvement, ni règlement, ni solde de départ. Un écart se corrige, si le
 * gérant le décide, par un ajustement du solde de départ (lien depuis la ligne, jamais automatique).</p>
 *
 * <p>Les soldes sont signés du point de vue de la boutique : positif, le partenaire doit de l'argent à la boutique ; négatif, la boutique lui
 * en doit (avance d'un client comprise). Écart = solde confirmé − solde de l'application. L'application ne conserve pas l'historique des
 * soldes : le solde de l'application est celui <b>de l'enregistrement de l'inventaire</b>, pas de sa date (docs/lot-3.md).</p>
 *
 * <p>Verrou : la boutique seule (aucune fiche de partenaire n'est modifiée).</p>
 */
@Service
public class InventaireCreancesService {
    /** Nombre maximal de partenaires dans un même inventaire (formulaire borné). */
    public static final int LIGNES_MAXIMUM = 100;
    public static final int TEXTE_MAXIMUM = 255;

    private final StockLockRepository verrous;
    private final SoldePartenaireService soldes;
    private final PartenairesFinanciersService financiers;
    private final ClientRepository clients;
    private final FournisseurRepository fournisseurs;
    private final PartenaireFinancierRepository partenairesFinanciers;
    private final InventaireCreancesRepository inventaires;
    private final BoutiqueService boutiques;
    private final UtilisateurRepository utilisateurs;
    private final EcheanceService echeances;

    public InventaireCreancesService(StockLockRepository verrous, SoldePartenaireService soldes, PartenairesFinanciersService financiers,
                                     ClientRepository clients, FournisseurRepository fournisseurs, PartenaireFinancierRepository partenairesFinanciers,
                                     InventaireCreancesRepository inventaires, BoutiqueService boutiques, UtilisateurRepository utilisateurs,
                                     EcheanceService echeances) {
        this.verrous = verrous; this.soldes = soldes; this.financiers = financiers; this.clients = clients; this.fournisseurs = fournisseurs;
        this.partenairesFinanciers = partenairesFinanciers; this.inventaires = inventaires; this.boutiques = boutiques;
        this.utilisateurs = utilisateurs; this.echeances = echeances;
    }

    /** Un partenaire proposé à la saisie, avec le solde de l'application (signé : positif, il doit à la boutique). */
    public record Candidat(TypeLigneInventaire type, Long id, String nom, BigDecimal solde) {
        public String cle() { return type.name() + ":" + id; }
    }

    /** Partenaires proposés (au plus {@link #LIGNES_MAXIMUM}) et nombre de ceux qui ne tiennent pas dans la page. */
    public record Candidats(List<Candidat> liste, int masques) { }

    /** Une ligne saisie : la clé « TYPE:identifiant », le montant confirmé (vide : ligne ignorée), son sens et la raison de l'écart. */
    public record Saisie(String cle, BigDecimal montant, SensSolde sens, String raison) { }

    // ---- Saisie ---------------------------------------------------------------------------------------------

    /** Partenaires de la boutique avec leur solde actuel, ceux qui ont un solde d'abord (du plus grand au plus petit en valeur absolue). */
    @Transactional(readOnly = true)
    public Candidats candidats(String recherche) {
        DroitsEtats.exigerGerant();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        String q = recherche == null ? "" : recherche.strip().toLowerCase(Locale.ROOT);
        List<Candidat> tous = new ArrayList<>();
        Map<Long, SoldePartenaire> sc = soldes.soldesClients();
        for (Client c : clients.findAllByBoutiqueId(boutiqueId)) tous.add(new Candidat(TypeLigneInventaire.CLIENT, c.getId(), c.getNom(), sc.get(c.getId()).soldeNet()));
        Map<Long, SoldePartenaire> sf = soldes.soldesFournisseurs();
        for (Fournisseur f : fournisseurs.findAllByBoutiqueId(boutiqueId)) tous.add(new Candidat(TypeLigneInventaire.FOURNISSEUR, f.getId(), f.getNom(), sf.get(f.getId()).soldeNet()));
        for (var vue : financiers.lister(false)) tous.add(new Candidat(TypeLigneInventaire.FINANCIER, vue.partenaire().getId(), vue.partenaire().getNom(), vue.solde().total().negate()));
        List<Candidat> retenus = tous.stream().filter(c -> q.isEmpty() || c.nom().toLowerCase(Locale.ROOT).contains(q))
                .sorted(Comparator.comparing((Candidat c) -> c.solde().signum() == 0).thenComparing((Candidat c) -> c.solde().abs(), Comparator.reverseOrder())
                        .thenComparing(c -> c.nom().toLowerCase(Locale.ROOT)).thenComparing(Candidat::cle)).toList();
        return new Candidats(retenus.stream().limit(LIGNES_MAXIMUM).toList(), Math.max(0, retenus.size() - LIGNES_MAXIMUM));
    }

    /**
     * Enregistre l'inventaire des lignes dont un montant est saisi. Un second envoi du même jeton rend l'inventaire déjà enregistré.
     * Rien d'autre n'est écrit : ni mouvement, ni règlement, ni solde.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public InventaireCreances enregistrer(LocalDate dateInventaire, String noteSaisie, String jeton, List<Saisie> saisies) {
        DroitsEtats.exigerGerant();
        if (jeton == null || jeton.isBlank() || jeton.length() > 36) throw new IllegalArgumentException("Rechargez le formulaire d'inventaire.");
        if (dateInventaire == null) throw new IllegalArgumentException("Choisissez la date de l'inventaire.");
        if (dateInventaire.isAfter(echeances.aujourdHui())) throw new IllegalArgumentException("La date de l'inventaire ne peut pas être dans le futur.");
        String note = texte(noteSaisie, "La note");
        List<Saisie> utiles = saisies == null ? List.of() : saisies.stream().filter(s -> s != null && s.montant() != null).toList();
        if (utiles.isEmpty()) throw new IllegalArgumentException("Saisissez le solde confirmé d'au moins un partenaire.");
        if (utiles.size() > LIGNES_MAXIMUM) throw new IllegalArgumentException("Un inventaire comporte au plus " + LIGNES_MAXIMUM + " partenaires.");

        verrous.verrouillerBoutiqueCourante();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        InventaireCreances deja = inventaires.findByBoutiqueIdAndJeton(boutiqueId, jeton).orElse(null);
        if (deja != null) return deja;

        InventaireCreances inventaire = new InventaireCreances();
        inventaire.setBoutique(boutiques.boutiqueCourante());
        inventaire.setUtilisateur(utilisateurCourant());
        inventaire.setDateInventaire(dateInventaire);
        inventaire.setDateCreation(java.time.LocalDateTime.now());
        inventaire.setNote(note);
        inventaire.setJeton(jeton);
        Set<String> vus = new HashSet<>();
        for (Saisie s : utiles) {
            if (!vus.add(s.cle())) throw new IllegalArgumentException("Un partenaire ne peut figurer qu'une fois dans l'inventaire.");
            inventaire.getLignes().add(ligne(inventaire, boutiqueId, s));
        }
        return inventaires.saveAndFlush(inventaire);
    }

    private LigneInventaire ligne(InventaireCreances inventaire, Long boutiqueId, Saisie s) {
        String[] morceaux = s.cle() == null ? new String[0] : s.cle().split(":");
        if (morceaux.length != 2 || !morceaux[1].matches("[0-9]{1,18}")) throw new IllegalArgumentException("Partenaire inconnu.");
        TypeLigneInventaire type;
        try { type = TypeLigneInventaire.valueOf(morceaux[0]); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Partenaire inconnu."); }
        Long id = Long.valueOf(morceaux[1]);
        BigDecimal montant = Montants.positifOuNul(s.montant(), "Le solde confirmé");
        if (montant.signum() > 0 && s.sens() == null) throw new IllegalArgumentException("Choisissez le sens du solde confirmé : « il me doit » ou « je lui dois ».");
        BigDecimal confirme = montant.signum() == 0 ? Montants.ZERO : s.sens() == SensSolde.JE_LUI_DOIS ? montant.negate() : montant;

        LigneInventaire ligne = new LigneInventaire();
        ligne.setInventaire(inventaire);
        ligne.setType(type);
        BigDecimal appli;
        switch (type) {
            case CLIENT -> {
                Client c = clients.findByIdAndBoutiqueId(id, boutiqueId).orElseThrow(InventaireCreancesService::partenaireInconnu);
                ligne.setClient(c); ligne.setNom(c.getNom()); appli = soldes.soldeClient(id).soldeNet();
            }
            case FOURNISSEUR -> {
                Fournisseur f = fournisseurs.findByIdAndBoutiqueId(id, boutiqueId).orElseThrow(InventaireCreancesService::partenaireInconnu);
                ligne.setFournisseur(f); ligne.setNom(f.getNom()); appli = soldes.soldeFournisseur(id).soldeNet();
            }
            default -> {
                PartenaireFinancier p = partenairesFinanciers.findByIdAndBoutiqueId(id, boutiqueId).orElseThrow(InventaireCreancesService::partenaireInconnu);
                ligne.setPartenaireFinancier(p); ligne.setNom(p.getNom()); appli = financiers.vue(id).solde().total().negate();
            }
        }
        ligne.setSoldeAppli(Montants.valider(appli, "Le solde de l'application"));
        ligne.setSoldeConfirme(Montants.valider(confirme, "Le solde confirmé"));
        ligne.setEcart(Montants.valider(confirme.subtract(appli), "L'écart"));
        ligne.setRaison(texte(s.raison(), "La raison"));
        return ligne;
    }

    private static String texte(String saisi, String quoi) {
        if (saisi == null || saisi.isBlank()) return null;
        String t = saisi.strip();
        if (t.length() > TEXTE_MAXIMUM || t.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r')) {
            throw new IllegalArgumentException(quoi + " comporte au plus " + TEXTE_MAXIMUM + " caractères, sans caractère de contrôle.");
        }
        return t;
    }

    // ---- Lectures -------------------------------------------------------------------------------------------

    /** Un inventaire de la liste, avec son nombre de lignes et d'écarts. */
    public record Resume(InventaireCreances inventaire, long lignes, long ecarts) { }

    @Transactional(readOnly = true)
    public List<Resume> lister() {
        DroitsEtats.exigerGerant();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        Map<Long, long[]> comptes = new LinkedHashMap<>();
        for (Object[] c : inventaires.comptesParInventaire(boutiqueId)) comptes.put(((Number) c[0]).longValue(), new long[]{((Number) c[1]).longValue(), c[2] == null ? 0 : ((Number) c[2]).longValue()});
        return inventaires.findTop200ByBoutiqueIdOrderByDateInventaireDescIdDesc(boutiqueId).stream()
                .map(i -> new Resume(i, comptes.getOrDefault(i.getId(), new long[]{0, 0})[0], comptes.getOrDefault(i.getId(), new long[]{0, 0})[1])).toList();
    }

    /** Un inventaire de la boutique avec ses lignes (404 sinon). */
    @Transactional(readOnly = true)
    public InventaireCreances trouver(Long id) {
        DroitsEtats.exigerGerant();
        InventaireCreances inventaire = inventaires.findByIdAndBoutiqueId(id, boutiques.idBoutiqueCourante())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Inventaire introuvable"));
        inventaire.getLignes().forEach(l -> { l.getNom(); });
        return inventaire;
    }

    private static ResponseStatusException partenaireInconnu() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Partenaire introuvable");
    }

    private Utilisateur utilisateurCourant() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return utilisateurs.findByEmail(Emails.normaliser(a.getName())).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Utilisateur introuvable"));
    }
}
