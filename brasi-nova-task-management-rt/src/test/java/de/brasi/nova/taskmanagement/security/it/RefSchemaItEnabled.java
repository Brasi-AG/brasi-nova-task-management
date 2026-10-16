package de.brasi.nova.taskmanagement.security.it;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Meta-Annotation: markiert eine IT-Klasse als "nur mit RefSchema-MSSQL-Zugang lauffaehig". Bindet
 * die {@link RefSchemaItEnabledCondition} ein (skip statt fail, wenn nicht konfiguriert).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RefSchemaItEnabledCondition.class)
public @interface RefSchemaItEnabled {}
