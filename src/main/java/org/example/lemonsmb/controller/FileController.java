package org.example.lemonsmb.controller;

import org.example.lemonsmb.service.SmbService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.example.lemonsmb.model.FileEntry;
import java.util.concurrent.CompletableFuture;

@RestController
public class FileController {

    @Autowired
    private SmbService smbService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 获取文件夹元数据
     */
    @GetMapping("/metadata")
    public String metadata() {
        return redisTemplate.opsForValue().get("metadata");
    }

    /**
     * 获取文件列表
     */
    @GetMapping("/files")
    public CompletableFuture<List<FileEntry>> list(
            @RequestParam(defaultValue = "") String path,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {
        return smbService.listFiles(path, offset, limit);
    }

    /**
     * 获取文件详细信息
     */
    @GetMapping("/file-info")
    public CompletableFuture<Map<String, Object>> getFileInfo(@RequestParam String path) {
        return smbService.getFileInfo(path)
                .thenApply(fileInfo -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("name", fileInfo.getName());
                    result.put("size", fileInfo.getSize());
                    result.put("lastModified", fileInfo.getLastModified());
                    result.put("isDirectory", fileInfo.isDirectory());
                    result.put("extension", getFileExtension(fileInfo.getName()));
                    result.put("type", getFileType(getFileExtension(fileInfo.getName())));
                    return result;
                });
    }

    /**
     * 获取图片资源
     */
    @GetMapping("/image")
    public CompletableFuture<ResponseEntity<byte[]>> image(
            @RequestParam String id,
            @RequestParam(defaultValue = "true") boolean thumbnail) {
        System.out.println("请求图片: " + id + ", thumbnail: " + thumbnail);
        String ext = getFileExtension(id);
        MediaType type = getImageMediaType(ext);
        
        return smbService.loadImage(id, thumbnail)
                .thenApply(data -> {
                    System.out.println("图片数据长度: " + data.length + " bytes");
                    if (data.length == 0) {
                        return ResponseEntity.notFound().build();
                    }
                    return ResponseEntity.ok()
                            .contentType(type)
                            .header("Cache-Control", "public, max-age=86400") // 缓存24小时
                            .body(data);
                });
    }

    /**
     * 获取文件内容（用于预览）
     */
    @GetMapping("/preview")
    public CompletableFuture<ResponseEntity<byte[]>> preview(@RequestParam String id) {
        String ext = getFileExtension(id);
        String mimeType = getMimeType(ext);
        
        return smbService.loadFile(id)
                .thenApply(data -> {
                    MediaType mediaType = MediaType.parseMediaType(mimeType);
                    return ResponseEntity.ok()
                            .contentType(mediaType)
                            .header("Cache-Control", "public, max-age=3600") // 缓存1小时
                            .body(data);
                });
    }

