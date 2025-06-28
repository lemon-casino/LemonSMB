package org.example.lemonsmb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "smb")
public class SmbProperties {
    private String host;
    private String username;
    private String password;
    private String share;
    /**
     * Directory of the Eagle library inside the share. Example:
     * "视觉素材库/视觉素材库.library".
     */
    private String libraryDir;
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getShare() { return share; }
    public void setShare(String share) { this.share = share; }
    public String getLibraryDir() { return libraryDir; }
    public void setLibraryDir(String libraryDir) { this.libraryDir = libraryDir; }
}
