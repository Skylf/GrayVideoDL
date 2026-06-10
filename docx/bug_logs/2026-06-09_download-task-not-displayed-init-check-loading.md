# Bug: 下载后列表不显示当前下载项 + 优化：初始化检查加载动画

**日期**: 2026-06-09  
**版本**: v0.3A  
**优先级**: 高  

## 现象

### Bug：点击下载后跳转列表不显示当前正在下载的项
用户在首页解析视频、选择画质、点击下载后，自动跳转到下载列表页，但当前正在下载的任务没有出现在列表中。需要手动回到首页再进入下载 Tab，或杀掉 App 重新进入才能看到。

### 优化：初始化检查无加载提示
App 首次启动时，后台执行初始化检查，界面无任何提示（白屏或静默状态），用户不知道正在检查。检查完成后突然弹出结果对话框，体验突兀。

## 根因

### Bug 根因一：异步加载时序竞争（已修复）
`DownloadFragment.onResume()` 中的代码流程：

```
onResume()
  -> loadTasks()           // 异步：在后台线程加载 JSON 文件
  -> postDelayed(autoStartPendingTasks(), 500)  // 500ms 后检查并启动下载
```

`loadTasks()` 开启后台线程异步读取 `download_tasks.json` 文件，完成后通过 `mainHandler.post()` 更新 `taskList`。而 `autoStartPendingTasks()` 在固定 500ms 后执行，如果此时 `loadTasks()` 的异步线程还未完成（IO 延迟、线程调度等因素），`taskList` 仍为空列表，`autoStartPendingTasks()` 找不到任何需要启动的任务。该方法只会被调用一次，因此下载任务永远不会被启动。

### Bug 根因二：hide/show 切换不触发 onResume（新发现，本次修复）
`MainActivity.switchFragment()` 使用 `hide()`/`show()` 策略管理 Fragment 切换。但 Android Fragment 生命周期中，`show()` **不会触发 `onResume()`**，只会触发 `onHiddenChanged()`。

日志证实：
```
第一次切换到下载 Tab:
  switchFragment: 创建新的 Download Fragment 实例
  → 触发完整生命周期 → onResume() → loadTasks → autoStartPendingTasks ✅

第二次及之后切换到下载 Tab:
  switchFragment: 复用已有的 Download Fragment
  → 只触发 onHiddenChanged(false) → onResume() 不执行 ❌
  → loadTasks 不执行 → autoStartPendingTasks 不执行 → 新任务不显示
```

### 优化分析
初始化检查在 `GrayVideoDLApp` 的后台线程执行，`MainActivity.pollInitCheckResult()` 以 500ms 间隔轮询检查结果。轮询期间界面无任何反馈，用户不知道后台正在执行检查。

## 修复

### Bug 修复

#### 修复一：异步加载时序竞争
修改 `DownloadFragment.java`：
1. `loadTasks()` 增加 `boolean autoStart` 参数，加载完成后根据该参数决定是否调用 `autoStartPendingTasks()`
2. `onResume()` 调用 `loadTasks(true)`，确保任务列表加载完成后立即自动启动待处理任务
3. 移除原来的 `postDelayed(autoStartPendingTasks, 500)` 固定延时调用
4. `onCreateView()` 调用 `loadTasks(false)`（首次加载不启动）

修改位置：[DownloadFragment.java:105-112](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/download/DownloadFragment.java#L105-L112)、[DownloadFragment.java:149-177](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/download/DownloadFragment.java#L149-L177)

#### 修复二：hide/show 不触发 onResume
在 `DownloadFragment.java` 中添加 `onHiddenChanged()` 覆盖，当 Fragment 从隐藏变为可见时（`!hidden`），执行与 `onResume()` 相同的逻辑（`loadTasks(true)` + `startAutoRefresh()`）。

修改位置：[DownloadFragment.java:128-147](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/download/DownloadFragment.java#L128-L147)

### 优化修复
修改 `MainActivity.java`：
1. `pollInitCheckResult()` 先调用 `showInitCheckLoadingDialog()` 显示加载对话框（带转圈 ProgressBar + "正在进行初始化检查，请稍后..."）
2. 新增 `doPollInitCheck()` 方法，检查就绪后随机延迟 1~3 秒再展示结果
3. 新增 `dismissInitCheckLoadingDialog()` 关闭加载对话框
4. 超时（超过 15 秒）时自动关闭加载对话框

修改位置：[MainActivity.java:157-249](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/MainActivity.java#L157-L249)

## 验证方式

### Bug 验证
1. 在首页解析 B站视频，选择画质，点击下载
2. 确认自动跳转到下载 Tab
3. 观察下载列表中是否立即出现当前下载项
4. 进度条是否开始实时更新

### 优化验证
1. 清除 App 数据或重新安装
2. 启动 App，观察是否立即弹出"正在进行初始化检查，请稍后..."对话框（含转圈动画）
3. 等待 1~3 秒后，对话框自动切换为检查结果
4. 检查结果对话框 3 秒后自动关闭
