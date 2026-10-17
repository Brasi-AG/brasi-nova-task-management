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

import de.brasi.nova.taskmanagement.service.provider.BaseTaskProviders;

import de.brasi.nova.taskmanagement.model.TaskModels.TaskModel;
import de.brasi.nova.taskmanagement.model.TaskModels.TaskCreateModel;
import de.brasi.nova.taskmanagement.model.TaskModels.TaskSingleUpdateModel;
import de.brasi.nova.taskmanagement.model.TaskModels.TaskInfoModel;

public interface BaseTaskService extends NovaHateoasService<TaskModel, TaskCreateModel, TaskSingleUpdateModel, TaskInfoModel>,
    InfoResolvingService<TaskInfoModel> {

    @NovaBusinessLogic(provider = BaseTaskProviders.GetTasksByDirectionIdProvider.class)
    CollectionModel<TaskModel> findTasksForDirection(UUID directionUuid) throws EntityNotFoundException;
    @NovaBusinessLogic(provider = BaseTaskProviders.GetTasksByPriorityIdProvider.class)
    CollectionModel<TaskModel> findTasksForPriority(UUID priorityUuid) throws EntityNotFoundException;
    @NovaBusinessLogic(provider = BaseTaskProviders.GetTasksByTaskStatusIdProvider.class)
    CollectionModel<TaskModel> findTasksForTaskStatus(UUID taskStatusUuid) throws EntityNotFoundException;

}
