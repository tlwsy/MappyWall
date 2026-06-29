# MappyWall

MappyWall 是一个 Minecraft Fabric 客户端模组，用来规划、打开和整理大型地图墙。它只在客户端运行，目标是让玩家在原版服务器上也能按网格路线跑图、绑定地图 ID，并在完成后得到清晰的挂图顺序。

当前构建目标为 Minecraft `1.21.11`、Fabric Loader `0.19.3`、Fabric API `0.140.2+1.21.11` 和 Java `21`。

## 功能

- 创建地图墙任务，设置地图比例尺、宽度、高度和锚点。
- 按地图区域规划跑图路线，并在 HUD 中显示目标、进度和暂停状态。
- 支持手动跑图，以及需要显式启用的自动走路模式。
- 自动绑定已打开地图和墙面位置，支持非连续地图 ID。
- 输出挂图顺序，方便把地图按列、行挂回墙上。
- 纯规划逻辑与 Minecraft 客户端集成分离，核心路线和持久化逻辑可单独测试。

## 安装

1. 安装 Fabric Loader 和 Fabric API。
2. 下载或自行构建 MappyWall。
3. 将生成的 `.jar` 放入 Minecraft 的 `mods` 目录。
4. 启动客户端，并在按键设置中查看 `MappyWall` 分类。

## 使用

- 按 `M` 打开 MappyWall 任务界面。
- 新建任务时选择地图墙尺寸、比例尺、锚点和跑图模式。
- 按 `U` 暂停或继续当前跑图任务。
- 按 `K` 触发急停，停止自动控制并暂停任务。
- 在任务界面中可以重新激活任务、输出挂图顺序或删除任务。

空地图在原版机制下会先开成比例尺 0。若任务目标是更高比例尺，请在开图后使用制图台自行放大，或按 HUD 提示继续处理。

## ModMenu 链接

在 ModMenu 中，MappyWall 的两个链接按钮指向 GitHub：

- 主页: <https://github.com/tlwsy/MappyWall>
- 问题: <https://github.com/tlwsy/MappyWall/issues>

## 服务器兼容性

MappyWall 是 client-only 模组，不添加方块、物品、实体或需要服务端 Fabric 的网络协议。它可以加入原版兼容服务器。

自动走路会控制移动和使用物品，必须由玩家显式选择开启。某些服务器可能禁止自动移动或快速跑图，请先确认服务器规则；需要立即停止时按 `K`。

## 开发

常用命令：

```powershell
$env:GRADLE_USER_HOME='E:\MappyWall\.gradle-user-home'
.\gradlew.bat test
.\gradlew.bat build
```

若只需要测试纯核心逻辑，可以关闭 Fabric 编译：

```powershell
.\gradlew.bat -PenableFabric=false test
```

设计说明见 [docs/design.md](docs/design.md)，构建环境说明见 [docs/build-notes.md](docs/build-notes.md)。

## 许可证

MappyWall 使用 [GNU General Public License v3.0 or later](LICENSE) 发布。
