# Bug: 在 B站 登录流程中添加调试日志

**日期**: 2026-06-09
**版本**: v0.0.1F
**优先级**: 高

## 背景

在之前修复了 `shouldOverrideUrlLoading` 导航拦截问题和 `injectCookieReader` httpOnly Cookie 检测问题后，B站 登录后仍然跳转到主页且状态显示未登录。需要通过 Logcat 日志准确定位问题所在。

## 疑问点

需要确认以下环节：

1. 用户登录后，`onPageFinished` 最终加载的是什么 URL？
2. `CookieManager.getCookie(".bilibili.com")` 在登录成功后是否返回了 SESSDATA？
3. `injectCookieReader()` 中的 JS `document.cookie` 是否返回了 Cookie？
4. `saveCookiesFromJs()` 是否被调用？调用时 Cookie 内容是什么？
5. SharedPreferences 中的 `bilibili_logged_in` 是否被成功写入 true？
6. 登录回调 `onLoginResult` 是否被正确触发？
7. `updateBiliLoginStatus()` 读取到的是什么状态？

## 修改

### 修改文件 1

[BilibiliLoginFragment.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/settings/BilibiliLoginFragment.java)

添加了 Log.d/Log.w/Log.e 日志到以下方法：

| 方法 | 日志标签 (TAG=`BiliLogin`) | 记录内容 |
|---|---|---|
| `setupWebView()` | D/BiliLogin | Cookie 清除开始/完成 |
| `onPageStarted()` | D/BiliLogin | 每次页面 URL 加载 |
| `onPageFinished()` | D/BiliLogin | 页面完成 URL + CookieManager 结果（前200字符） |
| `shouldOverrideUrlLoading()` | D/BiliLogin | 导航 URL + 请求方法（GET/POST） |
| `injectCookieReader()` | D/BiliLogin | JS document.cookie 结果 + CookieManager 结果 + 是否检测到 SESSDATA |
| `saveCookiesFromJs()` | D/BiliLogin | Cookie 长度、文件保存路径、SharedPreferences 写入、回调通知 |

### 修改文件 2

[SettingsFragment.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/settings/SettingsFragment.java)

添加了 Log.d 日志到以下方法：

| 方法 | 日志标签 (TAG=`SettingsFrag`) | 记录内容 |
|---|---|---|
| 点击 B站 卡片 | D/SettingsFrag | 当前登录状态 + 操作（退出确认/打开登录）|
| 登录回调 | D/SettingsFrag | success + msg 参数值 |
| `updateBiliLoginStatus()` | D/SettingsFrag | `isLoggedIn()` 返回值 |
| `showLogoutConfirm()` 退出 | D/SettingsFrag | SharedPreferences 清除、Cookie 文件删除、WebView Cookie 清除 |

## 查看方法

在 Android Studio 的 Logcat 中，使用过滤条件：

```
tag:^BiliLogin$ tag:^SettingsFrag$
```

或分别过滤：

```
tag:BiliLogin
```

```
tag:SettingsFrag
```

## 预期日志输出

正常登录成功的日志应当如下：

