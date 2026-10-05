package org.example.stock.security;

import org.example.stock.enums.Role;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.UtilisateurRepository;
import org.example.stock.service.UtilisateurService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Locale;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:comptes-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@WithMockUser(username = "admin-comptes@example.test", roles = "ADMIN")
class CompteUtilisateurTests {
    static final String SECRET = "MotDePasseTest42";
    @Autowired MockMvc mvc;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired UtilisateurService service;
    @MockitoSpyBean PasswordEncoder encoder;

    @BeforeEach
    void preparer() {
        utilisateurs.deleteAll();
        Utilisateur admin = new Utilisateur(); admin.setNom("Administrateur"); admin.setEmail("admin-comptes@example.test");
        admin.setRole(Role.ADMIN);
        admin.setMotDePasse("$2a$10$.J5tnOlvK5hz1YmzGTfCveVTFmwylAyCesO4/wU4eI9S0zgbIyYm2");
        utilisateurs.saveAndFlush(admin);
    }

    @Test
    void leFormulaireDuNavigateurCreeUnCaissierEtConserveLaSessionAdmin() throws Exception {
        var page = mvc.perform(get("/register").header("Accept-Language", "fr-FR")).andExpect(status().isOk()).andReturn();
        String html = page.getResponse().getContentAsString();
        assertThat(html).contains("15 caractères", "72 octets", "autocomplete=\"new-password\"");
        var csrf = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertThat(csrf.find()).isTrue();
        mvc.perform(post("/register").header("Accept-Language", "fr-FR").header("User-Agent", "Navigateur de test")
                .contentType("application/x-www-form-urlencoded")
                .session((MockHttpSession) page.getRequest().getSession(false)).param("_csrf", csrf.group(1))
                .param("nom", "  Nouveau caissier  ").param("email", "  NOUVEAU@EXAMPLE.TEST  ").param("password", SECRET))
                .andExpect(redirectedUrl("/register?created"))
                .andExpect(org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated()
                        .withUsername("admin-comptes@example.test")).andReturn();
        Utilisateur compte = utilisateurs.findByEmail("nouveau@example.test").orElseThrow();
        assertThat(compte.getNom()).isEqualTo("Nouveau caissier");
        assertThat(compte.getRole()).isEqualTo(Role.CAISSIER);
        assertThat(compte.getDateInscription()).isNotNull();
        assertThat(compte.getMotDePasse()).isNotEqualTo(SECRET);
        assertThat(encoder.matches(SECRET, compte.getMotDePasse())).isTrue();
        assertThat(utilisateurs.findByEmail("admin-comptes@example.test").orElseThrow().getRole()).isEqualTo(Role.ADMIN);
    }

