-- =====================================================================
-- 深圳三多能源储能管理平台 · system 域（energy-system 服务）
-- Flyway V2：P0-1 网关鉴权 —— 种子管理员密码升级为 BCrypt
-- 说明：V1 种子的 {noop}admin123 仅限本地开发；本迁移替换为 BCrypt 哈希
--       （明文 admin123，DelegatingPasswordEncoder 可校验）。
--       生产环境部署后必须立即修改 admin 密码（P0-4 密钥外置 + 强口令）。
-- 幂等：WHERE 限定仅当仍为 noop 占位时更新，重复执行结果不变。
-- =====================================================================
UPDATE `sys_user`
SET `password` = '{bcrypt}$2a$10$F5EQNT8SSr7kgCqWA2SmKO0/KoRcWNR8BczVmdF3WrKZkS4bbifDK'
WHERE `tenant_id` = 1 AND `username` = 'admin' AND `password` = '{noop}admin123';
