package com.chithira.fieryhold;

import com.chithira.fieryhold.AppConfig.Option;

import java.nio.file.Path;

/** One file plus the settings chosen in the popup. */
public record PrintJob(
        Path file,
        Option media,
        Option finishing,
        boolean testPrint,
        int copies,
        String instructions,
        String customerName) {

    public String fileName() {
        return file.getFileName().toString();
    }
}
