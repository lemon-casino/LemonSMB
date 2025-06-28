package org.example.lemonsmb.controller;

import org.example.lemonsmb.service.SmbService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    public CompletableFuture<List<String>> list(@RequestParam(defaultValue = "") String path) {
        return smbService.listFiles(path);
    }
}
