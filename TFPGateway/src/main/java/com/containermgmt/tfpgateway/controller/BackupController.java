package com.containermgmt.tfpgateway.controller;

import com.containermgmt.tfpgateway.dto.BackupRequest;
import com.containermgmt.tfpgateway.service.BackupService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Controller
@Slf4j
public class BackupController {

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    @GetMapping("/backup")
    public String backupPage(Model model) {
        model.addAttribute("backupRequest", new BackupRequest());
        return "backup";
    }

    @GetMapping("/backup/count")
    public void countRecords(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            HttpServletResponse response) throws IOException {

        long count = backupService.countForBackup(dateFrom, dateTo);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"count\":" + count + "}");
    }

    @PostMapping("/backup/export")
    public void exportBackup(
            @ModelAttribute BackupRequest req,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes) throws IOException {

        String format = req.getFormat() != null ? req.getFormat().toUpperCase() : "SQL";
        String ext = switch (format) {
            case "CSV"    -> "csv";
            case "JSON"   -> "json";
            case "NDJSON" -> "ndjson";
            default       -> "sql";
        };

        String fromStr = req.getDateFrom() != null ? req.getDateFrom().format(FILE_DATE) : "all";
        String toStr   = req.getDateTo()   != null ? req.getDateTo().format(FILE_DATE)   : "all";
        String filename = "backup_" + fromStr + "_" + toStr + "." + ext;

        response.setContentType(ext.equals("csv") ? "text/csv" : "application/octet-stream");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");

        try {
            backupService.exportAndOptionallyDelete(
                    req.getDateFrom(), req.getDateTo(),
                    format, req.isDeleteAfterBackup(),
                    req.getSqlCommitEvery(),
                    response.getOutputStream());
            response.getOutputStream().flush();
            log.info("Backup exported: file={}, format={}, deleteAfterBackup={}",
                    filename, format, req.isDeleteAfterBackup());
        } catch (Exception e) {
            log.error("Backup export failed", e);
            if (!response.isCommitted()) {
                response.resetBuffer();
                response.setContentType(MediaType.TEXT_PLAIN_VALUE);
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.getWriter().write("Export failed: " + e.getMessage());
            }
        }
    }
}
