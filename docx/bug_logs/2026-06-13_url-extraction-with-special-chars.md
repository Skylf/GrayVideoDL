# Bug: URL提取失败导致解析错误

**日期**: 2026-06-13  
**版本**: v0.6A  
**优先级**: 高  

## 现象

用户输入包含链接的文本（如 `【【MC建筑展示】竹溪深-哔哩哔哩】`https://b23.tv/YYktobT`）时，整个文本被当作 URL 传给 yt-dlp，导致解析失败：

```
ERROR: [generic] '【【MC建筑展示】竹溪深-哔哩哔哩】`https://b23.tv/YYktobT`' is not a valid URL.
```

## 根因

虽然已经实现了正则表达式提取链接的功能，但存在两个问题：

1. **正则表达式可能匹配不完整**：当 URL 结尾包含反引号或其他特殊字符时，正则可能无法正确匹配完整的 URL
2. **缺少末尾清理**：即使提取出了 URL，末尾可能残留引号、反引号等特殊字符，导致 yt-dlp 无法识别

## 修复

修改 `extractUrlFromInput()` 方法：

1. 添加额外验证：确保提取出的字符串以 `http://` 或 `https://` 开头
2. 清理 URL 末尾：移除末尾的引号、反引号、空格等特殊字符

## 修改文件

- [HomeFragment.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/home/HomeFragment.java)
  - 修改 `extractUrlFromInput()` 方法，添加 URL 验证和末尾清理逻辑

## 验证

- 输入 `【【MC建筑展示】竹溪深-哔哩哔哩】`https://b23.tv/YYktobT``：应正确提取 `https://b23.tv/YYktobT`
- 输入 `"https://www.bilibili.com/video/BV1xx"`（带双引号）：应正确提取 `https://www.bilibili.com/video/BV1xx`
- 输入 `看看这个视频 https://kuaishou.com/xxx 真好看`：应正确提取 `https://kuaishou.com/xxx`