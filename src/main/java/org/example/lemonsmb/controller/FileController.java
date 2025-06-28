package org.example.lemonsmb.controller;

import org.example.lemonsmb.service.SmbService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
public class FileController {

    @Autowired
    private SmbService smbService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @GetMapping("/metadata")
    public String metadata() {
        return redisTemplate.opsForValue().get("metadata");
    }

    @GetMapping("/files")
    public CompletableFuture<List<String>> list(
            @RequestParam(defaultValue = "") String path,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {
        return smbService.listFiles(path, offset, limit);
    }

    @GetMapping("/image")
    public CompletableFuture<ResponseEntity<byte[]>> image(
            @RequestParam String id,
            @RequestParam(defaultValue = "true") boolean thumbnail) {
        String ext = "";
        int dot = id.lastIndexOf('.');
        if (dot > 0) {
            ext = id.substring(dot + 1).toLowerCase();
        }
        MediaType type = switch (ext) {
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "png" -> MediaType.IMAGE_PNG;
            case "gif" -> MediaType.IMAGE_GIF;
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
        return smbService.loadImage(id, thumbnail)
                .thenApply(data -> ResponseEntity.ok().contentType(type).body(data));
    }
}
