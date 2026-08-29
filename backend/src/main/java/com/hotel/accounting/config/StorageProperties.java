package com.hotel.accounting.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文件存储目录：Excel 上传暂存 + 旁车解析结果 JSON。
 */
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    private String uploadDir = "./storage/uploads";
    private String parsedDir = "./storage/parsed";

    public String getUploadDir() {
        return uploadDir;
    }

    public void setUploadDir(String uploadDir) {
        this.uploadDir = uploadDir;
    }

    public String getParsedDir() {
        return parsedDir;
    }

    public void setParsedDir(String parsedDir) {
        this.parsedDir = parsedDir;
    }
}
