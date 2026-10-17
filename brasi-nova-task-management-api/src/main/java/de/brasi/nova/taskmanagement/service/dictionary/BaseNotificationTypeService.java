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

import de.brasi.nova.taskmanagement.service.provider.dictionary.BaseNotificationTypeProviders;

import de.brasi.nova.taskmanagement.model.dictionary.NotificationTypeModels.NotificationTypeModel;
import de.brasi.nova.taskmanagement.model.dictionary.NotificationTypeModels.NotificationTypeCreateModel;
import de.brasi.nova.taskmanagement.model.dictionary.NotificationTypeModels.NotificationTypeSingleUpdateModel;
import de.brasi.nova.taskmanagement.model.dictionary.NotificationTypeModels.NotificationTypeInfoModel;

public interface BaseNotificationTypeService extends NovaHateoasDictionaryService<NotificationTypeModel, NotificationTypeCreateModel, NotificationTypeSingleUpdateModel, NotificationTypeInfoModel>,
    InfoResolvingService<NotificationTypeInfoModel> {


}
