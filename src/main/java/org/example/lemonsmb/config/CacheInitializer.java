package org.example.lemonsmb.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.lemonsmb.service.CacheProgressTracker;
import org.example.lemonsmb.service.CacheService;
import org.example.lemonsmb.service.SmbService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 缓存初始化器，系统启动时预加载数据到Redis
 */
@Component
public class CacheInitializer implements ApplicationRunner {
    
    @Autowired
    private SmbService smbService;
    
    @Autowired
    private CacheService cacheService;
    
    @Autowired
    private CacheProgressTracker progressTracker;
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    private final ObjectMapper mapper = new ObjectMapper();
    
    // 用于跟踪文件夹与文件的关系
    private final Map<String, List<String>> folderFilesMap = new ConcurrentHashMap<>();
    
    @Override
    public void run(ApplicationArguments args) {
        System.out.println("开始初始化缓存...");
        try {
            // 检查缓存是否已完成
            if (progressTracker.isCacheCompleted()) {
                System.out.println("检测到缓存已完成，跳过初始化过程");
                return;
            }
            
            initializeCache();
            
            // 标记缓存已完成
            progressTracker.markCacheCompleted();
            
            System.out.println("缓存初始化完成，共缓存文件夹数量: " + cacheService.getCachedFolderCount() + 
                    ", 文件数量: " + cacheService.getCachedFileCount() +
                    ", 其他缓存数量: " + cacheService.getOtherCacheCount());
        } catch (Exception e) {
            System.err.println("缓存初始化失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 初始化缓存
     */
    private void initializeCache() {
        try {
            // 获取正确的路径
            String libraryDir = smbService.getProperties().getLibraryDir();
            System.out.println("使用库目录: " + libraryDir);
            
            // 1. 读取主metadata.json
            CompletableFuture<byte[]> metadataFuture = smbService.loadFile(libraryDir + "/metadata.json");
            String mainMetadata = new String(metadataFuture.get());
            
            if (!mainMetadata.isEmpty()) {
                // 缓存主metadata（永久有效）
                cacheService.cacheValuePermanently("metadata", mainMetadata);
                
                // 获取metadata.json的最后修改时间
                try {
                    long lastModified = smbService.getFileLastModifiedTime(libraryDir + "/metadata.json");
                    progressTracker.updateMetadataLastModified(lastModified);
                } catch (Exception e) {
                    System.err.println("获取metadata.json最后修改时间失败: " + e.getMessage());
                }
                
                // 2. 解析主metadata.json，获取文件夹结构
                JsonNode root = mapper.readTree(mainMetadata);
                if (root.has("folders")) {
                    processFolders(root.path("folders"));
                }
            }
            
            // 3. 扫描images目录，缓存文件元数据
            scanImagesDirectory(libraryDir);
            
            // 4. 将文件夹与文件的关联关系存入Redis
            saveFolderFilesRelation();
            
        } catch (Exception e) {
            System.err.println("缓存初始化失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 处理文件夹结构
     */
    private void processFolders(JsonNode folders) {
        if (folders.isArray()) {
            for (JsonNode folder : folders) {
                String id = folder.path("id").asText();
                String name = folder.path("name").asText();
                
                // 检查文件夹是否已处理
                if (progressTracker.isFolderProcessed(id)) {
                    System.out.println("文件夹 " + id + " (" + name + ") 已处理，跳过");
                    continue;
                }
                
                // 初始化文件夹的文件列表
                folderFilesMap.putIfAbsent(id, new ArrayList<>());
                
                // 缓存文件夹信息（永久有效）
                try {
                    cacheService.cacheValuePermanently("folder_info:" + id, mapper.writeValueAsString(folder));
                    // 标记文件夹已处理
                    progressTracker.markFolderProcessed(id);
                } catch (Exception e) {
                    System.err.println("缓存文件夹信息失败: " + id + ", 错误: " + e.getMessage());
                }
                
                // 递归处理子文件夹
                if (folder.has("children")) {
                    processFolders(folder.path("children"));
                }
            }
        }
    }
    
    /**
     * 扫描images目录
     */
    private void scanImagesDirectory(String libraryDir) {
        try {
            // 获取images目录下的所有内容
            String imagesPath = libraryDir + "/images";
            System.out.println("扫描目录: " + imagesPath);
            
            // 获取上次处理位置
            String lastPosition = progressTracker.getLastPosition();
            int startIndex = 0;
            
            if (lastPosition != null && !lastPosition.isEmpty()) {
                try {
                    startIndex = Integer.parseInt(lastPosition);
                    System.out.println("从上次中断位置继续: " + startIndex);
                } catch (NumberFormatException e) {
                    System.err.println("解析上次处理位置失败: " + e.getMessage());
                }
            }
            
            CompletableFuture<List<Map<String, Object>>> listFuture =
                    smbService.listDirectories(imagesPath, 0, Integer.MAX_VALUE);
            List<Map<String, Object>> infoDirectories = listFuture.get();
            
            System.out.println("找到 " + infoDirectories.size() + " 个可能的.info目录");
            
            // 处理每个.info目录下的metadata.json
            int processed = 0;
            int skipped = 0;
            for (int i = startIndex; i < infoDirectories.size(); i++) {
                Map<String, Object> entry = infoDirectories.get(i);
                if (entry.get("name").toString().endsWith(".info")) {
                    String dirName = entry.get("name").toString();
                    
                    // 更新当前处理位置
                    progressTracker.updateLastPosition(String.valueOf(i));
                    
                    // 处理.info目录
                    boolean success = processInfoDirectory(libraryDir, dirName);
                    if (success) {
                        processed++;
                    } else {
                        skipped++;
                    }
                    
                    // 每处理100个目录打印一次进度
                    if ((processed + skipped) % 100 == 0) {
                        System.out.println("已处理 " + processed + " 个.info目录，跳过 " + skipped + " 个目录，当前进度: " + 
                                (i + 1) + "/" + infoDirectories.size() + " (" + 
                                String.format("%.2f", (i + 1) * 100.0 / infoDirectories.size()) + "%)");
                    }
                    
                    // 每处理10个目录暂停一下，避免创建太多连接
                    if ((processed + skipped) % 10 == 0) {
                        try {
                            Thread.sleep(500); // 暂停500毫秒
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
            
            // 处理完成后，清除位置记录
            progressTracker.updateLastPosition("");
            
            System.out.println("共处理了 " + processed + " 个.info目录，跳过 " + skipped + " 个目录");
            
        } catch (Exception e) {
            System.err.println("扫描images目录失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 处理.info目录
     * @return 处理是否成功
     */
    private boolean processInfoDirectory(String libraryDir, String infoDir) {
        try {
            String metadataPath = libraryDir + "/images/" + infoDir + "/metadata.json";
            
            // 先检查文件是否存在
            try {
                CompletableFuture<Boolean> existsFuture = smbService.fileExists(metadataPath);
                Boolean exists = existsFuture.get();
                if (Boolean.FALSE.equals(exists)) {
                    // 如果metadata.json不存在，静默跳过
                    return false;
                }
            } catch (Exception e) {
                // 忽略检查错误，会在后续处理中捕获
            }
            
            CompletableFuture<byte[]> fileFuture = smbService.loadFile(metadataPath);
            byte[] fileData = fileFuture.get();
            
            if (fileData != null && fileData.length > 0) {
                String metadataJson = new String(fileData);
                JsonNode metadata = mapper.readTree(metadataJson);
                String fileId = metadata.path("id").asText();
                
                // 检查文件是否已处理
                if (progressTracker.isFileProcessed(fileId)) {
                    System.out.println("文件 " + fileId + " 已处理，跳过");
                    return true;
                }
                
                // 缓存文件元数据（永久有效）
                cacheService.cacheFileMeta(fileId, metadataJson);
                
                // 同时使用新的缓存键格式存储（永久有效）
                String metaKey = cacheService.getFullCacheKey("meta:" + fileId);
                redisTemplate.opsForValue().set(metaKey, metadataJson);
                
                // 处理文件夹关联
                if (metadata.has("folders") && metadata.path("folders").isArray()) {
                    for (JsonNode folderNode : metadata.path("folders")) {
                        String folderId = folderNode.asText();
                        // 将文件ID添加到对应文件夹的列表中
                        List<String> files = folderFilesMap.computeIfAbsent(folderId, k -> new ArrayList<>());
                        files.add(fileId);
                    }
                }
                
                // 标记文件已处理
                progressTracker.markFileProcessed(fileId);
                
                return true;
            }
            return false;
        } catch (Exception e) {
            // 只有在不是文件不存在的错误时才记录日志
            if (e.getMessage() == null || !e.getMessage().contains("STATUS_OBJECT_NAME_NOT_FOUND")) {
                System.err.println("处理.info目录失败: " + infoDir + ", 错误: " + e.getMessage());
            }
            return false;
        }
    }
    
    /**
     * 将文件夹与文件的关联关系存入Redis
     */
    private void saveFolderFilesRelation() {
        System.out.println("开始保存文件夹与文件的关联关系...");
        int count = 0;
        
        for (Map.Entry<String, List<String>> entry : folderFilesMap.entrySet()) {
            String folderId = entry.getKey();
            List<String> fileIds = entry.getValue();
            
            if (!fileIds.isEmpty()) {
                // 缓存文件夹-文件关联（永久有效）
                cacheService.cacheFolderFiles(folderId, fileIds);
                
                // 同时缓存为JSON格式（永久有效）
                try {
                    cacheService.cacheValuePermanently("folder_files:" + folderId, mapper.writeValueAsString(fileIds));
                } catch (Exception e) {
                    System.err.println("缓存文件夹文件关系失败: " + folderId + ", 错误: " + e.getMessage());
                }
                
                // 同时使用新的缓存键格式更新Redis列表（永久有效）
                String folderKey = cacheService.getFullCacheKey("folder:" + folderId);
                redisTemplate.delete(folderKey);
                for (String fileId : fileIds) {
                    redisTemplate.opsForList().rightPush(folderKey, fileId);
                }
                
                count++;
                
                // 每处理100个文件夹打印一次进度
                if (count % 100 == 0) {
                    System.out.println("已保存 " + count + " 个文件夹的关联关系");
                }
            }
        }
        
        System.out.println("共保存了 " + count + " 个文件夹的关联关系");
    }
} 