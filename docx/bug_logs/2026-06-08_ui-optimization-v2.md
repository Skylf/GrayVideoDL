# Bug/优化: 首页 UI 优化 v2

**日期**: 2026-06-08
**版本**: v1.0.0
**优先级**: 中

## 问题

1. **格式列表鱼龙混杂**：视频分辨率和音频格式混在一起显示，用户难以选择
2. **文件大小未知**：B站等平台的格式 filesize 为 null，显示"未知大小"
3. **无法滚动查看日志**：结果卡片出现后日志被挤到屏幕外，ScrollView 无法滚动
4. **复制日志按钮太小**：28dp 高度导致"复制日志"文字被截断
5. **缺少成功反馈**：解析成功后没有直观的视觉提示

## 根因

1. 格式列表未区分视频/音频，统一在一个容器中显示
2. yt-dlp 部分格式返回 null 的 filesize 字段
3. 外层 ScrollView 缺少 `fillViewport="true"`，导致内容填不满时不触发滚动
4. 按钮高度不足 36dp 时 MaterialButton 的文本可能被截断
5. 解析成功后没有用户反馈机制

## 修复

### 1. fragment_home.xml
- 添加 `android:fillViewport="true"` 到外层 ScrollView
- 结果卡片中拆分为"视频画质"和"音频"两个独立区域
- 复制按钮高度改为 36dp
- 添加 success toast 浮层（顶部）

### 2. HomeFragment.java (重写)
- 读取 SharedPreferences 中 `merge_enabled` 设置
- 合并模式下只显示视频格式，隐藏音频区域
- 不合并模式下分两栏显示"视频画质"和"音频"
- 成功浮层渐显 → 停留 1.5s → 渐隐消失
- 格式选中高亮逻辑优化

### 3. SettingsFragment.java
- 实现 SharedPreferences 持久化存储合并开关状态
- 键名 `merge_enabled`，默认 true

### 修改的文件
- `Files/app/src/main/res/layout/fragment_home.xml`
- `Files/app/src/main/java/.../ui/home/HomeFragment.java`
- `Files/app/src/main/java/.../ui/settings/SettingsFragment.java`
- `Files/app/src/main/res/values/colors.xml`（添加成功颜色）
- `Files/app/src/main/res/drawable/bg_success_toast.xml`（新建）

## 验证
1. 编译通过：`BUILD SUCCESSFUL in 1s`
2. 解析 B站 视频 → 仅显示视频画质列表（合并模式）
3. 关闭合并开关 → 显示"视频画质"+"音频"两个区域
4. 解析成功 → 顶部渐显"✓ 解析成功"，1.5秒后消失
5. 日志可折叠展开，展开后内容区可滚动
