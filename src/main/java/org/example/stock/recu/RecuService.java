package org.example.stock.recu;

import org.example.stock.enums.FormatRecu;
import org.example.stock.export.ContexteDocumentService;
import org.example.stock.export.TelechargementExport;
import org.example.stock.export.Textes;
import org.example.stock.model.Achat;
import org.example.stock.model.Client;
import org.example.stock.model.DetailVente;
import org.example.stock.model.Fournisseur;
import org.example.stock.model.Montants;
import org.example.stock.model.MouvementCaisse;
import org.example.stock.model.Retour;
import org.example.stock.model.SourceMouvement;
import org.example.stock.model.Utilisateur;
import org.example.stock.model.Vente;
import org.example.stock.repository.AchatRepository;
import org.example.stock.repository.ClientRepository;
import org.example.stock.repository.FournisseurRepository;
import org.example.stock.repository.MouvementCaisseRepository;
import org.example.stock.repository.VenteRepository;
import org.example.stock.service.BoutiqueService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reçus PDF des ventes et des règlements, au format choisi par la boutique (A4 ou ticket 80 mm).
 *
 * <p>Lecture seule : aucun numéro n'est consommé, aucun mouvement créé. La boutique est toujours celle du compte connecté ;
 * une vente, un achat, un client, un fournisseur ou un mouvement d'une autre boutique est introuvable (404). Les droits
 * d'accès sont ceux des pages d'origine, posés par {@code SecurityConfig} sur les adresses du {@link RecuController} :
 * le reçu d'une vente et celui d'un règlement de vente ou de solde client sont ouverts au caissier, ceux d'un achat et
 * d'un solde fournisseur sont réservés au gérant.</p>
 *
 * <p>Un reçu ne contient jamais de coût ni de marge : seules les données de la facture (prix de vente, quantités, montants
 * réglés) sont lues, les colonnes de coût ne sont même pas consultées.</p>
 */
@Service
@Transactional(readOnly = true)
public class RecuService {
    /** Un reçu produit : le PDF et un nom de fichier sûr. */
    public record Recu(byte[] pdf, String nomFichier) { }

    private final BoutiqueService boutiques;
    private final VenteRepository ventes;
    private final AchatRepository achats;
    private final ClientRepository clients;
    private final FournisseurRepository fournisseurs;
    private final MouvementCaisseRepository mouvements;
    private final ContexteDocumentService contextes;
    private final RecuPdf pdf;

    public RecuService(BoutiqueService boutiques, VenteRepository ventes, AchatRepository achats, ClientRepository clients,
                       FournisseurRepository fournisseurs, MouvementCaisseRepository mouvements, ContexteDocumentService contextes, RecuPdf pdf) {
        this.boutiques = boutiques; this.ventes = ventes; this.achats = achats; this.clients = clients;
        this.fournisseurs = fournisseurs; this.mouvements = mouvements; this.contextes = contextes; this.pdf = pdf;
    }

    /** Le bouton « Imprimer le reçu » n'est proposé après une opération que si la boutique ne l'a pas désactivé. */
    public String proposer(String url) {
        return boutiques.boutiqueCourante().isProposerImpressionRecu() ? url : null;
    }

    public FormatRecu formatCourant() { return boutiques.boutiqueCourante().getFormatRecu(); }

    // ---- Vente ----------------------------------------------------------------------------------------------

    public Recu recuVente(Long venteId) {
        Vente vente = vente(venteId);
        RecuVente vue = vueVente(vente);
        return new Recu(rendre("pdf/recu-vente", Map.of("titre", "Reçu de vente", "recu", vue)), TelechargementExport.nomSur("recu-vente_" + vue.numero()) + ".pdf");
    }

