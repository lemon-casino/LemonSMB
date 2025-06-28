package org.example.lemonsmb.service;

import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import com.hierynomus.smbj.share.SMB2CreateDisposition;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.smbj.share.SMB2ShareAccess;
import com.hierynomus.smbj.auth.AuthenticationContext;
import org.example.lemonsmb.config.SmbProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class SmbService {

    @Autowired
    private SmbProperties properties;

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
        String target = properties.getLibraryDir() + "/images";
        if (path != null && !path.isEmpty()) {
            target = target + "/" + path;
        }
        try (DiskShare share = connectShare()) {
            int count = 0;
            for (FileIdBothDirectoryInformation f : share.list(target)) {
                if (count++ < offset) {
                    continue;
                }
                result.add(f.getFileName());
                if (result.size() >= limit) {
                    break;
                }
            }
        } catch (IOException e) {
            result.add("ERROR:" + e.getMessage());
        }
        return CompletableFuture.completedFuture(result);
    }
}
