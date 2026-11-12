package org.example.stock.migration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Fixtures fictives identiques sur H2 et MariaDB. */
final class IdentiteBoutiqueMigrationScenarios {
    static final List<String> VALIDES = List.of("ancien", "vide", "telephone_seul", "logo_seul", "complet", "relance_logo", "reprise_identite", "nouvelle_boutique", "schema_neuf");
    static final List<String> INVALIDES = List.of("telephone_longueur", "telephone_type", "telephone_obligatoire", "telephone_defaut",
            "logo_contenu_texte", "logo_revision_longueur", "logo_sans_cle", "logo_sans_fk", "logo_fk_cascade", "logo_fk_autre_table",
            "logo_revision_invalide", "logo_vide", "logo_corrompu", "logo_trop_grand", "logo_dimensions");
    private IdentiteBoutiqueMigrationScenarios() {}

    static void schema(Connection c, boolean vide) throws Exception {
        TresorerieMigrationScenarios.schema(c, vide);
        TresorerieMigration.migrer(c, true);
        if (!vide) TresorerieMigrationScenarios.preparer(c, "activite_existante");
    }

    static void preparer(Connection c, String scenario) throws Exception {
        if (Set.of("ancien", "vide", "nouvelle_boutique", "schema_neuf").contains(scenario)) return;
        if (scenario.equals("telephone_seul")) { ajouterTelephone(c); return; }
        if (scenario.equals("logo_seul")) { IdentiteBoutiqueMigration.creerLogos(c); insererLogo(c, png(2), revision()); return; }
        if (scenario.equals("reprise_identite")) {
            ajouterTelephone(c); IdentiteBoutiqueMigration.creerLogos(c);
            sql(c, "UPDATE boutique SET telephone_secondaire='+224 622 00 00 00' WHERE id=1");
            insererLogo(c, png(2), revision()); return;
        }
        IdentiteBoutiqueMigration.migrer(c, true);
        switch (scenario) {
            case "complet" -> {}
            case "relance_logo" -> { sql(c, "UPDATE boutique SET telephone_secondaire='+224 622 00 00 00' WHERE id=1"); insererLogo(c, png(2), revision()); }
            case "telephone_longueur" -> sql(c, "ALTER TABLE boutique " + alter(c) + " telephone_secondaire VARCHAR(51) NULL");
            case "telephone_type" -> sql(c, "ALTER TABLE boutique " + alter(c) + " telephone_secondaire CHAR(50) NULL");
            case "telephone_obligatoire" -> { sql(c, "UPDATE boutique SET telephone_secondaire='' WHERE telephone_secondaire IS NULL"); sql(c, "ALTER TABLE boutique " + alter(c) + " telephone_secondaire VARCHAR(50) NOT NULL"); }
            case "telephone_defaut" -> sql(c, "ALTER TABLE boutique ALTER COLUMN telephone_secondaire SET DEFAULT 'contact'");
            case "logo_contenu_texte" -> sql(c, "ALTER TABLE logo_boutique " + alter(c) + " contenu VARCHAR(100) NOT NULL");
            case "logo_revision_longueur" -> sql(c, "ALTER TABLE logo_boutique " + alter(c) + " revision VARCHAR(35) NOT NULL");
            case "logo_sans_cle" -> {
                retirerRelation(c); sql(c, "ALTER TABLE logo_boutique DROP PRIMARY KEY");
                sql(c, "ALTER TABLE logo_boutique ADD CONSTRAINT fk_logo_boutique FOREIGN KEY(boutique_id) REFERENCES boutique(id)");
            }
            case "logo_sans_fk" -> retirerRelation(c);
            case "logo_fk_cascade" -> { retirerRelation(c); sql(c, "ALTER TABLE logo_boutique ADD CONSTRAINT fk_logo_boutique FOREIGN KEY(boutique_id) REFERENCES boutique(id) ON DELETE CASCADE"); }
            case "logo_fk_autre_table" -> { retirerRelation(c); sql(c, "ALTER TABLE logo_boutique ADD CONSTRAINT fk_logo_boutique FOREIGN KEY(boutique_id) REFERENCES fournisseur(id)"); }
            case "logo_revision_invalide" -> insererLogo(c, png(2), "invalide");
            case "logo_vide" -> insererLogo(c, new byte[0], revision());
            case "logo_corrompu" -> { byte[] image = png(2); insererLogo(c, Arrays.copyOf(image, 35), revision()); }
            case "logo_trop_grand" -> insererLogo(c, new byte[IdentiteBoutiqueMigration.TAILLE_MAX + 1], revision());
            case "logo_dimensions" -> insererLogo(c, png(513), revision());
            default -> throw new IllegalStateException("Scénario identité boutique inconnu.");
        }
    }

