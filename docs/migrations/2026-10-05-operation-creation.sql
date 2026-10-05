-- Exécuter sur la base applicative sélectionnée, après sauvegarde et arrêt de l'application.
-- Ajout seulement : aucun achat, vente, stock, compte ou mouvement existant n'est réécrit.
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
