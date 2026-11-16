# 粒子贴图

`manifest.js` 把 sprite id 映射到 PNG，由 `../../tools/sync-assets.js` 生成，不要手改。
清单是 JS 而不是 JSON：直接双击 `index.html` 时 `file://` 下 `fetch` 会被拦，脚本标签不会。

| 目录 | 内容 | 进版本库 |
| --- | --- | --- |
| `astro/` | `sprites.js` 生成的近似贴图，`node ../tools/gen-astro-textures.js` 重做 | 是 |
| `<namespace>/` | 手放或从模组同步的贴图，文件名即贴图名，父目录即命名空间 | 是 |
| `minecraft/` | 从本机客户端 jar 解出的原版贴图 | 否 |

命中顺序：原版 > 模组 > 近似；都没有时预览用程序化形状，画面不会空。

## 加一张贴图

放到 `<namespace>/名字.png`，然后：

```powershell
node web/tools/sync-assets.js
```

重开页面（或刷新）即可在「外观 → 贴图」里选到 `<namespace>:名字`。

## 从模组同步

模组的粒子贴图放在 `src/client/resources/assets/<namespace>/textures/particle/`，
跑同一条命令就会复制到这里并写进清单。

## 解出原版贴图

```powershell
node web/tools/sync-assets.js --vanilla
```

从 `~/.gradle/caches/fabric-loom/<版本>/minecraft-client.jar` 解出全部粒子贴图，
并读取 `assets/minecraft/particles/*.json` 记录「一个定义对应多张贴图」的关系，
供 `appearance.spriteVariation` 的 particle / effect / cycle 三种变体使用。
