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
            redisTemplate.opsForValue().set("metadata", metadata);
            smbService.refreshMetadataCache();
        } catch (IOException ignored) {
        }
        try {
            String mtime = smbService.readFile(base + "/mtime.json");
            redisTemplate.opsForValue().set("mtime", mtime);
        } catch (IOException ignored) {
        }
    }
}
