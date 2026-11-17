package com.ruangpay.service;

import com.ruangpay.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class EventJournal {
    private final Path path;

    public EventJournal() {
        this.path = Path.of(System.getenv().getOrDefault(
                "EVENT_JOURNAL_PATH", "/data/events.jsonl"));
    }

    public synchronized void record(String type, Map<String,Object> payload) {
        try {
            Files.createDirectories(path.getParent());
            Map<String,Object> event = new LinkedHashMap<>();
            event.put("timestamp", Instant.now().toString());
            event.put("type", type);
            event.put("payload", payload);
            Files.writeString(path, Json.stringify(event) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Failed to write event journal: " + e.getMessage());
        }
    }
}