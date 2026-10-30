# 开发与构建

本仓库独立构建，与 Radiance 目录同级，不被本体 Gradle 包含。先在配套本体仓库执行本地开发构件发布；只写本机 Maven，不推送远端：

```powershell
.\gradlew.bat :version:1.21.1-neoforge:publishToMavenLocal
```

再在本仓库根目录执行：

```powershell
.\gradlew.bat spotlessApply
.\gradlew.bat check verifyRelease
.\gradlew.bat verifyCompliance --strict
```

`target.properties` 的 `radiance_version` 固定 `Radiance-game` 与探针用 `Radiance-probe`；不复制 API，也不把它们内嵌到 Extra。本体同版本的旧构建可能没有新 API，必须使用当前交付配对。API 数据合同和 Native 版本从本体生成定义取值，不在此处另抄。

访问变换在构建时从本体开发 JAR 提取，独立测试因此使用同一 Minecraft 访问合同。第三方嵌入库也由自己的固定输入提取；没有相应模组时，仅在实际运行中跳过其适配器，编译仍需要登记的输入。本机路径只在 local.toml，不引用本体仓库的源码目录。

产品 Mixin、第三方源格式与专项算法在本仓库；共性执行在本体公共平台。探针与原生工具不进入 Extra 产品 JAR。当前配置缓存因测试任务的本地构件系统属性捕获 Gradle 配置而关闭，不修改模板绕过检查。

公共能力以本体的《渲染平台公共接入》为准。Voxy 投影纹理和 Sable 持久缓冲通过 `RenderResources` 持有并释放；来源图层和特殊程序资格在本仓库解释。Veil 仍使用已界定的 framebuffer／顶点数组／程序互操作，不为改包名重复实现。不得直接改本体的可变纹理映射或取得 Vulkan 对象及设备地址。

组合运行使用本体发行 JAR 和 Extra 发行 JAR 两份实际产物。首次候选的项目内会话与人工步骤由上层 `validations/conformance-takeover/` 工具准备；不向 Prism 投放，不启动用户实例。

浮空水晶的世界几何计算程序在构建时编译；使用 `VULKAN_SDK` 中的 glslangValidator，未设置该变量时从 PATH 查找。需要完整 Vulkan SDK，编译产物随 Extra 发布。
