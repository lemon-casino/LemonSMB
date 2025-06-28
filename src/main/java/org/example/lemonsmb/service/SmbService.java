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
import org.example.lemonsmb.config.SmbProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
@Service
public class SmbService {

    @Autowired
    private SmbProperties properties;

    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode metadataCache;

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
     * Read a file from the SMB share using UTF-8 encoding.
     */
    public String readFile(String remotePath) throws IOException {
        try (DiskShare share = connectShare()) {
            File f = share.openFile(remotePath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null);
            try (InputStream is = f.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    @Async
    public CompletableFuture<List<String>> listFiles(String path, int offset, int limit) {
        List<String> result = new ArrayList<>();
        try {
            if (metadataCache == null) {
                String meta = readFile(properties.getLibraryDir() + "/metadata.json");
                metadataCache = mapper.readTree(meta).path("folders");
            }

            String folderId = null;
            if (path != null && !path.isEmpty()) {
                folderId = findFolderId(metadataCache, path.split("/"), 0);
            }

            String imagesBase = properties.getLibraryDir() + "/images";
            try (DiskShare share = connectShare()) {
                int processed = 0;
                for (FileIdBothDirectoryInformation f : share.list(imagesBase)) {
                    if (!f.getFileName().endsWith(".info")) {
                        continue;
                    }
                    String imageId = f.getFileName().replace(".info", "");
                    String metaPath = imagesBase + "/" + f.getFileName() + "/metadata.json";
                    try {
                        String imgMeta = readFile(metaPath);
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
                            result.add(imageId + "." + node.path("ext").asText());
                            if (result.size() >= limit) {
                                break;
                            }
                        }
                    } catch (IOException ignore) {
                    }
                }
            }
        } catch (IOException e) {
            result.add("ERROR:" + e.getMessage());
        }
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
}
