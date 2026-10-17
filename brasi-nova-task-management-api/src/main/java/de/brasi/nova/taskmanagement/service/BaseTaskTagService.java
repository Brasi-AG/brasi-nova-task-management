/*
 * Copyright (C) 2026 Keel GmbH. Alle Rechte vorbehalten.
 */
package de.brasi.nova.taskmanagement.service;

import java.util.UUID;

import org.springframework.hateoas.CollectionModel;

import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.service.NovaHateoasDictionaryService;
import io.keel.framework.api.service.annotation.NovaBusinessLogic;
import io.keel.framework.api.cache.InfoResolvingService;

import de.brasi.nova.taskmanagement.service.provider.BaseTaskTagProviders;

import de.brasi.nova.taskmanagement.model.TaskTagModels.TaskTagModel;
import de.brasi.nova.taskmanagement.model.TaskTagModels.TaskTagCreateModel;
import de.brasi.nova.taskmanagement.model.TaskTagModels.TaskTagSingleUpdateModel;
import de.brasi.nova.taskmanagement.model.TaskTagModels.TaskTagInfoModel;

public interface BaseTaskTagService extends NovaHateoasDictionaryService<TaskTagModel, TaskTagCreateModel, TaskTagSingleUpdateModel, TaskTagInfoModel>,
    InfoResolvingService<TaskTagInfoModel> {

    @NovaBusinessLogic(provider = BaseTaskTagProviders.GetTaskTagsBySeverityIdProvider.class)
    CollectionModel<TaskTagModel> findTaskTagsForSeverity(UUID severityUuid) throws EntityNotFoundException;

}
