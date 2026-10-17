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

import de.brasi.nova.taskmanagement.service.provider.BaseTaskCommentProviders;

import de.brasi.nova.taskmanagement.model.TaskCommentModels.TaskCommentModel;
import de.brasi.nova.taskmanagement.model.TaskCommentModels.TaskCommentCreateModel;
import de.brasi.nova.taskmanagement.model.TaskCommentModels.TaskCommentSingleUpdateModel;
import de.brasi.nova.taskmanagement.model.TaskCommentModels.TaskCommentInfoModel;

public interface BaseTaskCommentService extends NovaHateoasService<TaskCommentModel, TaskCommentCreateModel, TaskCommentSingleUpdateModel, TaskCommentInfoModel>,
    InfoResolvingService<TaskCommentInfoModel> {


}
