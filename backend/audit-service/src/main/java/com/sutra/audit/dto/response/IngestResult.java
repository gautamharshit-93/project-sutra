package com.sutra.audit.dto.response;

/** How many events a service sent and how many were new (the rest were repeated deliveries). */
public record IngestResult(int received, int stored) {
}
