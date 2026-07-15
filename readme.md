# GlobalFloat 原生插件 - 全局悬浮窗

## 功能

- 退出 App 后，悬浮窗仍显示在桌面/其他应用上方
- 前台服务保活，通知栏显示运行状态
- 支持拖动、语音搜题、关键词搜索

## 为什么需要原生插件？

UniApp 纯 Vue 页面只能在 App 内部显示，**无法**在退出应用后继续悬浮。
Android 全局悬浮需要：

1. `SYSTEM_ALERT_WINDOW` 权限
2. `WindowManager` 系统级悬浮窗
3. `ForegroundService` 前台服务保活

这些必须通过 **Android 原生代码** 实现，已通过 `nativeplugins/GlobalFloat` 集成。

## 打包步骤（必做）

### 1. 制作自定义调试基座

HBuilderX 菜单：

```
发行 → 原生 App-制作自定义调试基座
```

勾选本地插件 **GlobalFloat**，等待打包完成。

### 2. 用自定义基座运行

```
运行 → 运行到手机或模拟器 → 运行到 Android App 基座
```

选择 **自定义调试基座**（不是标准基座）。

### 3. 正式发布

```
发行 → 原生 App-云打包
```

同样勾选 **GlobalFloat** 插件。

## 使用步骤

1. 打开 App → 点击 **开启全局悬浮窗**
2. 授予「显示在其他应用上层」权限
3. 按 Home 键退出 App，悬浮窗仍保留
4. 点击 🎤 念题目，或输入关键词搜索
5. 点击 × 或 App 内「关闭全局悬浮窗」结束

## 文件说明

| 文件 | 说明 |
|------|------|
| `nativeplugins/GlobalFloat/` | Android 原生插件 |
| `static/global-float/index.html` | 悬浮窗内嵌搜题页面 |
| `static/data/questions.json` | 题库数据 |
| `utils/globalFloat.js` | JS 调用桥接 |

## 常见问题

**Q: 提示「未集成 GlobalFloat 原生插件」？**  
A: 你正在用标准基座运行，请制作并使用自定义调试基座。

**Q: 悬浮窗闪退？**  
A: 检查是否授予悬浮窗权限，以及是否允许通知/前台服务。

**Q: 语音识别不可用？**  
A: 确认麦克风权限，并使用支持中文语音识别的设备。
