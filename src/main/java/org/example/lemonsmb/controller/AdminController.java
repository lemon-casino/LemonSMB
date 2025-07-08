package org.example.lemonsmb.controller;

import org.example.lemonsmb.service.CacheProgressTracker;
import org.example.lemonsmb.service.CacheService;
import org.example.lemonsmb.service.FileWatchService;
import org.example.lemonsmb.service.SmbService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 管理员控制器，提供缓存管理相关的接口
 */
@RestController
@RequestMapping("/admin")
public class AdminController {
    
    @Autowired
    private SmbService smbService;
    
    @Autowired
    private CacheService cacheService;
    
    @Autowired
    private CacheProgressTracker progressTracker;
    
    @Autowired
    private FileWatchService fileWatchService;
    
    /**
     * 重建索引
     */
    @GetMapping("/rebuild-index")
    public Map<String, Object> rebuildIndex() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 重置缓存状态
            progressTracker.resetCacheStatus();
            
            // 异步重建索引
            CompletableFuture<Void> future = smbService.indexLibrary();
            
            result.put("status", "success");
            result.put("message", "索引重建已启动，请稍后查看日志了解进度");
            
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", "启动索引重建失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 获取缓存状态
     */
    @GetMapping("/cache-status")
    public Map<String, Object> getCacheStatus() {
        Map<String, Object> result = new HashMap<>();
        
        result.put("isCompleted", progressTracker.isCacheCompleted());
        result.put("processedFilesCount", progressTracker.getProcessedFilesCount());
        result.put("processedFoldersCount", progressTracker.getProcessedFoldersCount());
        result.put("cachedFilesCount", cacheService.getCachedFileCount());
        result.put("cachedFoldersCount", cacheService.getCachedFolderCount());
        result.put("otherCacheCount", cacheService.getOtherCacheCount());
        result.put("lastPosition", progressTracker.getLastPosition());
        result.put("metadataLastModified", progressTracker.getMetadataLastModified());
        
        return result;
    }
    
    /**
     * 清除缓存进度
     */
    @GetMapping("/clear-progress")
    public Map<String, Object> clearProgress() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            progressTracker.clearAllProgress();
            
            result.put("status", "success");
            result.put("message", "缓存进度已清除");
            
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", "清除缓存进度失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 手动检查metadata.json变化
     */
    @GetMapping("/check-metadata")
    public Map<String, Object> checkMetadata() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            fileWatchService.forceCheckMetadata();
            
            result.put("status", "success");
            result.put("message", "已触发metadata.json检查");
            
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", "检查metadata.json失败: " + e.getMessage());
        }
        
        return result;
    }
} 