    RecuVente vueVente(Vente vente) {
        List<RecuVente.Ligne> lignes = new ArrayList<>();
        for (DetailVente l : vente.getLignes()) {
            String unite = l.getProduit() != null && l.getProduit().getUnite() != null && !l.getProduit().getUnite().isBlank() ? " " + l.getProduit().getUnite().strip() : "";
            BigDecimal prix = Montants.ouZero(l.getPrixUnitaire());
            int quantite = l.getQuantite() == null ? 0 : l.getQuantite();
            lignes.add(new RecuVente.Ligne(l.getProduit() == null ? "Article supprimé" : douces(l.getProduit().getNom()), quantite + unite,
                    Textes.montant(prix), Textes.montant(prix.multiply(BigDecimal.valueOf(quantite)))));
        }
        BigDecimal retours = Montants.ouZero(vente.getMontantRetours()), avance = Montants.ouZero(vente.getAvanceUtilisee()), surplus = vente.getSurplus();
        String numeros = vente.getRetours().isEmpty() ? null : String.join(", ", vente.getRetours().stream().map(Retour::getNumero).toList());
        // Compte du paiement saisi à la création (les règlements ultérieurs ont leur propre reçu).
        Set<String> comptes = new LinkedHashSet<>();
        for (MouvementCaisse m : mouvements.findByVenteIdAndBoutiqueIdOrderByDateMouvementDescIdDesc(vente.getId(), boutiques.idBoutiqueCourante()))
            if (SourceMouvement.VENTE.equals(m.getSource()) && m.getCompte() != null) comptes.add(m.getCompte().getLibelle());
        String compte = comptes.isEmpty() ? null : String.join(", ", comptes);
        return new RecuVente(numeroVente(vente), Textes.dateHeure(vente.getDateVente()), nomUtilisateur(vente.getVendeur(), "Non renseigné"),
                vente.getClient() == null ? "Client de passage" : douces(vente.getClient().getNom()), lignes, Textes.montant(Montants.ouZero(vente.getMontantTotal())),
                retours.signum() > 0 ? Textes.montant(retours) : null, numeros, retours.signum() > 0 ? Textes.montant(vente.getTotalEffectif()) : null,
                Textes.montant(Montants.ouZero(vente.getMontantVerse())), avance.signum() > 0 ? Textes.montant(avance) : null,
                surplus.signum() > 0 ? Textes.montant(surplus) : null, Textes.montant(vente.getResteAPayer()), vente.getResteAPayer().signum() == 0,
                compte, "Merci de votre visite.");
    }

    // ---- Règlements -----------------------------------------------------------------------------------------

    /** Reçu d'un règlement ultérieur d'une vente (source {@code REGLEMENT_VENTE}) rattaché à la vente de l'adresse. */
    public Recu recuReglementVente(Long venteId, Long mouvementId) {
        Vente vente = vente(venteId);
        MouvementCaisse m = reglement(mouvementId, SourceMouvement.REGLEMENT_VENTE, vente.getId(), m2 -> m2.getVente());
        // Versé juste après ce règlement = versé actuel − effet des mouvements enregistrés depuis (règlements en plus, annulations en moins).
        BigDecimal apres = BigDecimal.ZERO;
        for (MouvementCaisse autre : mouvements.findByVenteIdAndBoutiqueIdOrderByDateMouvementDescIdDesc(vente.getId(), boutiques.idBoutiqueCourante()))
            if (autre.getId() > m.getId() && estEffetSurVerse(autre, SourceMouvement.REGLEMENT_VENTE)) apres = apres.add(autre.getMontant());
        BigDecimal verse = Montants.ouZero(vente.getMontantVerse()).subtract(apres);
        BigDecimal total = Montants.ouZero(vente.getMontantTotal()).subtract(retoursJusqua(vente.getRetours(), m.getDateMouvement()));
        BigDecimal reste = total.subtract(verse).subtract(Montants.ouZero(vente.getAvanceUtilisee())).max(BigDecimal.ZERO);
        RecuReglement vue = vueReglement(m, "Règlement d'une vente", "Vente " + numeroVente(vente) + " du " + jour(vente.getDateVente()),
                vente.getClient() == null ? "Client de passage" : douces(vente.getClient().getNom()), true, m.getMontant(), reste, "Merci pour votre règlement.");
        return recu(vue, "recu-reglement-vente");
    }

    /** Reçu d'un paiement d'achat (source {@code REGLEMENT_ACHAT}) : la boutique paie son fournisseur. */
    public Recu recuReglementAchat(Long achatId, Long mouvementId) {
        Achat achat = achats.findByIdAndBoutiqueId(achatId, boutiques.idBoutiqueCourante()).orElseThrow(RecuService::introuvable);
        MouvementCaisse m = reglement(mouvementId, SourceMouvement.REGLEMENT_ACHAT, achat.getId(), m2 -> m2.getAchat());
        // Les paiements sont des sorties (montants négatifs) : le versé de l'achat varie à l'opposé du montant du mouvement.
        BigDecimal apres = BigDecimal.ZERO;
        for (MouvementCaisse autre : mouvements.findByAchatIdAndBoutiqueIdOrderByDateMouvementDescIdDesc(achat.getId(), boutiques.idBoutiqueCourante()))
            if (autre.getId() > m.getId() && estEffetSurVerse(autre, SourceMouvement.REGLEMENT_ACHAT)) apres = apres.subtract(autre.getMontant());
        BigDecimal verse = Montants.ouZero(achat.getMontantVerse()).subtract(apres);
        BigDecimal total = Montants.ouZero(achat.getMontantTotal()).subtract(retoursJusqua(achat.getRetours(), m.getDateMouvement()));
        BigDecimal reste = total.subtract(verse).subtract(Montants.ouZero(achat.getAvanceUtilisee())).max(BigDecimal.ZERO);
        RecuReglement vue = vueReglement(m, "Paiement d'un achat", "Achat " + (achat.getNumero() != null ? achat.getNumero() : "#" + achat.getId())
                        + (achat.getDateAchat() == null ? "" : " du " + jour(achat.getDateAchat())),
                achat.getFournisseur() == null ? null : achat.getFournisseur().getNom(), false, m.getMontant().negate(), reste, "Paiement enregistré par la boutique.");
        return recu(vue, "recu-paiement-achat");
    }

