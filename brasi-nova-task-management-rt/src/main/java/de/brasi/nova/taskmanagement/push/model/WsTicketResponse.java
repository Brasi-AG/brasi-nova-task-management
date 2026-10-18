package de.brasi.nova.taskmanagement.push.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Antwort von {@code POST /api/v1/ws-tickets} (arch-v2 §6).
 *
 * <p>Das {@code ticket} ist ein opaker, kryptographisch zufaelliger (&gt;=128 Bit), kurzlebiger
 * Einmal-Token. Der Client verbindet damit {@code WS /ws?ticket=...} (kein JWT in der URL).
 *
 * @param ticket    opaker Einmal-Token (Base64url ohne Padding)
 * @param expiresAt Ablaufzeitpunkt (UTC); nach Ablauf wird der Token beim Handshake abgelehnt
 */
public record WsTicketResponse(
    @Schema(description = "Opaker Einmal-Token (Base64url ohne Padding, >= 128 Bit Zufall).",
        example = "abc123XYZ_opakes-token-hier")
    String ticket,
    @Schema(description = "Ablaufzeitpunkt des Tickets (UTC); nach Ablauf wird der Token beim Handshake abgelehnt.",
        example = "2026-10-18T08:00:30Z")
    Instant expiresAt) {}
