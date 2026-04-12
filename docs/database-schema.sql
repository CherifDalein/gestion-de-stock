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
    email VARCHAR(255),
    mot_de_passe VARCHAR(255),
    date_inscription DATE,
    role VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS produit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    nom VARCHAR(255),
    reference VARCHAR(255),
    prix_achat DOUBLE,
    prix_vente DOUBLE,
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
    montant_total DOUBLE,
    montant_verse DOUBLE,
    fournisseur_id BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT fk_achat_fournisseur
        FOREIGN KEY (fournisseur_id) REFERENCES fournisseur (id)
);

CREATE TABLE IF NOT EXISTS vente (
    id BIGINT NOT NULL AUTO_INCREMENT,
    date_vente DATETIME(6),
    montant_total DOUBLE,
    montant_verse DOUBLE,
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
    prix_achat_unitaire DOUBLE,
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
    prix_unitaire DOUBLE,
    PRIMARY KEY (id),
    CONSTRAINT fk_detail_vente_vente
        FOREIGN KEY (vente_id) REFERENCES vente (id),
    CONSTRAINT fk_detail_vente_produit
        FOREIGN KEY (produit_id) REFERENCES produit (id)
);

CREATE TABLE IF NOT EXISTS mouvement_caisse (
    id BIGINT NOT NULL AUTO_INCREMENT,
    date_mouvement DATETIME(6),
    montant DOUBLE,
    type VARCHAR(255),
    motif VARCHAR(255),
    source VARCHAR(255),
    utilisateur_id BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT fk_mouvement_caisse_utilisateur
        FOREIGN KEY (utilisateur_id) REFERENCES utilisateur (id)
);
