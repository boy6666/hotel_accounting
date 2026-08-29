package com.hotel.accounting.controller;

import com.hotel.accounting.common.ApiResult;
import com.hotel.accounting.common.PageResult;
import com.hotel.accounting.model.ImportBatch;
import com.hotel.accounting.service.ImportService;
import com.hotel.accounting.service.TemplateExcelService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Excel 导入（03 §12）：模板下载 + 上传/解析 + 预览 + 智能归类 + 确认落库 + 历史/删除。
 */
@RestController
@RequestMapping("/api/imports")
public class ImportController {

    private final ImportService importService;
    private final TemplateExcelService templateExcelService;

    public ImportController(ImportService importService, TemplateExcelService templateExcelService) {
        this.importService = importService;
        this.templateExcelService = templateExcelService;
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template() {
        byte[] bytes = templateExcelService.build();
        String filename = URLEncoder.encode("月度记账模板.xlsx", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    @PostMapping
    public ApiResult<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                                 @RequestParam(required = false) String month) {
        return ApiResult.ok(importService.upload(file, month));
    }

    @GetMapping("/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResult.ok(importService.detail(id));
    }

    @GetMapping("/{id}/preview")
    public ApiResult<Map<String, Object>> preview(@PathVariable Long id) {
        return ApiResult.ok(importService.preview(id));
    }

    @GetMapping("/{id}/mapping")
    public ApiResult<Map<String, Object>> mapping(@PathVariable Long id) {
        return ApiResult.ok(importService.mapping(id));
    }

    @PostMapping("/{id}/confirm")
    public ApiResult<Map<String, Object>> confirm(@PathVariable Long id,
                                                  @RequestBody(required = false) ImportService.ConfirmReq req) {
        return ApiResult.ok(importService.confirm(id, req));
    }

    @GetMapping
    public ApiResult<PageResult<ImportBatch>> list(@RequestParam(required = false) String month,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResult.ok(importService.list(month, status, page, pageSize));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        importService.delete(id);
        return ApiResult.ok();
    }
}
