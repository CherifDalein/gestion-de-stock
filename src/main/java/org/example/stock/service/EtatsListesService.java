package org.example.stock.service;

import org.example.stock.enums.TypeCompteTresorerie;
import org.example.stock.enums.NatureProduit;
import org.example.stock.enums.TypeOperationTresorerie;
import org.example.stock.export.Colonne;
import org.example.stock.export.FormatPapier;
import org.example.stock.export.LimiteExport;
import org.example.stock.export.TableauExport;
import org.example.stock.export.Textes;
import org.example.stock.model.*;
import org.example.stock.repository.CompteTresorerieBoutiqueRepository;
import org.example.stock.repository.EtatsRepository;
import org.example.stock.repository.MouvementCaisseRepository;
import org.example.stock.repository.TableauDeBordRepository;
import org.example.stock.service.TableauDeBordService.LigneVendeur;
import org.example.stock.service.TableauDeBordService.ValeurStock;
import org.example.stock.service.TableauDeBordService.VentesParVendeur;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * États en listes du gérant : état du stock, liste des dépenses, liste des encaissements, ventes par vendeur. Chaque total
 * est rapproché du calcul du tableau de bord (valeur du stock, dépenses) ; en cas d'écart, l'état est refusé.
 */
@Service
@Transactional(readOnly = true)
public class EtatsListesService {
    private static final int PAQUET = 1000;
    private static final List<String> SOURCES_HORS_ENCAISSEMENTS = List.of(BilanService.SOLDE_INITIAL, BilanService.TRANSFERT, BilanService.ANNULATION);

    private final EtatsRepository etats;
    private final TableauDeBordService tableau;
    private final TableauDeBordRepository requetes;
    private final MouvementCaisseRepository mouvements;
    private final BoutiqueService boutiques;
    private final EcheanceService echeances;
    private final LimiteExport limite;
    private final CompteTresorerieBoutiqueRepository comptesRepo;

    public EtatsListesService(EtatsRepository etats, TableauDeBordService tableau, TableauDeBordRepository requetes,
                              MouvementCaisseRepository mouvements, BoutiqueService boutiques, EcheanceService echeances, LimiteExport limite,
                              CompteTresorerieBoutiqueRepository comptesRepo) {
        this.etats = etats; this.tableau = tableau; this.requetes = requetes; this.mouvements = mouvements;
        this.boutiques = boutiques; this.echeances = echeances; this.limite = limite; this.comptesRepo = comptesRepo;
    }

    /**
     * Comptes à lister dans un tableau « par compte » : les comptes actifs, plus les comptes archivés qui ont une valeur ;
     * dans l'ordre type, ordre d'affichage. Un type qui compte plusieurs comptes est suivi d'un sous-total.
     */
    private List<CompteTresorerieBoutique> comptesAListerPar(Long boutiqueId, java.util.Set<Long> avecValeur) {
        return comptesRepo.lister(boutiqueId).stream().filter(c -> c.isActif() || avecValeur.contains(c.getId())).toList();
    }

    // ==== État du stock ========================================================================================

