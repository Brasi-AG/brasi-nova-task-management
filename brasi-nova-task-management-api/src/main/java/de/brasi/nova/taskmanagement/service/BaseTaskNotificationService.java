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

import de.brasi.nova.taskmanagement.service.provider.BaseTaskNotificationProviders;

import de.brasi.nova.taskmanagement.model.TaskNotificationModels.TaskNotificationModel;
import de.brasi.nova.taskmanagement.model.TaskNotificationModels.TaskNotificationCreateModel;
import de.brasi.nova.taskmanagement.model.TaskNotificationModels.TaskNotificationSingleUpdateModel;
import de.brasi.nova.taskmanagement.model.TaskNotificationModels.TaskNotificationInfoModel;

public interface BaseTaskNotificationService extends NovaHateoasService<TaskNotificationModel, TaskNotificationCreateModel, TaskNotificationSingleUpdateModel, TaskNotificationInfoModel>,
    InfoResolvingService<TaskNotificationInfoModel> {

    @NovaBusinessLogic(provider = BaseTaskNotificationProviders.GetTaskNotificationsBySeverityIdProvider.class)
    CollectionModel<TaskNotificationModel> findTaskNotificationsForSeverity(UUID severityUuid) throws EntityNotFoundException;
    @NovaBusinessLogic(provider = BaseTaskNotificationProviders.GetTaskNotificationsByTypeIdProvider.class)
    CollectionModel<TaskNotificationModel> findTaskNotificationsForType(UUID typeUuid) throws EntityNotFoundException;

}
