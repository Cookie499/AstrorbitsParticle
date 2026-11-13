# AstrorbitsParticle

服务端驱动的演唱会粒子特效模组（Minecraft 26.2 / Fabric / Kotlin）。

服务端只下发粒子参数、随机种子与播放进度，粒子由客户端确定性计算并批渲染；
特效只发给位于演唱会场景内、且在可见距离内的客户端，中途加入的客户端会收到带进度的补发。

## 用法

1. 启动一次服务器，生成 `config/astrorbitsparticle/scenes.json` 与 `effects/` 下的示例定义。
2. 修改场景范围为舞台和观众区；`effects/` 下每个 json 的文件名就是定义 id。
3. ``effect play` 也接受内联定义，不需要先写文件：

```text
/astrorbits effect play {"style":"astrorbitsparticle:radial_burst","duration":1.5,"visibility":{"requireScene":false},"params":{"speed":5}}
```

/astrorbits effect play radial_burst` 在当前位置播放，`/astrorbits effect list` 查看活跃特效。
4. 改完配置执行 `/astrorbits reload`。

命令需要 gamemaster 权限。场景与定义都在 `config/astrorbitsparticle/` 下。

没有布景时可以直接用内置预设验证样式：

```text
/astrorbits test list        查看可用预设
/astrorbits test radial      在当前位置播放单个预设
/astrorbits test all         把 9 个预设围成一圈同时播放
```

## 文档

- [粒子样式表](docs/粒子样式表.md)：9 种内置样式的参数、运动学、随机流与 JSON 示例
- [样式参数](docs/样式参数.md)：由代码声明生成的参数默认值、范围与说明
- [配置文件](docs/配置.md)：上限与单帧预算的默认值、范围
- [接口文档](docs/接口文档.md)：其他模组如何依赖、生成特效与注册自定义样式
- [参考模组调研](docs/参考模组调研.md)：MadParticle、ExParticle、VortyLib 的做法与本项目的改进顺序
- [架构设计](docs/架构设计.md)：分层、数据模型、exp4j 表达式、网络协议、可见性与渲染

## 构建

需要 JDK 25：

```text
.\gradlew.bat build
```

## 许可证

本项目以 GPL-3.0 发布，版权归 Cookie_qwq 所有，详见 [LICENSE](LICENSE)。
