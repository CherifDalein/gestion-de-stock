package org.example.stock.migration;

import jakarta.validation.Validation;
import org.example.stock.config.LengthCheckedBcryptPasswordEncoder;
import org.example.stock.form.InscriptionForm;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.security.SecureRandom;
import java.util.*;

/** Commande locale avec accès JDBC privilégié, jamais exposée par une route web. */
public class InitialAdmin {
    public static void main(String[] args) {
        if (args.length != 1 || !args[0].equals("--create-if-empty")) throw new IllegalArgumentException("Utiliser --create-if-empty");
        Path identifiants = null;
        try {
            Properties config = new Properties();
            try (var r = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { config.load(r); }
            String url = AccountEmailMigration.resolve(config.getProperty("spring.datasource.url"));
            var cible = java.net.URI.create(url.substring(5));
            if (!Set.of("localhost", "127.0.0.1").contains(cible.getHost()) || !"/stock_pro".equals(cible.getPath()))
                throw new IllegalStateException("Cette commande exige la base locale stock_pro");
            String email = System.getenv().getOrDefault("INITIAL_ADMIN_EMAIL", "admin@stock.local");
            byte[] aleatoire = new byte[24]; new SecureRandom().nextBytes(aleatoire);
            String password = System.getenv().getOrDefault("INITIAL_ADMIN_PASSWORD", Base64.getUrlEncoder().withoutPadding().encodeToString(aleatoire));
            try (Connection c = DriverManager.getConnection(url, AccountEmailMigration.resolve(config.getProperty("spring.datasource.username")),
                    AccountEmailMigration.resolve(config.getProperty("spring.datasource.password")))) {
                // Conserver le secret même si la connexion échoue après une insertion validée par le serveur.
                identifiants = Files.createTempFile("stock-admin-", ".txt", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.writeString(identifiants, "Email : " + email + "\nMot de passe : " + password + "\n");
                creer(c, email, password);
            }
            System.out.println("Premier administrateur créé. Identifiants dans le fichier privé : " + identifiants);
        } catch (Exception e) {
            System.err.println("Création interrompue : " + (e instanceof IllegalStateException ? e.getMessage() : "Vérifiez la connexion et le schéma de la base"));
            if (identifiants != null) System.err.println("Fichier de préparation privé conservé : " + identifiants + ". Vérifiez l'état du compte avant de réessayer.");
            System.exit(1);
        }
    }

    static void creer(Connection c, String email, String password) throws Exception {
        InscriptionForm formulaire = new InscriptionForm(); formulaire.setNom("Administrateur"); formulaire.setEmail(email); formulaire.setPassword(password);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            if (!factory.getValidator().validate(formulaire).isEmpty()) throw new IllegalStateException("L'email ou le mot de passe ne respecte pas les règles de création des comptes");
        }
        boolean verrouSql = !c.getMetaData().getDatabaseProductName().equals("H2");
        String verrou = "stock:init-admin:" + c.getCatalog();
        if (verrouSql) try (var s = c.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            s.setString(1, verrou);
            try (var r = s.executeQuery()) { r.next(); if (r.getInt(1) != 1) throw new IllegalStateException("Une création initiale est déjà en cours"); }
        }
        try {
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM utilisateur")) {
                r.next(); if (r.getLong(1) != 0) throw new IllegalStateException("Des comptes existent déjà ; aucun administrateur n'a été ajouté");
            }
            try (var s = c.prepareStatement("INSERT INTO utilisateur(nom,email,mot_de_passe,date_inscription,role) VALUES(?,?,?,?,?)")) {
                s.setString(1, formulaire.getNom()); s.setString(2, formulaire.getEmail());
                s.setString(3, new LengthCheckedBcryptPasswordEncoder().encode(password));
                s.setDate(4, java.sql.Date.valueOf(java.time.LocalDate.now())); s.setString(5, "ADMIN"); s.executeUpdate();
            }
        } finally {
            if (verrouSql) try (var s = c.prepareStatement("SELECT RELEASE_LOCK(?)")) { s.setString(1, verrou); s.executeQuery().close(); }
        }
    }
}
