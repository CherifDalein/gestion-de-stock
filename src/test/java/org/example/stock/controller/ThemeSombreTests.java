package org.example.stock.controller;

import org.example.stock.enums.Role;
import org.example.stock.model.Boutique;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.BoutiqueRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Thème sombre : initialisation dans le head avant les feuilles de style, sélecteur du menu du compte, bascule des pages publiques. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:theme-sombre;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class ThemeSombreTests {
    static final String GERANT = "theme-gerant@example.test";

    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired MockMvc mvc;

    @BeforeEach
    void preparer() {
        if (utilisateurs.findByEmail(GERANT).isPresent()) return;
        Boutique b = new Boutique(); b.setNom("Boutique thème");
        b = boutiques.saveAndFlush(b);
        Utilisateur u = new Utilisateur(); u.setBoutique(b); u.setEmail(GERANT); u.setNom("Awa"); u.setRole(Role.ADMIN);
        utilisateurs.saveAndFlush(u);
    }

    private String page(String url, boolean connecte) throws Exception {
        var requete = get(url);
        if (connecte) requete = requete.with(user(GERANT).roles("ADMIN"));
        return mvc.perform(requete).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String tete(String html) {
        Matcher m = Pattern.compile("(?s)<head>(.*?)</head>").matcher(html);
        assertThat(m.find()).as("balise head").isTrue();
        return m.group(1);
    }

    /** Le script de thème est synchrone (ni async ni defer) et placé avant la première feuille de style. */
    private static void verifierInitialisation(String tete) {
        assertThat(tete).contains("<meta name=\"color-scheme\" content=\"light dark\">");
        assertThat(tete).contains("name=\"theme-color\"");
        Matcher script = Pattern.compile("<script[^>]*src=\"/js/theme\\.js\"[^>]*>").matcher(tete);
        assertThat(script.find()).as("theme.js dans le head").isTrue();
        assertThat(script.group()).doesNotContain("async").doesNotContain("defer").doesNotContain("module");
        int premiereFeuille = tete.indexOf("rel=\"stylesheet\"");
        assertThat(premiereFeuille).isPositive();
        assertThat(script.start()).as("theme.js avant les feuilles de style").isLessThan(premiereFeuille);
        assertThat(tete).contains("/css/theme.css");
    }

    @Test
    void lApplicationInitialiseLeThemeAvantLesFeuillesDeStyleEtOffreLeSelecteurDansLeMenuDuCompte() throws Exception {
        String html = page("/clients", true);
        verifierInitialisation(tete(html));
        assertThat(html).contains("<link href=\"/css/theme.css\"");
        // Menu du compte : un radiogroup « Thème » avec Auto (défaut), Clair, Sombre, dans le menu déroulant.
        int menu = html.indexOf("aria-labelledby=\"navbarDropdown\"");
        assertThat(menu).isPositive();
        String menuCompte = html.substring(menu, html.indexOf("</ul>", menu));
        assertThat(menuCompte).contains("role=\"radiogroup\"", "Thème");
        assertThat(menuCompte).contains("name=\"rfc-theme-choix\" value=\"auto\"", "name=\"rfc-theme-choix\" value=\"light\"", "name=\"rfc-theme-choix\" value=\"dark\"");
        assertThat(menuCompte).contains("Auto", "Clair", "Sombre");
        assertThat(menuCompte.indexOf("Thème")).isLessThan(menuCompte.indexOf("Se déconnecter"));
        // Le menu reste ouvert quand on choisit un thème.
        assertThat(html).contains("data-bs-auto-close=\"outside\"");
        // Logo : les deux variantes sont servies, theme.css n'affiche que la bonne ; le logo de boutique n'est pas concerné.
        assertThat(html).contains("rfc-compta-logo.svg", "rfc-compta-logo-fond-sombre.svg", "rfc-compta-marque-fond-sombre.svg");
    }

    @Test
    void lesPagesPubliquesInitialisentLeThemeEtOffrentLaBasculeLuneSoleil() throws Exception {
        for (String url : new String[]{"/login", "/accueil", "/inscription"}) {
            String html = page(url, false);
            verifierInitialisation(tete(html));
            assertThat(html).as(url).contains("data-theme-bascule", "aria-pressed=\"false\"");
            assertThat(html).as(url).contains("rfc-compta-logo-fond-sombre.svg");
        }
    }

    @Test
    void lesGabaritsHorsMiseEnPagePrincipaleChargentLeThemeAvantLeursFeuilles() throws Exception {
        // Pages d'erreur et d'accès terminé : rendues par Spring hors MockMvc, on vérifie le gabarit.
        for (String gabarit : new String[]{"error.html", "compte/essai-termine.html", "compte/mot-de-passe-oublie.html", "register.html"}) {
            String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/templates", gabarit));
            assertThat(source).as(gabarit).contains("fragments/theme :: tete").contains("/css/theme.css");
            assertThat(source.indexOf("fragments/theme :: tete")).as(gabarit).isLessThan(source.indexOf("rel=\"stylesheet\""));
        }
        // Les gabarits d'impression restent sans thème : ils s'impriment en clair.
        for (String gabarit : new String[]{"factures/template_vente.html", "devis/impression.html", "retours/impression.html"}) {
            assertThat(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/templates", gabarit))).as(gabarit).doesNotContain("theme");
        }
    }

    @Test
    void leModuleDeThemeEtLaFeuilleSontServisEtLeSombreNeTouchePasLImpression() throws Exception {
        assertThat(mvc.perform(get("/js/theme.js")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains("rfc-theme", "prefers-color-scheme", "data-bs-theme");
        String css = mvc.perform(get("/css/theme.css")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // Toutes les couleurs sombres sont dans un bloc « not print » : l'impression reste en clair.
        assertThat(css).contains("@media not print");
        assertThat(css.substring(0, css.indexOf("@media not print"))).doesNotContain("[data-bs-theme=\"dark\"] {");
    }
}
