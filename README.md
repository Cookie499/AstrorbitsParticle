# AstrorbitsParticle

服务端驱动的演唱会粒子特效模组（Minecraft 26.2 / Fabric / Kotlin）。

服务端只下发粒子参数、随机种子与播放进度，粒子由客户端确定性计算并批渲染；
特效只发给位于演唱会场景内、且在可见距离内的客户端，中途加入的客户端会收到带进度的补发。

## 文档

- [粒子样式表](docs/粒子样式表.md)：9 种内置样式的参数、运动学、随机流与 JSON 示例
- [架构设计](docs/架构设计.md)：分层、数据模型、exp4j 表达式、网络协议、可见性与渲染

## Setup

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using.

## 许可证

本项目以 GPL-3.0 发布，版权归 Cookie_qwq 所有，详见 [LICENSE](LICENSE)。
