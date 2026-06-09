# Bug: B站登录/退出状态异常

**日期**: 2026-06-09
**版本**: v0.0.1F
**优先级**: 高

## 现象

1. **无法退出登录**：点击"退出"按钮后，UI 短暂显示未登录，但再次操作时又恢复为已登录状态，用户无法真正退出登录
2. **退出后自动登录**：已退出登录后，点击"登录"按钮，WebView 不显示登录页面，而是直接跳转到 B站 主页（www.bilibili.com），且应用自动将用户标记为已登录状态

## 根因

两个 bug 的根因是同一个问题：**`CookieManager.removeAllCookies()` 是异步操作，但代码中没有等待其完成就立即加载了登录页面**。

具体触发链路：
1. 在 [BilibiliLoginFragment.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/settings/BilibiliLoginFragment.java) 的 `setupWebView()` 方法中，调用 `removeAllCookies(null)` 时传入了 `null` 回调（不等待完成），紧接着立即执行 `webView.loadUrl()` 加载登录页
2. 由于 Cookie 尚未清除完毕，WebView 加载 `passport.bilibili.com/login` 时仍携带旧的 `SESSDATA` 等登录凭证
3. B站 服务端检测到有效会话，自动 302 重定向到 `www.bilibili.com` 主页
4. 页面加载完成后 `injectCookieReader()` 执行，通过 JS 读取到 `SESSDATA`，调用 `saveCookiesFromJs()` 将 `bilibili_logged_in` 重新设为 `true`
5. 用户就此"被登录"——实际上从未输入过密码

同样地，在 [SettingsFragment.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/settings/SettingsFragment.java) 的 `showLogoutConfirm()` 中，`removeAllCookies(null)` 也没有回调，且 Cookie 文件删除依赖 `getCookieFilePath()` 的文件存在性检查，不够健壮。

## 修复

### 修改文件 1：BilibiliLoginFragment.java
- **位置**：`setupWebView()` 方法
- **变更**：
  - 将 `removeAllCookies(null)` → `removeAllCookies(() -> { ... })`
  - 将 `WebStorage.deleteAllData()` 和 `webView.loadUrl()` 移入 `removeAllCookies` 的回调中执行
  - 确保 **Cookie 完全清除完成后** 才加载登录页面
- **具体修改**：[BilibiliLoginFragment.java#L171-L182](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/settings/BilibiliLoginFragment.java#L171-L182)

### 修改文件 2：SettingsFragment.java
- **位置**：`showLogoutConfirm()` 方法
- **变更**：
  - Cookie 文件删除：改用直接构造 `File(getFilesDir(), "bilibili_cookies.txt")` 路径，不依赖 `getCookieFilePath()` 的存在性检查
  - `removeAllCookies(null)` → `removeAllCookies(() -> { ... })`，将 `WebStorage.deleteAllData()` 移入回调
- **具体修改**：[SettingsFragment.java#L114-L145](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/settings/SettingsFragment.java#L114-L145)

## 验证

1. 用户登录 B站 → 确认状态显示"已登录"
2. 点击"退出" → 确认状态变更为"未登录"
3. 点击"登录" → 确认显示 B站 登录页面（输入密码/扫码），不会自动跳转到主页
4. 关闭登录页面回到设置 → 确认状态仍为"未登录"
5. 正常输入账号密码登录 → 确认状态变为"已登录"
