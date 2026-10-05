CREATE DATABASE IF NOT EXISTS stock_pro
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE stock_pro;

CREATE TABLE IF NOT EXISTS categorie (
    id BIGINT NOT NULL AUTO_INCREMENT,
    nom VARCHAR(255),
    PRIMARY KEY (id),
    CONSTRAINT uk_categorie_nom UNIQUE (nom)
);

CREATE TABLE IF NOT EXISTS client (
    id BIGINT NOT NULL AUTO_INCREMENT,
    nom VARCHAR(255),
    telephone VARCHAR(255),
    email VARCHAR(255),
    adresse VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS fournisseur (
    id BIGINT NOT NULL AUTO_INCREMENT,
    nom VARCHAR(255),
    telephone VARCHAR(255),
    email VARCHAR(255),
    adresse VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS utilisateur (
    id BIGINT NOT NULL AUTO_INCREMENT,
    nom VARCHAR(255),
    email VARCHAR(255) NOT NULL,
    mot_de_passe VARCHAR(255),
    date_inscription DATE,
    role ENUM('ADMIN', 'CAISSIER'),
    PRIMARY KEY (id),
    CONSTRAINT uk_utilisateur_email UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS produit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    version BIGINT NOT NULL DEFAULT 0,
    nom VARCHAR(255),
    reference VARCHAR(255),
    prix_achat DECIMAL(17,2) NOT NULL,
    prix_vente DECIMAL(17,2) NOT NULL,
    quantite BIGINT,
    categorie_id BIGINT,
    fournisseur_id BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT fk_produit_categorie
        FOREIGN KEY (categorie_id) REFERENCES categorie (id),
    CONSTRAINT fk_produit_fournisseur
        FOREIGN KEY (fournisseur_id) REFERENCES fournisseur (id)
);

CREATE TABLE IF NOT EXISTS achat (
    id BIGINT NOT NULL AUTO_INCREMENT,
    date_achat DATETIME(6),
    montant_total DECIMAL(17,2),
    montant_verse DECIMAL(17,2),
    fournisseur_id BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT fk_achat_fournisseur
        FOREIGN KEY (fournisseur_id) REFERENCES fournisseur (id)
);

CREATE TABLE IF NOT EXISTS vente (
    id BIGINT NOT NULL AUTO_INCREMENT,
    date_vente DATETIME(6),
    montant_total DECIMAL(17,2),
    montant_verse DECIMAL(17,2),
    client_id BIGINT,
    PRIMARY KEY (id),
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
    date_mouvement DATETIME(6),
    montant DECIMAL(17,2),
    type VARCHAR(255),
    motif VARCHAR(255),
    source VARCHAR(255),
    utilisateur_id BIGINT,
    achat_id BIGINT,
    vente_id BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT fk_mouvement_caisse_utilisateur
        FOREIGN KEY (utilisateur_id) REFERENCES utilisateur (id),
    CONSTRAINT fk_mouvement_caisse_achat
        FOREIGN KEY (achat_id) REFERENCES achat (id),
    CONSTRAINT fk_mouvement_caisse_vente
        FOREIGN KEY (vente_id) REFERENCES vente (id)
);

CREATE TABLE IF NOT EXISTS operation_creation (
    jeton VARCHAR(36) NOT NULL,
    utilisateur_id BIGINT NOT NULL,
    type ENUM('ACHAT', 'VENTE') NOT NULL,
    date_creation DATETIME(6) NOT NULL,
    empreinte VARCHAR(64),
    achat_id BIGINT,
    vente_id BIGINT,
    PRIMARY KEY (jeton),
    CONSTRAINT uk_operation_creation_achat UNIQUE (achat_id),
    CONSTRAINT uk_operation_creation_vente UNIQUE (vente_id),
    CONSTRAINT fk_operation_creation_utilisateur FOREIGN KEY (utilisateur_id) REFERENCES utilisateur (id),
    CONSTRAINT fk_operation_creation_achat FOREIGN KEY (achat_id) REFERENCES achat (id),
    CONSTRAINT fk_operation_creation_vente FOREIGN KEY (vente_id) REFERENCES vente (id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
