package org.example.lemonsmb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.lemonsmb.model.FileEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 缓存服务，封装Redis缓存操作
 */
@Service
public class CacheService {
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    private final ObjectMapper mapper = new ObjectMapper();
    
    // 缓存命名空间前缀
    private static final String CACHE_PREFIX = "cache:";
    private static final String META_KEY = "meta:";
    private static final String FOLDER_KEY = "folder:";
    private static final String FILE_KEY = "file:";
    
    // 需要添加cache:前缀的键前缀列表（CompletableFuture相关）
    private static final Set<String> PREFIXED_KEYS = new HashSet<>(Arrays.asList(
        "image:", "file:", "files:", "file-info:"
    ));
    
    // 不需要添加cache:前缀的键前缀列表
    private static final Set<String> NON_PREFIXED_KEYS = new HashSet<>(Arrays.asList(
        "folder:", "meta:", "metadata", "folder-files:", "folder_info:"
    ));
    
    /**
     * 判断是否需要为键添加cache:前缀
     * @param key 缓存键
     * @return 是否需要添加前缀
     */
    private boolean shouldAddPrefix(String key) {
        // 检查是否明确指定为不需要前缀的键
        for (String nonPrefixedKey : NON_PREFIXED_KEYS) {
            if (key.startsWith(nonPrefixedKey) || key.equals(nonPrefixedKey.substring(0, nonPrefixedKey.length() - 1))) {
                return false;
            }
        }
        
        // 检查是否明确指定为需要前缀的键
        for (String prefixedKey : PREFIXED_KEYS) {
            if (key.startsWith(prefixedKey)) {
                return true;
            }
        }
        
        // 默认添加前缀（对于未明确指定的键）
        return true;
    }
    
    /**
     * 获取完整的缓存键（根据键类型决定是否添加前缀）
     * @param key 原始缓存键
     * @return 完整的缓存键
     */
    public String getFullCacheKey(String key) {
        return shouldAddPrefix(key) ? CACHE_PREFIX + key : key;
    }
    
    /**
     * 获取文件夹下的文件列表
     */
    public List<FileEntry> getFolderFiles(String folderId, int offset, int limit) {
        // 优先从新缓存格式获取
        String cacheKey = getFullCacheKey(FOLDER_KEY + folderId);
        List<String> fileIds = redisTemplate.opsForList().range(cacheKey, offset, offset + limit - 1);
        
        List<FileEntry> result = new ArrayList<>();
        
        if (fileIds != null && !fileIds.isEmpty()) {
            for (String fileId : fileIds) {
                if (fileId == null || fileId.isEmpty()) {
                    continue;
                }
                
                FileEntry entry = getFileEntry(fileId);
                if (entry != null) {
                    result.add(entry);
                }
            }
        }
        
        return result;
    }
    
    /**
     * 获取文件条目
     */
    public FileEntry getFileEntry(String fileId) {
        // 优先从新缓存格式获取
        String cacheKey = getFullCacheKey(META_KEY + fileId);
        String meta = redisTemplate.opsForValue().get(cacheKey);
        
        if (meta != null) {
            try {
                JsonNode node = mapper.readTree(meta);
                String name = node.path("name").asText();
                String ext = node.path("ext").asText();
                String fullName = ext.isEmpty() ? name : name + "." + ext;
                return new FileEntry(fileId, fullName);
            } catch (Exception e) {
                System.err.println("解析元数据错误: " + e.getMessage());
            }
        }
        return null;
    }
    
    /**
     * 缓存文件夹与文件的关联关系
     */
    public void cacheFolderFiles(String folderId, List<String> fileIds) {
        // 使用新的缓存键格式
        String cacheKey = getFullCacheKey(FOLDER_KEY + folderId);
        redisTemplate.delete(cacheKey);
        if (!fileIds.isEmpty()) {
            redisTemplate.opsForList().rightPushAll(cacheKey, fileIds.toArray(new String[0]));
            // 设置缓存过期时间，避免长期占用内存
            redisTemplate.expire(cacheKey, 24, TimeUnit.HOURS);
        }
    }
    
    /**
     * 缓存文件元数据
     */
    public void cacheFileMeta(String fileId, String metadata) {
        // 使用新的缓存键格式
        String cacheKey = getFullCacheKey(META_KEY + fileId);
        redisTemplate.opsForValue().set(cacheKey, metadata);
        // 设置缓存过期时间
        redisTemplate.expire(cacheKey, 24, TimeUnit.HOURS);
    }
    
    /**
     * 缓存任意对象（用于CompletableFuture结果）
     * @param key 缓存键
     * @param value 缓存值
     * @param expireHours 过期时间（小时）
     */
    public void cacheValue(String key, String value, int expireHours) {
        String cacheKey = getFullCacheKey(key);
        redisTemplate.opsForValue().set(cacheKey, value);
        redisTemplate.expire(cacheKey, expireHours, TimeUnit.HOURS);
    }
    
    /**
     * 获取缓存的值
     * @param key 缓存键
     * @return 缓存的值，如果不存在返回null
     */
    public String getCachedValue(String key) {
        return redisTemplate.opsForValue().get(getFullCacheKey(key));
    }
    
    /**
     * 检查键是否存在于缓存中
     * @param key 缓存键
     * @return 是否存在
     */
    public boolean hasCachedValue(String key) {
        return redisTemplate.hasKey(getFullCacheKey(key));
    }
    
    /**
     * 删除缓存
     * @param key 缓存键
     */
    public void deleteCachedValue(String key) {
        redisTemplate.delete(getFullCacheKey(key));
    }
    
    /**
     * 检查文件元数据是否已缓存
     */
    public boolean isFileCached(String fileId) {
        // 检查新缓存

        return redisTemplate.hasKey(getFullCacheKey(META_KEY + fileId));
    }
    
    /**
     * 获取缓存中的文件总数
     */
    public long getCachedFileCount() {
        // 获取新缓存中的文件数

        // 返回总数（可能有重复，但这只是一个统计数据）
        return redisTemplate.keys(META_KEY + "*").size();
    }
    
    /**
     * 获取缓存中的文件夹总数
     */
    public long getCachedFolderCount() {
        // 获取新缓存中的文件夹数

        // 返回总数（可能有重复，但这只是一个统计数据）
        return redisTemplate.keys(FOLDER_KEY + "*").size();
    }
    
    /**
     * 获取缓存中的其他缓存总数
     */
    public long getOtherCacheCount() {
        return redisTemplate.keys(CACHE_PREFIX + "*").size();
    }
    
    /**
     * 获取文件的完整路径缓存键
     */
    public String getFilePathCacheKey(String filePath) {
        System.out.println("!------->"+ CACHE_PREFIX + FILE_KEY + filePath);
        // 检查filePath是否已经包含file:前缀，避免重复添加
        if (filePath.startsWith("file:")) {
            return CACHE_PREFIX + filePath;
        } else {
            return CACHE_PREFIX + FILE_KEY + filePath;
        }
    }
} 