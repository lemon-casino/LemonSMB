package org.example.lemonsmb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 缓存进度跟踪器，用于记录缓存进度和支持断点续传
 */
@Service
public class CacheProgressTracker {
    
    private static final String PROGRESS_KEY = "cache:progress";
    private static final String COMPLETED_KEY = "cache:progress:completed";
    private static final String LAST_POSITION_KEY = "cache:progress:last_position";
    private static final String PROCESSED_FOLDERS_KEY = "cache:progress:folders";
    private static final String PROCESSED_FILES_KEY = "cache:progress:files";
    private static final String LAST_UPDATE_TIME_KEY = "cache:progress:last_update_time";
    private static final String METADATA_LAST_MODIFIED_KEY = "cache:metadata:last_modified";
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    private final ObjectMapper mapper = new ObjectMapper();
    
    /**
     * 检查缓存是否已完成
     */
    public boolean isCacheCompleted() {
        String completed = redisTemplate.opsForValue().get(COMPLETED_KEY);
        return "true".equals(completed);
    }
    
    /**
     * 标记缓存已完成
     */
    public void markCacheCompleted() {
        redisTemplate.opsForValue().set(COMPLETED_KEY, "true");
        // 记录完成时间
        redisTemplate.opsForValue().set(LAST_UPDATE_TIME_KEY, String.valueOf(System.currentTimeMillis()));
        System.out.println("缓存已标记为完成状态");
    }
    
    /**
     * 重置缓存完成状态
     */
    public void resetCacheStatus() {
        redisTemplate.opsForValue().set(COMPLETED_KEY, "false");
        System.out.println("缓存状态已重置");
    }
    
    /**
     * 获取上次处理位置
     */
    public String getLastPosition() {
        return redisTemplate.opsForValue().get(LAST_POSITION_KEY);
    }
    
    /**
     * 更新处理位置
     */
    public void updateLastPosition(String position) {
        redisTemplate.opsForValue().set(LAST_POSITION_KEY, position);
    }
    
    /**
     * 记录已处理的文件夹
     */
    public void markFolderProcessed(String folderId) {
        redisTemplate.opsForSet().add(PROCESSED_FOLDERS_KEY, folderId);
    }
    
    /**
     * 检查文件夹是否已处理
     */
    public boolean isFolderProcessed(String folderId) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(PROCESSED_FOLDERS_KEY, folderId));
    }
    
    /**
     * 获取所有已处理的文件夹
     */
    public Set<String> getProcessedFolders() {
        Set<String> members = redisTemplate.opsForSet().members(PROCESSED_FOLDERS_KEY);
        return members != null ? members : new HashSet<>();
    }
    
    /**
     * 记录已处理的文件
     */
    public void markFileProcessed(String fileId) {
        redisTemplate.opsForSet().add(PROCESSED_FILES_KEY, fileId);
    }
    
    /**
     * 检查文件是否已处理
     */
    public boolean isFileProcessed(String fileId) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(PROCESSED_FILES_KEY, fileId));
    }
    
    /**
     * 获取已处理的文件数量
     */
    public long getProcessedFilesCount() {
        Long size = redisTemplate.opsForSet().size(PROCESSED_FILES_KEY);
        return size != null ? size : 0;
    }
    
    /**
     * 获取已处理的文件夹数量
     */
    public long getProcessedFoldersCount() {
        Long size = redisTemplate.opsForSet().size(PROCESSED_FOLDERS_KEY);
        return size != null ? size : 0;
    }
    
    /**
     * 更新metadata.json的最后修改时间
     */
    public void updateMetadataLastModified(long timestamp) {
        redisTemplate.opsForValue().set(METADATA_LAST_MODIFIED_KEY, String.valueOf(timestamp));
    }
    
    /**
     * 获取metadata.json的最后修改时间
     */
    public long getMetadataLastModified() {
        String value = redisTemplate.opsForValue().get(METADATA_LAST_MODIFIED_KEY);
        return value != null ? Long.parseLong(value) : 0;
    }
    
    /**
     * 清除所有进度信息
     */
    public void clearAllProgress() {
        redisTemplate.delete(PROGRESS_KEY);
        redisTemplate.delete(COMPLETED_KEY);
        redisTemplate.delete(LAST_POSITION_KEY);
        redisTemplate.delete(PROCESSED_FOLDERS_KEY);
        redisTemplate.delete(PROCESSED_FILES_KEY);
        redisTemplate.delete(LAST_UPDATE_TIME_KEY);
        System.out.println("所有缓存进度信息已清除");
    }
} 