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
    """构建通用的 yt-dlp 选项字典

    针对 Bilibili 的 HTTP 412 问题，需要模拟完整浏览器请求头，
    尤其是 Origin 头。同时定期更新 User-Agent 版本避免被拦截。
    """
    # 构建完整的浏览器请求头，模拟真实 Chrome 浏览器访问
    # 注意：Bilibili 对缺少 Origin 头的请求会返回 412 Precondition Failed
    headers = {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/130.0.0.0 Safari/537.36"
        ),
        "Accept": (
            "text/html,application/xhtml+xml,"
            "application/xml;q=0.9,image/avif,image/webp,"
            "image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
        ),
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        "Referer": "https://www.bilibili.com/",
        "Origin": "https://www.bilibili.com",
    }

    opts = {
        "quiet": True,
        "no_warnings": True,
        "http_headers": headers,
        "extractor_args": {
            "BiliBili": ["web"],
        },
        "format_sort": ["res", "codec", "vbr"],
    }
    # 使用 cookiefile 方式传递 Cookie（与命令行 --cookies 等效）
    # 注意：如果 Cookie 文件存在但内容过期，可能导致 412 错误
    if cookie_file and os.path.exists(cookie_file):
        # 检查 Cookie 文件是否为空或只有头信息（无实际 Cookie 条目）
        with open(cookie_file, "r", encoding="utf-8") as cf:
            content = cf.read()
        # 检查是否有非注释、非空行的实际 Cookie 条目
        has_valid_cookies = any(
            line.strip() and not line.startswith("#")
            for line in content.split("\n")
        )
        if has_valid_cookies:
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

        # 尝试使用 Cookie 提取信息
        # 如果失败（如 Cookie 过期导致 412），则尝试不使用 Cookie 重试
        last_error = None
        retry_without_cookie = bool(cookie_file)

        for attempt in range(2 if retry_without_cookie else 1):
            try:
                # 第一次使用 Cookie，第二次（如果有）不使用
                current_cookie = cookie_file if attempt == 0 else ""
                ydl_opts = _get_common_opts(current_cookie)
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
                            # 优先使用确切文件大小，若为0则回退到估算大小
                            "filesize": fmt.get("filesize", 0) or fmt.get("filesize_approx", 0),
                            "vcodec": fmt.get("vcodec", ""),
                            "acodec": fmt.get("acodec", ""),
                            "format_note": format_note,
                            "is_locked": is_locked,
                        })
                    result["formats"] = format_list
                    # 提取成功，跳出重试循环
                    break

            except Exception as attempt_error:
                last_error = attempt_error
                error_msg = str(attempt_error)
                # 如果 Cookie 导致的错误（412 或类似），尝试不使用 Cookie
                if retry_without_cookie and attempt == 0 and (
                        "412" in error_msg or "403" in error_msg
                        or "Precondition" in error_msg):
                    continue
                # 其他错误或不需重试，直接抛出
                raise

        # 如果所有重试都失败，抛出最后一个错误
        if last_error is not None:
            raise last_error

    except Exception as e:
        result["status"] = "error"
        result["error"] = f"视频信息提取失败: {str(e)}"

    return json.dumps(result, ensure_ascii=False)


def downloadVideo(video_url: str, format_id: str, output_dir: str,
                  cookie_file: str = "") -> str:
    """
    下载指定视频的特定格式到本地目录（无进度回调版本，兼容旧调用）。

    Args:
        video_url: 视频链接
        format_id: 格式 ID（如 "30032"）
        output_dir: 输出目录
        cookie_file: Cookie 文件路径

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


def downloadVideoWithProgress(video_url: str, format_id: str,
                               output_dir: str, cookie_file: str = "",
                               progress_file: str = "",
                               cancel_flag_file: str = "") -> str:
    """
    下载指定视频并支持进度回调和取消操作。
    通过 progress_hook 实时写入进度到 progress_file，
    同时检测 cancel_flag_file 是否存在以支持取消/暂停。

    Args:
        video_url: 视频链接
        format_id: 格式 ID（如 "30032"）
        output_dir: 输出目录
        cookie_file: Cookie 文件路径
        progress_file: 进度输出文件路径（JSON 格式）
        cancel_flag_file: 取消标志文件路径（存在即取消）

    Returns:
        JSON 格式的下载结果（含最终进度信息）
    """
    result = {
        "status": "ok", "filepath": "",
        "title": "", "ext": "", "error": "",
        "progress": 100,
    }

    try:
        from yt_dlp import YoutubeDL

        # 确保输出目录存在
        os.makedirs(output_dir, exist_ok=True)

        # 定义进度回调函数（yt-dlp 的 progress_hooks）
        def progress_hook(d):
            """yt-dlp 进度回调：将当前进度写入 progress_file"""
            status = d.get("status", "")
            percent = 0
            # 获取下载进度百分比
            if status == "downloading":
                total_bytes = d.get("total_bytes") or d.get("total_bytes_estimate", 0)
                downloaded = d.get("downloaded_bytes", 0)
                if total_bytes and total_bytes > 0:
                    percent = int(downloaded * 100 / total_bytes)
                elif status == "finished":
                    percent = 100
            elif status == "finished":
                percent = 100

            # 写入进度文件
            if progress_file:
                try:
                    progress_data = {
                        "percent": percent,
                        "speed": d.get("speed", 0) or 0,
                        "eta": d.get("eta", 0) or 0,
                        "status": status,
                        "downloaded_bytes": d.get("downloaded_bytes", 0),
                        "total_bytes": d.get("total_bytes", 0),
                    }
                    with open(progress_file, "w", encoding="utf-8") as pf:
                        pf.write(json.dumps(progress_data, ensure_ascii=False))
                except Exception:
                    pass  # 写入进度文件失败不影响下载

            # 检测取消标志文件（用于暂停/取消）
            if cancel_flag_file and os.path.exists(cancel_flag_file):
                raise Exception("DOWNLOAD_CANCELLED_BY_USER")

        ydl_opts = _get_common_opts(cookie_file)
        ydl_opts.update({
            "format": format_id,
            "outtmpl": os.path.join(output_dir, "%(title)s.%(ext)s"),
            "overwrites": True,
            "progress_hooks": [progress_hook],
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

        # 下载正常完成，写入 100% 进度
        if progress_file:
            try:
                with open(progress_file, "w", encoding="utf-8") as pf:
                    pf.write(json.dumps({
                        "percent": 100, "status": "finished",
                        "downloaded_bytes": 0, "total_bytes": 0,
                    }, ensure_ascii=False))
            except Exception:
                pass

    except Exception as e:
        error_msg = str(e)
        if "DOWNLOAD_CANCELLED_BY_USER" in error_msg:
            # 用户主动取消/暂停
            result["status"] = "paused"
            result["error"] = "用户暂停下载"
            result["progress"] = 0
            if progress_file:
                try:
                    with open(progress_file, "w", encoding="utf-8") as pf:
                        pf.write(json.dumps({
                            "percent": 0, "status": "paused",
                            "downloaded_bytes": 0, "total_bytes": 0,
                        }, ensure_ascii=False))
                except Exception:
                    pass
        else:
            # 其他错误
            result["status"] = "error"
            result["error"] = f"下载失败: {error_msg}"

    # 清理取消标志文件（如果存在）
    if cancel_flag_file and os.path.exists(cancel_flag_file):
        try:
            os.remove(cancel_flag_file)
        except Exception:
            pass

    return json.dumps(result, ensure_ascii=False)
