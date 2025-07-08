package org.example.lemonsmb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.lemonsmb.config.SmbProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 文件监控服务，用于监控metadata.json文件的变化
 */
@Service
public class FileWatchService {
    
    @Autowired
    private SmbService smbService;
    
    @Autowired
    private CacheService cacheService;
    
    @Autowired
    private CacheProgressTracker progressTracker;
    
    @Autowired
    private SmbProperties properties;
    
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean isUpdating = new AtomicBoolean(false);
    
    @PostConstruct
    public void init() {
        System.out.println("文件监控服务已初始化");
    }
    
    /**
     * 定时检查metadata.json文件是否有变化
     * 每5分钟执行一次
     */
    @Scheduled(fixedDelay = 300000)
    public void checkMetadataChanges() {
        if (isUpdating.get()) {
            System.out.println("上一次更新操作尚未完成，跳过本次检查");
            return;
        }
        
        try {
            String metadataPath = properties.getLibraryDir() + "/metadata.json";
            long lastModified = getFileLastModified(metadataPath);
            long storedLastModified = progressTracker.getMetadataLastModified();
            
            if (lastModified > storedLastModified) {
                System.out.println("检测到metadata.json文件变化，上次修改时间: " + storedLastModified + ", 当前修改时间: " + lastModified);
                
                // 标记正在更新
                isUpdating.set(true);
                
                // 更新缓存
                updateMetadataCache(metadataPath);
                
                // 更新最后修改时间
                progressTracker.updateMetadataLastModified(lastModified);
                
                // 更新完成
                isUpdating.set(false);
            }
        } catch (Exception e) {
            isUpdating.set(false);
            System.err.println("检查metadata.json变化时发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 获取文件的最后修改时间
     */
    private long getFileLastModified(String filePath) {
        try {
            return smbService.getFileLastModifiedTime(filePath);
        } catch (Exception e) {
            System.err.println("获取文件最后修改时间失败: " + e.getMessage());
        }
        return 0;
    }
    
    /**
     * 更新metadata.json缓存
     */
    private void updateMetadataCache(String metadataPath) {
        try {
            System.out.println("开始更新metadata.json缓存");
            
            // 读取metadata.json文件
            String metadataContent = smbService.readFile(metadataPath);
            
            // 缓存metadata.json内容
            cacheService.cacheValuePermanently("metadata", metadataContent);
            
            // 解析metadata.json
            JsonNode root = mapper.readTree(metadataContent);
            
            // 处理文件夹结构
            if (root.has("folders")) {
                updateFolderStructure(root.path("folders"));
            }
            
            System.out.println("metadata.json缓存更新完成");
        } catch (IOException e) {
            System.err.println("更新metadata.json缓存失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 更新文件夹结构
     */
    private void updateFolderStructure(JsonNode folders) {
        if (folders.isArray()) {
            for (JsonNode folder : folders) {
                String id = folder.path("id").asText();
                
                try {
                    // 缓存文件夹信息
                    cacheService.cacheValuePermanently("folder_info:" + id, mapper.writeValueAsString(folder));
                } catch (Exception e) {
                    System.err.println("缓存文件夹信息失败: " + id + ", 错误: " + e.getMessage());
                }
                
                // 递归处理子文件夹
                if (folder.has("children")) {
                    updateFolderStructure(folder.path("children"));
                }
            }
        }
    }
    
    /**
     * 手动触发metadata.json检查
     */
    public void forceCheckMetadata() {
        System.out.println("手动触发metadata.json检查");
        checkMetadataChanges();
    }
} 