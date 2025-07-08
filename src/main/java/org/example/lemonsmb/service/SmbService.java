package org.example.lemonsmb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.protocol.transport.TransportException;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.common.SMBRuntimeException;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import org.example.lemonsmb.config.SmbProperties;
import org.example.lemonsmb.model.FileEntry;
import org.example.lemonsmb.model.FileInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Service
public class SmbService {

    @Autowired
    private SmbProperties properties;

    @Autowired
    @Qualifier("byteRedisTemplate")
    private RedisTemplate<String, byte[]> byteRedisTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;
    
    @Autowired
    private CacheService cacheService;

    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode metadataCache;
    
    // 连接池相关
    private SMBClient smbClient;
    private Connection connection;
    private Session session;
    private DiskShare share;
    private final Lock connectionLock = new ReentrantLock();
    private boolean initialized = false;
    
    @PostConstruct
    public void init() {
        try {
            connectionLock.lock();
            if (!initialized) {
                System.out.println("初始化SMB连接...");
                smbClient = new SMBClient();
                connection = smbClient.connect(properties.getHost());
                AuthenticationContext auth = new AuthenticationContext(
                        properties.getUsername(), properties.getPassword().toCharArray(), null);
                session = connection.authenticate(auth);
                share = (DiskShare) session.connectShare(properties.getShare());
                initialized = true;
                System.out.println("SMB连接初始化完成");
            }
        } catch (Exception e) {
            System.err.println("初始化SMB连接失败: " + e.getMessage());
            e.printStackTrace();
        } finally {
            connectionLock.unlock();
        }
    }
    
