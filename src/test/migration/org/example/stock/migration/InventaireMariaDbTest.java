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
                    TarificationMigration.migrer(c,true); // Le service actuel conserve aussi le prix de vente du stock initial.
                    int query=serveur.indexOf('?');String url=query<0?serveur+base:serveur.substring(0,query)+base+serveur.substring(query);
                    var source=new DriverManagerDataSource(url,user,pass);source.setDriverClassName("org.mariadb.jdbc.Driver");
                    try(var contexte=new AnnotationConfigApplicationContext()) {
                        contexte.getEnvironment().setActiveProfiles("abonnement-mariadb-service-test","inventaire-mariadb-service-test");
                        contexte.registerBean(DataSource.class,()->source);contexte.register(AbonnementServiceMariaDbTest.ConfigurationJpa.class,Services.class);contexte.refresh();
                        verifierServices(contexte);
                    }finally{SecurityContextHolder.clearContext();}
                    InventaireMigration.migrer(c,false);
                    System.out.println("MariaDB : migrations relançables, stock et caisse initiaux concurrents, refus des corrections et fermeture après achat à crédit validés.");
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
        Long id=produit.getId();var initial=new StockInitialForm();initial.setVersion(produit.getVersion());initial.setQuantite(10L);initial.setCoutUnitaire(BigDecimal.TEN);initial.setPrixVente(new BigDecimal("15.00"));initial.setMotif("Déjà détenu");
        exiger(concurrent(()->inventaire.initialiser(id,initial))==1,"Deux stocks initiaux ont été acceptés");
        connecter("premier@inventaire.test");exiger(produits.findById(id).orElseThrow().getQuantite()==10,"Stock incorrect");
        exiger(inventaire.consulter(id,0).historique().size()==1&&caisse.getSoldeActuel().signum()==0,"Stock initial non atomique ou dépense fictive");
        var correction=new AjustementStockForm();correction.setVersion(produits.findById(id).orElseThrow().getVersion());correction.setQuantite(8L);correction.setMotif("Comptage");
        boolean refuse=false;try{inventaire.ajuster(id,correction);}catch(IllegalArgumentException e){refuse=true;}
        exiger(refuse&&produits.findById(id).orElseThrow().getQuantite()==10&&inventaire.consulter(id,0).historique().size()==1,"Une correction manuelle est encore possible");
        var ouverture=new OuvertureCaisseForm();ouverture.setMontant(BigDecimal.ZERO);
        exiger(concurrent(()->caisse.initialiser(ouverture))==1,"Deux ouvertures à zéro ont été acceptées");
        connecter("premier@inventaire.test");exiger(caisse.listerTous().size()==1&&!caisse.peutInitialiser(),"Ouverture répétée");
        var fournisseur=new Fournisseur();fournisseur.setBoutique(boutique);fournisseur.setNom("Fournisseur factice");fournisseur=contexte.getBean(FournisseurRepository.class).saveAndFlush(fournisseur);
        var achat=new Achat();achat.setFournisseur(fournisseur);achat.setMontantVerse(BigDecimal.ZERO);
        var ligne=new DetailAchat();ligne.setProduit(produits.findById(id).orElseThrow());ligne.setQuantite(1);ligne.setPrixAchatUnitaire(BigDecimal.TEN);ligne.setPrixVenteUnitaire(new BigDecimal("15.00"));achat.setLignes(new ArrayList<>(List.of(ligne)));
        var parallele=new Produit();parallele.setNom("Préparation parallèle");parallele.setReference("PARALLELE");parallele.setCategorie(cat);parallele=service.ajouterProduit(parallele);Long paralleleId=parallele.getId();
        var stockParallele=new StockInitialForm();stockParallele.setVersion(parallele.getVersion());stockParallele.setQuantite(2L);stockParallele.setCoutUnitaire(BigDecimal.TEN);stockParallele.setPrixVente(new BigDecimal("15.00"));stockParallele.setMotif("Déjà détenu avant la première vente");
        var resultats=concurrentDifferent(()->contexte.getBean(AchatService.class).enregistrerAchat(achat),()->inventaire.initialiser(paralleleId,stockParallele));
        connecter("premier@inventaire.test");
        exiger(resultats.getFirst()&&produits.findById(id).orElseThrow().getQuantite()==11,"Le premier achat concurrent n'a pas été enregistré");
        exiger(produits.findById(paralleleId).orElseThrow().getQuantite()==(resultats.get(1)?2:0),"Stock initial concurrent non atomique");
        exiger(boutiques.findById(boutique.getId()).orElseThrow().isInitialisationTerminee(),"L'achat à crédit n'a pas fermé le démarrage");
        exiger(caisse.listerTous().size()==1&&caisse.getSoldeActuel().signum()==0,"L'achat à crédit a inventé un versement");
        var nouveau=new Produit();nouveau.setNom("Créé après démarrage");nouveau.setReference("APRES");nouveau.setCategorie(cat);nouveau=service.ajouterProduit(nouveau);
        exiger(nouveau.getQuantite()==0&&!nouveau.isPrixDefinis(),"Une fiche à zéro ne peut plus être créée");
        var autreInitial=new StockInitialForm();autreInitial.setVersion(nouveau.getVersion());autreInitial.setQuantite(2L);autreInitial.setCoutUnitaire(BigDecimal.TEN);autreInitial.setPrixVente(new BigDecimal("15.00"));autreInitial.setMotif("Essai après fermeture");
        refuse=false;try{inventaire.initialiser(nouveau.getId(),autreInitial);}catch(IllegalArgumentException e){refuse=true;}
        exiger(refuse&&produits.findById(nouveau.getId()).orElseThrow().getQuantite()==0,"Un produit créé ensuite a reçu un stock initial");
    }
    private static int concurrent(Runnable action)throws Exception {
        var barrier=new CyclicBarrier(2);List<Throwable> erreurs=new CopyOnWriteArrayList<>();
        try(var pool=Executors.newFixedThreadPool(2)){List<Future<?>> futures=new ArrayList<>();for(String email:List.of("premier@inventaire.test","second@inventaire.test"))futures.add(pool.submit(()->{connecter(email);try{barrier.await(5,TimeUnit.SECONDS);action.run();}catch(Throwable e){erreurs.add(e);}finally{SecurityContextHolder.clearContext();}}));for(var f:futures)f.get(15,TimeUnit.SECONDS);}
        if(erreurs.stream().anyMatch(e->!(e instanceof IllegalArgumentException)&&!(e instanceof ProduitModifieException)))throw new IllegalStateException("Concurrence : erreur inattendue");return erreurs.size();
    }
    private static List<Boolean> concurrentDifferent(Runnable achat,Runnable initial)throws Exception {
        var barrier=new CyclicBarrier(2);var actions=List.of(achat,initial);var emails=List.of("premier@inventaire.test","second@inventaire.test");
        try(var pool=Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> resultats=new ArrayList<>();
            for(int index=0;index<2;index++){final int numero=index;resultats.add(pool.submit(()->{connecter(emails.get(numero));try{barrier.await(5,TimeUnit.SECONDS);actions.get(numero).run();return true;}catch(IllegalArgumentException e){return false;}finally{SecurityContextHolder.clearContext();}}));}
            return List.of(resultats.get(0).get(15,TimeUnit.SECONDS),resultats.get(1).get(15,TimeUnit.SECONDS));
        }
    }
    private static void connecter(String email){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(email,"unused",List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));}
    private static void verifierBase(String base){exiger(base.matches("stock_inventaire_test_[a-z0-9]+")&&base.length()<64,"Base temporaire inattendue");}
    private static void exiger(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private static void sql(Connection c,String sql)throws SQLException{try(var s=c.createStatement()){s.execute(sql);}}
    @Configuration(proxyBeanMethods=false) @Profile("inventaire-mariadb-service-test")
    @Import({InventaireService.class,ProduitService.class,BoutiqueService.class,EssaiBoutiqueService.class,StockLockRepository.class,CaisseService.class,TrialProperties.class,InitialisationBoutiqueService.class,AchatService.class})
    static class Services {}
}
