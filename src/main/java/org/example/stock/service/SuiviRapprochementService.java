package org.example.stock.service;

import org.example.stock.export.DroitsExport;
import org.example.stock.model.CompteTresorerieBoutique;
import org.example.stock.repository.CompteTresorerieBoutiqueRepository;
import org.example.stock.repository.MouvementCaisseRepository;
import org.example.stock.repository.ReleveBancaireRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Suivi du dernier rapprochement validé de chaque compte Banque et Orange Money (tableau de bord du gérant). */
@Service
public class SuiviRapprochementService {
    /** Au-delà de ce nombre de jours sans rapprochement validé, le compte est signalé. */
    public static final int ALERTE_JOURS = 45;

    private final ReleveBancaireRepository releves;
    private final CompteTresorerieBoutiqueRepository comptes;
    private final MouvementCaisseRepository mouvements;
    private final BoutiqueService boutiques;
    private final EcheanceService horloge;

    public SuiviRapprochementService(ReleveBancaireRepository releves, CompteTresorerieBoutiqueRepository comptes, MouvementCaisseRepository mouvements,
                                     BoutiqueService boutiques, EcheanceService horloge) {
        this.releves = releves; this.comptes = comptes; this.mouvements = mouvements; this.boutiques = boutiques; this.horloge = horloge;
    }

    /**
     * @param dernier clôture du dernier relevé validé ; {@code null} si le compte n'a jamais été rapproché
     * @param jours   jours écoulés depuis cette clôture ; {@code null} si jamais rapproché
     * @param alerte  vrai si le dernier rapprochement date de plus de 45 jours, ou si un compte déjà utilisé n'a jamais été rapproché
     */
    public record Suivi(CompteTresorerieBoutique compte, LocalDate dernier, Long jours, boolean alerte) { }

    @Transactional(readOnly = true)
    public List<Suivi> suivi() {
        DroitsExport.exigerGerant();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        Map<Long, LocalDate> derniers = new HashMap<>();
        for (Object[] ligne : releves.dernieresValidations(boutiqueId)) derniers.put((Long) ligne[0], (LocalDate) ligne[1]);
        LocalDate aujourdHui = horloge.aujourdHui();
        return comptes.listerActifs(boutiqueId).stream().filter(ReleveBancaireService::rapprochable).map(c -> {
            LocalDate dernier = derniers.get(c.getId());
            if (dernier != null) {
                long jours = ChronoUnit.DAYS.between(dernier, aujourdHui);
                return new Suivi(c, dernier, jours, jours > ALERTE_JOURS);
            }
            boolean utilise = mouvements.existeMouvementAutreQueSoldeDepart(c.getId());
            return new Suivi(c, null, null, utilise);
        }).toList();
    }

    public static long nombreEnAlerte(List<Suivi> suivi) { return suivi.stream().filter(Suivi::alerte).count(); }
}
