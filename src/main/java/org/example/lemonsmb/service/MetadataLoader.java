package org.example.lemonsmb.service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
@Component
public class MetadataLoader {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private SmbService smbService;


    @EventListener(ApplicationReadyEvent.class)
    public void loadMetadata() throws IOException {
        String base = smbService.getProperties().getLibraryDir();
        try {
            String metadata = smbService.readFile(base + "/metadata.json");
            try {
                redisTemplate.opsForValue().set("metadata", metadata);
            } catch (Exception ignore) {
                // redis unavailable
            }
        } catch (IOException ignored) {
        }
        try {
            String mtime = smbService.readFile(base + "/mtime.json");
            try {
                redisTemplate.opsForValue().set("mtime", mtime);
            } catch (Exception ignore) {
                // redis unavailable
            }
        } catch (IOException ignored) {
        }

        // Build folder to image index for fast lookup
        try {
            smbService.indexLibrary();
        } catch (Exception ignore) {
            // ignore indexing failures
        }
    }
}
