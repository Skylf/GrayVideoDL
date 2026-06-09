# Bug: B站登录成功后程序仍显示未登录

**日期**: 2026-06-09
**版本**: v0.0.1F
**优先级**: 高

## 现象

用户在 B站 登录页面输入账号密码（或扫码）成功登录后，页面自动跳转到 B站 主页（www.bilibili.com），但登录对话框关闭后设置页面仍显示"未登录（点击登录以解锁高画质）"，登录状态未被正确保存和识别。

## 根因

存在两个导致登录检测失败的问题：

### 问题 1：WebView 导航拦截破坏了 Cookie 同步（主要问题）

`shouldOverrideUrlLoading()` 中使用了 `return true` + `view.loadUrl()` 的模式来拦截所有导航：

```java
public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
    view.loadUrl(request.getUrl().toString());
    return true;  // 告诉 WebView "我已处理，你别管了"
}
```

这个模式有严重副作用：
- `return true` 让 WebView 取消自身的原生导航
- 手动 `view.loadUrl()` 发起的是 **GET 请求**，丢失了原始请求的 POST 数据和表单参数
- **登录表单的 POST 提交被转换为 GET，服务器无法正确处理登录请求**
- 即使登录成功，302 重定向响应中的 `Set-Cookie` 响应头在手动重新加载过程中可能未被正确写入 `CookieManager`
- 由于 Cookie 未写入 `CookieManager`，后续 `CookieManager.getCookie(".bilibili.com")` 返回空，检测不到 `SESSDATA`

### 问题 2：httpOnly Cookie 无法通过 JS document.cookie 读取（次要问题）

即使 Cookie 被正确设置，`injectCookieReader()` 中使用的 `document.cookie` 也无法读取 httpOnly 标记的 `SESSDATA`（浏览器安全机制，防 XSS）。

相关代码：[BilibiliLoginFragment.java#L165-L170](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/settings/BilibiliLoginFragment.java#L165-L170)

## 修复

### 修改文件

[BilibiliLoginFragment.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/settings/BilibiliLoginFragment.java)

### 变更内容

#### 1. 修复 `shouldOverrideUrlLoading` — 让 WebView 原生处理导航

`return true` + `view.loadUrl()` → `return false`：

```java
public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
    // 让 WebView 原生处理所有导航（包括 POST 表单提交、重定向等），
    // 确保 Set-Cookie 响应头被正确写入 CookieManager。
    return false;
}
```

这确保了：
- 登录表单的 POST 提交正常到达 B站 服务器
- 服务器返回的 302 重定向被 WebView 自动跟随
- `Set-Cookie` 响应头被正确解析并写入 `CookieManager`

#### 2. 在 `onPageFinished` 添加 URL 跳转检测

参考 `BilibiliLoginDialog` 中已验证有效的实现方式：当页面跳转到 `www.bilibili.com` 主站时，通过 `CookieManager` 直接读取并保存 Cookie：

```java
if (url.contains("www.bilibili.com")
        && !url.contains("login")
        && !url.contains("passport")) {
    String cookies = CookieManager.getInstance().getCookie(".bilibili.com");
    if (cookies != null && cookies.contains("SESSDATA")) {
        saveCookiesFromJs(cookies);
    }
}
```

#### 3. `injectCookieReader()` 保留双重检测作为辅助

保留 JS `document.cookie` + `CookieManager` 的双重检测策略作为辅助检测手段，在非标准登录流程中仍可能发挥作用。

### 相关修改

[SettingsFragment.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/settings/SettingsFragment.java) — `showLogoutConfirm()` 中的 `removeAllCookies` lambda 参数类型修复：`() → {}` → `value → {}`。

## 验证

1. 点击"登录"打开 B站 登录页面
2. 输入账号密码（或扫码）成功登录
3. 页面自动跳转到 B站 主页后登录对话框关闭
4. 确认设置页面显示"已登录，已解锁高画质"
5. 确认应用内部 `bilibili_cookies.txt` 文件已生成且包含 SESSDATA
6. 重启应用，确认登录状态保持
7. 点击"退出"后确认状态变为"未登录"，重新点击"登录"应正常显示登录页面而非自动跳转
