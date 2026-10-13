package org.example.stock.migration;

import org.example.stock.config.TrialProperties;
import org.example.stock.enums.Role;
import org.example.stock.form.*;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.example.stock.service.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Schéma supprimable contrôlé ; aucune connexion à la base utilisateur stock_pro. */
public final class InventaireMariaDbTest {
    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        try {
            Properties p=new Properties();try(var r=Files.newBufferedReader(Path.of("src/main/resources/application.properties"))){p.load(r);}
            String serveur=InscriptionEssaiMariaDbTest.urlServeurTest(AccountEmailMigration.resolve(p.getProperty("spring.datasource.url")),System.getenv("INVENTAIRE_TEST_DB_URL"));
            String user=AccountEmailMigration.resolve(p.getProperty("spring.datasource.username")),pass=AccountEmailMigration.resolve(p.getProperty("spring.datasource.password"));
            String base="stock_inventaire_test_"+Long.toUnsignedString(System.nanoTime(),36);verifierBase(base);
            try(var c=DriverManager.getConnection(serveur,user,pass)) {
                sql(c,"CREATE DATABASE "+base+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                try {
                    c.setCatalog(base);
                    String schema=Files.readString(Path.of("docs/database-schema.sql")).replaceAll("(?m)^--[^\\r\\n]*","");
                    for(String statement:schema.split(";")){statement=statement.strip();if(!statement.isEmpty()&&!statement.startsWith("CREATE DATABASE")&&!statement.startsWith("USE ")&&!statement.startsWith("CREATE TABLE IF NOT EXISTS mouvement_stock"))sql(c,statement);}
                    var avant=InventaireMigration.photographie(c);InventaireMigration.migrer(c,false);exiger(!BoutiquesMigration.tables(c).containsKey("mouvement_stock"),"Check a modifié le schéma");
                    InventaireMigration.migrer(c,true);InventaireMigration.migrer(c,true);exiger(avant.equals(InventaireMigration.photographie(c)),"Migration a modifié les anciennes données");
                    int query=serveur.indexOf('?');String url=query<0?serveur+base:serveur.substring(0,query)+base+serveur.substring(query);
                    var source=new DriverManagerDataSource(url,user,pass);source.setDriverClassName("org.mariadb.jdbc.Driver");
                    try(var contexte=new AnnotationConfigApplicationContext()) {
                        contexte.getEnvironment().setActiveProfiles("abonnement-mariadb-service-test","inventaire-mariadb-service-test");
                        contexte.registerBean(DataSource.class,()->source);contexte.register(AbonnementServiceMariaDbTest.ConfigurationJpa.class,Services.class);contexte.refresh();
                        verifierServices(contexte);
                    }finally{SecurityContextHolder.clearContext();}
                    InventaireMigration.migrer(c,false);
                    System.out.println("MariaDB : migration relançable, initialisation et caisse concurrentes, ajustement et conservation de la caisse validés.");
                }finally{c.setCatalog(null);verifierBase(base);sql(c,"DROP DATABASE "+base);System.out.println("Base temporaire inventaire supprimée.");}
            }
        }catch(Exception e){System.err.println("Vérification MariaDB inventaire interrompue : "+(e instanceof IllegalStateException?e.getMessage():"opération impossible ; aucune donnée privée publiée."));System.exit(1);}
    }
    private static void verifierServices(AnnotationConfigApplicationContext contexte)throws Exception {
        var boutiques=contexte.getBean(BoutiqueRepository.class);var utilisateurs=contexte.getBean(UtilisateurRepository.class);var categories=contexte.getBean(CategorieRepository.class);
        var produits=contexte.getBean(ProduitRepository.class);var service=contexte.getBean(ProduitService.class);var inventaire=contexte.getBean(InventaireService.class);var caisse=contexte.getBean(CaisseService.class);
        var boutique=new Boutique();boutique.setNom("Test MariaDB");boutique=boutiques.saveAndFlush(boutique);
        for(String email:List.of("premier@inventaire.test","second@inventaire.test")){var acteur=new Utilisateur();acteur.setBoutique(boutique);acteur.setNom("Gérant de test");acteur.setEmail(email);acteur.setRole(Role.ADMIN);utilisateurs.saveAndFlush(acteur);}
        var cat=new Categorie();cat.setBoutique(boutique);cat.setNom("Test");cat=categories.saveAndFlush(cat);
        connecter("premier@inventaire.test");var produit=new Produit();produit.setNom("Test");produit.setReference("TEST");produit.setCategorie(cat);produit.setPrixVente(BigDecimal.TEN);produit=service.ajouterProduit(produit);
        Long id=produit.getId();var initial=new StockInitialForm();initial.setVersion(produit.getVersion());initial.setQuantite(10L);initial.setCoutUnitaire(BigDecimal.TEN);initial.setMotif("Déjà détenu");
        exiger(concurrent(()->inventaire.initialiser(id,initial))==1,"Deux stocks initiaux ont été acceptés");
        connecter("premier@inventaire.test");exiger(produits.findById(id).orElseThrow().getQuantite()==10,"Stock incorrect");
        exiger(inventaire.consulter(id,0).historique().size()==1&&caisse.getSoldeActuel().signum()==0,"Stock initial non atomique ou dépense fictive");
        var correction=new AjustementStockForm();correction.setVersion(produits.findById(id).orElseThrow().getVersion());correction.setQuantite(8L);correction.setMotif("Comptage");inventaire.ajuster(id,correction);
        exiger(produits.findById(id).orElseThrow().getQuantite()==8&&inventaire.consulter(id,0).historique().size()==2,"Correction non enregistrée");
        var ouverture=new OuvertureCaisseForm();ouverture.setMontant(BigDecimal.ZERO);
        exiger(concurrent(()->caisse.initialiser(ouverture))==1,"Deux ouvertures à zéro ont été acceptées");
        connecter("premier@inventaire.test");exiger(caisse.listerTous().size()==1&&!caisse.peutInitialiser(),"Ouverture répétée");
    }
    private static int concurrent(Runnable action)throws Exception {
        var barrier=new CyclicBarrier(2);List<Throwable> erreurs=new CopyOnWriteArrayList<>();
        try(var pool=Executors.newFixedThreadPool(2)){List<Future<?>> futures=new ArrayList<>();for(String email:List.of("premier@inventaire.test","second@inventaire.test"))futures.add(pool.submit(()->{connecter(email);try{barrier.await(5,TimeUnit.SECONDS);action.run();}catch(Throwable e){erreurs.add(e);}finally{SecurityContextHolder.clearContext();}}));for(var f:futures)f.get(15,TimeUnit.SECONDS);}
        if(erreurs.stream().anyMatch(e->!(e instanceof IllegalArgumentException)&&!(e instanceof ProduitModifieException)))throw new IllegalStateException("Concurrence : erreur inattendue");return erreurs.size();
    }
    private static void connecter(String email){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(email,"unused",List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));}
    private static void verifierBase(String base){exiger(base.matches("stock_inventaire_test_[a-z0-9]+")&&base.length()<64,"Base temporaire inattendue");}
    private static void exiger(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private static void sql(Connection c,String sql)throws SQLException{try(var s=c.createStatement()){s.execute(sql);}}
    @Configuration(proxyBeanMethods=false) @Profile("inventaire-mariadb-service-test")
    @Import({InventaireService.class,ProduitService.class,BoutiqueService.class,EssaiBoutiqueService.class,StockLockRepository.class,CaisseService.class,TrialProperties.class})
    static class Services {}
}
