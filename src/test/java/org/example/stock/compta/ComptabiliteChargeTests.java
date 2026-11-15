package org.example.stock.compta;

import jakarta.persistence.EntityManager;
import org.example.stock.model.*;
import org.example.stock.enums.TypeCompteTresorerie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Charge : les livres de 10 000 opérations se calculent en moins de 2 secondes (journal, grand livre et balance compris). */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:compta-charge;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
class ComptabiliteChargeTests {
    static final int OPERATIONS = 10_000;

    @Autowired ComptabiliteService comptabilite;
    @Autowired EntityManager em;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @Test
    void dixMilleOperationsSeCalculentEnMoinsDeDeuxSecondes() {
        Long boutiqueId = new TransactionTemplate(transactions).execute(s -> inserer());
        LocalDate fin = LocalDate.of(2026, 12, 31);
        // Un premier calcul chauffe la machine virtuelle et les plans de requêtes ; le chronomètre mesure le suivant.
        comptabilite.calculer(boutiqueId, LocalDate.of(2026, 1, 1), fin);
        long debut = System.nanoTime();
        var livres = new TransactionTemplate(transactions).execute(s -> {
            var l = comptabilite.calculer(boutiqueId, LocalDate.of(2026, 1, 1), fin);
            comptabilite.journal(l, null, null, null);
            comptabilite.grandLivre(l, "", null, null);
            comptabilite.balance(l);
            return l;
        });
        long ms = (System.nanoTime() - debut) / 1_000_000;
        System.out.println("CHARGE " + OPERATIONS + " opérations : " + ms + " ms, " + livres.ecritures().size() + " écritures");
        assertThat(ms).as("durée en ms").isLessThan(2000);
        assertThat(livres.ecritures().size()).isGreaterThanOrEqualTo(OPERATIONS);
        assertThat(livres.ecritures()).allSatisfy(e -> assertThat(e.estEquilibree()).isTrue());
        BigDecimal debitTotal = livres.ecritures().stream().map(Ecriture::totalDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditTotal = livres.ecritures().stream().map(Ecriture::totalCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debitTotal).isEqualByComparingTo(creditTotal);
    }

    /** 10 000 opérations : 3 000 ventes avec leur encaissement, 1 500 achats avec leur règlement, 1 000 dépenses (ventes et achats comptent pour deux). */
    private Long inserer() {
        Boutique b = new Boutique(); b.setNom("Boutique de charge");
        em.persist(b);
        em.flush();
        CompteTresorerieBoutique compte = b.getComptesTresorerie().stream().filter(c -> c.getType() == TypeCompteTresorerie.ESPECES).findFirst().orElseThrow();
        Client client = new Client(); client.setBoutique(b); client.setNom("Client de charge"); em.persist(client);
        Fournisseur fournisseur = new Fournisseur(); fournisseur.setBoutique(b); fournisseur.setNom("Fournisseur de charge"); em.persist(fournisseur);
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 8, 0);
        int n = 0;
        for (int i = 0; i < 3000; i++) {
            Vente v = new Vente(); v.setBoutique(b); v.setNumero("V-" + i); v.setDateVente(base.plusMinutes(i)); v.setMontantTotal(new BigDecimal("10000")); v.setMontantVerse(new BigDecimal("10000"));
            v.setClient(client); em.persist(v);
            mouvement(b, compte, base.plusMinutes(i), "10000", "VENTE", "Vente " + i, v, null);
            n += 2;
            if (n % 500 == 0) { em.flush(); em.clear(); client = em.merge(client); fournisseur = em.merge(fournisseur); b = em.merge(b); compte = em.merge(compte); }
        }
        for (int i = 0; i < 1500; i++) {
            Achat a = new Achat(); a.setBoutique(b); a.setNumero("A-" + i); a.setDateAchat(base.plusMinutes(i)); a.setMontantTotal(new BigDecimal("5000")); a.setMontantVerse(new BigDecimal("3000"));
            a.setFournisseur(fournisseur); em.persist(a);
            mouvement(b, compte, base.plusMinutes(i), "-3000", "ACHAT", "Achat " + i, null, a);
            n += 2;
            if (n % 500 == 0) { em.flush(); em.clear(); client = em.merge(client); fournisseur = em.merge(fournisseur); b = em.merge(b); compte = em.merge(compte); }
        }
        for (int i = 0; i < 1000; i++) {
            mouvement(b, compte, base.plusMinutes(i), "-1000", "AUTRE_CHARGE", "Charge " + i, null, null);
            n++;
            if (n % 500 == 0) { em.flush(); em.clear(); b = em.merge(b); compte = em.merge(compte); }
        }
        em.flush();
        assertThat(n).isEqualTo(OPERATIONS);
        return b.getId();
    }

    private void mouvement(Boutique b, CompteTresorerieBoutique compte, LocalDateTime date, String montant, String source, String motif, Vente v, Achat a) {
        MouvementCaisse m = new MouvementCaisse();
        m.setBoutique(b); m.setCompte(compte); m.setDateMouvement(date); m.setMontant(new BigDecimal(montant)); m.setType(montant.startsWith("-") ? "SORTIE" : "ENTREE");
        m.setSource(source); m.setMotif(motif); m.setVente(v); m.setAchat(a);
        em.persist(m);
    }
}
