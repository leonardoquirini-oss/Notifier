package com.containermgmt.tfpgateway.dto;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

public class BackupRequest {

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateFrom;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateTo;

    private String format = "SQL";

    private boolean deleteAfterBackup;

    private int sqlCommitEvery = 1000;

    public LocalDate getDateFrom() { return dateFrom; }
    public void setDateFrom(LocalDate dateFrom) { this.dateFrom = dateFrom; }

    public LocalDate getDateTo() { return dateTo; }
    public void setDateTo(LocalDate dateTo) { this.dateTo = dateTo; }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public boolean isDeleteAfterBackup() { return deleteAfterBackup; }
    public void setDeleteAfterBackup(boolean deleteAfterBackup) { this.deleteAfterBackup = deleteAfterBackup; }

    public int getSqlCommitEvery() { return sqlCommitEvery; }
    public void setSqlCommitEvery(int sqlCommitEvery) { this.sqlCommitEvery = sqlCommitEvery; }
}
