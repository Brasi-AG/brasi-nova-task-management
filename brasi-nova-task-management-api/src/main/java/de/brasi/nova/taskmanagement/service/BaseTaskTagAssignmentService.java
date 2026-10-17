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

import de.brasi.nova.taskmanagement.service.provider.BaseTaskTagAssignmentProviders;

import de.brasi.nova.taskmanagement.model.TaskTagAssignmentModels.TaskTagAssignmentModel;
import de.brasi.nova.taskmanagement.model.TaskTagAssignmentModels.TaskTagAssignmentCreateModel;
import de.brasi.nova.taskmanagement.model.TaskTagAssignmentModels.TaskTagAssignmentSingleUpdateModel;
import de.brasi.nova.taskmanagement.model.TaskTagAssignmentModels.TaskTagAssignmentInfoModel;

public interface BaseTaskTagAssignmentService extends NovaHateoasService<TaskTagAssignmentModel, TaskTagAssignmentCreateModel, TaskTagAssignmentSingleUpdateModel, TaskTagAssignmentInfoModel>,
    InfoResolvingService<TaskTagAssignmentInfoModel> {

    @NovaBusinessLogic(provider = BaseTaskTagAssignmentProviders.GetTaskTagAssignmentsByTagIdProvider.class)
    CollectionModel<TaskTagAssignmentModel> findTaskTagAssignmentsForTag(UUID tagUuid) throws EntityNotFoundException;

}
