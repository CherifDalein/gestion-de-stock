package org.example.stock.service;

import org.example.stock.model.ParametresPlateforme;
import org.example.stock.repository.ParametresPlateformeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ParametresPlateformeService {
    private final PlateformeService plateforme;
    private final EssaiBoutiqueService essais;
    private final ParametresPlateformeRepository parametres;

    public ParametresPlateformeService(PlateformeService plateforme, EssaiBoutiqueService essais,
                                      ParametresPlateformeRepository parametres) {
        this.plateforme = plateforme; this.essais = essais; this.parametres = parametres;
    }

    public int consulterDureeEssai() {
        plateforme.verifierAdministrationPlateforme();
        return essais.dureeEssaiJours();
    }

    @Transactional
    public void modifierDureeEssai(Integer jours) {
        plateforme.verifierAdministrationPlateforme();
        if (jours == null || jours < 1 || jours > 3650) {
            throw new IllegalArgumentException("La durée d'essai doit être comprise entre 1 et 3650 jours.");
        }
        ParametresPlateforme reglage = parametres.findById(1).orElseGet(() -> {
            ParametresPlateforme p = new ParametresPlateforme(); p.setId(1); return p;
        });
        reglage.setDureeEssaiJours(jours);
        parametres.saveAndFlush(reglage);
    }
}
