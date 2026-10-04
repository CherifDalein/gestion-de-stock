import java.nio.file.*;
import java.sql.*;
import java.math.BigDecimal;
import java.util.*;

class StockMoneyMigrationTest {
    static int passed;
    static Connection connection;
    static String migration;
    static final List<String> tables = List.of("produit", "achat", "vente", "detail_achat", "detail_vente", "mouvement_caisse");
    public static void main(String[] args) throws Exception {
        Properties config = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { config.load(reader); }
        String url = resolve(config.getProperty("spring.datasource.url"));
        var target = java.net.URI.create(url.substring(5));
        if (!target.getHost().equals("localhost") || target.getPort() != 3306 || !target.getPath().equals("/stock_pro")) throw new IllegalStateException("Serveur local requis");
        url = url.replace("/stock_pro?", "/?");
        migration = Files.readString(Path.of("docs/migrations/2026-10-05-money-decimal.sql"));
        try (var c = DriverManager.getConnection(url, resolve(config.getProperty("spring.datasource.username")), resolve(config.getProperty("spring.datasource.password")))) {
            connection = c;
            for (String name : List.of("valide", "bruit", "decimales", "negatif", "plafond", "surpaiement", "prixnull", "backup")) {
                String database = "stock_money_test_" + name + "_" + Long.toUnsignedString(System.nanoTime(), 36);
                execute("CREATE DATABASE " + database);
                try {
                    execute("USE " + database);
                    String schema = Files.readString(Path.of("docs/database-schema.sql"));
                    schema = schema.substring(schema.indexOf("CREATE TABLE"));
                    executeScript(schema.replace("DECIMAL(17,2)", "DOUBLE"));
                    execute("INSERT INTO produit (id,prix_achat,prix_vente,quantite) VALUES (1,0.10,0.20,10)");
                    execute("INSERT INTO achat (id,montant_total,montant_verse) VALUES (1,0.30,NULL)");
                    execute("INSERT INTO vente (id,montant_total,montant_verse) VALUES (1,0.50,0.20)");
                    execute("INSERT INTO detail_achat (id,prix_achat_unitaire,quantite) VALUES (1,0.10,3)");
                    execute("INSERT INTO detail_vente (id,prix_unitaire,quantite) VALUES (1,0.20,1)");
                    execute("INSERT INTO mouvement_caisse (montant) VALUES (0.10),(0.20),(-0.05)");
                    switch (name) {
                        case "bruit" -> execute("UPDATE produit SET prix_achat=0.30000000000000004");
                        case "decimales" -> execute("UPDATE produit SET prix_achat=0.001");
                        case "negatif" -> execute("UPDATE produit SET prix_achat=-1");
                        case "plafond" -> execute("UPDATE produit SET prix_achat=1000000000000000");
                        case "surpaiement" -> execute("UPDATE achat SET montant_verse=0.40");
                        case "prixnull" -> execute("UPDATE detail_vente SET prix_unitaire=NULL");
                        case "backup" -> execute("CREATE TABLE money_backup_20261005_produit LIKE produit");
                    }
                    String originalMode = scalar("SELECT @@SESSION.sql_mode");
                    boolean valid = name.equals("valide") || name.equals("bruit");
                    try {
                        executeScript(migration);
                        if (!valid) throw new AssertionError("Une migration invalide a été acceptée : " + name);
                    } catch (SQLException error) {
                        if (valid || !error.getSQLState().equals("45000")) throw error;
                    }
                    if (!scalar("SELECT @@SESSION.sql_mode").equals(originalMode)) throw new AssertionError("SQL mode non restauré");
                    int decimals = Integer.parseInt(scalar("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND DATA_TYPE='decimal' AND NUMERIC_PRECISION=17 AND NUMERIC_SCALE=2"));
                    if (decimals != (valid ? 9 : 0)) throw new AssertionError("Conversion partielle non attendue " + name);
                    if (valid) {
                        if (Integer.parseInt(scalar("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE 'money_backup_20261005_%'")) != 6) throw new AssertionError("Sauvegardes manquantes");
                        if (new BigDecimal(scalar("SELECT SUM(montant) FROM mouvement_caisse")).compareTo(new BigDecimal("0.25")) != 0) throw new AssertionError("Caisse incorrecte");
                        if (new BigDecimal(scalar("SELECT prix_achat FROM produit")).compareTo(new BigDecimal(name.equals("bruit") ? "0.30" : "0.10")) != 0) throw new AssertionError("Prix incorrect");
                        if (!scalar("SELECT montant_verse IS NULL FROM achat").equals("1")) throw new AssertionError("NULL réécrit");
                        if (!scalar("SELECT quantite FROM produit").equals("10")) throw new AssertionError("Stock modifié");
                        for (String table : tables) {
                            if (!scalar("SELECT COUNT(*) FROM " + table).equals(scalar("SELECT COUNT(*) FROM money_backup_20261005_" + table))) throw new AssertionError("Nombre de lignes modifié");
                        }
                        executeScript(migration); // une base déjà migrée ne recrée pas les sauvegardes
                        if (Integer.parseInt(scalar("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE 'money_backup_20261005_%' AND DATA_TYPE='double'")) != 9) throw new AssertionError("Anciennes colonnes non conservées");
                    } else if (!name.equals("backup") && !scalar("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE 'money_backup_20261005_%'").equals("0")) {
                        throw new AssertionError("Sauvegarde créée avant validation complète");
                    }
                    passed++; System.out.println("OK migration " + name);
                } finally {
                    if (!database.startsWith("stock_money_test_")) throw new IllegalStateException("Base de test requise");
                    execute("DROP DATABASE " + database);
                }
            }
        }
        System.out.println(passed + " scénarios réussis ; bases temporaires supprimées.");
    }
    static void execute(String sql) throws SQLException { try (var s=connection.createStatement()) { s.execute(sql); } }
    static String scalar(String sql) throws SQLException { try (var s=connection.createStatement(); var r=s.executeQuery(sql)) { r.next(); return r.getString(1); } }
    static void executeScript(String script) throws SQLException {
        String delimiter = ";"; StringBuilder sql = new StringBuilder();
        for (String line : script.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("DELIMITER ")) { delimiter=trimmed.substring(10); continue; }
            if (trimmed.startsWith("--") || trimmed.isBlank()) continue;
            sql.append(line).append('\n');
            if (trimmed.endsWith(delimiter)) {
                String statement=sql.toString().stripTrailing();
                execute(statement.substring(0,statement.length()-delimiter.length())); sql.setLength(0);
            }
        }
        if (!sql.toString().isBlank()) throw new IllegalArgumentException("SQL incomplet");
    }
    static String resolve(String value) {
        if (!value.startsWith("${")) return value;
        String expression=value.substring(2,value.length()-1); int colon=expression.indexOf(':');
        return System.getenv().getOrDefault(expression.substring(0,colon),expression.substring(colon+1));
    }
}
