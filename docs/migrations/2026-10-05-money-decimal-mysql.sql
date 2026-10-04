-- Variante MySQL 8 ; nécessite les droits CREATE ROUTINE / ALTER ROUTINE.
-- Exécuter dans la base visée, application arrêtée, après sauvegarde complète.
-- Les ALTER TABLE provoquent des commits implicites : cette migration n'est pas atomique.
-- Le contrôle accepte seulement un bruit flottant inférieur ou égal à 0.0000001.
DELIMITER //
DROP PROCEDURE IF EXISTS stock_migrate_money_20261005//
CREATE PROCEDURE stock_migrate_money_20261005()
main: BEGIN
    DECLARE original_mode TEXT DEFAULT @@SESSION.sql_mode;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        SET SESSION sql_mode = original_mode;
        RESIGNAL;
    END;
    SET SESSION sql_mode = CONCAT_WS(',', original_mode, 'STRICT_ALL_TABLES');
    IF EXISTS (SELECT 1 FROM produit WHERE
        prix_achat IS NULL
        OR ABS(prix_achat) >= 1000000000000000
        OR ABS(prix_achat - ROUND(prix_achat, 2)) > 0.0000001
        OR prix_achat < 0
        OR prix_vente IS NULL
        OR ABS(prix_vente) >= 1000000000000000
        OR ABS(prix_vente - ROUND(prix_vente, 2)) > 0.0000001
        OR prix_vente < 0) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Montants invalides dans produit : migration interrompue avant conversion';
    END IF;
    IF EXISTS (SELECT 1 FROM achat WHERE
        montant_total IS NULL
        OR ABS(montant_total) >= 1000000000000000
        OR ABS(montant_total - ROUND(montant_total, 2)) > 0.0000001
        OR montant_total < 0
        OR ABS(montant_verse) >= 1000000000000000
        OR ABS(montant_verse - ROUND(montant_verse, 2)) > 0.0000001
        OR montant_verse < 0
        OR ROUND(COALESCE(montant_verse, 0), 2) > ROUND(montant_total, 2)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Montants invalides dans achat : migration interrompue avant conversion';
    END IF;
    IF EXISTS (SELECT 1 FROM vente WHERE
        montant_total IS NULL
        OR ABS(montant_total) >= 1000000000000000
        OR ABS(montant_total - ROUND(montant_total, 2)) > 0.0000001
        OR montant_total < 0
        OR ABS(montant_verse) >= 1000000000000000
        OR ABS(montant_verse - ROUND(montant_verse, 2)) > 0.0000001
        OR montant_verse < 0
        OR ROUND(COALESCE(montant_verse, 0), 2) > ROUND(montant_total, 2)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Montants invalides dans vente : migration interrompue avant conversion';
    END IF;
    IF EXISTS (SELECT 1 FROM detail_achat WHERE
        prix_achat_unitaire IS NULL
        OR ABS(prix_achat_unitaire) >= 1000000000000000
        OR ABS(prix_achat_unitaire - ROUND(prix_achat_unitaire, 2)) > 0.0000001
        OR prix_achat_unitaire < 0) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Montants invalides dans detail_achat : migration interrompue avant conversion';
    END IF;
    IF EXISTS (SELECT 1 FROM detail_vente WHERE
        prix_unitaire IS NULL
        OR ABS(prix_unitaire) >= 1000000000000000
        OR ABS(prix_unitaire - ROUND(prix_unitaire, 2)) > 0.0000001
        OR prix_unitaire < 0) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Montants invalides dans detail_vente : migration interrompue avant conversion';
    END IF;
    IF EXISTS (SELECT 1 FROM mouvement_caisse WHERE
        montant IS NULL
        OR ABS(montant) >= 1000000000000000
        OR ABS(montant - ROUND(montant, 2)) > 0.0000001) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Montants invalides dans mouvement_caisse : migration interrompue avant conversion';
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND ((TABLE_NAME = 'produit' AND COLUMN_NAME IN ('prix_achat','prix_vente')) OR (TABLE_NAME = 'achat' AND COLUMN_NAME IN ('montant_total','montant_verse')) OR (TABLE_NAME = 'vente' AND COLUMN_NAME IN ('montant_total','montant_verse')) OR (TABLE_NAME = 'detail_achat' AND COLUMN_NAME IN ('prix_achat_unitaire')) OR (TABLE_NAME = 'detail_vente' AND COLUMN_NAME IN ('prix_unitaire')) OR (TABLE_NAME = 'mouvement_caisse' AND COLUMN_NAME IN ('montant')))
        AND DATA_TYPE = 'decimal' AND NUMERIC_PRECISION = 17 AND NUMERIC_SCALE = 2) = 9 THEN
        SET SESSION sql_mode = original_mode;
        SELECT 'Les neuf colonnes sont déjà en DECIMAL(17,2)' AS resultat;
        LEAVE main;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('money_backup_20261005_produit','money_backup_20261005_achat','money_backup_20261005_vente','money_backup_20261005_detail_achat','money_backup_20261005_detail_vente','money_backup_20261005_mouvement_caisse')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Sauvegarde de migration déjà présente : vérifier la précédente exécution';
    END IF;

    -- Copier les six tables avant le premier ALTER ; conserver ces copies après migration.
    CREATE TABLE money_backup_20261005_produit LIKE produit;
    INSERT INTO money_backup_20261005_produit SELECT * FROM produit;
    CREATE TABLE money_backup_20261005_achat LIKE achat;
    INSERT INTO money_backup_20261005_achat SELECT * FROM achat;
    CREATE TABLE money_backup_20261005_vente LIKE vente;
    INSERT INTO money_backup_20261005_vente SELECT * FROM vente;
    CREATE TABLE money_backup_20261005_detail_achat LIKE detail_achat;
    INSERT INTO money_backup_20261005_detail_achat SELECT * FROM detail_achat;
    CREATE TABLE money_backup_20261005_detail_vente LIKE detail_vente;
    INSERT INTO money_backup_20261005_detail_vente SELECT * FROM detail_vente;
    CREATE TABLE money_backup_20261005_mouvement_caisse LIKE mouvement_caisse;
    INSERT INTO money_backup_20261005_mouvement_caisse SELECT * FROM mouvement_caisse;

    ALTER TABLE produit
        MODIFY COLUMN prix_achat DECIMAL(17,2) NOT NULL,
        MODIFY COLUMN prix_vente DECIMAL(17,2) NOT NULL;
    ALTER TABLE achat
        MODIFY COLUMN montant_total DECIMAL(17,2) NULL,
        MODIFY COLUMN montant_verse DECIMAL(17,2) NULL;
    ALTER TABLE vente
        MODIFY COLUMN montant_total DECIMAL(17,2) NULL,
        MODIFY COLUMN montant_verse DECIMAL(17,2) NULL;
    ALTER TABLE detail_achat
        MODIFY COLUMN prix_achat_unitaire DECIMAL(17,2) NULL;
    ALTER TABLE detail_vente
        MODIFY COLUMN prix_unitaire DECIMAL(17,2) NULL;
    ALTER TABLE mouvement_caisse
        MODIFY COLUMN montant DECIMAL(17,2) NULL;
    SET SESSION sql_mode = original_mode;
    SELECT 'Migration effectuée ; conserver les six tables money_backup_20261005_*' AS resultat;
END//
CALL stock_migrate_money_20261005()//
DROP PROCEDURE stock_migrate_money_20261005//
DELIMITER ;
