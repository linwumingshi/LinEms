# deploy/env — 本地密钥注入

- `local.env`（gitignored）：本机 dev 密钥值。`start-stack.sh` 启动时自动 source；
  `init-nacos-config.sh` 读取它推送 Nacos dataId。
- `local.env.example`（提交）：键名模板，值全 `***`。新环境 `cp local.env.example local.env` 后填值。
- 生产：不依赖本文件，用部署平台/CI 注入同名环境变量即可（服务从 env 读 NACOS_USERNAME/NACOS_PASSWORD，
  其它密钥经 Nacos 配置中心读取——生产 Nacos 需开启强制认证，见 Phase9 §5.5）。

注意：值含 `&`（如 `pa&ss`）必须单引号包裹，否则被 bash 当作后台符。