    /** Reçu d'un règlement du solde de départ d'un client (source {@code SOLDE_CLIENT}). */
    public Recu recuReglementSoldeClient(Long clientId, Long mouvementId) {
        Client client = clients.findByIdAndBoutiqueId(clientId, boutiques.idBoutiqueCourante()).orElseThrow(RecuService::introuvable);
        MouvementCaisse m = reglement(mouvementId, SourceMouvement.SOLDE_CLIENT, client.getId(), m2 -> m2.getClient());
        BigDecimal regle = BigDecimal.ZERO;
        for (MouvementCaisse autre : mouvements.findByClientIdAndSourceInAndBoutiqueIdOrderByDateMouvementDescIdDesc(client.getId(),
                List.of(SourceMouvement.SOLDE_CLIENT, SourceMouvement.ANNULATION), boutiques.idBoutiqueCourante()))
            if (autre.getId() <= m.getId() && estEffetSurVerse(autre, SourceMouvement.SOLDE_CLIENT)) regle = regle.add(autre.getMontant());
        BigDecimal reste = Montants.ouZero(client.getSoldeInitial()).max(BigDecimal.ZERO).subtract(regle).max(BigDecimal.ZERO);
        RecuReglement vue = vueReglement(m, "Règlement du solde de départ d'un client", "Solde de départ du client", client.getNom(), true,
                m.getMontant(), reste, "Merci pour votre règlement.");
        return recu(vue, "recu-solde-client");
    }

    /** Reçu d'un règlement du solde de départ d'un fournisseur (source {@code SOLDE_FOURNISSEUR}). */
    public Recu recuReglementSoldeFournisseur(Long fournisseurId, Long mouvementId) {
        Fournisseur fournisseur = fournisseurs.findByIdAndBoutiqueId(fournisseurId, boutiques.idBoutiqueCourante()).orElseThrow(RecuService::introuvable);
        MouvementCaisse m = reglement(mouvementId, SourceMouvement.SOLDE_FOURNISSEUR, fournisseur.getId(), m2 -> m2.getFournisseur());
        BigDecimal regle = BigDecimal.ZERO;
        for (MouvementCaisse autre : mouvements.findByFournisseurIdAndSourceInAndBoutiqueIdOrderByDateMouvementDescIdDesc(fournisseur.getId(),
                List.of(SourceMouvement.SOLDE_FOURNISSEUR, SourceMouvement.ANNULATION), boutiques.idBoutiqueCourante()))
            if (autre.getId() <= m.getId() && estEffetSurVerse(autre, SourceMouvement.SOLDE_FOURNISSEUR)) regle = regle.subtract(autre.getMontant());
        BigDecimal reste = Montants.ouZero(fournisseur.getSoldeInitial()).negate().max(BigDecimal.ZERO).subtract(regle).max(BigDecimal.ZERO);
        RecuReglement vue = vueReglement(m, "Règlement du solde de départ d'un fournisseur", "Solde de départ du fournisseur", fournisseur.getNom(), false,
                m.getMontant().negate(), reste, "Paiement enregistré par la boutique.");
        return recu(vue, "recu-solde-fournisseur");
    }

    // ---- Commun ---------------------------------------------------------------------------------------------

