"""
ytdlp_bridge.py
GrayVideoDL 的 Python 桥接模块。
通过 Chaquopy 在 Android 应用中运行，提供 yt-dlp 的封装接口。
提供环境检测、视频信息提取、视频下载等功能。
"""

import sys
import json
import os


def testEnvironment() -> str:
    """
    测试 Python 运行环境是否正常。
    返回 Python 版本、yt-dlp 安装状态等环境信息（JSON 格式字符串）。
    """
    result = {
        "python_version": sys.version,
        "yt_dlp_installed": False,
        "yt_dlp_version": "",
        "status": "ok",
        "error": "",
    }
    try:
        import yt_dlp
        result["yt_dlp_installed"] = True
        result["yt_dlp_version"] = yt_dlp.version.__version__
    except ImportError as import_error:
        result["status"] = "ok"
        result["error"] = f"yt-dlp 导入失败: {str(import_error)}"
    except Exception as unknown_error:
        result["status"] = "error"
        result["error"] = f"未知错误: {str(unknown_error)}"
    return json.dumps(result, ensure_ascii=False)


def _get_common_opts(cookie_file=""):
    """构建通用的 yt-dlp 选项字典"""
    opts = {
        "quiet": True,
        "no_warnings": True,
        "http_headers": {
            "User-Agent": (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/125.0.0.0 Safari/537.36"
            ),
            "Accept": (
                "text/html,application/xhtml+xml,"
                "application/xml;q=0.9,image/avif,image/webp,"
                "image/apng,*/*;q=0.8"
            ),
            "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
            "Referer": "https://www.bilibili.com/",
        },
        "extractor_args": {
            "BiliBili": ["web"],
        },
        "format_sort": ["res", "codec", "vbr"],
    }
    # 使用 cookiefile 方式传递 Cookie（与命令行 --cookies 等效）
    if cookie_file and os.path.exists(cookie_file):
        opts["cookiefile"] = cookie_file
    return opts


def extractVideoInfo(video_url: str, cookie_file: str = "") -> str:
    """
    提取指定视频链接的信息。
    使用 yt-dlp 获取视频标题、格式、时长等元数据。

    Args:
        video_url: 视频网页链接
        cookie_file: Cookie 文件路径（Netscape 格式，与 --cookies 等效）

    Returns:
        JSON 格式的视频信息字符串
    """
    result = {
        "title": "", "duration": 0, "formats": [],
        "thumbnail": "", "status": "ok", "error": "",
    }

    try:
        from yt_dlp import YoutubeDL

        ydl_opts = _get_common_opts(cookie_file)
        ydl_opts.update({
            "simulate": True,
            "skip_download": True,
        })

        with YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(video_url, download=False)
            result["title"] = info.get("title", "未知标题")
            result["duration"] = info.get("duration", 0)
            result["thumbnail"] = info.get("thumbnail", "")

            formats = info.get("formats", [])
            format_list = []
            for fmt in formats:
                has_url = bool(fmt.get("url"))
                format_note = fmt.get("format_note", "")
                is_locked = not has_url and ("missing" in format_note.lower()
                                             or "premium" in format_note.lower()
                                             or "会员" in format_note)
                format_list.append({
                    "format_id": fmt.get("format_id", ""),
                    "ext": fmt.get("ext", ""),
                    "resolution": fmt.get("resolution", ""),
                    "filesize": fmt.get("filesize", 0),
                    "vcodec": fmt.get("vcodec", ""),
                    "acodec": fmt.get("acodec", ""),
                    "format_note": format_note,
                    "is_locked": is_locked,
                })
            result["formats"] = format_list

    except Exception as e:
        result["status"] = "error"
        result["error"] = f"视频信息提取失败: {str(e)}"

    return json.dumps(result, ensure_ascii=False)


def downloadVideo(video_url: str, format_id: str, output_dir: str,
                  cookie_file: str = "") -> str:
    """
    下载指定视频的特定格式到本地目录。

    Args:
        video_url: 视频链接
        format_id: 格式 ID（如 "30032"）
        output_dir: 输出目录
        cookies_str: Cookie 字符串

    Returns:
        JSON 格式的下载结果
    """
    result = {
        "status": "ok", "filepath": "",
        "title": "", "ext": "", "error": "",
    }

    try:
        from yt_dlp import YoutubeDL

        # 确保输出目录存在
        os.makedirs(output_dir, exist_ok=True)

        ydl_opts = _get_common_opts(cookie_file)
        ydl_opts.update({
            "format": format_id,
            "outtmpl": os.path.join(output_dir, "%(title)s.%(ext)s"),
            "overwrites": True,
        })

        with YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(video_url, download=True)
            result["title"] = info.get("title", "未知标题")
            result["ext"] = info.get("ext", "")

            # 查找下载后的文件
            filename = ydl.prepare_filename(info)
            if os.path.exists(filename):
                result["filepath"] = filename
            else:
                for f in os.listdir(output_dir):
                    fpath = os.path.join(output_dir, f)
                    if os.path.isfile(fpath) and f.startswith(
                            info.get("title", "")):
                        result["filepath"] = fpath
                        result["ext"] = f.split(".")[-1]
                        break

            if not result["filepath"]:
                result["status"] = "error"
                result["error"] = "无法找到下载的文件"
            else:
                result["status"] = "ok"

    except Exception as e:
        result["status"] = "error"
        result["error"] = f"下载失败: {str(e)}"

    return json.dumps(result, ensure_ascii=False)
