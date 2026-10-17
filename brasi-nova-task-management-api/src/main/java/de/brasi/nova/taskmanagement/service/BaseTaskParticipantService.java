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

import de.brasi.nova.taskmanagement.service.provider.BaseTaskParticipantProviders;

import de.brasi.nova.taskmanagement.model.TaskParticipantModels.TaskParticipantModel;
import de.brasi.nova.taskmanagement.model.TaskParticipantModels.TaskParticipantCreateModel;
import de.brasi.nova.taskmanagement.model.TaskParticipantModels.TaskParticipantSingleUpdateModel;
import de.brasi.nova.taskmanagement.model.TaskParticipantModels.TaskParticipantInfoModel;

public interface BaseTaskParticipantService extends NovaHateoasService<TaskParticipantModel, TaskParticipantCreateModel, TaskParticipantSingleUpdateModel, TaskParticipantInfoModel>,
    InfoResolvingService<TaskParticipantInfoModel> {


}