    private RecuReglement vueReglement(MouvementCaisse m, String nature, String document, String partenaire, boolean encaissement,
                                       BigDecimal montant, BigDecimal reste, String mention) {
        MouvementCaisse annulation = mouvements.findAnnulationsDe(boutiques.idBoutiqueCourante(), List.of(m.getId())).stream().findFirst().orElse(null);
        String annuleLe = annulation == null ? null : "ANNULÉ le " + (annulation.getDateMouvement() == null ? "date non renseignée" : Textes.dateHeure(annulation.getDateMouvement()));
        return new RecuReglement(nature, "Règlement n° " + m.getId(), Textes.dateHeure(m.getDateMouvement()), document, douces(partenaire), encaissement,
                Textes.montant(montant), m.getCompte() == null ? "Non renseigné" : m.getCompte().getLibelle(), Textes.montant(reste), reste.signum() == 0,
                nomUtilisateur(m.getUtilisateur(), "Non renseigné"), annuleLe, annulation == null ? null : douces(annulation.getMotif()),
                annulation == null ? null : nomUtilisateur(annulation.getUtilisateur(), null), mention);
    }

    private Recu recu(RecuReglement vue, String base) {
        String numero = vue.reference().replaceAll("\\D", "");
        return new Recu(rendre("pdf/recu-reglement", Map.of("titre", "Reçu de règlement", "recu", vue)), TelechargementExport.nomSur(base + "_" + numero) + ".pdf");
    }

    private byte[] rendre(String gabarit, Map<String, Object> variables) {
        return pdf.rendre(gabarit, new HashMap<>(variables), contextes.courant(), formatCourant());
    }

    private Vente vente(Long id) {
        return ventes.findByIdAndBoutiqueId(id, boutiques.idBoutiqueCourante()).orElseThrow(RecuService::introuvable);
    }

    /** Le mouvement doit exister dans la boutique, être un règlement de la source attendue et appartenir au document de l'adresse. */
    private MouvementCaisse reglement(Long mouvementId, String source, Long documentId, java.util.function.Function<MouvementCaisse, Object> lien) {
        if (mouvementId == null) throw introuvable();
        MouvementCaisse m = mouvements.findByIdAndBoutiqueId(mouvementId, boutiques.idBoutiqueCourante()).orElseThrow(RecuService::introuvable);
        Object cible = lien.apply(m);
        Long lie = cible instanceof Vente v ? v.getId() : cible instanceof Achat a ? a.getId() : cible instanceof Client c ? c.getId() : cible instanceof Fournisseur f ? f.getId() : null;
        if (!source.equals(m.getSource()) || !Objects.equals(lie, documentId) || m.getMontant() == null) throw introuvable();
        return m;
    }

    /** Vrai pour un règlement de la source donnée ou pour l'annulation d'un tel règlement : les deux font varier le versé du document. */
    private static boolean estEffetSurVerse(MouvementCaisse m, String sourceReglement) {
        if (m.getMontant() == null) return false;
        if (sourceReglement.equals(m.getSource())) return true;
        return SourceMouvement.ANNULATION.equals(m.getSource()) && m.getAnnulationDe() != null && sourceReglement.equals(m.getAnnulationDe().getSource());
    }

    /** Valeur des marchandises retournées jusqu'à la date du règlement : les retours plus tardifs ne changent pas le reste de ce jour-là. */
    private static BigDecimal retoursJusqua(List<Retour> retours, LocalDateTime date) {
        BigDecimal total = BigDecimal.ZERO;
        for (Retour r : retours) if (r.getDateRetour() != null && date != null && !r.getDateRetour().isAfter(date)) total = total.add(Montants.ouZero(r.getMontantTotal()));
        return total;
    }

    private static String jour(LocalDateTime date) { return date == null ? "date non renseignée" : Textes.date(date.toLocalDate()); }

    private static String numeroVente(Vente v) { return v.getNumero() != null ? v.getNumero() : "#" + v.getId(); }

    private static String nomUtilisateur(Utilisateur u, String defaut) {
        return u == null || u.getNom() == null || u.getNom().isBlank() ? defaut : douces(u.getNom().strip());
    }

    /**
     * Un mot sans espace plus long qu'une ligne de ticket (référence, code, nom collé) ne passerait pas à la ligne : une
     * espace est ajoutée tous les 12 caractères des mots de plus de 16 caractères pour qu'ils passent à la ligne.
     */
    static String douces(String texte) {
        if (texte == null || texte.length() <= 16) return texte;
        StringBuilder sortie = new StringBuilder(texte.length() + 8);
        int mot = 0;
        for (int i = 0; i < texte.length(); i++) {
            char c = texte.charAt(i);
            if (Character.isWhitespace(c)) mot = 0;
            else if (++mot > 12 && Character.isLetterOrDigit(c) == Character.isLetterOrDigit(texte.charAt(i - 1)) && !Character.isLowSurrogate(c)) { sortie.append(' '); mot = 1; }
            sortie.append(c);
        }
        return sortie.toString();
    }

    private static ResponseStatusException introuvable() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Reçu introuvable");
    }
}
