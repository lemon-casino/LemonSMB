package org.example.lemonsmb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class MetadataLoader {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper mapper = new ObjectMapper();

    @EventListener(ApplicationReadyEvent.class)
    public void loadMetadata() throws IOException {
        Path path = Path.of("视觉素材库.library/metadata.json");
        if (Files.exists(path)) {
            String json = Files.readString(path);
            redisTemplate.opsForValue().set("metadata", json);
        }
        Path mtime = Path.of("视觉素材库.library/mtime.json");
        if (Files.exists(mtime)) {
            String json = Files.readString(mtime);
            redisTemplate.opsForValue().set("mtime", json);
        }
    }
}
