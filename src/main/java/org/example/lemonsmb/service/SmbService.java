package org.example.lemonsmb.service;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import com.hierynomus.protocol.transport.TransportException;
import com.hierynomus.smbj.common.SMBRuntimeException;
import org.example.lemonsmb.config.SmbProperties;
import org.example.lemonsmb.model.FileInfo;
import org.example.lemonsmb.model.FileEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
@Service
public class SmbService {

    private static final Logger log = LoggerFactory.getLogger(SmbService.class);

    @Autowired
    private SmbProperties properties;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode metadataCache;

    private byte[] readBytes(String remotePath) throws IOException {
        DiskShare share = null;
        try {
            share = connectShare();
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
            throw new IOException(e);
        } finally {
            safeClose(share);
        }
    }

    @Async
    public CompletableFuture<byte[]> loadImage(String id, boolean thumbnail) {
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
        
        log.info("加载图片 - ID: {}, thumbnail: {}", id, thumbnail);
        
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
                    log.debug("尝试加载缩略图: {}", thumbnailPath);
                    byte[] data = readBytes(thumbnailPath);
                    log.debug("缩略图加载成功，数据长度: {}", data.length);
                    return CompletableFuture.completedFuture(data);
                } catch (IOException thumbnailError) {
                    log.debug("缩略图不存在，回退到原图: {}", thumbnailError.getMessage());
                    // 缩略图不存在，回退到原图
                }
            }
            
            // 加载原图
            String originalFileName = name + "." + ext;
            String originalPath = infoDir + "/" + originalFileName;
            log.debug("加载原图: {}", originalPath);
            
            byte[] data = readBytes(originalPath);
            log.debug("原图加载成功，数据长度: {}", data.length);
            return CompletableFuture.completedFuture(data);
            
        } catch (IOException e) {
            log.error("加载图片完全失败: {}", e.getMessage());
            return CompletableFuture.completedFuture(new byte[0]);
        }
    }

    /**
     * 异步加载任意文件的字节数据
     */
    @Async
    public CompletableFuture<byte[]> loadFile(String id) {
        log.debug("Loading file {}", id);
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
            log.trace("Reading file bytes from {}", filePath);
            return CompletableFuture.completedFuture(readBytes(filePath));
        } catch (IOException e) {
            log.error("加载文件失败 {}", e.getMessage());
            return CompletableFuture.completedFuture(new byte[0]);
        }
    }

    /**
     * 异步获取文件详细信息
     */
    @Async
    public CompletableFuture<FileInfo> getFileInfo(String id) {
        log.debug("Getting file info for {}", id);
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
            DiskShare share = null;
            try {
                share = connectShare();
                com.hierynomus.msfscc.fileinformation.FileStandardInformation fileStdInfo =
                    share.getFileInformation(filePath).getStandardInformation();
                if (fileStdInfo != null) {
                    info.setSize(fileStdInfo.getEndOfFile());
                }
            } catch (Exception e) {
                // 如果获取文件信息失败，使用元数据中的信息
                log.debug("无法获取文件 {} 的实际大小: {}", filePath, e.getMessage());
            } finally {
                safeClose(share);
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
        log.debug("Connecting to SMB share {} on host {}", properties.getShare(), properties.getHost());
        SMBClient client = new SMBClient();
        Connection connection = client.connect(properties.getHost());
        AuthenticationContext auth = new AuthenticationContext(
                properties.getUsername(), properties.getPassword().toCharArray(), null);
        Session session = connection.authenticate(auth);
        log.debug("Authenticated user {}", properties.getUsername());
        DiskShare share = (DiskShare) session.connectShare(properties.getShare());
        log.debug("Connected to share {}", properties.getShare());
        return share;
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
            log.trace("Closing share");
            share.close();
            log.trace("Share closed");
        } catch (TransportException | SMBRuntimeException e) {
            log.warn("Error closing share gracefully: {}", e.getMessage());
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
        log.debug("Reading file {}", remotePath);
        DiskShare share = null;
        try {
            share = connectShare();
            File f = share.openFile(remotePath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null);
            try (InputStream is = f.getInputStream()) {
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                log.trace("Read {} bytes from {}", content.length(), remotePath);
                return content;
            }
        } catch (com.hierynomus.mssmb2.SMBApiException e) {
            // Wrap SMB errors so callers can handle uniformly
            throw new IOException(e);
        } finally {
            safeClose(share);
        }
    }

    @Async
    public CompletableFuture<List<FileEntry>> listFiles(String path, int offset, int limit) {
        log.info("Listing files - path: {}, offset: {}, limit: {}", path, offset, limit);
        List<FileEntry> result = new ArrayList<>();
        try {
            if (metadataCache == null) {
                String meta = redisTemplate.opsForValue().get("metadata");
                if (meta == null) {
                    log.debug("Metadata cache miss, reading from share");
                    meta = readFile(properties.getLibraryDir() + "/metadata.json");
                } else {
                    log.debug("Loaded metadata from Redis cache");
                }
                if (meta != null) {
                    metadataCache = mapper.readTree(meta).path("folders");
                }
            }
            String folderId = resolveFolderId(path);
            log.debug("Resolved folder id: {} for path: {}", folderId, path);

            String imagesBase = properties.getLibraryDir() + "/images";
            DiskShare share = null;
            try {
                share = connectShare();
                int processed = 0;
                for (FileIdBothDirectoryInformation f : share.list(imagesBase)) {
                    if (!f.getFileName().endsWith(".info")) {
                        continue;
                    }
                    log.trace("Checking file {}", f.getFileName());
                    String imageId = f.getFileName().replace(".info", "");
                    String metaPath = imagesBase + "/" + f.getFileName() + "/metadata.json";
                    try (File mf = share.openFile(metaPath,
                            EnumSet.of(AccessMask.GENERIC_READ),
                            null,
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OPEN,
                            null);
                         InputStream is = mf.getInputStream()) {
                        String imgMeta = new String(is.readAllBytes(), StandardCharsets.UTF_8);
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
                            log.trace("Adding file {} ({})", fileName, id);
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
                safeClose(share);
            }
        } catch (IOException e) {
            // Log the error but return whatever results were collected
            log.error("Failed to list files: {}", e.getMessage());
        }
        log.info("Listed {} files", result.size());
        return CompletableFuture.completedFuture(result);
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

    private boolean containsFolderId(JsonNode folders, String id) {
        if (folders == null) {
            return false;
        }
        for (JsonNode folder : folders) {
            if (id.equals(folder.path("id").asText())) {
                return true;
            }
            if (containsFolderId(folder.path("children"), id)) {
                return true;
            }
        }
        return false;
    }
    /**
     * Resolve a folder path or ID to the corresponding folder ID.
     * The supplied path may include extra leading segments not present
     * in the metadata hierarchy. This method first checks if the given
     * string already matches an existing folder ID. If not, it attempts
     * to locate the folder by progressively matching the path segments
     * against the metadata tree.
     */
    private String resolveFolderId(String path) {
        if (path == null || path.isEmpty() || metadataCache == null) {
            return null;
        }
        log.trace("Resolving folder ID for path {}", path);
        if (containsFolderId(metadataCache, path)) {
            return path;
        }
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            String[] subset = new String[parts.length - i];
            System.arraycopy(parts, i, subset, 0, subset.length);
            String id = findFolderId(metadataCache, subset, 0);
            if (id != null) {
                log.trace("Path {} resolved to id {}", String.join("/", subset), id);
                return id;
            }
        }
        log.trace("No folder ID found for path {}", path);
        return null;
    }
}