    static void verifier(Connection c, String scenario) throws Exception {
        var avant = IdentiteBoutiqueMigration.photographie(c); var schemas = IdentiteBoutiqueMigration.schemasAnciens(c);
        var tables = BoutiquesMigration.tables(c); var logos = IdentiteBoutiqueMigration.logos(c);
        if (INVALIDES.contains(scenario)) {
            for (boolean appliquer : List.of(false, true)) {
                boolean refuse = false;
                try { IdentiteBoutiqueMigration.migrer(c, appliquer); } catch (IllegalStateException e) { refuse = true; }
                exiger(refuse && avant.equals(IdentiteBoutiqueMigration.photographie(c)) && schemas.equals(IdentiteBoutiqueMigration.schemasAnciens(c))
                        && tables.equals(BoutiquesMigration.tables(c)) && logos.equals(IdentiteBoutiqueMigration.logos(c)), "Schéma incohérent accepté ou données modifiées : " + scenario);
            }
            return;
        }
        IdentiteBoutiqueMigration.migrer(c, false);
        exiger(tables.equals(BoutiquesMigration.tables(c)) && avant.equals(IdentiteBoutiqueMigration.photographie(c))
                && logos.equals(IdentiteBoutiqueMigration.logos(c)), "Le contrôle a modifié la base");
        IdentiteBoutiqueMigration.migrer(c, true);
        exiger(BoutiquesMigration.tables(c).keySet().stream().filter(t -> !Lot2Migration.NOUVELLES_TABLES.contains(t)).count() == 20 && avant.equals(IdentiteBoutiqueMigration.photographie(c))
                && schemas.equals(IdentiteBoutiqueMigration.schemasAnciens(c)) && logos.equals(IdentiteBoutiqueMigration.logos(c)), "Les anciennes données ou logos ont changé");
        if (!Set.of("reprise_identite", "relance_logo").contains(scenario))
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE telephone_secondaire IS NOT NULL") == 0, "Téléphone historique inventé");
        IdentiteBoutiqueMigration.migrer(c, true); IdentiteBoutiqueMigration.migrer(c, false);
        exiger(avant.equals(IdentiteBoutiqueMigration.photographie(c)) && logos.equals(IdentiteBoutiqueMigration.logos(c)), "Relance non idempotente");
        if (scenario.equals("nouvelle_boutique")) {
            sql(c, "INSERT INTO boutique(id,nom,date_creation) VALUES(99,'Nouvelle identité factice','2025-01-01 00:00:00.123456')");
            for (String compte : List.of("CAISSE", "ORANGE_MONEY", "BANQUE")) sql(c, "INSERT INTO compte_tresorerie_boutique(boutique_id,compte) VALUES(99,'" + compte + "')");
            var nouveau = IdentiteBoutiqueMigration.photographie(c);
            IdentiteBoutiqueMigration.migrer(c, true);
            exiger(nouveau.equals(IdentiteBoutiqueMigration.photographie(c)) && logos.equals(IdentiteBoutiqueMigration.logos(c))
                    && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE id=99 AND telephone_secondaire IS NULL") == 1, "Nouvelle boutique modifiée par relance");
        }
    }

    /** Schéma neuf des lots antérieurs au lot 3 : le script actuel sans les comptes de trésorerie multiples. */
    static void schemaNeuf(Connection c) throws Exception { schemaNeuf(c, false); }

    /** Schéma neuf actuel, comptes de trésorerie multiples du lot 3 compris. */
    static void schemaNeufLot3(Connection c) throws Exception { schemaNeuf(c, true); }

    private static void schemaNeuf(Connection c, boolean avecLot3) throws Exception {
        String brut = Files.readString(Path.of("docs/database-schema.sql"));
        String script = (avecLot3 ? brut : SchemaHistorique.sansLot3(brut)).replaceAll("(?m)^--[^\\r\\n]*", "");
        if (!maria(c)) script = script.replace(" ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci", "");
        for (String instruction : script.split(";")) {
            instruction = instruction.strip();
            if (!instruction.isEmpty() && !instruction.startsWith("CREATE DATABASE") && !instruction.startsWith("USE ")) sql(c, instruction);
        }
    }

    private static void ajouterTelephone(Connection c) throws SQLException { sql(c, "ALTER TABLE boutique ADD COLUMN telephone_secondaire VARCHAR(50) NULL"); }
    private static void retirerRelation(Connection c) throws SQLException { sql(c, "ALTER TABLE logo_boutique DROP " + (maria(c) ? "FOREIGN KEY" : "CONSTRAINT") + " fk_logo_boutique"); }
    private static String revision() { return "a0000000-0000-0000-0000-000000000001"; }
    static void insererLogo(Connection c, byte[] contenu, String revision) throws SQLException {
        c.setReadOnly(false);
        try (var s = c.prepareStatement("INSERT INTO logo_boutique(boutique_id,contenu,revision) VALUES(1,?,?)")) { s.setBytes(1, contenu); s.setString(2, revision); s.executeUpdate(); }
    }
    static byte[] png(int taille) throws Exception {
        var image = new BufferedImage(taille, taille, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x80118a66);
        var sortie = new ByteArrayOutputStream(); ImageIO.write(image, "PNG", sortie); return sortie.toByteArray();
    }
    static void sql(Connection c, String requete) throws SQLException { c.setReadOnly(false); IdentiteBoutiqueMigration.sql(c, requete); }
    static boolean maria(Connection c) throws SQLException { return c.getMetaData().getDatabaseProductName().equals("MariaDB"); }
    private static String alter(Connection c) throws SQLException { return maria(c) ? "MODIFY COLUMN" : "ALTER COLUMN"; }
    static void exiger(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
