package org.example.lemonsmb.service;

import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.auth.AuthenticationContext;
import org.example.lemonsmb.config.SmbProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class SmbService {

    @Autowired
    private SmbProperties properties;

    private DiskShare connectShare() throws IOException {
        SMBClient client = new SMBClient();
        Connection connection = client.connect(properties.getHost());
        AuthenticationContext auth = new AuthenticationContext(
                properties.getUsername(), properties.getPassword().toCharArray(), null);
        Session session = connection.authenticate(auth);
        return (DiskShare) session.connectShare(properties.getShare());
    }

    @Async
    public CompletableFuture<List<String>> listFiles(String path) {
        List<String> result = new ArrayList<>();
        try (DiskShare share = connectShare()) {
            for (FileIdBothDirectoryInformation f : share.list(path)) {
                result.add(f.getFileName());
            }
        } catch (IOException e) {
            result.add("ERROR:" + e.getMessage());
        }
        return CompletableFuture.completedFuture(result);
    }
}