```
D/BiliLogin: setupWebView: 开始清除 Cookie...
D/BiliLogin: setupWebView: Cookie 清除完成（value=true），加载登录页面
D/BiliLogin: onPageStarted: url=https://passport.bilibili.com/login
D/BiliLogin: onPageFinished: url=https://passport.bilibili.com/login
D/BiliLogin: injectCookieReader: JS document.cookie 返回空或无效（value="null"）
D/BiliLogin: injectCookieReader: CookieManager.getCookie(.bilibili.com)=null
D/BiliLogin: injectCookieReader: 未检测到 SESSDATA，暂不保存
... 用户输入账号密码并提交登录 ...
D/BiliLogin: onPageStarted: url=https://passport.bilibili.com/api/login  (POST)
D/BiliLogin: shouldOverrideUrlLoading: url=..., method=POST
D/BiliLogin: onPageFinished: url=https://www.bilibili.com/  ← 登录成功，跳转到主页
D/BiliLogin: onPageFinished: 检测到跳转到 B站主站
D/BiliLogin: onPageFinished: CookieManager.getCookie(.bilibili.com)=SESSDATA=xxx; ...  ← 有 Cookie
D/BiliLogin: onPageFinished: 通过 CookieManager 检测到 SESSDATA，保存 Cookie
D/BiliLogin: saveCookiesFromJs: 开始保存 Cookie，长度=300
D/BiliLogin: saveCookiesFromJs: 文件已保存到 ...
D/BiliLogin: saveCookiesFromJs: SharedPreferences 已更新，bilibili_logged_in=true
D/BiliLogin: saveCookiesFromJs: 通知回调 onLoginResult(true)
D/SettingsFrag: 登录回调：success=true, msg="B站 登录成功"
D/SettingsFrag: updateBiliLoginStatus: isLoggedIn=true  ← UI 更新
```

## 结论

### 日志分析结果

从日志第 44 行找到了关键证据：

```
shouldOverrideUrlLoading: url=https://passport.biligame.com/x/passport-login/web/crossDomain?...
&SESSDATA=4b5e813e,1796528508,060d3*...&bili_jct=e6ac8bb9...&...
```

**SESSDATA 仅出现在跨域认证 URL 的查询参数中**，从未出现在 `CookieManager.getCookie(".bilibili.com")` 返回的内容中（日志第 53/59/64/72/77 行一直返回 `包含SESSDATA=false`）。

### 根因

B站 移动端 H5 登录流程的工作方式是：
1. 用户在 `passport.bilibili.com/login` 输入账号密码
2. 登录成功后，B站 服务端不通过 Set-Cookie 设置 SESSDATA，而是通过**跨域重定向**将 SESSDATA、bili_jct、DedeUserID 等登录凭证放在 URL 参数中，依次传递给子域（biligame → bilibili.cn → huasheng.cn）
3. 各子域服务器从 URL 参数获取凭证并设置对应域的 Cookie
4. 最终重定向回 `passport.bilibili.com/login`，再跳转到 `m.bilibili.com`

因为 SESSDATA **从未通过 Set-Cookie 响应头设置**，而是通过 URL 参数传递，所以 `CookieManager.getCookie(".bilibili.com")` 永远拿不到 SESSDATA，之前的修复方向无效。

### 修复方案

新增 `extractCookiesFromUrl()` 方法，在 `shouldOverrideUrlLoading()` 中拦截跨域 URL（`contains("crossDomain") && contains("SESSDATA=")`），从 URL 查询参数中手动提取 SESSDATA、bili_jct、DedeUserID 等凭证，构建 Cookie 字符串并保存。

对应代码变更：
- [BilibiliLoginFragment.java#L198-L205](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/settings/BilibiliLoginFragment.java#L198-L205) — `shouldOverrideUrlLoading` 中的跨域登录检测
- [BilibiliLoginFragment.java#L326-L403](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/settings/BilibiliLoginFragment.java#L326-L403) — `extractCookiesFromUrl()` 方法

### 验证结果 ✅

**2026-06-09 经测试验证通过：**
- ✅ 正常登录 — 登录后自动跳转到 `m.bilibili.com`，`extractCookiesFromUrl` 成功截获 URL 参数中的 SESSDATA
- ✅ Cookie 保存 — `saveCookiesFromJs` 成功将 SESSDATA、bili_jct、DedeUserID 写入 `bilibili_cookies.txt` 和 SharedPreferences
- ✅ 状态更新 — UI 正确显示"已登录，已解锁高画质"
- ✅ 功能可用 — 使用保存的 Cookie 成功解析 B站 2K 画质视频
- ✅ 退出登录正常 — 退出后重新登录正常显示登录页面
