package org.example.stock.service;

import jakarta.persistence.EntityManager;
import org.example.stock.enums.Role;
import org.example.stock.form.ParametresBoutiqueForm;
import org.example.stock.model.Boutique;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.BoutiqueRepository;
import org.example.stock.repository.LogoBoutiqueRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Transactions réellement validées : vérifier les annulations et la première création simultanée. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:logo-boutique-service-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
class LogoBoutiqueServiceTests {
    @Autowired ParametresBoutiqueService service;
    @Autowired LogoBoutiqueService lecture;
    @Autowired LogoBoutiqueRepository logos;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager em;
    Boutique boutique;
    Utilisateur gerant, secondGerant;

    @BeforeEach
    void preparer() {
        logos.deleteAll(); utilisateurs.deleteAll(); boutiques.deleteAll();
        var b = new Boutique(); b.setNom("Boutique initiale"); boutique = boutiques.saveAndFlush(b);
        gerant = compte("premier@logo-service.test"); secondGerant = compte("second@logo-service.test");
        connecter(gerant);
    }

    @AfterEach
    void deconnecter() { SecurityContextHolder.clearContext(); }

    @Test
    void uneAnnulationApresEcritureAnnuleEnsembleCoordonneesEtImage() throws Exception {
        service.modifierPourBoutiqueCourante(formulaire("Avant", 0xffff0000));
        var avant = logos.findById(boutique.getId()).orElseThrow();
        byte[] contenu = avant.getContenu().clone(); String revision = avant.getRevision();
        var changement = formulaire("Après", 0xff0000ff);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.modifierPourBoutiqueCourante(changement); em.flush();
            throw new IllegalStateException("Annulation simulée");
        })).hasMessage("Annulation simulée");
        assertThat(boutiques.findById(boutique.getId()).orElseThrow().getNom()).isEqualTo("Avant");
        assertThat(logos.findById(boutique.getId()).orElseThrow().getContenu()).isEqualTo(contenu);
        assertThat(logos.findById(boutique.getId()).orElseThrow().getRevision()).isEqualTo(revision);
        assertThat(lecture.getPourBoutiqueCourante()).isEqualTo(contenu);
    }

    @Test
    void uneAnnulationDeSuppressionConserveLeLogo() throws Exception {
        service.modifierPourBoutiqueCourante(formulaire("Avant", 0xffff0000));
        byte[] contenu = logos.findById(boutique.getId()).orElseThrow().getContenu().clone();
        var effacement = new ParametresBoutiqueForm(); effacement.setNom("Effacé"); effacement.setSupprimerLogo(true);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.modifierPourBoutiqueCourante(effacement); em.flush();
            throw new IllegalStateException("Annulation de suppression");
        })).hasMessageContaining("Annulation");
        assertThat(logos.findById(boutique.getId()).orElseThrow().getContenu()).isEqualTo(contenu);
        assertThat(boutiques.findById(boutique.getId()).orElseThrow().getNom()).isEqualTo("Avant");
    }

    @Test
    void deuxPremiersImportsNeCreentQuUnLogoEtUneIdentiteCoherente() throws Exception {
        var premier = formulaire("Rouge", 0xffff0000); var second = formulaire("Bleu", 0xff0000ff);
        var depart = new CyclicBarrier(2);
        try (var executeur = Executors.newFixedThreadPool(2)) {
            var a = executeur.submit(() -> modifierEnParallele(gerant, premier, depart));
            var b = executeur.submit(() -> modifierEnParallele(secondGerant, second, depart));
            a.get(15, TimeUnit.SECONDS); b.get(15, TimeUnit.SECONDS);
        }
        assertThat(logos.count()).isOne();
        String nom = boutiques.findById(boutique.getId()).orElseThrow().getNom();
        var image = ImageIO.read(new ByteArrayInputStream(logos.findById(boutique.getId()).orElseThrow().getContenu()));
        assertThat(nom).isIn("Rouge", "Bleu");
        assertThat(image.getRGB(0, 0)).isEqualTo(nom.equals("Rouge") ? 0xffff0000 : 0xff0000ff);
    }

    @Test
    void leSecondNumeroEstNormaliseEtPeutEtreRetireSansToucherAuPremier() {
        var f = new ParametresBoutiqueForm(); f.setNom("Commerce"); f.setTelephone("  +224 620 000 001  ");
        f.setTelephoneSecondaire("  +224 620 000 002  "); service.modifierPourBoutiqueCourante(f);
        assertThat(service.chargerPourBoutiqueCourante().getTelephoneSecondaire()).isEqualTo("+224 620 000 002");
        f.setTelephoneSecondaire(" \n "); service.modifierPourBoutiqueCourante(f);
        var b = boutiques.findById(boutique.getId()).orElseThrow();
        assertThat(b.getTelephoneSecondaire()).isNull(); assertThat(b.getTelephone()).isEqualTo("+224 620 000 001");
    }

    private void modifierEnParallele(Utilisateur acteur, ParametresBoutiqueForm form, CyclicBarrier depart) {
        connecter(acteur);
        try { depart.await(5, TimeUnit.SECONDS); service.modifierPourBoutiqueCourante(form); }
        catch (Exception erreur) { throw new IllegalStateException(erreur); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private Utilisateur compte(String email) {
        var u = new Utilisateur(); u.setEmail(email); u.setNom("Gérant fictif"); u.setBoutique(boutique); u.setRole(Role.ADMIN);
        u.setMotDePasse("empreinte-fictive"); return utilisateurs.saveAndFlush(u);
    }
    private static void connecter(Utilisateur compte) {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(compte.getEmail(), "",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))); SecurityContextHolder.setContext(contexte);
    }
    private static ParametresBoutiqueForm formulaire(String nom, int couleur) throws Exception {
        var f = new ParametresBoutiqueForm(); f.setNom(nom);
        var image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y=0; y<4; y++) for (int x=0; x<4; x++) image.setRGB(x,y,couleur);
        try (var sortie = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", sortie);
            f.setLogo(new MockMultipartFile("logo", "image.png", "image/png", sortie.toByteArray()));
        }
        return f;
    }
}
