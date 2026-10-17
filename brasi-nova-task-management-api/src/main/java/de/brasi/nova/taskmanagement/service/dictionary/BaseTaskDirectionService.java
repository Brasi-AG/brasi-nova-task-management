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

import de.brasi.nova.taskmanagement.service.provider.dictionary.BaseTaskDirectionProviders;

import de.brasi.nova.taskmanagement.model.dictionary.TaskDirectionModels.TaskDirectionModel;
import de.brasi.nova.taskmanagement.model.dictionary.TaskDirectionModels.TaskDirectionCreateModel;
import de.brasi.nova.taskmanagement.model.dictionary.TaskDirectionModels.TaskDirectionSingleUpdateModel;
import de.brasi.nova.taskmanagement.model.dictionary.TaskDirectionModels.TaskDirectionInfoModel;

public interface BaseTaskDirectionService extends NovaHateoasDictionaryService<TaskDirectionModel, TaskDirectionCreateModel, TaskDirectionSingleUpdateModel, TaskDirectionInfoModel>,
    InfoResolvingService<TaskDirectionInfoModel> {


}
