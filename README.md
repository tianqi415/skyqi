# Skyqi 浏览器

轻量级 Android 浏览器：顶部极简地址栏、底部固定工具栏、标签面板。

## 特性

- 极简界面：顶部地址栏 + 底部固定工具栏（主页 / 后退 / 前进 / 标签数 / 更多）
- 多标签浏览与标签面板
- 点击地址栏小锁查看 SSL 证书详情（颁发给、颁发者、有效期、签名算法、证书链）
- 搜索引擎切换（百度 / 必应 / 谷歌 / 搜狗）
- 导航首页，支持自定义主页
- 无痕模式、清除缓存
- 基于 Android WebView（Chromium 内核）

## 构建

需要 JDK 17 与 Android SDK。

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export ANDROID_HOME=/opt/android-sdk
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 安装

包名 `com.skyqi.browser`。若安装过旧包名版本，请先卸载旧版。

仓库根目录的 `Skyqi-1.0.1.apk` 为当前发行版安装包（版本 1.0.1）。

## 开源协议

本项目采用 [MIT License](LICENSE) 开源。

## 更新说明

各版本修复内容与变更记录见 [CHANGELOG.md](CHANGELOG.md)。

## 开发

天奇

仓库：https://github.com/tianqi415/skyqi
