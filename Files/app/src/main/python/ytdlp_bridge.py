"""
yt-dlp 桥接模块
为 Android Java 代码提供 Python yt-dlp 调用接口

包含函数：
- extractVideoInfo(url, cookieFile): 解析视频信息
- downloadVideo(url, formatId, outputDir, cookies): 下载视频
- downloadVideoWithProgress(url, formatId, outputDir, cookies, progressFile, cancelFlag): 带进度的下载
- testEnvironment(): 测试环境
"""

import json
import sys
import os
import time
import yt_dlp
from yt_dlp import YoutubeDL
from yt_dlp.utils import DownloadError

# 使用 Android Logcat 输出日志，标签为 "FF-media"
# 通过 Chaquopy 的 Java 桥接调用 android.util.Log
from java import jclass
_AndroidLog = jclass('android.util.Log')
# 日志标签常量 - 用于 logcat 过滤，用户可通过 adb logcat -s FF-media 查看
LOGCAT_TAG = "FF-media"

# 全局 FFmpeg 路径缓存，供下载函数中使用
_FFMPEG_PATH = None

def log_d(message):
    """输出 DEBUG 级别 logcat 日志（标签: FF-media）"""
    _AndroidLog.d(LOGCAT_TAG, message)

def log_i(message):
    """输出 INFO 级别 logcat 日志（标签: FF-media）"""
    _AndroidLog.i(LOGCAT_TAG, message)

def log_w(message):
    """输出 WARN 级别 logcat 日志（标签: FF-media）"""
    _AndroidLog.w(LOGCAT_TAG, message)

def log_e(message):
    """输出 ERROR 级别 logcat 日志（标签: FF-media）"""
    _AndroidLog.e(LOGCAT_TAG, message)

# ========== 合并流程日志辅助 ==========
# 合并流程的阶段标识，用于追踪合并进度
MERGE_PHASE_BEFORE = "合并前准备"
MERGE_PHASE_DOWNLOAD = "下载阶段"
MERGE_PHASE_PROCESSOR = "后处理器阶段(音视频合并)"
MERGE_PHASE_VERIFY = "合并验证"
MERGE_PHASE_DONE = "完成"

def log_merge(phase, message):
    """
    输出合并流程日志（通过 Android Logcat，标签 FF-media）
    格式: [merge][阶段名] 消息内容
    """
    _AndroidLog.i(LOGCAT_TAG, f"[merge][{phase}] {message}")

