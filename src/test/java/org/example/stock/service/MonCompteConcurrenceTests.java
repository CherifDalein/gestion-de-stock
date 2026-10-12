package org.example.stock.service;

import org.example.stock.enums.Role;
import org.example.stock.form.ChangementMotDePasseForm;
import org.example.stock.model.Boutique;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.BoutiqueRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Deux transactions réelles commencent avec la même copie du compte, puis tentent le changement ensemble. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:mon-compte-concurrence-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
class MonCompteConcurrenceTests {
    @Autowired MonCompteService service;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void unSeulChangementConcurrentPeutUtiliserLeMemeAncienMotDePasse() throws Exception {
        String ancien = "Ancien42";
        String nouveauA = "Une première phrase secrète";
        String nouveauB = "Une deuxième phrase secrète";
        Boutique boutique = new Boutique(); boutique.setNom("Commerce concurrence");
        boutique = boutiques.saveAndFlush(boutique);
        Utilisateur compte = new Utilisateur(); compte.setNom("Caissier concurrence"); compte.setEmail("concurrence@mon-compte.test");
        compte.setRole(Role.CAISSIER); compte.setBoutique(boutique); compte.setDateInscription(LocalDate.of(2026, 10, 13));
        compte.setMotDePasse(encoder.encode(ancien)); compte = utilisateurs.saveAndFlush(compte);
        Long id = compte.getId(); String email = compte.getEmail(); String hashInitial = compte.getMotDePasse();
        CountDownLatch copiesChargees = new CountDownLatch(2);
        CountDownLatch demarrer = new CountDownLatch(1);
        var executants = Executors.newFixedThreadPool(2);
        try {
            Future<RuntimeException> premier = executants.submit(() -> changer(email, ancien, nouveauA, copiesChargees, demarrer));
            Future<RuntimeException> second = executants.submit(() -> changer(email, ancien, nouveauB, copiesChargees, demarrer));
            assertThat(copiesChargees.await(10, TimeUnit.SECONDS)).as("Les deux transactions ont chargé le même compte avant de modifier son mot de passe").isTrue();
            assertThat(utilisateurs.findById(id).orElseThrow().getMotDePasse()).isEqualTo(hashInitial);
            demarrer.countDown();
            RuntimeException erreurA = premier.get(20, TimeUnit.SECONDS);
            RuntimeException erreurB = second.get(20, TimeUnit.SECONDS);
            assertThat((erreurA == null ? 1 : 0) + (erreurB == null ? 1 : 0)).isEqualTo(1);
            RuntimeException erreur = erreurA != null ? erreurA : erreurB;
            assertThat(erreur).isInstanceOf(ChangementMotDePasseException.class);
            assertThat(((ChangementMotDePasseException) erreur).getChamp()).isEqualTo("ancienMotDePasse");
            Utilisateur apres = utilisateurs.findById(id).orElseThrow();
            String nouveauValide = erreurA == null ? nouveauA : nouveauB;
            String nouveauRefuse = erreurA == null ? nouveauB : nouveauA;
            assertThat(encoder.matches(nouveauValide, apres.getMotDePasse())).isTrue();
            assertThat(encoder.matches(nouveauRefuse, apres.getMotDePasse())).isFalse();
            assertThat(encoder.matches(ancien, apres.getMotDePasse())).isFalse();
            assertThat(apres.getEmail()).isEqualTo(email); assertThat(apres.getRole()).isEqualTo(Role.CAISSIER);
            assertThat(apres.isActif()).isTrue(); assertThat(apres.isAdministrateurPlateforme()).isFalse();
            assertThat(apres.getBoutique().getId()).isEqualTo(boutique.getId());
        } finally {
            demarrer.countDown();
            executants.shutdownNow();
            assertThat(executants.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            utilisateurs.deleteAll(); boutiques.deleteAll();
        }
    }

    private RuntimeException changer(String email, String ancien, String nouveau, CountDownLatch copiesChargees, CountDownLatch demarrer) {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(email, "", List.of(new SimpleGrantedAuthority("ROLE_CAISSIER"))));
        SecurityContextHolder.setContext(contexte);
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                // Les deux contextes JPA détiennent la copie précédente ; le verrouillage doit la rafraîchir.
                Utilisateur copie = utilisateurs.findByEmail(email).orElseThrow();
                assertThat(encoder.matches(ancien, copie.getMotDePasse())).isTrue();
                copiesChargees.countDown();
                attendre(demarrer);
                ChangementMotDePasseForm formulaire = new ChangementMotDePasseForm();
                formulaire.setAncienMotDePasse(ancien); formulaire.setNouveauMotDePasse(nouveau); formulaire.setConfirmationMotDePasse(nouveau);
                service.changerMotDePasse(formulaire);
            });
            return null;
        } catch (RuntimeException erreur) {
            return erreur;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void attendre(CountDownLatch demarrer) {
        try {
            if (!demarrer.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Le démarrage simultané n'a pas été déclenché");
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Le scénario concurrent a été interrompu", interruption);
        }
    }
}
