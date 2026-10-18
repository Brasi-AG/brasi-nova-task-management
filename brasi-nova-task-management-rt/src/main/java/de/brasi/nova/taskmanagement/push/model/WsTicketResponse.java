package de.brasi.nova.taskmanagement.push.model;

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
public record WsTicketResponse(String ticket, Instant expiresAt) {}