def check_ffmpeg_available():
    """检查 FFmpeg 是否可用且可执行（yt-dlp 合并依赖它）
    
    找到 FFmpeg 后，会缓存路径到全局变量 _FFMPEG_PATH，
    并设置环境变量 FFMPEG_PATH 和 FFMPEG_BINARY，
    供 downloadVideoWithProgress 中配置 ydl_opts 使用。
    """
    global _FFMPEG_PATH
    import subprocess
    import stat
    
    # 首先尝试从 FFmpegManager 写入的配置文件中读取 FFmpeg 路径
    # 配置文件由 FFmpegManager.java 的 setEnvForPython() 写入
    # 路径模式：/data/data/com.example.grayvideodl/cache/ffmpeg_path.conf
    configFile = "/data/data/com.example.grayvideodl/cache/ffmpeg_path.conf"
    if os.path.exists(configFile):
        try:
            with open(configFile, 'r') as f:
                ffmpeg_path_from_config = f.read().strip()
            if ffmpeg_path_from_config and os.path.exists(ffmpeg_path_from_config):
                log_i(f"从配置文件读取到 FFmpeg 路径: {ffmpeg_path_from_config}")
                # 验证 FFmpeg 是否可执行
                if verify_ffmpeg_executable(ffmpeg_path_from_config):
                    _FFMPEG_PATH = ffmpeg_path_from_config
                    set_ffmpeg_path(ffmpeg_path_from_config)
                    # 额外设置 FFMPEG_BINARY（某些 yt-dlp 版本使用）
                    os.environ['FFMPEG_BINARY'] = ffmpeg_path_from_config
                    return True
                else:
                    log_w(f"配置文件中的 FFmpeg 不可执行: {ffmpeg_path_from_config}")
        except Exception as e:
            log_w(f"读取 FFmpeg 配置文件失败: {e}")

    # 尝试通过 FFMPEG_PATH 环境变量查找
    ffmpeg_path_env = os.environ.get('FFMPEG_PATH', '')
    if ffmpeg_path_env and os.path.exists(ffmpeg_path_env):
        log_i(f"FFMPEG_PATH 环境变量指向的 FFmpeg: {ffmpeg_path_env}")
        if verify_ffmpeg_executable(ffmpeg_path_env):
            _FFMPEG_PATH = ffmpeg_path_env
            # 注意：此时 FFMPEG_PATH 环境变量已存在，但仍需确保 DIR 版本
            os.environ['FFMPEG_BINARY'] = ffmpeg_path_env
            return True
        else:
            log_w(f"FFMPEG_PATH 环境变量指向的 FFmpeg 不可执行: {ffmpeg_path_env}")
    
    # 尝试查找 Android 应用内部的 FFmpeg（由 FFmpegManager 下载到 files 目录）
    android_ffmpeg_paths = [
        '/data/user/0/com.example.grayvideodl/files/ffmpeg',
        '/data/data/com.example.grayvideodl/files/ffmpeg',
        '/data/local/tmp/ffmpeg',
        '/system/bin/ffmpeg'
    ]
    
    log_i("开始在 Android 内部路径搜索 FFmpeg...")
    for ffmpeg_path in android_ffmpeg_paths:
        exists = os.path.exists(ffmpeg_path)
        log_i(f"  检查路径: {ffmpeg_path} -> {'存在' if exists else '不存在'}")
        if exists:
            log_i(f"找到 Android 内部 FFmpeg: {ffmpeg_path}")
            # 验证 FFmpeg 是否可执行
            if verify_ffmpeg_executable(ffmpeg_path):
                # 设置全局 FFmpeg 路径
                _FFMPEG_PATH = ffmpeg_path
                set_ffmpeg_path(ffmpeg_path)
                os.environ['FFMPEG_BINARY'] = ffmpeg_path
                return True
            else:
                log_w(f"FFmpeg 文件存在但不可执行: {ffmpeg_path}")
                # 尝试添加执行权限
                try:
                    file_stat = os.stat(ffmpeg_path)
                    os.chmod(ffmpeg_path, file_stat.st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
                    log_i(f"已添加 FFmpeg 执行权限")
                    if verify_ffmpeg_executable(ffmpeg_path):
                        _FFMPEG_PATH = ffmpeg_path
                        set_ffmpeg_path(ffmpeg_path)
                        os.environ['FFMPEG_BINARY'] = ffmpeg_path
                        return True
                except Exception as e:
                    log_w(f"添加 FFmpeg 执行权限失败: {e}")
    
    log_e("FFmpeg 不可用或不可执行，音视频合并功能受限")
    return False


def verify_ffmpeg_executable(ffmpeg_path):
    """验证 FFmpeg 是否可执行"""
    import subprocess
    try:
        result = subprocess.run([ffmpeg_path, '-version'], capture_output=True, timeout=5)
        if result.returncode == 0:
            version_info = result.stdout.decode('utf-8', errors='ignore').split('\n')[0]
            log_i(f"FFmpeg 可执行验证成功: {version_info}")
            return True
        else:
            log_w(f"FFmpeg 执行验证失败，返回码: {result.returncode}")
            return False
    except FileNotFoundError:
        log_w(f"FFmpeg 可执行文件未找到: {ffmpeg_path}")
        return False
    except subprocess.TimeoutExpired:
        log_w(f"FFmpeg 执行超时")
        return False
    except Exception as e:
        log_w(f"FFmpeg 执行验证异常: {e}")
        return False

def set_ffmpeg_path(ffmpeg_path):
    """设置 yt-dlp 使用的 FFmpeg 路径"""
    try:
        # 通过环境变量设置
        os.environ['FFMPEG_PATH'] = ffmpeg_path
        log_i(f"已设置 FFMPEG_PATH 环境变量: {ffmpeg_path}")
    except Exception as e:
        log_e(f"设置 FFmpeg 路径失败: {e}")

def analyze_format_merge_need(formats, selected_format_id):
    """
    分析选定的格式是否需要合并（即视频流和音频流分离）。
    如果选定的格式只有视频没有音频，或只有音频没有视频，则需要合并。
    """
    selected = None
    for f in formats:
        if f.get('format_id') == selected_format_id:
            selected = f
            break
    
    if selected is None:
        log_w(f"未找到选定格式 {selected_format_id}")
        return {
            'needs_merge': False,
            'reason': '格式未找到',
            'vcodec': 'none',
            'acodec': 'none',
            'format_id': selected_format_id
        }
    
    vcodec = selected.get('vcodec', 'none')
    acodec = selected.get('acodec', 'none')
    has_video = vcodec not in ('none', None)
    has_audio = acodec not in ('none', None)
    
    # 判断是否需要合并
    needs_merge = not (has_video and has_audio)
    
    reason = ""
    if has_video and has_audio:
        reason = "视频流+音频流已合并，无需后处理合并"
    elif has_video and not has_audio:
        # 视频-only格式需要额外下载音频合并
        # 检查 formatId 是否包含 "+" 格式（yt-dlp 自动合并）
        if '+' in selected_format_id:
            reason = "使用yt-dlp自动合并格式(bestvideo+bestaudio)，下载后由FFmpeg合并"
        else:
            reason = "纯视频流(无音频)，需要额外下载音频流再合并"
        needs_merge = True
    elif not has_video and has_audio:
        reason = "纯音频流(无视频)"
        needs_merge = False  # 音频不需要合并
    else:
        reason = "未知格式(无视频无音频)"
        needs_merge = False
    
    return {
        'needs_merge': needs_merge,
        'reason': reason,
        'vcodec': vcodec,
        'acodec': acodec,
        'format_id': selected_format_id,
        'selected_format': selected
    }

def list_output_files(output_dir, title_hint=None):
    """列出输出目录中的所有文件，用于验证合并产物"""
    try:
        files = os.listdir(output_dir)
        video_files = [f for f in files if f.endswith(('.mp4', '.mkv', '.webm', '.m4a', '.mp3')) 
                       and not f.startswith('.')]
        
        log_merge(MERGE_PHASE_VERIFY, f"输出目录 '{output_dir}' 中共 {len(video_files)} 个媒体文件:")
        for f in sorted(video_files):
            fpath = os.path.join(output_dir, f)
            fsize = os.path.getsize(fpath)
            # 检测是否是中间临时文件（如 .f137.mp4, .f140.m4a）
            is_temp = '.f' in f and f.rsplit('.', 1)[0].split('.')[-1].startswith('f')
            tag = " [中间文件]" if is_temp else ""
            log_merge(MERGE_PHASE_VERIFY, f"  - {f} ({fsize} 字节){tag}")
        
        return video_files
    except Exception as e:
        log_e(f"列出输出目录失败: {e}")
        return []

# ========== 平台检测 ==========
def _detect_platform(url):
    """检测视频链接所属平台"""
    lower_url = url.lower().strip()
    if 'bilibili.com' in lower_url or 'b23.tv' in lower_url:
        return 'bilibili'
    elif 'douyin.com' in lower_url or 'iesdouyin.com' in lower_url or 'douyinvideo.com' in lower_url:
        return 'douyin'
    elif 'kuaishou.com' in lower_url or 'kuaishou.cn' in lower_url:
        return 'kuaishou'
    elif 'youtube.com' in lower_url or 'youtu.be' in lower_url:
        return 'youtube'
    elif 'tiktok.com' in lower_url:
        return 'tiktok'
    elif 'twitter.com' in lower_url or '//x.com' in lower_url:
        return 'twitter'
    elif 'instagram.com' in lower_url:
        return 'instagram'
    elif 'weibo.com' in lower_url:
        return 'weibo'
    elif 'xiaohongshu.com' in lower_url:
        return 'xiaohongshu'
    elif 'v.qq.com' in lower_url:
        return 'vqq'
    elif 'iqiyi.com' in lower_url:
        return 'iqiyi'
    elif 'youku.com' in lower_url:
        return 'youku'
    elif 'twitch.tv' in lower_url:
        return 'twitch'
    return 'unknown'

# ========== 视频信息提取 ==========
def extractVideoInfo(url, cookieFile=None):
    """
    解析视频信息
    :param url: 视频链接
    :param cookieFile: Cookie 文件路径（可选）
    :return: JSON 字符串，包含视频信息或错误
    """
    try:
        # 构建 yt-dlp 参数
        ydl_opts = {
            'quiet': True,
            'no_warnings': True,
            'extract_flat': False,
            'force_generic_extractor': False,
        }
        
        # 添加 Cookie 文件
        if cookieFile and os.path.exists(cookieFile):
            ydl_opts['cookiefile'] = cookieFile
        
        # 添加自定义请求头，模拟浏览器
        ydl_opts['headers'] = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
            'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
        }
        
        # 针对特定平台的特殊配置
        platform = _detect_platform(url)
        if platform == 'douyin':
            ydl_opts['referer'] = 'https://www.douyin.com/'
            ydl_opts['extractor_args'] = {'douyin': {'webpage_url': url}}
        elif platform == 'kuaishou':
            ydl_opts['referer'] = 'https://www.kuaishou.com/'
            ydl_opts['force_generic_extractor'] = True
        
        with YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)
            
            if info is None:
                return json.dumps({
                    'status': 'error',
                    'error': '未能提取到视频信息'
                })
            
            # 处理播放列表情况
            if 'entries' in info:
                info = info['entries'][0] if info['entries'] else info
            
            # 提取格式信息
            formats = []
            if 'formats' in info:
                for fmt in info['formats']:
                    format_info = {
                        'format_id': fmt.get('format_id', ''),
                        'ext': fmt.get('ext', ''),
                        'resolution': fmt.get('resolution', '') or fmt.get('height', 'unknown'),
                        'filesize': fmt.get('filesize', 0),
                        'vcodec': fmt.get('vcodec', ''),
                        'acodec': fmt.get('acodec', ''),
                        'fps': fmt.get('fps', 0),
                    }
                    formats.append(format_info)
            
            # 构建返回结果
            result = {
                'status': 'ok',
                'title': info.get('title', ''),
                'duration': info.get('duration', 0),
                'thumbnail': info.get('thumbnail', ''),
                'uploader': info.get('uploader', ''),
                'webpage_url': info.get('webpage_url', ''),
                'formats': formats,
                'error': ''
            }
            
            return json.dumps(result, ensure_ascii=False)
            
    except DownloadError as e:
        error_msg = str(e)
        return json.dumps({
            'status': 'error',
            'error': f'视频信息提取失败: {error_msg}',
            '已尝试URL': [url]
        })
    except Exception as e:
        return json.dumps({
            'status': 'error',
            'error': f'未知错误: {str(e)}'
        })