    @PreDestroy
    public void cleanup() {
        try {
            connectionLock.lock();
            System.out.println("关闭SMB连接...");
            if (share != null) {
                try {
                    share.close();
                } catch (Exception e) {
                    System.err.println("关闭共享连接失败: " + e.getMessage());
                }
            }
            if (session != null) {
                try {
                    session.close();
                } catch (Exception e) {
                    System.err.println("关闭会话失败: " + e.getMessage());
                }
            }
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception e) {
                    System.err.println("关闭连接失败: " + e.getMessage());
                }
            }
            if (smbClient != null) {
                try {
                    smbClient.close();
                } catch (Exception e) {
                    System.err.println("关闭SMB客户端失败: " + e.getMessage());
                }
            }
            initialized = false;
            System.out.println("SMB连接已关闭");
        } catch (Exception e) {
            System.err.println("清理SMB连接资源失败: " + e.getMessage());
        } finally {
            connectionLock.unlock();
        }
    }

    private byte[] readBytes(String remotePath) throws IOException {
        try {
            connectionLock.lock();
            if (!initialized) {
                init();
            }
            
            try {
                File f = share.openFile(remotePath,
                        EnumSet.of(AccessMask.GENERIC_READ),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        null);
                try (InputStream is = f.getInputStream()) {
                    return is.readAllBytes();
                }
            } catch (com.hierynomus.mssmb2.SMBApiException e) {
                // 如果发生连接错误，尝试重新初始化连接
                if (isConnectionError(e)) {
                    System.out.println("检测到连接错误，尝试重新初始化连接...");
                    cleanup();
                    init();
                    // 重试一次
                    File f = share.openFile(remotePath,
                            EnumSet.of(AccessMask.GENERIC_READ),
                            null,
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OPEN,
                            null);
                    try (InputStream is = f.getInputStream()) {
                        return is.readAllBytes();
                    }
                } else {
                    throw new IOException(e);
                }
            }
        } finally {
            connectionLock.unlock();
        }
    }
    
    private boolean isConnectionError(Exception e) {
        return e instanceof TransportException || 
               e instanceof SMBRuntimeException ||
               (e.getMessage() != null && e.getMessage().contains("EOF"));
    }

    @Async
    public CompletableFuture<byte[]> loadImage(String id, boolean thumbnail) {
        String cacheKey = "image:" + id + ":" + thumbnail;
        
        // 先尝试从缓存获取
        if (cacheService != null) {
            // 使用CacheService的方法获取缓存，它会自动添加cache:前缀
            byte[] cachedImage = byteRedisTemplate.opsForValue().get(cacheService.getFilePathCacheKey(cacheKey));
            if (cachedImage != null) {
                System.out.println("从缓存获取图片: " + id);
                return CompletableFuture.completedFuture(cachedImage);
            }
        }
        
        // 再尝试从旧缓存获取
        byte[] cached = byteRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            // 如果从旧缓存获取到，同时存入新缓存
            if (cacheService != null) {
                byteRedisTemplate.opsForValue().set(cacheService.getFilePathCacheKey(cacheKey), cached, Duration.ofHours(1));
                System.out.println("将图片从旧缓存迁移到新缓存: " + id);
            }
            return CompletableFuture.completedFuture(cached);
        }
        
        String imageId = id;
        String ext = "";
        int dot = id.lastIndexOf('.');
        if (dot > 0) {
            imageId = id.substring(0, dot);
            ext = id.substring(dot + 1);
        }
        String imagesBase = properties.getLibraryDir() + "/images";
        String infoDir = imagesBase + "/" + imageId + ".info";
        String metaPath = infoDir + "/metadata.json";
        
        System.out.println("加载图片 - ID: " + id + ", thumbnail: " + thumbnail);
        
        try {
            String meta = new String(readBytes(metaPath), StandardCharsets.UTF_8);
            JsonNode node = mapper.readTree(meta);
            String name = node.path("name").asText();
            if (ext.isEmpty()) {
                ext = node.path("ext").asText();
            }
            
            // 如果请求缩略图，先尝试加载缩略图
            if (thumbnail) {
                String thumbnailFileName = name + "_thumbnail." + ext;
                String thumbnailPath = infoDir + "/" + thumbnailFileName;
                
                try {
                    System.out.println("尝试加载缩略图: " + thumbnailPath);

                    byte[] data = readBytes(thumbnailPath);

                    String targetKey = cacheService != null ?
                            cacheService.getFilePathCacheKey(cacheKey) : cacheKey;
                    byteRedisTemplate.opsForValue().set(targetKey, data, Duration.ofHours(1));

                    System.out.println("缩略图加载成功，数据长度: " + data.length);
                    return CompletableFuture.completedFuture(data);
                } catch (IOException thumbnailError) {
                    System.out.println("缩略图不存在，回退到原图: " + thumbnailError.getMessage());
                    // 缩略图不存在，回退到原图
                }
            }
            
            // 加载原图
            String originalFileName = name + "." + ext;
            String originalPath = infoDir + "/" + originalFileName;
            System.out.println("加载原图: " + originalPath);
            
            byte[] data = readBytes(originalPath);

            String targetKey = cacheService != null ?
                    cacheService.getFilePathCacheKey(cacheKey) : cacheKey;
            byteRedisTemplate.opsForValue().set(targetKey, data, Duration.ofHours(1));

            System.out.println("原图加载成功，数据长度: " + data.length);
            return CompletableFuture.completedFuture(data);
            
        } catch (IOException e) {
            System.out.println("加载图片完全失败: " + e.getMessage());
            return CompletableFuture.completedFuture(new byte[0]);
        }
    }

    /**
     * 异步加载任意文件的字节数据
     */
    @Async
    public CompletableFuture<byte[]> loadFile(String id) {
        String cacheKey = "file:" + id;
        
        // 先尝试从缓存获取
        if (cacheService != null) {
            // 使用CacheService的方法获取缓存，它会自动添加cache:前缀
            byte[] cachedFile = byteRedisTemplate.opsForValue().get(cacheService.getFilePathCacheKey(cacheKey));
            if (cachedFile != null) {
                System.out.println("从缓存获取文件: " + id);
                return CompletableFuture.completedFuture(cachedFile);
            }
        }
        
        // 再尝试从旧缓存获取
        byte[] cached = byteRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            // 如果从旧缓存获取到，同时存入新缓存
            if (cacheService != null) {
                byteRedisTemplate.opsForValue().set(cacheService.getFilePathCacheKey(cacheKey), cached, Duration.ofHours(1));
                System.out.println("将文件从旧缓存迁移到新缓存: " + id);
            }
            return CompletableFuture.completedFuture(cached);
        }
        
        try {
            // 如果是直接路径，直接加载
            if (id.contains("/")) {
                byte[] data = readBytes(id);
                String targetKey = cacheService != null ?
                        cacheService.getFilePathCacheKey(cacheKey) : cacheKey;
                byteRedisTemplate.opsForValue().set(targetKey, data, Duration.ofHours(1));
                return CompletableFuture.completedFuture(data);
            }
            
            // 否则按照图片ID处理
            String imageId = id;
            String ext = "";
            int dot = id.lastIndexOf('.');
            if (dot > 0) {
                imageId = id.substring(0, dot);
                ext = id.substring(dot + 1);
            }
            String imagesBase = properties.getLibraryDir() + "/images";
            String infoDir = imagesBase + "/" + imageId + ".info";
            String metaPath = infoDir + "/metadata.json";
            
            String meta = new String(readBytes(metaPath), StandardCharsets.UTF_8);
            JsonNode node = mapper.readTree(meta);
            String name = node.path("name").asText();
            if (ext.isEmpty()) {
                ext = node.path("ext").asText();
            }
            String fileName = name + "." + ext;
            String filePath = infoDir + "/" + fileName;
            byte[] data = readBytes(filePath);

            String targetKey = cacheService != null ?
                    cacheService.getFilePathCacheKey(cacheKey) : cacheKey;
            byteRedisTemplate.opsForValue().set(targetKey, data, Duration.ofHours(1));
            
            return CompletableFuture.completedFuture(data);
        } catch (IOException e) {
            System.err.println("加载文件失败: " + id + ", 错误: " + e.getMessage());
            return CompletableFuture.completedFuture(new byte[0]);
        }
    }

    /**
     * 异步获取文件详细信息
     */
    @Async
    public CompletableFuture<FileInfo> getFileInfo(String id) {
        String imageId = id;
        String ext = "";
        int dot = id.lastIndexOf('.');
        if (dot > 0) {
            imageId = id.substring(0, dot);
            ext = id.substring(dot + 1);
        }
        String imagesBase = properties.getLibraryDir() + "/images";
        String infoDir = imagesBase + "/" + imageId + ".info";
        String metaPath = infoDir + "/metadata.json";
        
        try {
            String meta = new String(readBytes(metaPath), StandardCharsets.UTF_8);
            JsonNode node = mapper.readTree(meta);
            String name = node.path("name").asText();
            if (ext.isEmpty()) {
                ext = node.path("ext").asText();
            }
            String fileName = name + "." + ext;
            String filePath = infoDir + "/" + fileName;
            
            // 从元数据获取文件信息
            FileInfo info = new FileInfo();
            info.setName(fileName);
            info.setSize(node.path("size").asLong(0)); // 从元数据获取大小
            info.setLastModified(LocalDateTime.now()); // 使用当前时间作为默认值
            info.setDirectory(false);
            info.setPath(filePath);
            
            // 尝试获取实际文件大小
            try {
                connectionLock.lock();
                if (!initialized) {
                    init();
                }
                
                com.hierynomus.msfscc.fileinformation.FileStandardInformation fileStdInfo =
                    share.getFileInformation(filePath).getStandardInformation();
                if (fileStdInfo != null) {
                    info.setSize(fileStdInfo.getEndOfFile());
                }
            } catch (Exception e) {
                // 如果获取文件信息失败，使用元数据中的信息
            } finally {
                connectionLock.unlock();
            }
            
            return CompletableFuture.completedFuture(info);
        } catch (IOException e) {
            // 返回默认文件信息
            FileInfo info = new FileInfo();
            info.setName(id);
            info.setSize(0);
            info.setLastModified(LocalDateTime.now());
            info.setDirectory(false);
            info.setPath(id);
            return CompletableFuture.completedFuture(info);
        }
    }

    public SmbProperties getProperties() {
        return properties;
    }

    private DiskShare connectShare() throws IOException {
        SMBClient client = new SMBClient();
        Connection connection = client.connect(properties.getHost());
        AuthenticationContext auth = new AuthenticationContext(
                properties.getUsername(), properties.getPassword().toCharArray(), null);
        Session session = connection.authenticate(auth);
        return (DiskShare) session.connectShare(properties.getShare());
    }

    /**
     * Safely close a DiskShare, forcing the connection closed when a timeout occurs
     * during the normal logoff procedure.
     */
    private void safeClose(DiskShare share) {
        if (share == null) {
            return;
        }
        try {
            share.close();
        } catch (TransportException | SMBRuntimeException e) {
            try {
                share.getTreeConnect().getSession().getConnection().close(true);
            } catch (Exception ignore) {
                // swallow all exceptions during forced close
            }
        } catch (Exception ignore) {
            // swallow any other closing issues
        }
    }

    /**
     * Read a file from the SMB share using UTF-8 encoding.
     */
    public String readFile(String remotePath) throws IOException {
        try {
            connectionLock.lock();
            if (!initialized) {
                init();
            }
            
            try {
                File f = share.openFile(remotePath,
                        EnumSet.of(AccessMask.GENERIC_READ),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        null);
                try (InputStream is = f.getInputStream()) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (com.hierynomus.mssmb2.SMBApiException e) {
                // 如果发生连接错误，尝试重新初始化连接
                if (isConnectionError(e)) {
                    System.out.println("检测到连接错误，尝试重新初始化连接...");
                    cleanup();
                    init();
                    // 重试一次
                    File f = share.openFile(remotePath,
                            EnumSet.of(AccessMask.GENERIC_READ),
                            null,
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OPEN,
                            null);
                    try (InputStream is = f.getInputStream()) {
                        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    }
                } else {
                    throw new IOException(e);
                }
            }
        } finally {
            connectionLock.unlock();
        }
    }

    @Async
    public CompletableFuture<List<FileEntry>> listFiles(String path, int offset, int limit) {
        List<FileEntry> result = new ArrayList<>();

        try {
            // 检查path是否为文件夹ID格式（以M开头的字母数字组合）
            if (path != null && !path.isEmpty() && path.matches("^M[\\dA-Z]+$")) {
                System.out.println("检测到文件夹ID格式: " + path + "，直接从Redis获取文件列表");
                
                // 从Redis中获取该文件夹的文件ID列表（使用新的缓存键格式）
                List<String> fileIds = null;
                
                // 从新缓存格式获取
                if (cacheService != null) {
                    String folderKey = cacheService.getFullCacheKey("folder:" + path);
                    fileIds = redisTemplate.opsForList().range(folderKey, offset, offset + limit - 1);
                }
                
                if (fileIds != null && !fileIds.isEmpty()) {
                    for (String fileId : fileIds) {
                        if (fileId == null || fileId.isEmpty()) {
                            continue;
                        }
                        
                        // 从新缓存格式获取元数据
                        String metaKey = cacheService.getFullCacheKey("meta:" + fileId);
                        String meta = redisTemplate.opsForValue().get(metaKey);
                        
                        if (meta != null) {
                            try {
                                JsonNode node = mapper.readTree(meta);
                                String ext = node.path("ext").asText();
                                String fileName = node.path("name").asText() + "." + ext;
                                String id = fileId + "." + ext;
                                result.add(new FileEntry(id, fileName));
                            } catch (Exception e) {
                                System.err.println("解析文件元数据失败: " + e.getMessage());
                            }
                        }
                    }
                    return CompletableFuture.completedFuture(result);
                } else {
                    System.out.println("Redis中没有找到文件夹 " + path + " 的文件列表");
                    // 对于文件夹ID格式的请求，如果Redis中没有对应的文件列表，直接返回空列表
                    // 不再回退到扫描所有.info目录的逻辑
                    return CompletableFuture.completedFuture(result);
                }
            }

            // 只有在path不是文件夹ID格式时，才执行以下逻辑
            if (metadataCache == null) {
                String meta = readFile(properties.getLibraryDir() + "/metadata.json");
                metadataCache = mapper.readTree(meta).path("folders");
            }

            String folderId = null;
            if (path != null && !path.isEmpty()) {
                folderId = findFolderId(metadataCache, path.split("/"), 0);
            }

            String imagesBase = properties.getLibraryDir() + "/images";
            
            try {
                connectionLock.lock();
                if (!initialized) {
                    init();
                }
                
                int processed = 0;
                for (FileIdBothDirectoryInformation f : share.list(imagesBase)) {
                    if (!f.getFileName().endsWith(".info")) {
                        continue;
                    }
                    String imageId = f.getFileName().replace(".info", "");
                    String metaPath = imagesBase + "/" + f.getFileName() + "/metadata.json";
                    try {
                        File mf = share.openFile(metaPath,
                                EnumSet.of(AccessMask.GENERIC_READ),
                                null,
                                SMB2ShareAccess.ALL,
                                SMB2CreateDisposition.FILE_OPEN,
                                null);
                        String imgMeta;
                        try (InputStream is = mf.getInputStream()) {
                            imgMeta = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        }
                        
                        JsonNode node = mapper.readTree(imgMeta);
                        boolean match = folderId == null;
                        if (folderId != null) {
                            JsonNode arr = node.path("folders");
                            if (arr.isArray()) {
                                for (JsonNode n : arr) {
                                    if (folderId.equals(n.asText())) {
                                        match = true;
                                        break;
                                    }
                                }
                            }
                        }
                        if (match) {
                            if (processed++ < offset) {
                                continue;
                            }
                            String ext = node.path("ext").asText();
                            String fileName = node.path("name").asText() + "." + ext;
                            String id = imageId + "." + ext;
                            result.add(new FileEntry(id, fileName));
                            if (result.size() >= limit) {
                                break;
                            }
                        }
                    } catch (IOException | com.hierynomus.mssmb2.SMBApiException e) {
                        // Skip files without metadata or inaccessible entries
                    }
                }
            } finally {
                connectionLock.unlock();
            }
        } catch (IOException e) {
            System.err.println("列出文件失败: " + e.getMessage());
        }
        return CompletableFuture.completedFuture(result);
    }

    /**
     * 构建文件夹与文件的关联关系索引
     * 此方法会扫描images目录下的所有.info目录，读取其中的metadata.json，
     * 然后根据metadata.json中的folders字段，建立文件夹与文件的关联关系
     */
    @Async
    public CompletableFuture<Void> indexLibrary() {
        int processed = 0;
        int skipped = 0; // 添加跳过计数
        try {
            System.out.println("开始构建文件夹与文件的关联关系索引...");
            
            // 读取主metadata.json
            String mainMetadata = readFile(properties.getLibraryDir() + "/metadata.json");
            
            // 使用CacheService缓存主metadata
            if (cacheService != null) {
                cacheService.cacheValue("metadata", mainMetadata, 24);
            } else {
                redisTemplate.opsForValue().set("metadata", mainMetadata);
            }
            
            // 解析主metadata.json，获取文件夹结构
            JsonNode root = mapper.readTree(mainMetadata);
            if (root.has("folders")) {
                processAllFolders(root.path("folders"));
            }
            
            // 扫描images目录下的所有.info目录
            String imagesBase = properties.getLibraryDir() + "/images";
            
            try {
                connectionLock.lock();
                if (!initialized) {
                    init();
                }
                
                for (FileIdBothDirectoryInformation f : share.list(imagesBase)) {
                    if (!f.getFileName().endsWith(".info")) {
                        continue;
                    }
                    
                    String imageId = f.getFileName().replace(".info", "");
                    String metaPath = imagesBase + "/" + f.getFileName() + "/metadata.json";
                    
                    try {
                        // 先检查metadata.json是否存在
                        boolean metadataExists = false;
                        try {
                            metadataExists = share.fileExists(metaPath);
                        } catch (Exception e) {
                            // 忽略检查错误，会在后续处理中捕获
                        }
                        
                        if (!metadataExists) {
                            // 如果metadata.json不存在，跳过此.info目录
                            skipped++;
                            if (skipped % 100 == 0) {
                                System.out.println("已跳过 " + skipped + " 个没有metadata.json的.info目录");
                            }
                            continue;
                        }
                        
                        File mf = share.openFile(metaPath,
                                EnumSet.of(AccessMask.GENERIC_READ),
                                null,
                                SMB2ShareAccess.ALL,
                                SMB2CreateDisposition.FILE_OPEN,
                                null);
                        String imgMeta;
                        try (InputStream is = mf.getInputStream()) {
                            imgMeta = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        }
                        
                        JsonNode node = mapper.readTree(imgMeta);
                        
                        // 缓存文件元数据
                        if (cacheService != null) {
                            cacheService.cacheFileMeta(imageId, imgMeta);
                        } else {
                            redisTemplate.opsForValue().set("meta:" + imageId, imgMeta);
                        }
                        
                        // 处理文件夹关联
                        JsonNode folders = node.path("folders");
                        if (folders.isArray()) {
                            for (JsonNode folderNode : folders) {
                                String folderId = folderNode.asText();
                                // 将文件ID添加到对应文件夹的列表中
                                if (cacheService != null) {
                                    // 使用CacheService的方法添加到列表
                                    List<String> existingFiles = new ArrayList<>();
                                    // 使用新的缓存键格式
                                    String cachedFiles = cacheService.getCachedValue("folder_files:" + folderId);
                                    if (cachedFiles != null) {
                                        try {
                                            existingFiles = mapper.readValue(cachedFiles, 
                                                    mapper.getTypeFactory().constructCollectionType(List.class, String.class));
                                        } catch (Exception e) {
                                            // 忽略解析错误
                                        }
                                    }
                                    if (!existingFiles.contains(imageId)) {
                                        existingFiles.add(imageId);
                                        cacheService.cacheValue("folder_files:" + folderId, 
                                                mapper.writeValueAsString(existingFiles), 24);
                                    }
                                }
                                
                                // 使用新的缓存键格式更新Redis列表
                                String folderKey = cacheService != null ? 
                                    cacheService.getFullCacheKey("folder:" + folderId) : "folder:" + folderId;
                                redisTemplate.opsForList().rightPush(folderKey, imageId);
                            }
                        }
                        
                        processed++;
                        if (processed % 100 == 0) {
                            System.out.println("已处理 " + processed + " 个文件");
                        }
                    } catch (IOException | com.hierynomus.mssmb2.SMBApiException e) {
                        // 跳过无法访问的文件
                        if (e.getMessage().contains("STATUS_OBJECT_NAME_NOT_FOUND")) {
                            // 如果是文件不存在错误，说明是废弃资源，静默跳过
                            skipped++;
                            if (skipped % 100 == 0) {
                                System.out.println("已跳过 " + skipped + " 个废弃资源");
                            }
                        } else {
                            // 其他错误记录日志
                            System.err.println("处理文件失败: " + metaPath + ", 错误: " + e.getMessage());
                        }
                    }
                }
            } finally {
                connectionLock.unlock();
            }
            
            System.out.println("文件夹与文件的关联关系索引构建完成，共处理 " + processed + " 个文件，跳过 " + skipped + " 个废弃资源");
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            System.err.println("构建索引失败: " + e.getMessage());
            e.printStackTrace();
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 处理所有文件夹，为每个文件夹创建一个空的列表
     */
    private void processAllFolders(JsonNode folders) {
        if (folders.isArray()) {
            for (JsonNode folder : folders) {
                String id = folder.path("id").asText();
                
                // 为每个文件夹创建一个空的列表（如果不存在）
                String folderKey = cacheService != null ? 
                    cacheService.getFullCacheKey("folder:" + id) : "folder:" + id;
                    
                if (!Boolean.TRUE.equals(redisTemplate.hasKey(folderKey))) {
                    redisTemplate.opsForList().rightPush(folderKey, "");
                    redisTemplate.opsForList().leftPop(folderKey);
                }
                
                // 递归处理子文件夹
                if (folder.has("children")) {
                    processAllFolders(folder.path("children"));
                }
            }
        }
    }

    private String findFolderId(JsonNode folders, String[] names, int index) {
        if (folders == null || index >= names.length) {
            return null;
        }
        for (JsonNode folder : folders) {
            if (names[index].equals(folder.path("name").asText())) {
                if (index == names.length - 1) {
                    return folder.path("id").asText();
                }
                return findFolderId(folder.path("children"), names, index + 1);
            }
        }
        return null;
    }

    /**
     * 列出目录内容，返回Map格式的结果
     * 这个方法主要用于CacheInitializer获取目录列表
     */
    @Async
    public CompletableFuture<List<Map<String, Object>>> listDirectories(String path, int offset, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();

        System.out.println("列出目录内容: " + path);

        try {
            connectionLock.lock();
            if (!initialized) {
                init();
            }

            for (FileIdBothDirectoryInformation f : share.list(path)) {
                if (f.getFileName().equals(".") || f.getFileName().equals("..")) {
                    continue;
                }

                Map<String, Object> entry = new HashMap<>();
                entry.put("name", f.getFileName());
                entry.put("size", f.getEndOfFile());
                entry.put("lastModified", f.getLastWriteTime().toEpochMillis());

                // 使用按位与操作检查是否为目录
                // FILE_ATTRIBUTE_DIRECTORY = 0x00000010 (16 in decimal)
                entry.put("isDirectory", (f.getFileAttributes() & 0x10) == 0x10);

                result.add(entry);

                if (result.size() >= limit && limit > 0) {
                    break;
                }
            }
        } finally {
            connectionLock.unlock();
        }

        return CompletableFuture.completedFuture(result);
    }

    /**
     * 检查文件是否存在
     */
    @Async
    public CompletableFuture<Boolean> fileExists(String path) {
        try {
            connectionLock.lock();
            if (!initialized) {
                init();
            }
            
            try {
                return CompletableFuture.completedFuture(share.fileExists(path));
            } catch (Exception e) {
                // 如果是文件不存在错误，返回false
                if (e.getMessage() != null && e.getMessage().contains("STATUS_OBJECT_NAME_NOT_FOUND")) {
                    return CompletableFuture.completedFuture(false);
                }
                // 其他错误抛出异常
                throw e;
            }
        } catch (Exception e) {
            System.err.println("检查文件是否存在失败: " + path + ", 错误: " + e.getMessage());
            return CompletableFuture.completedFuture(false);
        } finally {
            connectionLock.unlock();
        }
    }


    @Async
    public CompletableFuture<List<FileEntry>> listFilesWithChildren(String folderId, int offset, int limit) {
        System.out.println("开始获取文件夹及子文件夹文件列表 - folderId: " + folderId + ", offset: " + offset + ", limit: " + limit);

        // 收集所有子文件夹ID（递归）
        List<String> allFolders = new ArrayList<>();
        collectChildFolders(folderId, allFolders);
        // 将自身放在列表首位
        allFolders.add(0, folderId);
        System.out.println("文件夹及子文件夹列表: " + allFolders);

        // 汇总所有文件ID，使用并行流提高获取速度
        List<String> allFileIds = allFolders.parallelStream()
                .flatMap(fid -> {
                    String folderKey = cacheService.getFullCacheKey("folder:" + fid);
                    List<String> fileIds = redisTemplate.opsForList().range(folderKey, 0, -1);
                    return fileIds != null ? fileIds.stream() : Stream.empty();
                })
                .collect(Collectors.toList());

        // 根据offset和limit切片
        int from = Math.min(offset, allFileIds.size());
        int to = Math.min(from + limit, allFileIds.size());
        List<String> pageIds = allFileIds.subList(from, to);

        // 批量获取元数据，减少与Redis的网络往返
        List<String> metaKeys = pageIds.stream()
                .map(id -> cacheService.getFullCacheKey("meta:" + id))
                .collect(Collectors.toList());
        List<String> metaList = redisTemplate.opsForValue().multiGet(metaKeys);
        if (metaList == null) {
            metaList = Collections.emptyList();
        }

        List<FileEntry> result = new ArrayList<>(metaList.size());
        IntStream.range(0, metaList.size()).parallel().forEach(i -> {
            String meta = metaList.get(i);
            if (meta == null) {
                return;
            }
            try {
                JsonNode node = mapper.readTree(meta);
                String ext = node.path("ext").asText();
                String fileName = node.path("name").asText() + (ext.isEmpty() ? "" : "." + ext);
                String id = pageIds.get(i) + (ext.isEmpty() ? "" : "." + ext);
                result.add(new FileEntry(id, fileName));
            } catch (Exception e) {
                System.err.println("解析文件元数据失败: " + pageIds.get(i) + ", 错误: " + e.getMessage());
            }
        });

        System.out.println("文件列表获取完成，共 " + result.size() + " 个文件");
        return CompletableFuture.completedFuture(result);
    }

    private void collectChildFolders(String folderId, List<String> result) {
        String infoJson = cacheService.getCachedValue("folder_info:" + folderId);
        if (infoJson == null) {
            return;
        }
        try {
            JsonNode node = mapper.readTree(infoJson);
            JsonNode children = node.path("children");
            if (children.isArray()) {
                for (JsonNode child : children) {
                    String cid = child.path("id").asText();
                    if (!cid.isEmpty()) {
                        result.add(cid);
                        collectChildFolders(cid, result);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("解析文件夹信息失败: " + e.getMessage());
        }
    }

    /**
     * 获取文件的最后修改时间
     * @param filePath 文件路径
     * @return 文件的最后修改时间（毫秒时间戳），如果获取失败则返回0
     */
    public long getFileLastModifiedTime(String filePath) throws IOException {
        try {
            connectionLock.lock();
            if (!initialized) {
                init();
            }
            
            try {
                // 获取文件所在目录路径和文件名
                String parentPath = filePath.substring(0, filePath.lastIndexOf('/'));
                String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
                
                // 列出目录中的文件
                for (FileIdBothDirectoryInformation fileInfo : share.list(parentPath)) {
                    if (fileName.equals(fileInfo.getFileName())) {
                        // 找到匹配的文件，返回其最后修改时间
                        return fileInfo.getLastWriteTime().toEpochMillis();
                    }
                }
                
                // 如果没有找到文件，抛出异常
                throw new IOException("File not found: " + filePath);
                
            } catch (com.hierynomus.mssmb2.SMBApiException e) {
                // 如果发生连接错误，尝试重新初始化连接
                if (isConnectionError(e)) {
                    System.out.println("检测到连接错误，尝试重新初始化连接...");
                    cleanup();
                    init();
                    
                    // 重试一次
                    String parentPath = filePath.substring(0, filePath.lastIndexOf('/'));
                    String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
                    
                    for (FileIdBothDirectoryInformation fileInfo : share.list(parentPath)) {
                        if (fileName.equals(fileInfo.getFileName())) {
                            return fileInfo.getLastWriteTime().toEpochMillis();
                        }
                    }
                    
                    throw new IOException("File not found: " + filePath);
                } else {
                    throw new IOException(e);
                }
            }
        } finally {
            connectionLock.unlock();
        }
    }

}
