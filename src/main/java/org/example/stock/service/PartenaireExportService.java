package org.example.stock.service;

import org.example.stock.export.*;
import org.example.stock.model.Client;
import org.example.stock.model.Fournisseur;
import org.example.stock.model.Montants;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Export des répertoires de clients et de fournisseurs (Excel et PDF) : mêmes fiches que {@code /clients} et {@code /fournisseurs},
 * avec ce que la liste affiche (coordonnées, solde, avance, reste du solde de départ), filtre « Coordonnées » et recherche.
 * Les clients sont ouverts au gérant et au caissier comme leur liste ; les fournisseurs sont réservés au gérant.
 */
@Service
@Transactional(readOnly = true)
public class PartenaireExportService {
    /** Valeurs du filtre « Coordonnées » de l'écran. */
    public static final Set<String> CONTACTS = Set.of("telephone", "email", "sans-contact");

    private final ClientService clients;
    private final FournisseurService fournisseurs;
    private final SoldePartenaireService soldes;
    private final LimiteExport limite;

    public PartenaireExportService(ClientService clients, FournisseurService fournisseurs, SoldePartenaireService soldes, LimiteExport limite) {
        this.clients = clients; this.fournisseurs = fournisseurs; this.soldes = soldes; this.limite = limite;
    }

    private record Fiche(String nom, String telephone, String email, String adresse) { }

    /** @param contact {@code telephone}, {@code email}, {@code sans-contact} ou null pour tous */
    public TableauExport preparerClients(String contact, String recherche) {
        List<Client> lus = clients.listerPourExport(limite.lignes());
        limite.verifier(lus.size());
        return construire("Clients", "Tous les clients", lus.stream().map(c -> new Entree(c.getId(), new Fiche(c.getNom(), c.getTelephone(), c.getEmail(), c.getAdresse()))).toList(),
                soldes.soldesClients(), contact, recherche);
    }

    public TableauExport preparerFournisseurs(String contact, String recherche) {
        DroitsExport.exigerGerant();
        List<Fournisseur> lus = fournisseurs.listerPourExport(limite.lignes());
        limite.verifier(lus.size());
        return construire("Fournisseurs", "Tous les fournisseurs", lus.stream().map(f -> new Entree(f.getId(), new Fiche(f.getNom(), f.getTelephone(), f.getEmail(), f.getAdresse()))).toList(),
                soldes.soldesFournisseurs(), contact, recherche);
    }

    private record Entree(Long id, Fiche fiche) { }

    private TableauExport construire(String titre, String tous, List<Entree> lus, Map<Long, SoldePartenaire> soldesParId, String contact, String recherche) {
        String q = recherche == null ? "" : recherche.strip();
        List<Entree> retenus = lus.stream()
                .filter(e -> contact == null || switch (contact) {
                    case "telephone" -> renseigne(e.fiche().telephone());
                    case "email" -> renseigne(e.fiche().email());
                    default -> !renseigne(e.fiche().telephone()) && !renseigne(e.fiche().email());
                })
                .filter(e -> q.isEmpty() || correspond(q, e.fiche()))
                .sorted(Comparator.comparing((Entree e) -> e.fiche().nom(), RechercheListe.ordreNaturel()).thenComparing(Entree::id))
                .toList();

        TableauExport tableau = TableauExport.titre(titre).nomFeuille(titre).filtres(decrireFiltres(tous, contact, q))
                .colonnes(Colonne.texte("Nom"), Colonne.texteCourt("Téléphone"), Colonne.texte("Email"), Colonne.texte("Adresse"),
                        Colonne.montant("Me doit"), Colonne.montant("Je dois"), Colonne.montant("Avance disponible"), Colonne.montant("Dont solde de départ"));
        BigDecimal medoit = Montants.ZERO, jedois = Montants.ZERO, avances = Montants.ZERO, depart = Montants.ZERO;
        for (Entree e : retenus) {
            SoldePartenaire s = soldesParId.get(e.id());
            BigDecimal net = s == null ? Montants.ZERO : s.soldeNet(), avance = s == null ? Montants.ZERO : s.avanceDisponible(),
                    resteDepart = s == null ? Montants.ZERO : s.resteSoldeDepart();
            BigDecimal doitAuMagasin = net.signum() > 0 ? net : Montants.ZERO, doitParLeMagasin = net.signum() < 0 ? net.negate() : Montants.ZERO;
            medoit = medoit.add(doitAuMagasin); jedois = jedois.add(doitParLeMagasin); avances = avances.add(avance); depart = depart.add(resteDepart);
            Fiche f = e.fiche();
            tableau.ligne(f.nom(), vide(f.telephone()), vide(f.email()), vide(f.adresse()), positif(doitAuMagasin), positif(doitParLeMagasin),
                    positif(avance), positif(resteDepart));
        }
        return tableau.totaux(retenus.size() + (retenus.size() > 1 ? " fiches" : " fiche"), null, null, null, medoit, jedois, avances, depart)
                .note("« Me doit » : ce que la personne doit à la boutique ; « Je dois » : ce que la boutique lui doit (solde net, avance déduite).");
    }

    private static String decrireFiltres(String tous, String contact, String recherche) {
        String base = contact == null ? tous : switch (contact) {
            case "telephone" -> "Avec téléphone";
            case "email" -> "Avec email";
            default -> "Sans téléphone ni email";
        };
        return recherche.isEmpty() ? base : base + " · Recherche : « " + recherche + " »";
    }

    private static boolean renseigne(String texte) { return texte != null && !texte.isBlank(); }

    private static String vide(String texte) { return renseigne(texte) ? texte.strip() : null; }

    private static BigDecimal positif(BigDecimal montant) { return montant.signum() > 0 ? montant : null; }

    /** Comme l'écran : chaque mot figure dans nom, téléphone, email ou adresse ; un mot de chiffres retrouve aussi un téléphone écrit autrement. */
    private static boolean correspond(String recherche, Fiche f) {
        String texte = String.join(" ", nonNul(f.nom()), nonNul(f.telephone()), nonNul(f.email()), nonNul(f.adresse()));
        String chiffres = chiffres(f.telephone());
        for (String mot : RechercheListe.simplifier(recherche).split("\\s+")) {
            if (mot.isEmpty()) continue;
            boolean telephone = mot.matches("[+\\d().\\/-]+") && mot.matches(".*\\d.*") && chiffres.contains(chiffres(mot));
            if (!RechercheListe.simplifier(texte).contains(mot) && !telephone) return false;
        }
        return true;
    }

    private static String nonNul(String texte) { return texte == null ? "" : texte; }

    private static String chiffres(String texte) { return texte == null ? "" : texte.replaceAll("\\D", ""); }
}
