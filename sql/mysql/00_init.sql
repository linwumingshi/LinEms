-- =====================================================================
-- EnergyX 储能管理平台 · Phase 2 数据库初始化脚本
-- 00_init.sql —— 分域逻辑库创建
-- 版本：v1.0    日期：2026-08-06
-- 设计依据：docs/design/Phase2-数据库设计.md §2  /  ADR-006（分库分表）
-- =====================================================================
-- 使用约定：
--   1. 生产环境主键由分布式ID（雪花）生成；本 DDL 中主键使用 AUTO_INCREMENT，
--      仅便于本地单实例初始化，应用接入时以雪花ID显式写入。
--   2. 分库边界（ShardingSphere 逻辑数据源）：
--        es_device / es_shadow / es_command  按 tenant_id 分库、device_id 分表
--        es_alarm / es_system 时间型大表按时间分区（见各文件）
--        es_product / es_ems / es_station    单逻辑库，行级隔离 + Redis 缓存
--   3. 脚本按 00→80 顺序执行，种子数据随各域文件内联。
-- =====================================================================

CREATE DATABASE IF NOT EXISTS `es_system`  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS `es_product` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS `es_device`  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS `es_shadow`  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS `es_command` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS `es_alarm`   DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS `es_ems`     DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS `es_station` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
