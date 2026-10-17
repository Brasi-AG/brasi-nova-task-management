/*
 * Copyright (C) 2026 Keel GmbH. Alle Rechte vorbehalten.
 */
package de.brasi.nova.taskmanagement.service.dictionary;

import java.util.UUID;

import org.springframework.hateoas.CollectionModel;

import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.service.NovaHateoasDictionaryService;
import io.keel.framework.api.service.annotation.NovaBusinessLogic;
import io.keel.framework.api.cache.InfoResolvingService;

import de.brasi.nova.taskmanagement.service.provider.dictionary.BaseTaskStatusProviders;

import de.brasi.nova.taskmanagement.model.dictionary.TaskStatusModels.TaskStatusModel;
import de.brasi.nova.taskmanagement.model.dictionary.TaskStatusModels.TaskStatusCreateModel;
import de.brasi.nova.taskmanagement.model.dictionary.TaskStatusModels.TaskStatusSingleUpdateModel;
import de.brasi.nova.taskmanagement.model.dictionary.TaskStatusModels.TaskStatusInfoModel;

public interface BaseTaskStatusService extends NovaHateoasDictionaryService<TaskStatusModel, TaskStatusCreateModel, TaskStatusSingleUpdateModel, TaskStatusInfoModel>,
    InfoResolvingService<TaskStatusInfoModel> {


}
