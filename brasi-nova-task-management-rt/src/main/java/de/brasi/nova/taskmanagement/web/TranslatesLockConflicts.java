package de.brasi.nova.taskmanagement.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Markiert einen handgeschriebenen Controller, dessen schreibende Endpunkte ({@code POST}, {@code
 * PUT}, {@code PATCH}, {@code DELETE}) ein Deadlock-Opfer oder einen anderen Sperrfehler der
 * Datenbank als 409 melden statt als 500 (Intake #167, {@link LockConflictTranslationAspect}).
 *
 * <p>Jeder schreibende Endpunkt eines so markierten Controllers dokumentiert die 409 in OpenAPI;
 * das prueft {@code LockConflictTranslationAspectTest}. Ein neuer handgeschriebener Controller mit
 * Datenbank-Schreibzugriff bekommt die Markierung ebenfalls.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TranslatesLockConflicts {}
