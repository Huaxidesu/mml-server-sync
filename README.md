# mml-server-sync

MML 服务器实例的独立更新器：读取 MML 生成的 `server.json`，在启动游戏前把模组、配置与
基础文件同步到 **游戏目录**。供 PCL2 / HMCL / Prism / MultiMC 等非 MML 启动器使用，
不兼容 HMCL `server-manifest.json` 或其他清单格式。

当前版本 **1.9.0**，更新记录见 [CHANGELOG.md](CHANGELOG.md)。

## 特性

- 按清单里的 SHA1 / SHA256 增量更新，只下载缺失或内容变化的文件。
- Java Agent 直挂游戏 JVM，`main` 之前完成更新；也支持命令行 / 启动前脚本。
- 清单默认 gzip 传输（8 MB → 约 1 MB），每秒一条进度，随时可以暂停 / 取消。
- 自选模组选择制：勾选装成启用，不勾装成 `.disabled`，选择记在状态文件里。
- 配置压缩包与基础包自动解压；清单里移除的旧文件按规则清理。
- 自更新：jar 同目录放 `index.json`，下次启动自动使用新版本。
- 界面中英双语，按系统语言自动切换。

## 快速开始

Java Agent（推荐，Prism / HMCL / PCL2 通用）：

```text
-javaagent:D:\Launcher\mml-server-sync.jar=https://example.com/server.json
```

命令行：

```powershell
java -jar mml-server-sync.jar -m https://example.com/server.json -g "D:\Instance\.minecraft"
```

同步日志默认写在 `<game-dir>/.mml-server-sync/mml-server-sync.log`。

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/agent.md](docs/agent.md) | Java Agent 接入、MML 启动器识别、JVM 属性与环境变量 |
| [docs/cli.md](docs/cli.md) | 命令行参数、退出码、启动器 / 启动前脚本接入 |
| [docs/optional-mods.md](docs/optional-mods.md) | 自选模组选择制、弹窗、`optional-state.txt`、`--skip` |
| [docs/sync.md](docs/sync.md) | 同步规则、状态文件、下载与解压细节、已知边界 |
| [docs/ui.md](docs/ui.md) | 进度窗、取消 / 暂停 / 继续、界面语言 |
| [docs/build.md](docs/build.md) | 构建与发版、自更新索引格式 |
| [docs/troubleshooting.md](docs/troubleshooting.md) | 常见问题排查 |
| [CHANGELOG.md](CHANGELOG.md) | 版本更新记录 |

## 要求

- 运行：Java 8–25；构建：JDK 17+（Gradle 用 `--release 8` 产出 Java 8 字节码）。
- 只支持 zip 格式的配置包与基础包。
- 需要能访问清单里的下载地址；CurseForge 条目没带 URL 时用 API Key 或 CDN 规则，
  个别被作者关闭 CDN 的文件会失败（见 [docs/troubleshooting.md](docs/troubleshooting.md)）。

## 构建

```powershell
.\gradlew.bat build
```

产物：`build/libs/mml-server-sync.jar`。发布包用 `.gradlew releaseIndex` 生成
（`index.json` + 版本化 jar）。构建细节、自更新索引格式与发版步骤见
[docs/build.md](docs/build.md)。

## 许可证

Apache-2.0，见 [LICENSE](LICENSE)。