# ========== 视频下载 ==========
def downloadVideo(url, formatId, outputDir, cookieFile=None):
    """
    下载视频（不带进度）
    :param url: 视频链接
    :param formatId: 格式ID
    :param outputDir: 输出目录
    :param cookieFile: Cookie 文件路径（可选）
    :return: JSON 字符串
    """
    try:
        # 确保输出目录存在
        os.makedirs(outputDir, exist_ok=True)
        
        ydl_opts = {
            'quiet': True,
            'no_warnings': True,
            'format': formatId,
            'outtmpl': os.path.join(outputDir, '%(title)s.%(ext)s'),
            'merge_output_format': 'mp4',
        }
        
        if cookieFile and os.path.exists(cookieFile):
            ydl_opts['cookiefile'] = cookieFile
        
        ydl_opts['headers'] = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        }
        
        with YoutubeDL(ydl_opts) as ydl:
            ydl.download([url])
        
        return json.dumps({'status': 'ok', 'error': ''})
        
    except Exception as e:
        return json.dumps({
            'status': 'error',
            'error': str(e)
        })

def downloadVideoWithProgress(url, formatId, outputDir, cookieFile=None, progressFile=None, cancelFlag=None):
    """
    下载视频（带进度回调）
    :param url: 视频链接
    :param formatId: 格式ID
    :param outputDir: 输出目录
    :param cookieFile: Cookie 文件路径（可选）
    :param progressFile: 进度文件路径（可选，用于写入进度）
    :param cancelFlag: 取消标志文件路径（可选，检测此文件存在则取消下载）
    :return: JSON 字符串
    """
    try:
        os.makedirs(outputDir, exist_ok=True)
        start_time = time.time()
        
        # 用于跟踪是否触发了 FFmpeg 不可用警告
        ffmpeg_warning_triggered = False
        ffmpeg_warning_message = ""
        
        # ==============================
        # 阶段1：合并前准备 - FFmpeg检查
        # ==============================
        log_merge(MERGE_PHASE_BEFORE, "========== 音视频合并流程开始 ==========")
        log_merge(MERGE_PHASE_BEFORE, f"URL: {url}")
        log_merge(MERGE_PHASE_BEFORE, f"formatId: {formatId}")
        
        # 检查 FFmpeg 是否可用
        ffmpeg_ok = check_ffmpeg_available()
        log_merge(MERGE_PHASE_BEFORE, f"FFmpeg可用: {ffmpeg_ok}")
        
        # 进度钩子
        def progress_hook(d):
            status = d.get('status', '')
            if status == 'downloading':
                downloaded_bytes = d.get('downloaded_bytes', 0)
                total_bytes = d.get('total_bytes', 0) or d.get('total_bytes_estimate', 0)
                percent = (downloaded_bytes / total_bytes) * 100 if total_bytes > 0 else 0
                
                # 写入进度文件（字段名必须与 Java 代码一致）
                if progressFile:
                    try:
                        progress_data = {
                            'status': 'downloading',
                            'percent': percent,
                            'downloaded_bytes': downloaded_bytes,
                            'total_bytes': total_bytes,
                            'speed': d.get('speed', 0),
                            'eta': d.get('eta', 0)
                        }
                        with open(progressFile, 'w') as f:
                            f.write(json.dumps(progress_data))
                        log_merge(MERGE_PHASE_DOWNLOAD, f"下载进度: {percent:.1f}%, "
                                  f"已下载={downloaded_bytes/1024/1024:.1f}MB, "
                                  f"总大小={total_bytes/1024/1024:.1f}MB, "
                                  f"速度={d.get('speed', 0)/1024/1024:.1f}MB/s, "
                                  f"ETA={d.get('eta', 0)}s")
                    except Exception as e:
                        log_e(f"写入进度文件失败: {e}")
                
                # 检查取消标志
                if cancelFlag and os.path.exists(cancelFlag):
                    log_merge(MERGE_PHASE_DOWNLOAD, "检测到取消标志，下载被用户取消")
                    raise Exception('下载已取消')
            elif status == 'finished':
                log_merge(MERGE_PHASE_DOWNLOAD, f"下载阶段完成，文件: {d.get('filename', '未知')}")
        
        # ==============================
        # 阶段2：音视频合并后处理器回调
        # ==============================
        # 自定义后处理器钩子，追踪合并进度
        def postprocessor_hook(d):
            """在 yt-dlp 后处理器执行时回调"""
            status = d.get('status', '')
            postprocessor = d.get('postprocessor', '未知')
            
            if status == 'started':
                log_merge(MERGE_PHASE_PROCESSOR, f"后处理器开始执行: {postprocessor}")
                log_merge(MERGE_PHASE_PROCESSOR, f"后处理器参数: {d}")
            elif status == 'processing':
                log_merge(MERGE_PHASE_PROCESSOR, f"后处理器执行中: {postprocessor}, 信息: {d}")
            elif status == 'finished':
                log_merge(MERGE_PHASE_PROCESSOR, f"后处理器完成: {postprocessor}")
                log_merge(MERGE_PHASE_PROCESSOR, f"处理结果: {d}")
            elif status == 'error':
                log_merge(MERGE_PHASE_PROCESSOR, f"后处理器错误: {d.get('error', '未知错误')}")
            else:
                log_merge(MERGE_PHASE_PROCESSOR, f"后处理器状态: status={status}, data={d}")
        
        # ==============================
        # 音视频合并配置
        # ==============================
        # 策略说明：
        # 1. 显式添加 FFmpegMerger 后处理器，确保不论 format 是否包含 +，
        #    只要下载了多个分离的流（视频+音频），后处理器就会执行合并。
        # 2. 同时保留 ffmpeg_location 配置，让 yt-dlp 知道 FFmpeg 的位置。
        # 3. 设置 FFMPEG_BINARY 和 FFMPEG_PATH 环境变量增强兼容性。
        
        # 使用全局缓存的 FFmpeg 路径（由 check_ffmpeg_available 设置）
        # 优先使用 _FFMPEG_PATH，fallback 到环境变量或其他路径
        ffmpeg_path = _FFMPEG_PATH
        if not (ffmpeg_path and os.path.exists(ffmpeg_path)):
            ffmpeg_path = os.environ.get('FFMPEG_BINARY', '')
        if not (ffmpeg_path and os.path.exists(ffmpeg_path)):
            ffmpeg_path = os.environ.get('FFMPEG_PATH', '')
        if not (ffmpeg_path and os.path.exists(ffmpeg_path)):
            # 尝试从已知路径查找
            android_ffmpeg_paths = [
                '/data/user/0/com.example.grayvideodl/files/ffmpeg',
                '/data/data/com.example.grayvideodl/files/ffmpeg',
                '/data/local/tmp/ffmpeg',
                '/system/bin/ffmpeg'
            ]
            for path in android_ffmpeg_paths:
                if os.path.exists(path):
                    ffmpeg_path = path
                    os.environ['FFMPEG_PATH'] = path
                    os.environ['FFMPEG_BINARY'] = path
                    break
        
        # ydl_opts 配置
        # 注意：postprocessors 暂不添加，待格式策略确定后再条件性地添加
        # 因为 FFmpegMerger 仅在 format 包含 +（多流合并）时有效
        ydl_opts = {
            'quiet': False,         # 关闭 quiet 以便查看 yt-dlp 详细输出
            'no_warnings': False,    # 保留警告
            'verbose': True,         # 开启 verbose 输出合并细节
            'format': formatId,
            'outtmpl': os.path.join(outputDir, '%(title)s.%(ext)s'),
            'merge_output_format': 'mp4',
            'overwrites': True,     # 强制覆盖已存在的文件
            'progress_hooks': [progress_hook],
            'postprocessor_hooks': [postprocessor_hook],
            # postprocessors 暂不设置，在格式策略决定后条件性添加
            'keepvideo': False,     # 合并完成后删除中间文件
            'noplaylist': True,
        }
        
        # 双重保障：配置 FFmpeg 路径
        # 在 ydl_opts 中设置 ffmpeg_location，同时已设置了环境变量
        if ffmpeg_path and os.path.exists(ffmpeg_path):
            ydl_opts['ffmpeg_location'] = ffmpeg_path
            log_i(f"配置 yt-dlp 使用 FFmpeg: {ffmpeg_path}")
            log_i(f"FFMPEG_BINARY 环境变量: {os.environ.get('FFMPEG_BINARY', '未设置')}")
        else:
            log_w("未找到有效的 FFmpeg 路径，合并可能失败")
            ffmpeg_ok = False
        
        # MediaMuxer 合并标志和视频ID，用于后续 FFmpeg 不可用时在 Java 层合并
        use_media_muxer = False
        video_id = ""
        
        if cookieFile and os.path.exists(cookieFile):
            ydl_opts['cookiefile'] = cookieFile
            log_i(f"使用Cookie文件: {cookieFile}")
        
        ydl_opts['headers'] = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        }
        
        # ==============================
        # 阶段3：提取视频信息并分析格式（使用临时 YoutubeDL 实例）
        # ==============================
        # 重要：必须先提取信息，分析格式，修改 ydl_opts['format']，
        # 然后再创建用于下载的 YoutubeDL 实例
        log_merge(MERGE_PHASE_BEFORE, "开始提取视频信息...")
        
        with YoutubeDL(ydl_opts) as ydl_info:
            info = ydl_info.extract_info(url, download=False)
        
        if info:
            log_merge(MERGE_PHASE_BEFORE, f"视频标题: {info.get('title', '未知')}")
            log_merge(MERGE_PHASE_BEFORE, f"视频ID: {info.get('id', '未知')}")
            
            # 分析格式
            formats = info.get('formats', [])
            log_merge(MERGE_PHASE_BEFORE, f"可用格式数量: {len(formats)}")
            
            # 分析选定格式的合并需求
            merge_analysis = analyze_format_merge_need(formats, formatId)
            log_merge(MERGE_PHASE_BEFORE, f"选定格式(formatId={formatId}) 分析结果:")
            log_merge(MERGE_PHASE_BEFORE, f"  - 视频编码: {merge_analysis['vcodec']}")
            log_merge(MERGE_PHASE_BEFORE, f"  - 音频编码: {merge_analysis['acodec']}")
            log_merge(MERGE_PHASE_BEFORE, f"  - 是否需要合并: {merge_analysis['needs_merge']}")
            log_merge(MERGE_PHASE_BEFORE, f"  - 原因: {merge_analysis['reason']}")
            
            # 统计所有格式中视频/音频的分布
            video_only = sum(1 for f in formats if f.get('vcodec') not in ('none', None) and f.get('acodec') in ('none', None))
            audio_only = sum(1 for f in formats if f.get('vcodec') in ('none', None) and f.get('acodec') not in ('none', None))
            combined = sum(1 for f in formats if f.get('vcodec') not in ('none', None) and f.get('acodec') not in ('none', None))
            log_merge(MERGE_PHASE_BEFORE, f"格式分布: 纯视频={video_only}, 纯音频={audio_only}, 已合并={combined}")
            
            # ==============================
            # 格式选择策略（根据 FFmpeg 可用性决定）
            # ==============================
            # 当选定的格式是纯视频流时：
            # - 如果 FFmpeg 可用：使用 "formatId+bestaudio" 格式，yt-dlp 会自动下载并合并
            # - 如果 FFmpeg 不可用：直接使用原始格式（纯视频），并给出警告
            
            if merge_analysis['needs_merge'] and merge_analysis['vcodec'] not in ('none', None) and merge_analysis['acodec'] in ('none', None):
                # 纯视频流，需要处理
                
                if ffmpeg_ok:
                    # FFmpeg 可用：自动添加最佳音频流
                    original_format = formatId
                    new_format = f"{formatId}+bestaudio"
                    log_merge(MERGE_PHASE_BEFORE, f"=== 自动音频流补充 ===")
                    log_merge(MERGE_PHASE_BEFORE, f"检测到纯视频流，FFmpeg 可用，自动添加最佳音频流")
                    log_merge(MERGE_PHASE_BEFORE, f"格式变更: {original_format} -> {new_format}")
                    log_merge(MERGE_PHASE_BEFORE, f"yt-dlp 将下载视频流 + 音频流，然后使用 FFmpeg 合并")
                    ydl_opts['format'] = new_format
                    formatId = new_format  # 更新 formatId 用于后续日志
                else:
                    # FFmpeg 不可用：无法合并，需要选择替代方案
                    log_w("FFmpeg 不可用，无法合并分离的视频流和音频流")
                    log_merge(MERGE_PHASE_BEFORE, "=== 启动格式选择策略 ===")
                    ffmpeg_warning_triggered = True
                    
                    # 策略1：查找已合并格式（视频+音频在同一格式中）
                    combined_formats = [f for f in formats 
                                        if f.get('vcodec') not in ('none', None) 
                                        and f.get('acodec') not in ('none', None)]
                    
                    if combined_formats:
                        # 选择分辨率最高的已合并格式
                        best_combined = max(combined_formats, 
                                           key=lambda f: f.get('height', 0) or 0)
                        new_format_id = best_combined.get('format_id')
                        log_merge(MERGE_PHASE_BEFORE, f"找到已合并格式，自动切换: {formatId} -> {new_format_id}")
                        log_merge(MERGE_PHASE_BEFORE, f"新格式信息: height={best_combined.get('height')}, "
                                  f"vcodec={best_combined.get('vcodec')}, acodec={best_combined.get('acodec')}")
                        ydl_opts['format'] = new_format_id
                        formatId = new_format_id  # 更新 formatId 用于后续日志
                        ffmpeg_warning_message = "已自动切换到已合并格式，视频应包含音频"
                    else:
                        # 策略2：没有已合并格式，使用 Android MediaMuxer 方案
                        # 分别下载视频流和音频流，在 Java 层使用 MediaMuxer 合并
                        # 绕过 FFmpeg 的 SELinux/noexec 执行限制
                        log_merge(MERGE_PHASE_BEFORE, f"无已合并格式，启动 MediaMuxer 合并方案")
                        log_merge(MERGE_PHASE_BEFORE, f"将分别下载视频流({formatId})和音频流(bestaudio)，在 Java 层合并")
                        video_id = info.get('id', '')
                        use_media_muxer = True
                        ffmpeg_warning_message = "使用 Android MediaMuxer 在 Java 层合并音视频"
                    
                    log_merge(MERGE_PHASE_BEFORE, "=== 格式选择策略结束 ===")
        
        # ==============================
        # 条件性添加 FFmpegMerger 后处理器
        # ==============================
        # 仅在最终 format 包含 +（多流合并）时才添加 FFmpegMerger
        # 若 format 为单个流，FFmpegMerger 会因缺少 'requested_formats' 而触发 KeyError
        # 注意：此时 formatId 已被格式策略更新为最终值
        if '+' in formatId:
            log_merge(MERGE_PHASE_BEFORE, f"检测到多流格式（{formatId}），添加 FFmpegMerger 后处理器进行音视频合并")
            ydl_opts['postprocessors'] = [{
                'key': 'FFmpegMerger',
            }]
        else:
            log_merge(MERGE_PHASE_BEFORE, f"单流格式（{formatId}），无需添加合并后处理器")
            ydl_opts['postprocessors'] = []
        
        # ==============================
        # 阶段4：开始下载（使用修改后的 ydl_opts 创建新的 YoutubeDL）
        # ==============================
        log_merge(MERGE_PHASE_DOWNLOAD, "========== 开始下载 ==========")
        
        # 记录下载前输出目录状态
        list_output_files(outputDir)
        
        # ==============================
        # 清理已存在的输出文件
        # ==============================
        # yt-dlp 在目标文件已存在时会跳过下载或添加后缀
        # 必须删除已存在的文件，确保重新下载并正确合并音视频
        if info:
            # 使用临时 YoutubeDL 实例获取预期文件名
            with YoutubeDL(ydl_opts) as ydl_temp:
                expected_filename = ydl_temp.prepare_filename(info)
            if expected_filename and os.path.exists(expected_filename):
                log_merge(MERGE_PHASE_DOWNLOAD, f"检测到已存在的文件，删除以触发重新下载: {expected_filename}")
                try:
                    os.remove(expected_filename)
                    log_merge(MERGE_PHASE_DOWNLOAD, f"已删除旧文件: {expected_filename}")
                except Exception as e:
                    log_merge(MERGE_PHASE_DOWNLOAD, f"删除旧文件失败: {e}")
        
        # 使用修改后的 ydl_opts 创建新的 YoutubeDL 实例进行下载
        with YoutubeDL(ydl_opts) as ydl:
            # 下载视频（包含后处理）
            downloaded_files = ydl.download([url])
            
            elapsed = time.time() - start_time
            log_merge(MERGE_PHASE_DOWNLOAD, f"下载/后处理总耗时: {elapsed:.1f} 秒")
            
            # 获取下载返回的文件路径
            if downloaded_files and len(downloaded_files) > 0 and downloaded_files[0]:
                filename = downloaded_files[0]
                log_merge(MERGE_PHASE_DOWNLOAD, f"download() 返回路径: {filename}")
            else:
                filename = ydl.prepare_filename(info) if info else ""
                log_merge(MERGE_PHASE_DOWNLOAD, f"下载返回为空，使用 prepare_filename: {filename}")
        
        # ==============================
        # 阶段4.5：MediaMuxer 音视频合并
        # 当 FFmpeg 不可用且无已合并格式时使用
        # ==============================
        if use_media_muxer and filename and os.path.exists(filename):
            log_merge(MERGE_PHASE_DOWNLOAD, "=== MediaMuxer 音视频合并 ===")
            
            # 获取视频文件路径（刚下载完成）
            video_path = filename
            
            # ==============================
            # 下载音频文件（使用单独的 YoutubeDL 实例）
            # ==============================
            # 构造音频输出路径模板，使用 _audio 后缀避免覆盖视频文件
            video_base_name = os.path.splitext(video_path)[0]
            audio_ydl_opts = dict(ydl_opts)
            # 音频只下载最佳音频流
            audio_ydl_opts['format'] = 'bestaudio'
            # 音频不需要任何后处理（无需合并）
            audio_ydl_opts['postprocessors'] = []
            # 音频输出模板：在视频文件名基础上加 _audio 后缀
            audio_ydl_opts['outtmpl'] = video_base_name + '_audio.%(ext)s'
            
            audio_file = None
            try:
                with YoutubeDL(audio_ydl_opts) as ydl_audio:
                    audio_result = ydl_audio.download([url])
                    log_merge(MERGE_PHASE_DOWNLOAD, f"音频 download() 返回: {audio_result}")
                    
                    # 尝试从 download() 返回值获取路径
                    if audio_result and len(audio_result) > 0 and audio_result[0]:
                        audio_file = audio_result[0]
                    else:
                        # 回退：在输出目录中查找音频临时文件
                        # 音频文件命名模式：{video_base_name}_audio.{ext}
                        audio_temp_base = video_base_name + '_audio.'
                        output_dir = os.path.dirname(video_path)
                        for f_name in os.listdir(output_dir):
                            if f_name.startswith(os.path.basename(video_base_name) + '_audio.'):
                                audio_file = os.path.join(output_dir, f_name)
                                break
                
                if audio_file and os.path.exists(audio_file):
                    log_merge(MERGE_PHASE_DOWNLOAD, f"音频下载完成: {audio_file}")
                    
                    # ==============================
                    # 使用 Java MediaMuxer 合并音视频
                    # MediaMuxerHelper 使用 Android 系统 API，无需 FFmpeg
                    # ==============================
                    # 临时输出文件（合并后重命名）
                    merged_temp = video_base_name + '_merged.mp4'
                    
                    # 通过 Chaquopy Java 桥接调用 MediaMuxerHelper 的静态方法
                    # 使用 jclass 加载 Java 类，与 Android Log 加载方式一致
                    MediaMuxerHelper = jclass('com.example.grayvideodl.MediaMuxerHelper')
                    merge_success = MediaMuxerHelper.mergeVideoAudio(
                        video_path, audio_file, merged_temp)
                    
                    if merge_success and os.path.exists(merged_temp):
                        # 合并成功：用合并文件替换原始视频文件
                        os.remove(video_path)
                        os.rename(merged_temp, video_path)
                        log_merge(MERGE_PHASE_DOWNLOAD, f"MediaMuxer 合并成功: {video_path}")
                        log_merge(MERGE_PHASE_DOWNLOAD, f"合并后文件大小: {os.path.getsize(video_path)} 字节")
                        # 更新 filename 变量，后续验证阶段使用
                        filename = video_path
                        # 清除警告（现在有声音了）
                        ffmpeg_warning_triggered = False
                        ffmpeg_warning_message = ""
                    else:
                        # 合并失败：保留原始视频文件（无音频）
                        log_merge(MERGE_PHASE_DOWNLOAD, f"MediaMuxer 合并失败，保留纯视频文件")
                        if os.path.exists(merged_temp):
                            try:
                                os.remove(merged_temp)
                            except:
                                pass
                        ffmpeg_warning_message = "MediaMuxer 合并失败，视频无声音"
                    
                    # 清理音频临时文件
                    try:
                        os.remove(audio_file)
                        log_merge(MERGE_PHASE_DOWNLOAD, f"已删除音频临时文件: {audio_file}")
                    except Exception as e:
                        log_merge(MERGE_PHASE_DOWNLOAD, f"删除音频临时文件失败: {e}")
                else:
                    log_merge(MERGE_PHASE_DOWNLOAD, f"音频下载失败，无法合并，视频将无声音")
                    ffmpeg_warning_message = "音频下载失败，视频无声音"
            except Exception as e:
                log_merge(MERGE_PHASE_DOWNLOAD, f"MediaMuxer 合并过程异常: {e}")
                ffmpeg_warning_message = f"MediaMuxer 合并异常: {e}"
        
        # ==============================
        # 阶段5：合并验证 - 检查最终文件
        # ==============================
        log_merge(MERGE_PHASE_VERIFY, "========== 合并验证 ==========")
        
        # 列出输出目录中的所有文件，检查合并结果
        output_files = list_output_files(outputDir)
        
        # 验证最终文件
        final_file = None
        if filename and os.path.exists(filename):
            final_file = filename
        else:
            # 尝试在输出目录中找最新的 .mp4 文件
            mp4_files = [f for f in output_files if f.endswith('.mp4')]
            if mp4_files:
                # 取最大的文件（排除中间临时文件）
                non_temp = [f for f in mp4_files if '.f' not in f.rsplit('.', 1)[0]]
                if non_temp:
                    latest = max(non_temp, key=lambda f: os.path.getsize(os.path.join(outputDir, f)))
                    final_file = os.path.join(outputDir, latest)
                    log_merge(MERGE_PHASE_VERIFY, f"在输出目录中找到合并后文件: {latest}")
        
        if final_file and os.path.exists(final_file):
            file_size = os.path.getsize(final_file)
            duration = elapsed
            log_merge(MERGE_PHASE_DONE, f"最终文件: {final_file}")
            log_merge(MERGE_PHASE_DONE, f"文件大小: {file_size} 字节 ({file_size/1024/1024:.1f} MB)")
            log_merge(MERGE_PHASE_DONE, f"总耗时: {duration:.1f} 秒")
            
            # ==============================
            # 音频流检测（仅 FFmpeg 可用时执行）
            # ==============================
            # 使用 FFmpeg 检查最终文件是否包含音频流
            # 如果 FFmpeg 可用但检测不到音频流，记录警告
            if ffmpeg_ok and _FFMPEG_PATH:
                try:
                    import subprocess
                    # 使用 ffmpeg -i 输出的流信息检查音频流数量
                    # ffmpeg -i file.mp4 2>&1 的输出中包含 Stream #0:0 Video... Stream #0:1 Audio...
                    check_result = subprocess.run(
                        [_FFMPEG_PATH, '-i', final_file],
                        capture_output=True, timeout=10
                    )
                    output = (check_result.stdout + check_result.stderr).decode('utf-8', errors='ignore')
                    
                    # 统计音频流数量（通过搜索 "Audio:" 关键字）
                    audio_stream_count = output.count('Audio:')
                    video_stream_count = output.count('Video:')
                    
                    log_merge(MERGE_PHASE_VERIFY, f"FFmpeg 流检测结果: 视频流={video_stream_count}, 音频流={audio_stream_count}")
                    
                    if audio_stream_count == 0 and video_stream_count > 0:
                        # 只有视频流没有音频流，说明合并可能失败
                        log_merge(MERGE_PHASE_VERIFY, "警告：最终文件没有音频流，音视频合并可能失败！")
                        ffmpeg_warning_triggered = True
                        ffmpeg_warning_message = f"合并后文件无音频流，视频可能无法播放声音"
                    elif audio_stream_count > 0:
                        log_merge(MERGE_PHASE_VERIFY, f"音频流检测通过，文件包含 {audio_stream_count} 个音频流")
                    else:
                        log_merge(MERGE_PHASE_VERIFY, "未检测到任何媒体流（可能是非媒体文件）")
                        
                except Exception as e:
                    log_merge(MERGE_PHASE_VERIFY, f"音频流检测异常: {e}")
        else:
            log_merge(MERGE_PHASE_VERIFY, f"警告：最终文件不存在或未找到: {filename}")
            # 尝试其他扩展名
            for ext in ['.mp4', '.mkv', '.webm']:
                base = filename.rsplit('.', 1)[0] if filename else ""
                alt = base + ext if base else ""
                if alt and os.path.exists(alt):
                    file_size = os.path.getsize(alt)
                    log_merge(MERGE_PHASE_VERIFY, f"找到其他扩展名文件: {alt} ({file_size} 字节)")
                    filename = alt
                    break
        
        log_merge(MERGE_PHASE_DONE, "========== 音视频合并流程结束 ==========")
        
        # 写入完成状态
        if progressFile:
            try:
                with open(progressFile, 'w') as f:
                    f.write(json.dumps({'status': 'completed', 'progress': 100}))
            except:
                pass
        
        # 返回包含文件路径的结果，如果触发了 FFmpeg 警告，包含警告信息
        result = {
            'status': 'ok',
            'error': '',
            'filepath': filename,
            'ffmpeg_warning': ffmpeg_warning_triggered,
            'ffmpeg_warning_message': ffmpeg_warning_message
        }
        return json.dumps(result)
        
    except Exception as e:
        log_merge(MERGE_PHASE_PROCESSOR, f"合并过程中发生异常: {e}")
        import traceback
        log_e(f"异常堆栈: {traceback.format_exc()}")
        if progressFile:
            try:
                with open(progressFile, 'w') as f:
                    f.write(json.dumps({'status': 'error', 'error': str(e)}))
            except:
                pass
        return json.dumps({
            'status': 'error',
            'error': str(e)
        })

# ========== 环境测试 ==========
def testEnvironment():
    """测试 yt-dlp 环境是否正常"""
    try:
        # 检查 yt-dlp 版本
        version = yt_dlp.version.__version__
        
        result = {
            'status': 'ok',
            'yt_dlp_installed': True,
            'yt_dlp_version': version,
            'python_version': f'{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}',
            'error': ''
        }
        
        return json.dumps(result)
        
    except Exception as e:
        return json.dumps({
            'status': 'error',
            'yt_dlp_installed': False,
            'error': str(e)
        })

# ========== 测试入口 ==========
if __name__ == '__main__':
    print(testEnvironment())
