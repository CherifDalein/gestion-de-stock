package org.example.stock.security;

import org.example.stock.enums.Role;
import org.example.stock.model.Boutique;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.BoutiqueRepository;
import org.example.stock.repository.LogoBoutiqueRepository;
import org.example.stock.repository.MouvementCaisseRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LogoBoutiqueSecurityTests {
    private static final String PARAMETRES = "/boutique/parametres", LOGO = "/boutique/logo";
    @Autowired MockMvc mvc;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired LogoBoutiqueRepository logos;
    @Autowired MouvementCaisseRepository mouvements;
    Boutique boutiqueA, boutiqueB;
    Utilisateur gerantA, gerantB, caissier;

    @BeforeEach
    void preparer() {
        boutiqueA = boutique("Boutique A"); boutiqueB = boutique("Boutique B");
        gerantA = compte("gerant-a@logos.test", boutiqueA, Role.ADMIN);
        gerantB = compte("gerant-b@logos.test", boutiqueB, Role.ADMIN);
        caissier = compte("caissier@logos.test", boutiqueA, Role.CAISSIER);
    }

    @Test
    void importEtDeuxTelephonesNeModifientQueLaBoutiqueCourante() throws Exception {
        long argentAvant = mouvements.count();
        mvc.perform(saisie(png(0xffff0000)).with(user(gerantA.getEmail()).roles("ADMIN")).with(csrf()))
                .andExpect(redirectedUrl(PARAMETRES));
        Boutique relue = boutiques.findById(boutiqueA.getId()).orElseThrow();
        assertThat(relue.getNom()).isEqualTo("Commerce actualisé");
        assertThat(relue.getTelephone()).isEqualTo("+224 620 000 001");
        assertThat(relue.getTelephoneSecondaire()).isEqualTo("+224 620 000 002");
        assertThat(boutiques.findById(boutiqueB.getId()).orElseThrow().getNom()).isEqualTo("Boutique B");
        assertThat(logos.findById(boutiqueA.getId()).orElseThrow().getRevision()).matches("[0-9a-f-]{36}");
        assertThat(logos.existsById(boutiqueB.getId())).isFalse();
        assertThat(mouvements.count()).isEqualTo(argentAvant);
        for (Utilisateur compte : new Utilisateur[]{gerantA, caissier}) {
            mvc.perform(get(LOGO).with(user(compte.getEmail()).roles(compte.getRole().name())))
                    .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                    .andExpect(header().string("Cache-Control", "private, no-store"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(content().bytes(logos.findById(boutiqueA.getId()).orElseThrow().getContenu()));
        }
    }

    @Test
    void laLectureNePeutPasChoisirUneAutreBoutique() throws Exception {
        importer(gerantA, png(0xffff0000)); importer(gerantB, png(0xff0000ff));
        byte[] imageA = logos.findById(boutiqueA.getId()).orElseThrow().getContenu();
        byte[] imageB = logos.findById(boutiqueB.getId()).orElseThrow().getContenu();
        assertThat(imageA).isNotEqualTo(imageB);
        mvc.perform(get(LOGO).param("boutiqueId", boutiqueB.getId().toString()).with(user(gerantA.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().bytes(imageA));
        mvc.perform(get(LOGO).with(user(gerantB.getEmail()).roles("ADMIN"))).andExpect(content().bytes(imageB));
    }

    @Test
    void unLogoAbsentRetourne404EtUnVisiteurNePeutPasLeLire() throws Exception {
        mvc.perform(get(LOGO).with(user(gerantA.getEmail()).roles("ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(get(LOGO)).andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    void remplacementConservationEtSuppressionRestentExplicites() throws Exception {
        importer(gerantA, png(0xffff0000));
        var avant = logos.findById(boutiqueA.getId()).orElseThrow();
        byte[] contenuAvant = avant.getContenu().clone(); String revisionAvant = avant.getRevision();
        mvc.perform(saisie(new byte[0]).with(user(gerantA.getEmail()).roles("ADMIN")).with(csrf())).andExpect(redirectedUrl(PARAMETRES));
        assertThat(logos.findById(boutiqueA.getId()).orElseThrow().getContenu()).isEqualTo(contenuAvant);
        importer(gerantA, png(0xff0000ff));
        assertThat(logos.findById(boutiqueA.getId()).orElseThrow().getContenu()).isNotEqualTo(contenuAvant);
        assertThat(logos.findById(boutiqueA.getId()).orElseThrow().getRevision()).isNotEqualTo(revisionAvant);
        mvc.perform(post(PARAMETRES).param("nom", "Commerce conservé").param("supprimerLogo", "true").param("_supprimerLogo", "on")
                .with(user(gerantA.getEmail()).roles("ADMIN")).with(csrf())).andExpect(redirectedUrl(PARAMETRES));
        assertThat(logos.existsById(boutiqueA.getId())).isFalse();
        mvc.perform(get(LOGO).with(user(caissier.getEmail()).roles("CAISSIER"))).andExpect(status().isNotFound());
    }

    @Test
    void unFauxPngConserveAussiLesAnciennesCoordonneesEtLeLogo() throws Exception {
        importer(gerantA, png(0xffff0000));
        byte[] avant = logos.findById(boutiqueA.getId()).orElseThrow().getContenu().clone();
        mvc.perform(saisie("<svg onload='alert(1)'/>".getBytes(StandardCharsets.UTF_8))
                .with(user(gerantA.getEmail()).roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("parametresBoutique", "logo"));
        assertThat(logos.findById(boutiqueA.getId()).orElseThrow().getContenu()).isEqualTo(avant);
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().getTelephoneSecondaire()).isEqualTo("+224 620 000 002");
    }

    @Test
    void uploadEtEffacementSimultanesSontRefusesSansEffacer() throws Exception {
        importer(gerantA, png(0xffff0000));
        byte[] avant = logos.findById(boutiqueA.getId()).orElseThrow().getContenu().clone();
        mvc.perform(saisie(png(0xff0000ff)).param("supprimerLogo", "true").with(user(gerantA.getEmail()).roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("parametresBoutique", "logo"));
        assertThat(logos.findById(boutiqueA.getId()).orElseThrow().getContenu()).isEqualTo(avant);
    }

    @Test
    void leCaissierEtUneAncienneAutoriteAdminNePeuventPasModifier() throws Exception {
        mvc.perform(saisie(png(0xff0000ff)).with(user(caissier.getEmail()).roles("CAISSIER")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(saisie(png(0xff0000ff)).with(user(caissier.getEmail()).roles("ADMIN")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get(LOGO).with(user(caissier.getEmail()).roles("ADMIN"))).andExpect(status().isForbidden());
        assertThat(logos.count()).isZero();
    }

    @Test
    void toutImportExigeLeJetonCsrf() throws Exception {
        mvc.perform(saisie(png(0xffff0000)).with(user(gerantA.getEmail()).roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(saisie(png(0xffff0000)).with(user(gerantA.getEmail()).roles("ADMIN")).with(csrf().useInvalidToken())).andExpect(status().isForbidden());
        assertThat(logos.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"boutiqueId", "boutique.id", "id", "actif", "revision", "contenu"})
    void lesProprietesInternesNePeuventPasEtreEnvoyees(String champ) throws Exception {
        mvc.perform(saisie(png(0xffff0000)).param(champ, boutiqueB.getId().toString()).with(user(gerantA.getEmail()).roles("ADMIN")).with(csrf()))
                .andExpect(status().isBadRequest());
        assertThat(logos.count()).isZero();
    }

    @Test
    void unSeulFichierEtUnSeulChampDeChaqueTypeSontAutorises() throws Exception {
        mvc.perform(saisie(png(0xffff0000)).file(new MockMultipartFile("logo", "autre.png", "image/png", png(0xff0000ff)))
                .with(user(gerantA.getEmail()).roles("ADMIN")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(saisie(png(0xffff0000)).file(new MockMultipartFile("intrusion", "fichier.png", "image/png", png(0xff0000ff)))
                .with(user(gerantA.getEmail()).roles("ADMIN")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(post(PARAMETRES).param("nom", "Intrusion").param("logo", "/etc/passwd").with(user(gerantA.getEmail()).roles("ADMIN")).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(saisie(png(0xffff0000)).param("telephoneSecondaire", "autre numéro")
                .with(user(gerantA.getEmail()).roles("ADMIN")).with(csrf())).andExpect(status().isBadRequest());
        assertThat(logos.count()).isZero();
    }

    @Test
    void unImportTropLourdConserveLeLogo() throws Exception {
        importer(gerantA, png(0xffff0000));
        byte[] avant = logos.findById(boutiqueA.getId()).orElseThrow().getContenu().clone();
        mvc.perform(saisie(new byte[2 * 1024 * 1024 + 1]).with(user(gerantA.getEmail()).roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("parametresBoutique", "logo"));
        assertThat(logos.findById(boutiqueA.getId()).orElseThrow().getContenu()).isEqualTo(avant);
    }

    @Test
    void unSecondNumeroTropLongNeModifiePasLaBoutique() throws Exception {
        mvc.perform(post(PARAMETRES).param("nom", "Nouveau nom").param("telephoneSecondaire", "1".repeat(51))
                .with(user(gerantA.getEmail()).roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("parametresBoutique", "telephoneSecondaire"));
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().getNom()).isEqualTo("Boutique A");
    }

    private void importer(Utilisateur gerant, byte[] png) throws Exception {
        mvc.perform(saisie(png).with(user(gerant.getEmail()).roles("ADMIN")).with(csrf())).andExpect(redirectedUrl(PARAMETRES));
    }
    private MockMultipartHttpServletRequestBuilder saisie(byte[] contenu) {
        var builder = multipart(PARAMETRES).file(new MockMultipartFile("logo", "logo-personnel.png", "image/png", contenu));
        builder.param("nom", "Commerce actualisé").param("adresse", "Conakry")
                .param("telephone", "  +224 620 000 001  ").param("telephoneSecondaire", "  +224 620 000 002  ");
        return builder;
    }
    private Boutique boutique(String nom) { var b = new Boutique(); b.setNom(nom); return boutiques.saveAndFlush(b); }
    private Utilisateur compte(String email, Boutique boutique, Role role) {
        var u = new Utilisateur(); u.setEmail(email); u.setNom("Utilisateur fictif"); u.setBoutique(boutique); u.setRole(role);
        u.setMotDePasse("empreinte-fictive"); return utilisateurs.saveAndFlush(u);
    }
    private static byte[] png(int couleur) throws Exception {
        var image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y=0; y<4; y++) for (int x=0; x<4; x++) image.setRGB(x,y,couleur);
        try (var sortie = new ByteArrayOutputStream()) { ImageIO.write(image, "png", sortie); return sortie.toByteArray(); }
    }
}
