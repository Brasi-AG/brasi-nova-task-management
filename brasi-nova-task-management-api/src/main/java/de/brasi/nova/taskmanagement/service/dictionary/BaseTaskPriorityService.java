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

import de.brasi.nova.taskmanagement.service.provider.dictionary.BaseTaskPriorityProviders;

import de.brasi.nova.taskmanagement.model.dictionary.TaskPriorityModels.TaskPriorityModel;
import de.brasi.nova.taskmanagement.model.dictionary.TaskPriorityModels.TaskPriorityCreateModel;
import de.brasi.nova.taskmanagement.model.dictionary.TaskPriorityModels.TaskPrioritySingleUpdateModel;
import de.brasi.nova.taskmanagement.model.dictionary.TaskPriorityModels.TaskPriorityInfoModel;

public interface BaseTaskPriorityService extends NovaHateoasDictionaryService<TaskPriorityModel, TaskPriorityCreateModel, TaskPrioritySingleUpdateModel, TaskPriorityInfoModel>,
    InfoResolvingService<TaskPriorityInfoModel> {


}
