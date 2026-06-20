# Feature: 链接正则提取 + 无内置提取器平台警告

**日期**: 2026-06-13  
**版本**: v0.6A  
**优先级**: 高  

## 功能描述

### 功能1：使用正则表达式从用户输入中提取链接
用户可能粘贴的不仅仅是纯链接，而是包含链接的一段话（如"看看这个视频 https://www.kuaishou.com/xxx 真好看"）。
需要在输入中自动识别并提取出有效的 http/https 链接，防止因输入了额外文字导致解析失败。

### 功能2：无内置提取器平台检测与自动消失警告
yt-dlp 对某些平台（如快手）没有内置提取器（extractor），只能使用通用提取器（generic extractor）进行解析，成功率较低。
当检测到用户输入的链接属于这类平台时，弹出模态警告框提示用户，4 秒后自动关闭，不阻塞后续解析流程。

## 实现方案

### 功能1：正则提取链接
- 在 `HomeFragment.java` 中新增 `URL_PATTERN` 常量（`java.util.regex.Pattern`），使用正则表达式 `https?://[-A-Za-z0-9+&@#/%?=~_|!:,.;]*[-A-Za-z0-9+&@#/%=~_|]` 匹配 http/https 链接
- 新增 `extractUrlFromInput(String input)` 方法，使用预编译的 Pattern 从输入文本中提取第一个匹配的链接
- 在 `onParseClick()` 中调用该方法，若提取出的链接与原文本不同，Toast 提示"已自动提取链接"

### 功能2：无内置提取器平台警告
- 在 `HomeFragment.java` 中新增 `PLATFORMS_WITHOUT_EXTRACTOR` 常量（`Set<String>`），当前包含快手（`PlatformCookieManager.PLATFORM_KUAISHOU`）
- 新增 `showUnsupportedPlatformWarning(String platformName)` 方法，使用 `AlertDialog.Builder` 创建模态警告框，并通过 `mainHandler.postDelayed()` 在 4 秒后自动关闭
- 新增 `getPlatformDisplayName(String platform)` 方法，将平台标识常量转换为中文显示名称
- 在 `onParseClick()` 中，提取链接后检测对应平台，若属于无内置提取器平台则弹出警告

## 修改文件

- [HomeFragment.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/home/HomeFragment.java)
  - 新增 4 个 import：`Arrays`, `HashSet`, `Set`, `Matcher`, `Pattern`
  - 新增 `URL_PATTERN` 常量（提取链接的正则表达式）
  - 新增 `PLATFORMS_WITHOUT_EXTRACTOR` 常量（无内置提取器平台集合）
  - 新增 `extractUrlFromInput()` 方法
  - 新增 `showUnsupportedPlatformWarning()` 方法
  - 新增 `getPlatformDisplayName()` 方法
  - 修改 `onParseClick()` 方法，集成链接提取和平台检测逻辑

## 验证
- 输入纯链接 `https://www.kuaishou.com/xxx`：正常识别链接，弹出快手平台警告
- 输入包含链接的一段话 `看看这个视频 https://www.kuaishou.com/xxx 怎么样`：自动提取链接，Toast 提示
- 输入无链接的纯文字 `你好世界`：提示"未找到有效的视频链接"
- 输入已支持平台的链接 `https://www.bilibili.com/video/BV1xx`：正常解析，不弹出警告

---

## 优化（2026-06-13）

### 优化内容
将无内置提取器警告的模态框改为和解析成功提示框一样的浮层样式，延时 6 秒消失。

### 修改文件

#### 1. 新增 drawable
- [bg_platform_warning_toast.xml](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/res/drawable/bg_platform_warning_toast.xml)
  - 橙色半透明背景 + 圆角矩形 + 边框，与成功提示浮层样式一致

#### 2. 修改 colors.xml
- [colors.xml](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/res/values/colors.xml)
  - 新增 `warning_bg` 颜色值 `#FFFFF3E0`

#### 3. 修改布局
- [fragment_home.xml](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/res/layout/fragment_home.xml)
  - 新增 `layout_platform_warning_toast` LinearLayout 浮层
  - 包含警告图标 TextView 和警告文本 TextView
  - 位于成功提示浮层下方

#### 4. 修改 HomeFragment.java
- [HomeFragment.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/home/HomeFragment.java)
  - 新增 `layoutPlatformWarningToast` 和 `tvPlatformWarningText` 控件声明
  - 在 `initViews()` 中初始化新控件
  - 重写 `showUnsupportedPlatformWarning()` 方法，改为浮层样式（渐显 + 6秒后渐隐）
  - 新增 `hidePlatformWarningToast()` 方法
  - 在 `doParse()` 中调用 `hidePlatformWarningToast()` 隐藏浮层

### 效果对比
| 优化前 | 优化后 |
|--------|--------|
| AlertDialog 模态框 | LinearLayout 浮层 |
| 4 秒后消失 | 6 秒后消失 |
| 需要用户点击"知道了"或等待 | 无需用户交互，自动渐隐 |
| 阻塞后续解析流程 | 不阻塞，解析可继续进行 |
| 居中显示 | 顶部显示，与成功提示框位置一致 |
