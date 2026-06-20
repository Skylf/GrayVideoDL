# Bug: 平台兼容性警告浮层不显示

**日期**: 2026-06-13  
**版本**: v0.6A  
**优先级**: 中  

## 现象

当检测到 yt-dlp 无内置提取器的平台链接（如快手）时，橙色背景的警告浮层没有显示，而是直接显示了解析成功的提示。

## 根因

**流程时序问题**：

1. `onParseClick()` 检测到无内置提取器平台，调用 `showUnsupportedPlatformWarning()` 显示警告浮层
2. 警告浮层开始显示并设置 6 秒后自动消失
3. 紧接着调用 `doParse(url)` 执行解析
4. **`doParse()` 方法开头立即调用了 `hidePlatformWarningToast()`**，把刚显示的警告浮层又隐藏了

警告浮层被错误地包含在解析流程的"重置状态"环节中，导致警告还没来得及显示就被隐藏了。

## 修复

修改 `doParse()` 方法，移除 `hidePlatformWarningToast()` 调用。

警告浮层有自己独立的生命周期：
- 由 `showUnsupportedPlatformWarning()` 触发显示，带渐显动画
- 6 秒后自动渐隐消失
- 不需要在解析开始时强制隐藏

## 修改文件

- [HomeFragment.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/home/HomeFragment.java)
  - 在 `doParse()` 方法中移除 `hidePlatformWarningToast()` 调用

## 验证

- 输入快手链接（`https://www.kuaishou.com/xxx`）
- 橙色警告浮层应正常显示在顶部，显示"快手 暂未独立支持，只能使用通用提取器，大概率会失败"
- 警告浮层显示 6 秒后自动消失
- 解析流程不受影响，继续执行