    public Etat stock() {
        DroitsEtats.exigerGerant();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        LocalDate jour = echeances.aujourdHui();
        List<Produit> produits = etats.produitsEnStock(boutiqueId, NatureProduit.STOCK, PageRequest.of(0, limite.lignes() + 1));
        limite.verifier(produits.size());

        Map<String, List<Produit>> parCategorie = new TreeMap<>(Comparator.comparing((String s) -> s.equals(SANS_CATEGORIE) ? 1 : 0)
                .thenComparing(s -> s.toLowerCase(Locale.ROOT)).thenComparing(Comparator.naturalOrder()));
        for (Produit p : produits) {
            String categorie = p.getCategorie() == null || p.getCategorie().getNom() == null || p.getCategorie().getNom().isBlank()
                    ? SANS_CATEGORIE : p.getCategorie().getNom().strip();
            parCategorie.computeIfAbsent(categorie, c -> new ArrayList<>()).add(p);
        }
        TableauExport t = TableauExport.titre("État du stock").filtres("Au jour de l'édition : " + Textes.date(jour))
                .colonnes(Colonne.texte("Catégorie"), Colonne.texte("Produit"), Colonne.texteCourt("Référence"), Colonne.entier("Quantité"),
                        Colonne.texteCourt("Unité"), Colonne.montant("Coût moyen"), Colonne.montant("Valeur"), Colonne.entier("Seuil"),
                        Colonne.texteCourt("État"));
        BigDecimal total = Montants.ZERO;
        for (var entree : parCategorie.entrySet()) {
            List<Produit> liste = entree.getValue();
            liste.sort(Comparator.comparing((Produit p) -> p.getNom() == null ? "" : p.getNom().toLowerCase(Locale.ROOT)).thenComparing(Produit::getId));
            BigDecimal sousTotal = Montants.ZERO;
            for (Produit p : liste) {
                BigDecimal valeur = valeur(p);
                sousTotal = sousTotal.add(valeur);
                t.ligne(entree.getKey(), p.getNom(), p.getReference(), p.getQuantite() == null ? 0L : p.getQuantite(), p.getUnite() == null ? "" : p.getUnite(),
                        Montants.ouZero(p.getCoutMoyen()), valeur, p.getSeuilAlerte() == null ? Produit.SEUIL_ALERTE_PAR_DEFAUT : p.getSeuilAlerte(),
                        etat(p));
            }
            t.sousTotal("Total " + entree.getKey() + " (" + liste.size() + (liste.size() > 1 ? " articles)" : " article)"), null, null, null, null, null,
                    sousTotal, null, null);
            total = total.add(sousTotal);
        }
        // Rapprochement avec la valeur du tableau de bord (Σ quantité × coût moyen des articles en stock).
        ValeurStock valeurTableau = tableau.valeurStock();
        if (valeurTableau.valeur().compareTo(total) != 0 || valeurTableau.nombreProduits() != produits.size()) {
            throw new IncoherenceAvanceException("La valeur du stock de l'état ne concorde pas avec celle du tableau de bord : relancez l'état.");
        }
        t.totaux("Total général (" + produits.size() + (produits.size() > 1 ? " articles)" : " article)"), null, null, null, null, null, total, null, null);
        t.note("Quantités et valeurs au jour de l'édition (" + Textes.date(jour) + ") : valeur = quantité × coût moyen pondéré actuel. Les services sont exclus. "
                + "La valeur à une date passée n'est pas calculable de façon fiable (le coût moyen n'est pas conservé jour par jour).");
        t.note("État : « Stock faible » quand la quantité est au plus égale au seuil d'alerte du produit (5 par défaut), « Rupture » à 0 ou moins.");
        return new Etat("stock", "État du stock", "Au jour de l'édition : " + Textes.date(jour), List.of(), List.of(t), FormatPapier.A4_PAYSAGE,
                "stock", null, jour);
    }

    private static final String SANS_CATEGORIE = "Sans catégorie";

