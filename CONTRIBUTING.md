# Contributing

这是独立维护的 FancyNpcs + ModelEngine 定制版。请在本仓库提交问题和 Pull Request。

- 构建环境与外部 API 依赖见 [README.md](README.md)。ModelEngine、BetterModel JAR 放在忽略的 `deps/`，或通过 Gradle 参数指定。
- 保留上游全部 NPC 和 packets 版本模块；修改模型行为时运行 `:plugins:fancynpcs-model:test`，并说明服务端和模型插件版本。
- 请勿提交外部插件 JAR、凭据、测试服数据或构建缓存。更新发行包前重新核对 `validation.json` 的验证范围和产物哈希。
- 保留 [LICENSE](LICENSE) 和 [NOTICE](NOTICE) 中的版权及来源说明。
