-- =====================================================================
-- V8__base_entity_deleted_columns.sql
-- 统一补 deleted 列（逻辑删除），配合 com.energyx.common.entity.BaseEntity @TableLogic
-- 幂等：存储过程遍历业务库所有表，缺 deleted 列则 ADD COLUMN DEFAULT 0
-- 存量数据 deleted=0（正常），零影响；流水表亦补列（实体不继承基类则不启用逻辑删除）
-- =====================================================================
DROP PROCEDURE IF EXISTS sp_add_deleted_column;

DELIMITER $$
CREATE PROCEDURE sp_add_deleted_column()
BEGIN
    DECLARE done INT DEFAULT 0;
    DECLARE tbl VARCHAR(200);
    DECLARE cur CURSOR FOR
        SELECT CONCAT(TABLE_SCHEMA, '.', TABLE_NAME)
        FROM information_schema.TABLES
        WHERE TABLE_SCHEMA IN ('es_system', 'es_product', 'es_device', 'es_rule', 'es_alarm',
                               'es_notify', 'es_command', 'es_shadow', 'es_station', 'es_ems')
          AND TABLE_NAME NOT LIKE 'flyway%'
          AND NOT EXISTS (
            SELECT 1 FROM information_schema.COLUMNS c
            WHERE c.TABLE_SCHEMA = TABLES.TABLE_SCHEMA
              AND c.TABLE_NAME = TABLES.TABLE_NAME
              AND c.COLUMN_NAME = 'deleted'
        );
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;

    OPEN cur;
    read_loop: LOOP
        FETCH cur INTO tbl;
        IF done THEN
            LEAVE read_loop;
        END IF;
        SET @sql = CONCAT('ALTER TABLE ', tbl,
                          ' ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除 0正常 1删除''');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END LOOP;
    CLOSE cur;
END$$
DELIMITER ;

CALL sp_add_deleted_column();
DROP PROCEDURE sp_add_deleted_column;
