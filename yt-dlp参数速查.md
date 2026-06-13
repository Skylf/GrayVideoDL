# yt-dlp 命令行参数速查

## 通用选项

| 参数 | 作用 |
|------|------|
| `-h, --help` | 打印帮助信息 |
| `-v, --verbose` | 输出详细调试日志 |
| `--ignore-errors` | 遇到下载错误时继续而非停止 |
| `--abort-on-error` | 遇到错误立即中止 |
| `-i, --ignore-errors` | 跳过错误项继续 |

## 视频/音频格式选择

| 参数 | 作用 |
|------|------|
| `-f FORMAT` | 指定格式，如 `best`、`worst`、`bv+ba`、`137+140` |
| `-F` | 列出所有可用格式（不下载） |
| `--merge-output-format FORMAT` | 合并时输出格式：`mp4`/`mkv`/`webm` |
| `-x, --extract-audio` | 仅提取音频 |
| `--audio-format FORMAT` | 音频格式：`mp3`/`aac`/`flac`/`m4a`/`opus` |
| `--audio-quality QUALITY` | 音频质量，0(最佳)-10(最差) |

## 输出与命名

| 参数 | 作用 |
|------|------|
| `-o, --output TEMPLATE` | 输出文件名模板，支持 `%(title)s`、`%(id)s`、`%(ext)s` 等变量 |
| `-P, --paths PATH` | 指定下载目录，等效于 `-o PATH/...` |
| `--restrict-filenames` | 文件名仅保留 ASCII 字符 |
| `--no-overwrites` | 不覆盖已有文件 |
| `-w, --no-overwrites` | 同上 |

## 下载控制

| 参数 | 作用 |
|------|------|
| `--limit-rate RATE` | 限速，如 `1M`、`500K` |
| `-r, --limit-rate RATE` | 同上 |
| `--playlist-start N` | 从播放列表第 N 项开始 |
| `--playlist-end N` | 到第 N 项结束 |
| `--max-downloads N` | 最多下载 N 个 |
| `--no-playlist` | 只下载单个视频，忽略播放列表 |
| `--download-archive FILE` | 记录已下载 ID，避免重复 |
| `-N, --concurrent-fragments N` | 并发分片数，加速下载 |

## 网络与请求

| 参数 | 作用 |
|------|------|
| `--proxy URL` | 设置代理，如 `socks5://127.0.0.1:1080` |
| `--cookies FILE` | 从 Netscape 格式 cookies 文件加载 |
| `--cookies-from-browser BROWSER` | 从浏览器提取 cookies，如 `chrome`、`firefox` |
| `--user-agent UA` | 自定义 User-Agent |
| `--referer URL` | 自定义 Referer |
| `--socket-timeout SEC` | Socket 超时秒数 |
| `-R, --retries N` | 失败重试次数，默认 10 |

## 字幕与缩略图

| 参数 | 作用 |
|------|------|
| `--write-subs` | 下载字幕 |
| `--write-auto-subs` | 下载自动生成字幕 |
| `--sub-langs LANGS` | 字幕语言，如 `en,zh-Hans` |
| `--embed-subs` | 将字幕嵌入视频 |
| `--write-thumbnail` | 下载缩略图 |
| `--embed-thumbnail` | 将缩略图嵌入文件 |

## 后处理

| 参数 | 作用 |
|------|------|
| `--remux-video FORMAT` | 不重新编码，直接重封装，如 `mp4→mkv` |
| `--recode-video FORMAT` | 重新编码视频，如 `mp4`、`avi` |
| `--embed-metadata` | 写入元数据到文件 |
| `--parse-metadata FROM:TO` | 解析并重写元数据字段 |

## 过滤器与选择

| 参数 | 作用 |
|------|------|
| `--datebefore DATE` | 仅下载此日期之前的视频 |
| `--dateafter DATE` | 仅下载此日期之后的视频 |
| `--min-filesize SIZE` | 最小文件大小过滤 |
| `--max-filesize SIZE` | 最大文件大小过滤 |
| `--match-title REGEX` | 标题匹配正则才下载 |
| `--reject-title REGEX` | 标题匹配正则则跳过 |

## 常用变量（用于 `-o` 模板）

| 变量 | 含义 |
|------|------|
| `%(title)s` | 视频标题 |
| `%(id)s` | 视频 ID |
| `%(ext)s` | 文件扩展名 |
| `%(uploader)s` | 上传者 |
| `%(upload_date)s` | 上传日期 YYYYMMDD |
| `%(duration)s` | 时长（秒） |
| `%(resolution)s` | 分辨率 |
| `%(filesize)s` | 文件大小 |
| `%(playlist_index)s` | 播放列表中序号 |