    @ParameterizedTest
    @CsvSource(value = {"nom|", "nom|   ", "email|", "email|abc", "email|sans-domaine@", "email|a b@example.test", "password|", "password|court"},
            delimiter = '|', nullValues = "NULL", emptyValue = "")
    void lesValeursInvalidesAffichentUneErreurSansCreerDeCompte(String champ, String valeur) throws Exception {
        var request = post("/register").with(csrf());
        for (String field : new String[]{"nom", "email", "password"}) {
            String valide = field.equals("nom") ? "Caissier" : field.equals("email") ? "nouveau@example.test" : SECRET;
            if (field.equals(champ)) { if (valeur != null) request.param(field, valeur); }
            else request.param(field, valide);
        }
        String html = mvc.perform(request).andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("inscription", champ))
                .andReturn().getResponse().getContentAsString();
        assertThat(utilisateurs.count()).isEqualTo(1);
        assertThat(html).doesNotContain(SECRET);
    }

    @ParameterizedTest
    @ValueSource(strings = {"nom", "email"})
    void lesChampsTropLongsSontRefuses(String champ) throws Exception {
        mvc.perform(post("/register").with(csrf()).param("nom", champ.equals("nom") ? "a".repeat(101) : "Caissier")
                .param("email", champ.equals("email") ? "a".repeat(245) + "@example.test" : "nouveau@example.test").param("password", SECRET))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("inscription", champ));
        assertThat(utilisateurs.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "é", "😀"})
    void lesMotsDePasseTropLongsEnOctetsSontRefuses(String caractere) throws Exception {
        String password = caractere.repeat(caractere.equals("a") ? 73 : caractere.equals("é") ? 37 : 19);
        mvc.perform(post("/register").with(csrf()).param("nom", "Caissier").param("email", "nouveau@example.test").param("password", password))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("inscription", "password"));
        assertThat(utilisateurs.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "😀"})
    void leMinimumCompteLesCaracteresUnicode(String caractere) throws Exception {
        String password = caractere.repeat(14);
        mvc.perform(post("/register").with(csrf()).param("nom", "Caissier").param("email", "nouveau@example.test").param("password", password))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("inscription", "password"));
        assertThat(utilisateurs.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "é", "😀"})
    void unMotDePasseDeQuinzeCaracteresEstAccepte(String caractere) {
        Utilisateur compte = service.registerUtilisateur("Caissier", "nouveau@example.test", caractere.repeat(15));
        assertThat(encoder.matches(caractere.repeat(15), compte.getMotDePasse())).isTrue();
    }

    @Test
    void leMotDePasseNestPasTrime() {
        String password = " " + SECRET + " ";
        Utilisateur compte = service.registerUtilisateur("Caissier", "nouveau@example.test", password);
        assertThat(encoder.matches(password, compte.getMotDePasse())).isTrue();
        assertThat(encoder.matches(SECRET, compte.getMotDePasse())).isFalse();
    }

    @Test
    void unDoublonNormaliseAfficheUneErreurEtNeReaffichePasLeMotDePasse() throws Exception {
        service.registerUtilisateur("Premier", "nouveau@example.test", SECRET);
        String html = mvc.perform(post("/register").with(csrf()).param("nom", "  Second  ")
                .param("email", " NOUVEAU@EXAMPLE.TEST ").param("password", SECRET))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("inscription", "email"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Cet email est déjà utilisé", "value=\"Second\"", "value=\"nouveau@example.test\"").doesNotContain(SECRET);
        assertThat(utilisateurs.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "id", "dateInscription", "motDePasse", "email.id"})
    void laCreationRefuseLesChampsInternes(String champ) throws Exception {
        mvc.perform(post("/register").with(csrf()).param("nom", "Caissier").param("email", "nouveau@example.test")
                .param("password", SECRET).param(champ, "ADMIN"))
                .andExpect(status().isBadRequest());
        assertThat(utilisateurs.count()).isEqualTo(1);
    }

    @Test
    @WithAnonymousUser
    void leLoginAccepteLaCasseEtLesEspacesPourUnAncienMotDePasseCourt() throws Exception {
        Utilisateur admin = utilisateurs.findByEmail("admin-comptes@example.test").orElseThrow();
        admin.setMotDePasse(encoder.encode("court")); utilisateurs.saveAndFlush(admin);
        mvc.perform(post("/login").with(csrf()).param("username", "  ADMIN-COMPTES@EXAMPLE.TEST  ").param("password", "court"))
                .andExpect(redirectedUrl("/"));
        assertThat(service.login("  ADMIN-COMPTES@EXAMPLE.TEST  ", "court").getId()).isEqualTo(admin.getId());
    }

    @Test
    void laNormalisationNeDependPasDeLaLangueDuSysteme() {
        Locale ancienne = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            Utilisateur compte = service.registerUtilisateur("Caissier", " IDENTITE@EXAMPLE.TEST ", SECRET);
            assertThat(compte.getEmail()).isEqualTo("identite@example.test");
            assertThat(service.login(" IDENTITE@EXAMPLE.TEST ", SECRET).getId()).isEqualTo(compte.getId());
        } finally { Locale.setDefault(ancienne); }
    }

    @Test
    void uneEcritureDirecteNormaliseAussiLEmailEtLaBaseRefuseLeDoublon() {
        Utilisateur premier = new Utilisateur(); premier.setNom("Premier"); premier.setEmail(" DIRECT@EXAMPLE.TEST ");
        utilisateurs.saveAndFlush(premier);
        assertThat(premier.getEmail()).isEqualTo("direct@example.test");
        Utilisateur second = new Utilisateur(); second.setEmail("direct@example.test");
        assertThatThrownBy(() -> utilisateurs.saveAndFlush(second)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(utilisateurs.count()).isEqualTo(2);
    }

    @Test
    void deuxCreationsSimultaneesNeCreentQuUnCompteEtUneErreurLisible() throws Exception {
        String password = "MotDePasseConcurrent42";
        CyclicBarrier preVerifications = new CyclicBarrier(2);
        doAnswer(invocation -> {
            if (password.contentEquals(invocation.getArgument(0, CharSequence.class))) preVerifications.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(encoder).encode(any(CharSequence.class));
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<org.springframework.test.web.servlet.MvcResult> a = pool.submit(() -> inscriptionConcurrente("CONCURRENT@EXAMPLE.TEST", password));
            Future<org.springframework.test.web.servlet.MvcResult> b = pool.submit(() -> inscriptionConcurrente(" concurrent@example.test ", password));
            var premiere = a.get(20, TimeUnit.SECONDS); var seconde = b.get(20, TimeUnit.SECONDS);
            assertThat(java.util.List.of(premiere.getResponse().getStatus(), seconde.getResponse().getStatus())).containsExactlyInAnyOrder(302, 200);
            var refusee = premiere.getResponse().getStatus() == 200 ? premiere : seconde;
            assertThat(refusee.getResponse().getContentAsString()).contains("Cet email est déjà utilisé").doesNotContain(password, "insert into", "uk_utilisateur");
        }
        assertThat(utilisateurs.count()).isEqualTo(2);
        assertThat(utilisateurs.findByEmail("concurrent@example.test").orElseThrow().getRole()).isEqualTo(Role.CAISSIER);
    }

    @Test
    void leServiceValideAussiLesAppelsDirects() {
        assertThatThrownBy(() -> service.registerUtilisateur(null, "nouveau@example.test", SECRET)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.registerUtilisateur("Caissier", null, SECRET)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.registerUtilisateur("Caissier", "nouveau@example.test", null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(utilisateurs.count()).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.MvcResult inscriptionConcurrente(String email, String password) throws Exception {
        return mvc.perform(post("/register").with(user("admin-comptes@example.test").roles("ADMIN")).with(csrf())
                .param("nom", "Caissier").param("email", email).param("password", password)).andReturn();
    }
}
