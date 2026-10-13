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
    email VARCHAR(254) NULL,
    date_creation DATETIME(6) NOT NULL,
    essai_debut DATETIME(6) NULL,
    essai_fin DATETIME(6) NULL,
    abonnement_debut DATETIME(6) NULL,
    abonnement_fin DATETIME(6) NULL,
    actif BOOLEAN NOT NULL DEFAULT TRUE,
    acces_offert BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id)
);

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
    quantite BIGINT,
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
    PRIMARY KEY (id),
    CONSTRAINT fk_achat_boutique
        FOREIGN KEY (boutique_id) REFERENCES boutique (id),
    CONSTRAINT fk_achat_fournisseur
        FOREIGN KEY (fournisseur_id) REFERENCES fournisseur (id)
);

CREATE TABLE IF NOT EXISTS vente (
    id BIGINT NOT NULL AUTO_INCREMENT,
    boutique_id BIGINT NOT NULL,
    date_vente DATETIME(6),
    montant_total DECIMAL(17,2),
    montant_verse DECIMAL(17,2),
    client_id BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT fk_vente_boutique
        FOREIGN KEY (boutique_id) REFERENCES boutique (id),
    CONSTRAINT fk_vente_client
        FOREIGN KEY (client_id) REFERENCES client (id)
);

CREATE TABLE IF NOT EXISTS detail_achat (
    id BIGINT NOT NULL AUTO_INCREMENT,
    achat_id BIGINT,
    produit_id BIGINT,
    quantite INT,
    prix_achat_unitaire DECIMAL(17,2),
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
    PRIMARY KEY (id),
    CONSTRAINT fk_detail_vente_vente
        FOREIGN KEY (vente_id) REFERENCES vente (id),
    CONSTRAINT fk_detail_vente_produit
        FOREIGN KEY (produit_id) REFERENCES produit (id)
);

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
    PRIMARY KEY (id),
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
