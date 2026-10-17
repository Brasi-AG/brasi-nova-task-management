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

import de.brasi.nova.taskmanagement.service.provider.BaseTaskUserStateProviders;

import de.brasi.nova.taskmanagement.model.TaskUserStateModels.TaskUserStateModel;
import de.brasi.nova.taskmanagement.model.TaskUserStateModels.TaskUserStateCreateModel;
import de.brasi.nova.taskmanagement.model.TaskUserStateModels.TaskUserStateSingleUpdateModel;
import de.brasi.nova.taskmanagement.model.TaskUserStateModels.TaskUserStateInfoModel;

public interface BaseTaskUserStateService extends NovaHateoasService<TaskUserStateModel, TaskUserStateCreateModel, TaskUserStateSingleUpdateModel, TaskUserStateInfoModel>,
    InfoResolvingService<TaskUserStateInfoModel> {


}
