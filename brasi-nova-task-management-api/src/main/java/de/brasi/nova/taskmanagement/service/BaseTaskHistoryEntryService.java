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

import de.brasi.nova.taskmanagement.service.provider.BaseTaskHistoryEntryProviders;

import de.brasi.nova.taskmanagement.model.TaskHistoryEntryModels.TaskHistoryEntryModel;
import de.brasi.nova.taskmanagement.model.TaskHistoryEntryModels.TaskHistoryEntryCreateModel;
import de.brasi.nova.taskmanagement.model.TaskHistoryEntryModels.TaskHistoryEntrySingleUpdateModel;
import de.brasi.nova.taskmanagement.model.TaskHistoryEntryModels.TaskHistoryEntryInfoModel;

public interface BaseTaskHistoryEntryService extends NovaHateoasService<TaskHistoryEntryModel, TaskHistoryEntryCreateModel, TaskHistoryEntrySingleUpdateModel, TaskHistoryEntryInfoModel>,
    InfoResolvingService<TaskHistoryEntryInfoModel> {


}
