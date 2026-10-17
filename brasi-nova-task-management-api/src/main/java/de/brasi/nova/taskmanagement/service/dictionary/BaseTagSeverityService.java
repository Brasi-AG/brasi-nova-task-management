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

import de.brasi.nova.taskmanagement.service.provider.dictionary.BaseTagSeverityProviders;

import de.brasi.nova.taskmanagement.model.dictionary.TagSeverityModels.TagSeverityModel;
import de.brasi.nova.taskmanagement.model.dictionary.TagSeverityModels.TagSeverityCreateModel;
import de.brasi.nova.taskmanagement.model.dictionary.TagSeverityModels.TagSeveritySingleUpdateModel;
import de.brasi.nova.taskmanagement.model.dictionary.TagSeverityModels.TagSeverityInfoModel;

public interface BaseTagSeverityService extends NovaHateoasDictionaryService<TagSeverityModel, TagSeverityCreateModel, TagSeveritySingleUpdateModel, TagSeverityInfoModel>,
    InfoResolvingService<TagSeverityInfoModel> {


}
