# Bug: 下载进度条不显示 + 删除按钮文字不全 + 新增路径显示与打开功能

**日期**: 2026-06-09  
**版本**: v0.3A  
**优先级**: 高  

## 现象

### Bug 1：下载进度条显示异常/不显示
用户在首页添加下载任务后，跳转到下载列表页，进度条要么不显示，要么显示为 0% 且不更新，或出现闪跳回 0% 再跳到正常值的情况。

### Bug 2：删除按钮文字显示不全
下载列表项中的「删除」按钮文字被截断，只显示部分笔画，尤其在暂停按钮可见时更明显。

### 新增功能要求
1. 下载列表中应显示文件的下载路径（存储位置）
2. 点击路径可打开文件夹，方便用户管理已下载的文件

## 根因

### Bug 1 根因
`DownloadFragment.java` 中的 `refreshRunnable`（自动刷新器）每 1 秒调用一次 `loadTasks()`，该方法从 JSON 文件重新加载任务列表。然而，下载进度数据由 `startProgressPolling()` 实时更新到内存中的 `taskList` 对象，**并未写入 JSON 文件**。因此每次 `loadTasks()` 都会用 JSON 文件中过期的数据（progress=0）覆盖内存中的实时进度值，导致进度条周期性回 0。

代码位置：
- 自动刷新器：[DownloadFragment.java:211-236](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/download/DownloadFragment.java#L211-L236)，调用 `loadTasks()` 从 JSON 重载数据
- 进度轮询：[DownloadFragment.java:525-602](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/download/DownloadFragment.java#L525-L602)，实时更新内存中的 progress 值
- `loadTasks()` 清空 taskList 后用文件数据覆盖：[DownloadFragment.java:157-177](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/download/DownloadFragment.java#L157-L177)

### Bug 2 根因
Material Design 3 的 `TextButton` 样式默认有最小宽度（约 64dp）。当列表项内暂停按钮和删除按钮同时显示时，两按钮挤在一行，空间不足以显示「删除」二字。虽然 `android:layout_width="wrap_content"` 设置了宽度自适应，但 MaterialButton 的 `minWidth` 属性（默认约 64dp）导致按钮不能收缩到文字实际宽度。

布局位置：[item_download.xml:97-104](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/res/layout/item_download.xml#L97-L104)

## 修复

### Bug 1 修复
修改 `DownloadFragment.java` 中的 `refreshRunnable`，将其中的 `loadTasks()` 调用替换为 `adapter.notifyDataSetChanged()`。这样自动刷新仅刷新 UI 显示，从内存中读取当前进度数据，不再从 JSON 文件重载，避免了覆盖实时进度的问题。

修改位置：[DownloadFragment.java:226-227](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/download/DownloadFragment.java#L226-L227)

### Bug 2 修复
在 `item_download.xml` 中的两个 MaterialButton（暂停/继续、删除）上添加 `android:minWidth="0dp"` 和 `app:minWidth="0dp"` 属性，移除 MaterialButton 的默认最小宽度限制，使按钮可以收缩到文字的实际宽度。

修改位置：[item_download.xml:94-96, 106-108](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/res/layout/item_download.xml#L94-L108)

### 新增功能 1：显示下载路径
- `DownloadTask.java`：新增 `downloadDir` 字段，提供 `getDownloadPathDisplay()` 方法返回可显示的路径
- `HomeFragment.java`：创建任务时调用 `task.setDownloadDir()` 记录下载目录
- `item_download.xml`：在进度条下方新增路径显示行，包含文件夹图标、路径文本和「打开」按钮
- `DownloadAdapter.java`：在 `bind()` 方法中绑定路径数据

### 新增功能 2：打开下载路径文件夹
- `OnTaskActionListener` 接口新增 `onOpenFolder(DownloadTask)` 方法
- `DownloadFragment.java` 实现该方法，通过系统 Intent 尝试打开文件夹（优先使用 DocumentsContract，备用 file URI）

## 修改的文件

| 文件 | 修改类型 | 说明 |
|------|----------|------|
| `DownloadFragment.java` | 修改 | refreshRunnable 改为 notifyDataSetChanged；新增 openFolder、onOpenFolder、showToast 方法 |
| `item_download.xml` | 修改 | 添加 `minWidth="0dp"` 修复按钮文字截断；新增路径显示行 |
| `DownloadTask.java` | 修改 | 新增 `downloadDir` 字段、getter/setter、getDownloadPathDisplay()、getFolderToOpen() |
| `HomeFragment.java` | 修改 | startDownload 中设置 downloadDir；新增 getDownloadDirectory 方法 |
| `DownloadAdapter.java` | 修改 | 接口新增 onOpenFolder；ViewHolder 新增路径控件引用；bind 中绑定路径和点击事件 |

## 验证方式
1. 下载进度验证：添加下载任务 → 跳转到下载列表 → 观察进度条是否实时增长、不闪跳回 0
2. 按钮文字验证：在下载进行中状态（暂停按钮可见时）→ 观察「删除」按钮文字是否完整显示
3. 路径显示验证：每个下载任务卡片下方应显示下载路径文本
4. 打开文件夹验证：点击路径行 → 系统文件管理器打开对应目录 → 确认文件存在
