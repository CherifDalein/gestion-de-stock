-- Création d'une base neuve uniquement : ne pas utiliser ce script pour migrer des tables existantes.
-- Pour une ancienne base, utiliser boutiquesCheck puis boutiquesMigrate après sauvegarde.
CREATE DATABASE IF NOT EXISTS stock_pro
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE stock_pro;

CREATE TABLE IF NOT EXISTS boutique (
    id BIGINT NOT NULL AUTO_INCREMENT,
    nom VARCHAR(255) NOT NULL,
    adresse VARCHAR(500) NULL,
    telephone VARCHAR(50) NULL,
    telephone_secondaire VARCHAR(50) NULL,
    email VARCHAR(254) NULL,
    date_creation DATETIME(6) NOT NULL,
    essai_debut DATETIME(6) NULL,
    essai_fin DATETIME(6) NULL,
    abonnement_debut DATETIME(6) NULL,
    abonnement_fin DATETIME(6) NULL,
    actif BOOLEAN NOT NULL DEFAULT TRUE,
    acces_offert BOOLEAN NOT NULL DEFAULT FALSE,
    initialisation_terminee BOOLEAN NOT NULL DEFAULT FALSE,
    delai_clients_jours INT NOT NULL DEFAULT 30,
    delai_fournisseurs_jours INT NOT NULL DEFAULT 30,
    prochain_numero_vente BIGINT NOT NULL DEFAULT 1,
    prochain_numero_achat BIGINT NOT NULL DEFAULT 1,
    prochain_numero_devis BIGINT NOT NULL DEFAULT 1,
    prochain_numero_retour_vente BIGINT NOT NULL DEFAULT 1,
    prochain_numero_retour_achat BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS logo_boutique (
    boutique_id BIGINT NOT NULL,
    contenu LONGBLOB NOT NULL,
    revision VARCHAR(36) NOT NULL,
    PRIMARY KEY (boutique_id),
    CONSTRAINT fk_logo_boutique
        FOREIGN KEY (boutique_id) REFERENCES boutique (id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS categorie (
    id BIGINT NOT NULL AUTO_INCREMENT,
    boutique_id BIGINT NOT NULL,
    nom VARCHAR(255),
    PRIMARY KEY (id),
    CONSTRAINT fk_categorie_boutique
        FOREIGN KEY (boutique_id) REFERENCES boutique (id),
    CONSTRAINT uk_categorie_boutique_nom UNIQUE (boutique_id, nom)
);

CREATE TABLE IF NOT EXISTS client (
    id BIGINT NOT NULL AUTO_INCREMENT,
    boutique_id BIGINT NOT NULL,
    nom VARCHAR(255),
    telephone VARCHAR(255),
    email VARCHAR(255),
    adresse VARCHAR(255),
    solde_initial DECIMAL(17,2) NOT NULL DEFAULT 0,
    echeance_solde_initial DATE NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_client_boutique
        FOREIGN KEY (boutique_id) REFERENCES boutique (id)
);

CREATE TABLE IF NOT EXISTS fournisseur (
    id BIGINT NOT NULL AUTO_INCREMENT,
    boutique_id BIGINT NOT NULL,
    nom VARCHAR(255),
    telephone VARCHAR(255),
    email VARCHAR(255),
    adresse VARCHAR(255),
    solde_initial DECIMAL(17,2) NOT NULL DEFAULT 0,
    echeance_solde_initial DATE NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_fournisseur_boutique
        FOREIGN KEY (boutique_id) REFERENCES boutique (id)
);

CREATE TABLE IF NOT EXISTS utilisateur (
    id BIGINT NOT NULL AUTO_INCREMENT,
    boutique_id BIGINT NOT NULL,
    nom VARCHAR(255),
    email VARCHAR(255) NOT NULL,
    mot_de_passe VARCHAR(255),
    date_inscription DATE,
    role ENUM('ADMIN', 'CAISSIER'),
    administrateur_plateforme BOOLEAN NOT NULL DEFAULT FALSE,
    actif BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    CONSTRAINT fk_utilisateur_boutique
        FOREIGN KEY (boutique_id) REFERENCES boutique (id),
    CONSTRAINT uk_utilisateur_email UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS produit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    boutique_id BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    nom VARCHAR(255),
    reference VARCHAR(255),
    prix_achat DECIMAL(17,2) NOT NULL,
    prix_vente DECIMAL(17,2) NOT NULL,
    prix_definis BOOLEAN NOT NULL DEFAULT TRUE,
    quantite BIGINT,
    nature VARCHAR(16) NOT NULL DEFAULT 'STOCK',
    unite VARCHAR(30) NULL,
    seuil_alerte BIGINT NOT NULL DEFAULT 5,
    cout_moyen DECIMAL(17,2) NOT NULL DEFAULT 0,
    categorie_id BIGINT,
    fournisseur_id BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT fk_produit_boutique
        FOREIGN KEY (boutique_id) REFERENCES boutique (id),
    CONSTRAINT fk_produit_categorie
        FOREIGN KEY (categorie_id) REFERENCES categorie (id),
    CONSTRAINT fk_produit_fournisseur
        FOREIGN KEY (fournisseur_id) REFERENCES fournisseur (id)
);

CREATE TABLE IF NOT EXISTS achat (
    id BIGINT NOT NULL AUTO_INCREMENT,
    boutique_id BIGINT NOT NULL,
    date_achat DATETIME(6),
    montant_total DECIMAL(17,2),
    montant_verse DECIMAL(17,2),
    fournisseur_id BIGINT,
    numero VARCHAR(30) NULL,
    date_echeance DATE NULL,
    avance_utilisee DECIMAL(17,2) NOT NULL DEFAULT 0,
    montant_retours DECIMAL(17,2) NOT NULL DEFAULT 0,
    utilisateur_id BIGINT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_achat_boutique_numero UNIQUE (boutique_id, numero),
    CONSTRAINT fk_achat_boutique
        FOREIGN KEY (boutique_id) REFERENCES boutique (id),
    CONSTRAINT fk_achat_fournisseur
        FOREIGN KEY (fournisseur_id) REFERENCES fournisseur (id),
    CONSTRAINT fk_achat_utilisateur
        FOREIGN KEY (utilisateur_id) REFERENCES utilisateur (id)
);

CREATE TABLE IF NOT EXISTS vente (
    id BIGINT NOT NULL AUTO_INCREMENT,
    boutique_id BIGINT NOT NULL,
    date_vente DATETIME(6),
    montant_total DECIMAL(17,2),
    montant_verse DECIMAL(17,2),
    client_id BIGINT,
    numero VARCHAR(30) NULL,
    date_echeance DATE NULL,
    avance_utilisee DECIMAL(17,2) NOT NULL DEFAULT 0,
    montant_retours DECIMAL(17,2) NOT NULL DEFAULT 0,
    vendeur_id BIGINT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_vente_boutique_numero UNIQUE (boutique_id, numero),
    CONSTRAINT fk_vente_boutique
        FOREIGN KEY (boutique_id) REFERENCES boutique (id),
    CONSTRAINT fk_vente_client
        FOREIGN KEY (client_id) REFERENCES client (id),
    CONSTRAINT fk_vente_vendeur
        FOREIGN KEY (vendeur_id) REFERENCES utilisateur (id)
);

CREATE TABLE IF NOT EXISTS detail_achat (
    id BIGINT NOT NULL AUTO_INCREMENT,
    achat_id BIGINT,
    produit_id BIGINT,
    quantite INT,
    prix_achat_unitaire DECIMAL(17,2),
    prix_vente_unitaire DECIMAL(17,2) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_detail_achat_achat
        FOREIGN KEY (achat_id) REFERENCES achat (id),
    CONSTRAINT fk_detail_achat_produit
        FOREIGN KEY (produit_id) REFERENCES produit (id)
);

CREATE TABLE IF NOT EXISTS detail_vente (
    id BIGINT NOT NULL AUTO_INCREMENT,
    vente_id BIGINT,
    produit_id BIGINT,
    quantite INT,
    prix_unitaire DECIMAL(17,2),
    cout_unitaire DECIMAL(17,2) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_detail_vente_vente
        FOREIGN KEY (vente_id) REFERENCES vente (id),
    CONSTRAINT fk_detail_vente_produit
        FOREIGN KEY (produit_id) REFERENCES produit (id)
);

CREATE TABLE IF NOT EXISTS compte_tresorerie_boutique (
    id BIGINT NOT NULL AUTO_INCREMENT,
    boutique_id BIGINT NOT NULL,
    compte VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_compte_tresorerie_boutique UNIQUE (boutique_id, compte),
    CONSTRAINT fk_compte_tresorerie_boutique
        FOREIGN KEY (boutique_id) REFERENCES boutique (id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS operation_tresorerie (
    id BIGINT NOT NULL AUTO_INCREMENT,
    boutique_id BIGINT NOT NULL,
    utilisateur_id BIGINT NOT NULL,
    jeton VARCHAR(36) NOT NULL,
    type VARCHAR(32) NOT NULL,
    compte_source VARCHAR(32) NOT NULL,
    compte_destination VARCHAR(32) NULL,
    montant DECIMAL(17,2) NOT NULL,
    motif VARCHAR(255) NOT NULL,
    date_operation DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_operation_tresorerie_jeton UNIQUE (jeton),
    CONSTRAINT fk_operation_tresorerie_boutique
        FOREIGN KEY (boutique_id) REFERENCES boutique (id),
    CONSTRAINT fk_operation_tresorerie_utilisateur
        FOREIGN KEY (utilisateur_id) REFERENCES utilisateur (id),
    INDEX idx_operation_tresorerie_boutique_date (boutique_id, date_operation, id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Lot 2 : devis et retours avec leurs lignes. Aucune ligne n'est créée par ce script.
CREATE TABLE IF NOT EXISTS devis (
    id BIGINT NOT NULL AUTO_INCREMENT,
    boutique_id BIGINT NOT NULL,
    numero VARCHAR(30) NOT NULL,
    client_id BIGINT NULL,
    utilisateur_id BIGINT NULL,
    date_devis DATETIME(6) NOT NULL,
    date_validite DATE NOT NULL,
    montant_total DECIMAL(17,2) NOT NULL,
    statut VARCHAR(16) NOT NULL,
    vente_id BIGINT NULL,
    note VARCHAR(255) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_devis_boutique_numero UNIQUE (boutique_id, numero),
    CONSTRAINT uk_devis_vente UNIQUE (vente_id),
    CONSTRAINT fk_devis_boutique
        FOREIGN KEY (boutique_id) REFERENCES boutique (id),
    CONSTRAINT fk_devis_client
        FOREIGN KEY (client_id) REFERENCES client (id),
    CONSTRAINT fk_devis_utilisateur
        FOREIGN KEY (utilisateur_id) REFERENCES utilisateur (id),
    CONSTRAINT fk_devis_vente
        FOREIGN KEY (vente_id) REFERENCES vente (id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS detail_devis (
    id BIGINT NOT NULL AUTO_INCREMENT,
    devis_id BIGINT NOT NULL,
    produit_id BIGINT NOT NULL,
    quantite INT NOT NULL,
    prix_unitaire DECIMAL(17,2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_detail_devis_devis
        FOREIGN KEY (devis_id) REFERENCES devis (id),
    CONSTRAINT fk_detail_devis_produit
        FOREIGN KEY (produit_id) REFERENCES produit (id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS retour (
    id BIGINT NOT NULL AUTO_INCREMENT,
    boutique_id BIGINT NOT NULL,
    numero VARCHAR(30) NOT NULL,
    type VARCHAR(16) NOT NULL,
    vente_id BIGINT NULL,
    achat_id BIGINT NULL,
    client_id BIGINT NULL,
    fournisseur_id BIGINT NULL,
    utilisateur_id BIGINT NULL,
    date_retour DATETIME(6) NOT NULL,
    montant_total DECIMAL(17,2) NOT NULL,
    montant_rembourse DECIMAL(17,2) NOT NULL DEFAULT 0,
    compte_tresorerie VARCHAR(16) NULL,
    montant_avoir DECIMAL(17,2) NOT NULL DEFAULT 0,
    motif VARCHAR(255) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_retour_boutique_numero UNIQUE (boutique_id, numero),
    CONSTRAINT fk_retour_boutique
        FOREIGN KEY (boutique_id) REFERENCES boutique (id),
    CONSTRAINT fk_retour_vente
        FOREIGN KEY (vente_id) REFERENCES vente (id),
    CONSTRAINT fk_retour_achat
        FOREIGN KEY (achat_id) REFERENCES achat (id),
    CONSTRAINT fk_retour_client
        FOREIGN KEY (client_id) REFERENCES client (id),
    CONSTRAINT fk_retour_fournisseur
        FOREIGN KEY (fournisseur_id) REFERENCES fournisseur (id),
    CONSTRAINT fk_retour_utilisateur
        FOREIGN KEY (utilisateur_id) REFERENCES utilisateur (id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS detail_retour (
    id BIGINT NOT NULL AUTO_INCREMENT,
    retour_id BIGINT NOT NULL,
    produit_id BIGINT NOT NULL,
    detail_vente_id BIGINT NULL,
    detail_achat_id BIGINT NULL,
    quantite INT NOT NULL,
    prix_unitaire DECIMAL(17,2) NOT NULL,
    cout_unitaire DECIMAL(17,2) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_detail_retour_retour
        FOREIGN KEY (retour_id) REFERENCES retour (id),
    CONSTRAINT fk_detail_retour_produit
        FOREIGN KEY (produit_id) REFERENCES produit (id),
    CONSTRAINT fk_detail_retour_detail_vente
        FOREIGN KEY (detail_vente_id) REFERENCES detail_vente (id),
    CONSTRAINT fk_detail_retour_detail_achat
        FOREIGN KEY (detail_achat_id) REFERENCES detail_achat (id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS mouvement_caisse (
    id BIGINT NOT NULL AUTO_INCREMENT,
    boutique_id BIGINT NOT NULL,
    date_mouvement DATETIME(6),
    montant DECIMAL(17,2),
    type VARCHAR(255),
    motif VARCHAR(255),
    source VARCHAR(255),
    utilisateur_id BIGINT,
    achat_id BIGINT,
    vente_id BIGINT,
    compte VARCHAR(32) NOT NULL DEFAULT 'CAISSE',
    operation_tresorerie_id BIGINT NULL,
    client_id BIGINT NULL,
    fournisseur_id BIGINT NULL,
    retour_id BIGINT NULL,
    annulation_de_id BIGINT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_mouvement_caisse_annulation UNIQUE (annulation_de_id),
    CONSTRAINT fk_mouvement_caisse_retour
        FOREIGN KEY (retour_id) REFERENCES retour (id),
    CONSTRAINT fk_mouvement_caisse_annulation
        FOREIGN KEY (annulation_de_id) REFERENCES mouvement_caisse (id),
    CONSTRAINT fk_mouvement_caisse_client
        FOREIGN KEY (client_id) REFERENCES client (id),
    CONSTRAINT fk_mouvement_caisse_fournisseur
        FOREIGN KEY (fournisseur_id) REFERENCES fournisseur (id),
    CONSTRAINT fk_mouvement_operation_tresorerie
        FOREIGN KEY (operation_tresorerie_id) REFERENCES operation_tresorerie (id),
    CONSTRAINT fk_mouvement_caisse_boutique
        FOREIGN KEY (boutique_id) REFERENCES boutique (id),
    CONSTRAINT fk_mouvement_caisse_utilisateur
        FOREIGN KEY (utilisateur_id) REFERENCES utilisateur (id),
    CONSTRAINT fk_mouvement_caisse_achat
        FOREIGN KEY (achat_id) REFERENCES achat (id),
    CONSTRAINT fk_mouvement_caisse_vente
        FOREIGN KEY (vente_id) REFERENCES vente (id)
);

CREATE TABLE IF NOT EXISTS operation_creation (
    jeton VARCHAR(36) NOT NULL,
    boutique_id BIGINT NOT NULL,
    utilisateur_id BIGINT NOT NULL,
    type ENUM('ACHAT', 'VENTE') NOT NULL,
    date_creation DATETIME(6) NOT NULL,
    empreinte VARCHAR(64),
    achat_id BIGINT,
    vente_id BIGINT,
    PRIMARY KEY (jeton),
    CONSTRAINT fk_operation_creation_boutique
        FOREIGN KEY (boutique_id) REFERENCES boutique (id),
    CONSTRAINT uk_operation_creation_achat UNIQUE (achat_id),
    CONSTRAINT uk_operation_creation_vente UNIQUE (vente_id),
    CONSTRAINT fk_operation_creation_utilisateur FOREIGN KEY (utilisateur_id) REFERENCES utilisateur (id),
    CONSTRAINT fk_operation_creation_achat FOREIGN KEY (achat_id) REFERENCES achat (id),
    CONSTRAINT fk_operation_creation_vente FOREIGN KEY (vente_id) REFERENCES vente (id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS reinitialisation_mot_de_passe (
    utilisateur_id BIGINT NOT NULL,
    empreinte_jeton VARCHAR(64) NOT NULL,
    empreinte_mot_de_passe VARCHAR(64) NOT NULL,
    cree_le DATETIME(6) NOT NULL,
    expire_le DATETIME(6) NOT NULL,
    PRIMARY KEY (utilisateur_id),
    CONSTRAINT uk_reinitialisation_mdp_jeton UNIQUE (empreinte_jeton),
    CONSTRAINT fk_reinitialisation_mdp_utilisateur
        FOREIGN KEY (utilisateur_id) REFERENCES utilisateur (id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Une demande confirme seulement l'email ; aucune boutique ni aucun mot de passe n'existe encore.
CREATE TABLE IF NOT EXISTS inscription_boutique (
    email VARCHAR(254) NOT NULL,
    empreinte_jeton VARCHAR(64) NOT NULL,
    cree_le DATETIME(6) NOT NULL,
    expire_le DATETIME(6) NOT NULL,
    PRIMARY KEY (email),
    CONSTRAINT uk_inscription_boutique_jeton UNIQUE (empreinte_jeton)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Aucun INSERT : sans réglage enregistré, la configuration stock.trial.days fournit 30 jours.
CREATE TABLE IF NOT EXISTS parametres_plateforme (
    id INTEGER NOT NULL,
    duree_essai_jours INTEGER NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Journal des décisions administratives ; aucune écriture historique n'est inventée.
CREATE TABLE IF NOT EXISTS historique_abonnement (
    id BIGINT NOT NULL AUTO_INCREMENT,
    boutique_id BIGINT NOT NULL,
    acteur_id BIGINT NOT NULL,
    acteur_nom VARCHAR(255) NOT NULL,
    action VARCHAR(32) NOT NULL,
    cree_le DATETIME(6) NOT NULL,
    periode_debut DATETIME(6) NULL,
    periode_fin DATETIME(6) NULL,
    ancienne_fin DATETIME(6) NULL,
    motif VARCHAR(500) NULL,
    jeton_action VARCHAR(36) NOT NULL,
    quantite INTEGER NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_historique_abonnement_jeton UNIQUE (jeton_action),
    CONSTRAINT fk_historique_abonnement_boutique
        FOREIGN KEY (boutique_id) REFERENCES boutique (id),
    CONSTRAINT fk_historique_abonnement_acteur
        FOREIGN KEY (acteur_id) REFERENCES utilisateur (id),
    INDEX idx_historique_abonnement_boutique_date (boutique_id, cree_le, id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS mouvement_stock (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    boutique_id BIGINT NOT NULL,
    produit_id BIGINT NOT NULL,
    acteur_id BIGINT NOT NULL,
    acteur_nom VARCHAR(255) NOT NULL,
    type VARCHAR(16) NOT NULL,
    cree_le DATETIME(6) NOT NULL,
    quantite_avant BIGINT NOT NULL,
    quantite_apres BIGINT NOT NULL,
    cout_unitaire DECIMAL(17,2) NULL,
    prix_vente DECIMAL(17,2) NULL,
    motif VARCHAR(500) NOT NULL,
    CONSTRAINT fk_mouvement_stock_boutique FOREIGN KEY (boutique_id) REFERENCES boutique(id),
    CONSTRAINT fk_mouvement_stock_produit FOREIGN KEY (produit_id) REFERENCES produit(id),
    INDEX idx_mouvement_stock_produit_date (boutique_id,produit_id,cree_le,id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