    /**
     * 下载文件
     */
    @GetMapping("/download")
    public CompletableFuture<ResponseEntity<byte[]>> download(@RequestParam String id) {
        return smbService.loadFile(id)
                .thenApply(data -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header("Content-Disposition", "attachment; filename=\"" + id + "\"")
                        .body(data));
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot + 1).toLowerCase() : "";
    }

    /**
     * 获取图片媒体类型
     */
    private MediaType getImageMediaType(String extension) {
        return switch (extension) {
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "png" -> MediaType.IMAGE_PNG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "webp" -> MediaType.parseMediaType("image/webp");
            case "bmp" -> MediaType.parseMediaType("image/bmp");
            case "svg" -> MediaType.parseMediaType("image/svg+xml");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    /**
     * 获取文件MIME类型
     */
    private String getMimeType(String extension) {
        return switch (extension) {
            // 图片类型
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "svg" -> "image/svg+xml";
            
            // 视频类型
            case "mp4" -> "video/mp4";
            case "avi" -> "video/x-msvideo";
            case "mov" -> "video/quicktime";
            case "wmv" -> "video/x-ms-wmv";
            case "flv" -> "video/x-flv";
            case "mkv" -> "video/x-matroska";
            case "webm" -> "video/webm";
            
            // 音频类型
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "flac" -> "audio/flac";
            case "aac" -> "audio/aac";
            case "ogg" -> "audio/ogg";
            case "m4a" -> "audio/mp4";
            
            // 文档类型
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt" -> "application/vnd.ms-powerpoint";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "txt" -> "text/plain; charset=utf-8";
            case "rtf" -> "application/rtf";
            
            // 压缩包类型
            case "zip" -> "application/zip";
            case "rar" -> "application/vnd.rar";
            case "7z" -> "application/x-7z-compressed";
            case "tar" -> "application/x-tar";
            case "gz" -> "application/gzip";
            
            // 代码文件类型
            case "js" -> "application/javascript";
            case "html", "htm" -> "text/html; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "json" -> "application/json; charset=utf-8";
            case "xml" -> "application/xml; charset=utf-8";
            case "java" -> "text/x-java-source; charset=utf-8";
            case "py" -> "text/x-python; charset=utf-8";
            case "cpp", "cc", "cxx" -> "text/x-c++src; charset=utf-8";
            case "c" -> "text/x-csrc; charset=utf-8";
            case "h" -> "text/x-chdr; charset=utf-8";
            case "php" -> "text/x-php; charset=utf-8";
            case "rb" -> "text/x-ruby; charset=utf-8";
            case "go" -> "text/x-go; charset=utf-8";
            case "rs" -> "text/x-rust; charset=utf-8";
            case "sql" -> "text/x-sql; charset=utf-8";
            case "sh" -> "text/x-shellscript; charset=utf-8";
            case "bat" -> "text/x-msdos-batch; charset=utf-8";
            case "ps1" -> "text/x-powershell; charset=utf-8";
            
            // 其他常见类型
            case "md", "markdown" -> "text/markdown; charset=utf-8";
            case "yml", "yaml" -> "text/yaml; charset=utf-8";
            case "csv" -> "text/csv; charset=utf-8";
            case "log" -> "text/plain; charset=utf-8";
            case "ini", "cfg", "conf" -> "text/plain; charset=utf-8";
            
            // 默认类型
            default -> "application/octet-stream";
        };
    }

    /**
     * 获取文件类型信息
     */
    private Map<String, Object> getFileType(String extension) {
        Map<String, Object> typeInfo = new HashMap<>();
        
        switch (extension) {
            // 图片类型
            case "jpg", "jpeg", "png", "gif", "bmp", "webp" -> {
                typeInfo.put("category", "image");
                typeInfo.put("name", "图片");
                typeInfo.put("icon", "fas fa-image");
                typeInfo.put("color", "#10b981");
                typeInfo.put("previewable", true);
            }
            case "svg" -> {
                typeInfo.put("category", "image");
                typeInfo.put("name", "矢量图");
                typeInfo.put("icon", "fas fa-vector-square");
                typeInfo.put("color", "#8b5cf6");
                typeInfo.put("previewable", true);
            }
            
            // 视频类型
            case "mp4", "avi", "mov", "wmv", "flv", "mkv", "webm" -> {
                typeInfo.put("category", "video");
                typeInfo.put("name", "视频");
                typeInfo.put("icon", "fas fa-video");
                typeInfo.put("color", "#ef4444");
                typeInfo.put("previewable", true);
            }
            
            // 音频类型
            case "mp3", "wav", "flac", "aac", "ogg", "m4a" -> {
                typeInfo.put("category", "audio");
                typeInfo.put("name", "音频");
                typeInfo.put("icon", "fas fa-music");
                typeInfo.put("color", "#f59e0b");
                typeInfo.put("previewable", true);
            }
            
            // 文档类型
            case "pdf" -> {
                typeInfo.put("category", "document");
                typeInfo.put("name", "PDF");
                typeInfo.put("icon", "fas fa-file-pdf");
                typeInfo.put("color", "#dc2626");
                typeInfo.put("previewable", true);
            }
            case "doc", "docx" -> {
                typeInfo.put("category", "document");
                typeInfo.put("name", "Word文档");
                typeInfo.put("icon", "fas fa-file-word");
                typeInfo.put("color", "#2563eb");
                typeInfo.put("previewable", false);
            }
            case "xls", "xlsx" -> {
                typeInfo.put("category", "document");
                typeInfo.put("name", "Excel表格");
                typeInfo.put("icon", "fas fa-file-excel");
                typeInfo.put("color", "#059669");
                typeInfo.put("previewable", false);
            }
            case "ppt", "pptx" -> {
                typeInfo.put("category", "document");
                typeInfo.put("name", "PowerPoint");
                typeInfo.put("icon", "fas fa-file-powerpoint");
                typeInfo.put("color", "#ea580c");
                typeInfo.put("previewable", false);
            }
            case "txt", "md", "log" -> {
                typeInfo.put("category", "text");
                typeInfo.put("name", "文本");
                typeInfo.put("icon", "fas fa-file-alt");
                typeInfo.put("color", "#64748b");
                typeInfo.put("previewable", true);
            }
            
            // 压缩包类型
            case "zip", "rar", "7z", "tar", "gz" -> {
                typeInfo.put("category", "archive");
                typeInfo.put("name", "压缩包");
                typeInfo.put("icon", "fas fa-file-archive");
                typeInfo.put("color", "#7c3aed");
                typeInfo.put("previewable", false);
            }
            
            // 代码文件类型
            case "js" -> {
                typeInfo.put("category", "code");
                typeInfo.put("name", "JavaScript");
                typeInfo.put("icon", "fab fa-js-square");
                typeInfo.put("color", "#f7df1e");
                typeInfo.put("previewable", true);
            }
            case "html", "htm" -> {
                typeInfo.put("category", "code");
                typeInfo.put("name", "HTML");
                typeInfo.put("icon", "fab fa-html5");
                typeInfo.put("color", "#e34f26");
                typeInfo.put("previewable", true);
            }
            case "css" -> {
                typeInfo.put("category", "code");
                typeInfo.put("name", "CSS");
                typeInfo.put("icon", "fab fa-css3-alt");
                typeInfo.put("color", "#1572b6");
                typeInfo.put("previewable", true);
            }
            case "java" -> {
                typeInfo.put("category", "code");
                typeInfo.put("name", "Java");
                typeInfo.put("icon", "fab fa-java");
                typeInfo.put("color", "#ed8b00");
                typeInfo.put("previewable", true);
            }
            case "py" -> {
                typeInfo.put("category", "code");
                typeInfo.put("name", "Python");
                typeInfo.put("icon", "fab fa-python");
                typeInfo.put("color", "#3776ab");
                typeInfo.put("previewable", true);
            }
            
            // 默认类型
            default -> {
                typeInfo.put("category", "unknown");
                typeInfo.put("name", "文件");
                typeInfo.put("icon", "fas fa-file");
                typeInfo.put("color", "#64748b");
                typeInfo.put("previewable", false);
            }
        }
        
        return typeInfo;
    }
}
