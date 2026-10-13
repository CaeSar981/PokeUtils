# libs —— 真实第三方依赖 jar（可选）

默认构建使用 `stub-api` 模块中的编译桩，无需本目录。

若你希望**直接针对真实插件 jar 编译**，把下列文件放到本目录：

- `PokeStar.jar`
- `PokemonAPI.jar`
- `Blank038API.jar`
- `MSGToggle.jar`
- `Pixelmon.jar`（Pixelmon 模组本体，或服务端 core jar）
- `VaultAPI.jar`（可选，也可继续用 jitpack 的 `com.github.MilkBowl:VaultAPI:1.7`）

然后按 `plugin/pom.xml` 中「真实依赖（可选）」区块的注释操作即可。

> 服务器上插件通常位于 `plugins/` 目录，直接复制过来即可。
