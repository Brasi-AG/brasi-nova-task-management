/*
 * Copyright (C) 2026 Keel GmbH. Alle Rechte vorbehalten.
 */
package de.brasi.nova.taskmanagement.service;

import java.util.UUID;

import org.springframework.hateoas.CollectionModel;

import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.service.NovaHateoasService;
import io.keel.framework.api.service.annotation.NovaBusinessLogic;
import io.keel.framework.api.cache.InfoResolvingService;

import de.brasi.nova.taskmanagement.service.provider.BaseTaskReminderReceiptProviders;

import de.brasi.nova.taskmanagement.model.TaskReminderReceiptModels.TaskReminderReceiptModel;
import de.brasi.nova.taskmanagement.model.TaskReminderReceiptModels.TaskReminderReceiptCreateModel;
import de.brasi.nova.taskmanagement.model.TaskReminderReceiptModels.TaskReminderReceiptSingleUpdateModel;
import de.brasi.nova.taskmanagement.model.TaskReminderReceiptModels.TaskReminderReceiptInfoModel;

public interface BaseTaskReminderReceiptService extends NovaHateoasService<TaskReminderReceiptModel, TaskReminderReceiptCreateModel, TaskReminderReceiptSingleUpdateModel, TaskReminderReceiptInfoModel>,
    InfoResolvingService<TaskReminderReceiptInfoModel> {


}
