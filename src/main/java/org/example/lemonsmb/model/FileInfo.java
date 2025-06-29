package org.example.lemonsmb.model;

import java.time.LocalDateTime;

/**
 * 文件信息封装类
 */
public class FileInfo {
    private String name;
    private long size;
    private LocalDateTime lastModified;
    private boolean isDirectory;
    private String path;

    public FileInfo() {}

    public FileInfo(String name, long size, LocalDateTime lastModified, boolean isDirectory, String path) {
        this.name = name;
        this.size = size;
        this.lastModified = lastModified;
        this.isDirectory = isDirectory;
        this.path = path;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public LocalDateTime getLastModified() {
        return lastModified;
    }

    public void setLastModified(LocalDateTime lastModified) {
        this.lastModified = lastModified;
    }

    public boolean isDirectory() {
        return isDirectory;
    }

    public void setDirectory(boolean directory) {
        isDirectory = directory;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    @Override
    public String toString() {
        return "FileInfo{" +
                "name='" + name + '\'' +
                ", size=" + size +
                ", lastModified=" + lastModified +
                ", isDirectory=" + isDirectory +
                ", path='" + path + '\'' +
                '}';
    }
} 