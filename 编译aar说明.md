# 如何把 GlobalFloat 编译成 aar（云打包必需）

## 问题原因

manifest 里**已经配置了** GlobalFloat，但插件目录里只有 `.java` 源码，**没有 `.aar` 文件**。

HBuilderX 云打包/自定义基座**只认 aar**，不会自动编译源码。所以手机上运行的基座实际**不包含** GlobalFloat，控制台才会报：

> 当前运行的基座不包含原生插件[GlobalFloat]

## 目标

编译完成后，确保存在：

```
nativeplugins/GlobalFloat/android/GlobalFloat.aar
```

然后**重新制作自定义基座**。

> 当前插件已包含 `ScreenshotGuardService`：**单屏退到后台**时由原生前台服务扫截屏 → OCR → DeepSeek → 通知，不依赖 WebView JS。
> 旧基座没有这个 Service 时，日志会提示需重打自定义基座。

---

## 方法一：Android Studio 离线编译（推荐）

### 1. 下载 uni-app 离线 SDK

打开：https://nativesupport.dcloud.net.cn/AppDocs/download/android.html

下载最新 **Android 离线 SDK**，解压。

### 2. 复制插件到 SDK

把本项目的 `nativeplugins/GlobalFloat/android/` 整个目录复制到离线 SDK 的：

```
SDK根目录/HBuilder-Integrate-AS/simpleDemo/libs/GlobalFloat/
```

或按 SDK 文档「原生插件集成」章节放置。

### 3. 在 Android Studio 打开 SDK 工程

用 Android Studio 打开 `HBuilder-Integrate-AS` 工程。

### 4. 编译 aar

菜单：**Build → Make Module 'GlobalFloat'**（或 Gradle 面板执行 `assembleRelease`）

### 5. 复制 aar 到项目

找到生成的 `GlobalFloat-release.aar`，复制到：

```
d:\zuobi\nativeplugins\GlobalFloat\android\GlobalFloat.aar
```

### 6. 重新打包

1. 手机**卸载**旧的自定义基座 App
2. HBuilderX：**发行 → 原生 App-云打包 → 打自定义调试基座**
3. 安装新基座
4. **运行 → 运行基座选择 → 自定义调试基座**
5. 再运行到手机

---

## 方法二：找有 Android Studio 的同事帮忙

把 `nativeplugins/GlobalFloat` 文件夹发给对方，让对方按上面步骤编译出 `GlobalFloat.aar` 放回 `android/` 目录即可。

---

## 检查是否成功

自定义基座安装后运行，控制台**不再出现**：

```
当前运行的基座不包含原生插件[GlobalFloat]
```

点击「开启全局悬浮窗」能正常弹出，按 Home 键退出后悬浮窗仍在。

---

## 临时方案

在 aar 编译完成前，可先用 **「应用内悬浮窗」**，不需要原生插件。