    private static BigDecimal valeur(Produit p) {
        return BigDecimal.valueOf(p.getQuantite() == null ? 0L : p.getQuantite()).multiply(Montants.ouZero(p.getCoutMoyen())).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static String etat(Produit p) {
        return switch (p.etatStock()) {
            case "rupture" -> "Rupture";
            case "faible" -> "Stock faible";
            case "disponible" -> "Disponible";
            default -> "Inconnu";
        };
    }

    // ==== Liste des dépenses ===================================================================================

    public Etat depenses(PeriodeEtat periode) {
        DroitsEtats.exigerGerant();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        List<OperationTresorerie> operations = etats.operations(boutiqueId, TypeOperationTresorerie.DEPENSE, periode.debut(), periode.fin(),
                PageRequest.of(0, limite.lignes() + 1));
        limite.verifier(operations.size());
        Map<Long, MouvementCaisse> annulations = new HashMap<>();
        for (MouvementCaisse a : etats.annulationsDOperations(boutiqueId, TypeOperationTresorerie.DEPENSE, periode.debut(), periode.fin())) {
            annulations.put(a.getAnnulationDe().getOperationTresorerie().getId(), a);
        }

        TableauExport t = TableauExport.titre("Liste des dépenses").nomFeuille("Dépenses").filtres(periode.texte())
                .colonnes(Colonne.dateHeure("Date"), Colonne.texteCourt("Compte"), Colonne.texte("Catégorie / motif"), Colonne.texte("Saisi par"),
                        Colonne.montant("Montant"), Colonne.texte("Statut"));
        BigDecimal total = Montants.ZERO;
        Map<Long, BigDecimal> parCompte = new HashMap<>();
        Map<Long, Integer> nombres = new HashMap<>();
        for (OperationTresorerie o : operations) {
            MouvementCaisse annulation = annulations.get(o.getId());
            t.ligne(o.getDateOperation(), o.getCompteSource().getNom(), o.getMotif(), nomUtilisateur(o.getUtilisateur()), o.getMontant(),
                    annulation == null ? "" : "Annulée le " + Textes.date(annulation.getDateMouvement().toLocalDate())
                            + (annulation.getMotif() == null || annulation.getMotif().isBlank() ? "" : " (" + annulation.getMotif().strip() + ")"));
            total = total.add(o.getMontant());
            parCompte.merge(o.getCompteSource().getId(), o.getMontant(), BigDecimal::add);
            nombres.merge(o.getCompteSource().getId(), 1, Integer::sum);
        }
        // Rapprochement avec les dépenses du tableau de bord.
        BigDecimal tableauDeBord = Montants.ouZero(requetes.depenses(boutiqueId, TypeOperationTresorerie.DEPENSE, periode.debut(), periode.fin()));
        if (tableauDeBord.compareTo(total) != 0) {
            throw new IncoherenceAvanceException("Le total des dépenses de l'état ne concorde pas avec celui du tableau de bord : relancez l'état.");
        }
        t.totaux("Total des dépenses (" + operations.size() + (operations.size() > 1 ? " dépenses)" : " dépense)"), null, null, null, total, null);
        t.note("L'application n'a pas de catégorie de dépense : le motif saisi en tient lieu. Les achats de marchandises et les transferts entre comptes ne sont pas des dépenses.");
        t.note("Une dépense ne s'annule pas dans l'application. Si une dépense porte une annulation par mouvement inverse, elle est signalée dans la colonne Statut "
                + "et reste comptée dans le total, comme au tableau de bord.");

        TableauExport comptes = TableauExport.titre("Total par compte").nomFeuille("Par compte").filtres(periode.texte())
                .colonnes(Colonne.texte("Compte"), Colonne.entier("Dépenses"), Colonne.montant("Montant"));
        List<CompteTresorerieBoutique> aLister = comptesAListerPar(boutiqueId, nombres.keySet());
        for (TypeCompteTresorerie type : TypeCompteTresorerie.values()) {
            List<CompteTresorerieBoutique> deCeType = aLister.stream().filter(c -> c.getType() == type).toList();
            int nombreType = 0; BigDecimal montantType = Montants.ZERO;
            for (CompteTresorerieBoutique compte : deCeType) {
                int n = nombres.getOrDefault(compte.getId(), 0); BigDecimal m = parCompte.getOrDefault(compte.getId(), Montants.ZERO);
                comptes.ligne(compte.getNom() + (compte.isActif() ? "" : " (archivé)"), n, m);
                nombreType += n; montantType = montantType.add(m);
            }
            if (deCeType.size() > 1) comptes.sousTotal("Total " + type.getLibelle(), nombreType, montantType);
        }
        comptes.totaux("Total", operations.size(), total);
        return new Etat("depenses", "Liste des dépenses", periode.texte(), List.of(), List.of(t, comptes), FormatPapier.A4_PAYSAGE,
                "depenses", periode.du(), periode.au());
    }

    // ==== Liste des encaissements ===============================================================================

    public Etat encaissements(PeriodeEtat periode) {
        DroitsEtats.exigerGerant();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        var max = PageRequest.of(0, limite.lignes() + 1);
        List<MouvementCaisse> entrees = etats.encaissements(boutiqueId, periode.debut(), periode.fin(), SOURCES_HORS_ENCAISSEMENTS, max);
        limite.verifier(entrees.size());
        List<MouvementCaisse> annulationsDEncaissements = etats.annulationsDEncaissements(boutiqueId, periode.debut(), periode.fin(), max);
        limite.verifier(annulationsDEncaissements.size());

        // Annulation éventuelle de chaque encaissement listé (même si elle date d'une autre période).
        Map<Long, MouvementCaisse> annulees = new HashMap<>();
        List<Long> ids = entrees.stream().map(MouvementCaisse::getId).toList();
        for (int i = 0; i < ids.size(); i += PAQUET) {
            for (MouvementCaisse a : mouvements.findAnnulationsDe(boutiqueId, ids.subList(i, Math.min(ids.size(), i + PAQUET)))) {
                annulees.put(a.getAnnulationDe().getId(), a);
            }
        }

        TableauExport t = TableauExport.titre("Liste des encaissements").nomFeuille("Encaissements").filtres(periode.texte())
                .colonnes(Colonne.dateHeure("Date"), Colonne.texteCourt("Compte"), Colonne.texte("Source"), Colonne.texteCourt("Document"),
                        Colonne.texte("Client / fournisseur"), Colonne.montant("Montant"), Colonne.texte("Saisi par"), Colonne.texte("Statut"));
        Map<Long, BigDecimal> parCompte = new HashMap<>();
        BigDecimal total = Montants.ZERO;
        for (MouvementCaisse m : entrees) {
            MouvementCaisse annulation = annulees.get(m.getId());
            t.ligne(m.getDateMouvement(), m.getCompte().getNom(), source(m), document(m), tiers(m), m.getMontant(), nomUtilisateur(m.getUtilisateur()),
                    annulation == null ? "" : "Annulé le " + Textes.date(annulation.getDateMouvement().toLocalDate())
                            + (annulation.getMotif() == null || annulation.getMotif().isBlank() ? "" : " (" + annulation.getMotif().strip() + ")"));
            total = total.add(m.getMontant());
            parCompte.merge(m.getCompte().getId(), m.getMontant(), BigDecimal::add);
        }
        t.totaux("Total des encaissements (" + entrees.size() + (entrees.size() > 1 ? " lignes)" : " ligne)"), null, null, null, null, total, null, null);
        t.note("Un encaissement annulé reste listé et comptabilisé : son annulation figure dans le tableau « Annulations d'encaissements » (en négatif) "
                + "et dans le total net.");

        TableauExport annulations = TableauExport.titre("Annulations d'encaissements").nomFeuille("Annulations").filtres(periode.texte())
                .colonnes(Colonne.dateHeure("Date"), Colonne.texteCourt("Compte"), Colonne.texte("Source"), Colonne.texteCourt("Document"),
                        Colonne.texte("Client / fournisseur"), Colonne.montant("Montant"), Colonne.texte("Saisi par"), Colonne.texte("Motif"));
        Map<Long, BigDecimal> annuleParCompte = new HashMap<>();
        BigDecimal totalAnnulations = Montants.ZERO;
        for (MouvementCaisse a : annulationsDEncaissements) {
            MouvementCaisse origine = a.getAnnulationDe();
            annulations.ligne(a.getDateMouvement(), a.getCompte().getNom(), "Annulation : " + source(origine) + " du " + Textes.date(origine.getDateMouvement().toLocalDate()),
                    document(origine), tiers(origine), a.getMontant(), nomUtilisateur(a.getUtilisateur()), a.getMotif() == null ? "" : a.getMotif());
            totalAnnulations = totalAnnulations.add(a.getMontant());
            annuleParCompte.merge(a.getCompte().getId(), a.getMontant(), BigDecimal::add);
        }
        annulations.totaux("Total des annulations (" + annulationsDEncaissements.size() + ")", null, null, null, null, totalAnnulations, null, null);
        List<Object[]> autres = etats.annulationsDePaiements(boutiqueId, periode.debut(), periode.fin());
        long autresNombre = autres.isEmpty() ? 0 : ((Number) autres.getFirst()[0]).longValue();
        if (autresNombre > 0) {
            annulations.note(autresNombre + (autresNombre > 1 ? " annulations de décaissements" : " annulation de décaissement")
                    + " (règlement d'achat ou de solde fournisseur, dépense…) pour " + Textes.montant(TableauDeBordService.montant(autres.getFirst()[1]))
                    + " GNF " + (autresNombre > 1 ? "n'apparaissent" : "n'apparaît") + " pas ici : ce ne sont pas des encaissements. "
                    + "Elles figurent dans les annulations du bilan.");
        }

        TableauExport comptes = TableauExport.titre("Totaux par compte").nomFeuille("Par compte").filtres(periode.texte())
                .colonnes(Colonne.texte("Compte"), Colonne.montant("Encaissements"), Colonne.montant("Annulations"), Colonne.montant("Net"));
        java.util.Set<Long> avecValeur = new java.util.HashSet<>(parCompte.keySet()); avecValeur.addAll(annuleParCompte.keySet());
        List<CompteTresorerieBoutique> aLister = comptesAListerPar(boutiqueId, avecValeur);
        for (TypeCompteTresorerie type : TypeCompteTresorerie.values()) {
            List<CompteTresorerieBoutique> deCeType = aLister.stream().filter(c -> c.getType() == type).toList();
            BigDecimal eType = Montants.ZERO, aType = Montants.ZERO;
            for (CompteTresorerieBoutique compte : deCeType) {
                BigDecimal e = parCompte.getOrDefault(compte.getId(), Montants.ZERO), a = annuleParCompte.getOrDefault(compte.getId(), Montants.ZERO);
                comptes.ligne(compte.getNom() + (compte.isActif() ? "" : " (archivé)"), e, a, e.add(a));
                eType = eType.add(e); aType = aType.add(a);
            }
            if (deCeType.size() > 1) comptes.sousTotal("Total " + type.getLibelle(), eType, aType, eType.add(aType));
        }
        comptes.totaux("Total général", total, totalAnnulations, total.add(totalAnnulations));
        return new Etat("encaissements", "Liste des encaissements", periode.texte(), List.of(), List.of(t, annulations, comptes),
                FormatPapier.A4_PAYSAGE, "encaissements", periode.du(), periode.au());
    }

    private static String source(MouvementCaisse m) {
        String source = m.getSource() == null ? "" : m.getSource();
        return switch (source) {
            case SourceMouvement.VENTE -> "Paiement à la vente";
            case SourceMouvement.REGLEMENT_VENTE -> "Règlement de vente";
            case SourceMouvement.SOLDE_CLIENT -> "Règlement du solde de départ d'un client";
            case "RETOUR_ACHAT" -> "Remboursement d'un fournisseur";
            case SourceMouvement.ACHAT -> "Paiement d'achat (correction)";
            case SourceMouvement.REGLEMENT_ACHAT -> "Règlement d'achat (correction)";
            case "" -> "Autre entrée";
            default -> SourceMouvement.libelle(source);
        };
    }

    private static String document(MouvementCaisse m) {
        if (m.getVente() != null) return m.getVente().getNumero() != null ? m.getVente().getNumero() : "Vente n° " + m.getVente().getId();
        if (m.getRetour() != null) return m.getRetour().getNumero();
        if (m.getClient() != null || m.getFournisseur() != null) return "Solde de départ";
        if (m.getOperationTresorerie() != null && m.getOperationTresorerie().getEmprunt() != null) return m.getOperationTresorerie().getEmprunt().getNumero();
        return "";
    }

    private static String tiers(MouvementCaisse m) {
        if (m.getVente() != null) return m.getVente().getClient() == null ? "Vente sans client" : m.getVente().getClient().getNom();
        if (m.getClient() != null) return m.getClient().getNom();
        if (m.getRetour() != null && m.getRetour().getFournisseur() != null) return m.getRetour().getFournisseur().getNom();
        if (m.getFournisseur() != null) return m.getFournisseur().getNom();
        if (m.getOperationTresorerie() != null && m.getOperationTresorerie().getPartenaireFinancier() != null) return m.getOperationTresorerie().getPartenaireFinancier().getNom();
        return "";
    }

    private static String nomUtilisateur(Utilisateur u) {
        if (u == null) return "";
        return u.getNom() != null && !u.getNom().isBlank() ? u.getNom().strip() : (u.getEmail() == null ? "" : u.getEmail());
    }

    // ==== Ventes par vendeur ====================================================================================

    /** L'état existant « Ventes par vendeur », mis en tableau pour le PDF et l'Excel : mêmes lignes que l'écran. */
    public Etat ventesParVendeur(PeriodeEtat periode) {
        DroitsEtats.exigerGerant();
        VentesParVendeur etat = tableau.ventesParVendeur(periode.du(), periode.au());
        TableauExport t = TableauExport.titre("Ventes par vendeur").nomFeuille("Ventes par vendeur").filtres(periode.texte())
                .colonnes(Colonne.texte("Vendeur"), Colonne.entier("Ventes"), Colonne.montant("Chiffre d'affaires"), Colonne.montant("Bénéfice brut"),
                        Colonne.montant("Panier moyen"));
        for (LigneVendeur l : etat.lignes()) {
            t.ligne(l.nom() + (l.desactive() ? " (compte désactivé)" : ""), l.nombreVentes(), l.chiffreAffaires(), l.beneficeBrut(), l.panierMoyen());
        }
        LigneVendeur total = etat.total();
        t.totaux("Total", total.nombreVentes(), total.chiffreAffaires(), total.beneficeBrut(), total.panierMoyen());
        t.note("Le chiffre d'affaires et le bénéfice brut sont nets des retours de vente, comptés à la date du retour pour le vendeur de la vente d'origine. "
                + "Le bénéfice brut est calculé au coût figé à la vente, comme dans le tableau de bord. Le panier moyen est le chiffre d'affaires divisé par le nombre de ventes.");
        if (etat.lignesSansCout() > 0) {
            t.note(etat.lignesSansCout() + (etat.lignesSansCout() == 1 ? " ligne de vente sans coût connu, comptée à 0" : " lignes de vente sans coût connu, comptées à 0")
                    + " : le bénéfice brut est surestimé d'autant.");
        }
        return new Etat("ventes-par-vendeur", "Ventes par vendeur", periode.texte(), List.of(), List.of(t), FormatPapier.A4_PORTRAIT,
                "ventes-par-vendeur", periode.du(), periode.au());
    }
}
