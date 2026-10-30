# 渲染来源依赖

Radiance API 能力以配套本体的 `render_abi.def` 为准；各来源版本与加载器版本从 Target 事实取值；第三方发行包从 inputs.toml 取值，本机路径只在 local.toml。迁移保留原适配范围，不自动批准新的来源版本。

Radiance-game 从明确版本的本地 Maven 构件取得，仅用于编译。运行需完整 Radiance 发行 JAR；本体不内嵌 Extra，Extra 不内嵌本体。

`voxy` 使用使用者指定的当前本地工作区普通构建 `0.2.20-beta.1+1.21.1-neoforge`；来源与 SHA-256 以 `inputs.toml` 为准，本机路径只写入 `local.toml`。`voxy_legacy` 只保留旧证据所用输入，不加入编译或实例。Extra 不内嵌 Voxy，兼容声明不扩展到未经验证的版本。

日常客户端通过本地输入 `radiance` 加载配套本体发行包，其版本与 SHA-256 登记在 inputs.toml，本机路径在 local.toml；编译仍只使用本地 Maven API，不内嵌本体。
