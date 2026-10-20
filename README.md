# 多轨快传 · 桌面 GUI 与可靠传输预览版

本仓库是 [HybridFileXfer](https://github.com/weixiansen574/HybridFileXfer) 的独立维护 fork，保留 USB / Wi-Fi 多通道传输。当前分支为 **4.0.0-preview，协议 400**；手机与电脑必须同时升级，不能混用 3.0 的安装包。`--version` 会显示构建提交号，方便核对源码与产物。

## 开始使用

1. 从本仓库 [Actions](https://github.com/zhao0349/HybridFileXfer/actions) 的同一次成功运行中下载桌面包和 `android-debug-and-reports` APK。桌面 ZIP 自带 Java；Windows 解压后双击 `HybridFileXfer.exe`，不会弹出命令提示符。Linux / macOS 使用对应包，平台和 CPU 架构以构建产物为准。
2. 可以先打开电脑界面，选择 USB / ADB 或网络连接，选择电脑初始目录，点击“等待并连接”。手机未连接或服务尚未启动时，窗口会等待并重试；“停止 / 断开”随时可用。
3. ADB 方式需要已安装 Android platform-tools 并可执行 `adb`，以及手机 USB 调试授权。勾选“通过 ADB 打开手机 App”后，电脑会请求启动手机端；在手机上确认配对、IO 模式与所需授权。网络方式请先在手机启动服务，再将显示的配对码填入已打开的电脑窗口。
4. 在手机双栏文件列表中选择文件，使用明确的“发送到电脑 / 接收到手机”按钮。桌面窗口显示进度、各通道速度、结果与历史。长按可开始选择，选择状态下长按另一项可选择区间；工具栏提供最近目录、存储位置和历史。
5. 断线后可在同一窗口重新连接。任务不会自动重放；重新选择相同源文件和目标目录即可尝试恢复，程序会验证已保存的文件前缀。

[桌面详细用法与 CLI](HybridFileXfer-PC/DESKTOP.md) · [验收与实现范围](docs/implementation-tracker.md) · [性能测试](docs/transfer-benchmarks.md) · [旧版文档存档](docs/upstream-3.0-readme.md)

## 文件保护与传输选项

默认保留重名文件的两份副本、启用 SHA-256 校验和断点恢复。可选择覆盖、跳过，或按完整内容校验做增量跳过；相同大小不代表相同内容。接收端明确设置的选项优先于发送端建议。

文件写入同目录 `.hfx-*.part`，只有完整传输且校验通过才提交为正式文件。失败时原始源文件和已有目标文件不会因清理而被删除；已确认的前缀与 `.meta` 元数据用于恢复。临时文件随实际数据增长，不按源文件的最终大小预分配。取消会关闭当前连接以终止阻塞，之后可重新连接。

单个无法读取、无权限、非法文件名或磁盘写入失败会记录在摘要中，并继续处理后续文件。超长文件名不做有损截断；失败项目有明确原因。若某个数据通道失效，未确认的数据块改由剩余通道处理；控制通道或所有数据通道断开则结束本次任务，保留恢复状态。

默认进度显示已确认字节与当前文件，未知总量不显示虚假百分比。可选预扫描以获得总量，但扫描大量小文件需要额外时间。校验、检查点与加密都有开销，不承诺多通道在所有设备上更快。

## Android 模式与后台运行

正常、Shizuku ROOT、Shizuku ADB，以及新增的 **ROOT（直接 su）** 模式共用文件操作接口。直接 su 功能吸收了 [上游 PR 118](https://github.com/weixiansen574/HybridFileXfer/pull/118) 的设计方向，保留现有模式选择器，补充拒绝、取消、超时和迟到回调清理。存储入口不绕过 Android 权限；外置卡、`Android/data` 和系统目录是否可用取决于所选模式与系统。

传输会话与 Activity 分离，旋转屏幕不重新占用端口；前台通知提供停止入口。长时间锁屏传输时，还需按手机系统设置允许应用后台运行、关闭针对本应用的省电限制，并确保电脑不进入睡眠。前台服务不能保证绕过所有厂商的后台策略。

## 连接、配对与端口

控制和数据通道需要配对认证；数据通道绑定当前会话，并使用 AES-GCM 保护传输。配对码只用于当前会话，不写入历史或偏好设置。只把配对码交给可信设备：配对后的手机文件浏览器可操作电脑当前用户可访问的路径，电脑“初始目录”不是权限隔离边界。Root 模式同样拥有更大的文件访问权限。

手机服务端口可配置（默认 5740）。ADB 本地端口默认自动分配，避免 Windows 保留端口冲突，仅移除本应用创建的转发规则。ADB Burst Mode 是可选实验项，不会强制重启全局 ADB 服务；其效果需要用目标设备测试。

## 从源码构建

桌面使用 JDK 21 或更新版本和 Python 3：

```text
python tools/build_desktop.py --test --package
```

结果在 `.build/distributions`，独立 JAR 保持 Java 8 字节码兼容。旧 Windows / 旧 Java 的运行能力不等于现代打包运行时的支持范围。

Android 使用 JDK 17、Android SDK 34，通过 `HybridFileXfer-Android/gradlew` 运行 `assembleDebug testDebugUnitTest lintDebug`。CI 会编译 JNI 库并生成调试 APK。调试包不是正式签名发布版；若与既有安装签名不同，不能直接覆盖安装，卸载会清除应用设置。

自动测试使用合成数据，覆盖真实 loopback 协议、错误配对、损坏数据、短写、恢复、故障通道、冲突策略与界面状态。它们不替代真实手机、USB 驱动、Root 实现、OEM 后台、外置文件系统和高 DPI 的验收。

## 许可证与来源

本项目使用 [GPL-3.0](LICENSE.txt)。原作者、历史说明及赞助信息保留在[旧版文档](docs/upstream-3.0-readme.md)。CI 桌面包使用 Eclipse Temurin Java 运行时，并保留运行时附带的许可证。本 fork 的反馈与 PR 请提交到 [zhao0349/HybridFileXfer](https://github.com/zhao0349/HybridFileXfer/pulls)。
