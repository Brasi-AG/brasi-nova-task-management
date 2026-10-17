/*
 * Copyright (C) 2026 Keel GmbH. Alle Rechte vorbehalten.
 */
package de.brasi.nova.taskmanagement.service.provider;

import java.util.List;
import java.util.UUID;

import org.springframework.hateoas.CollectionModel;

import io.keel.framework.api.cache.IdMappingService;
import io.keel.framework.api.assembler.NovaAssembler;
import io.keel.framework.api.service.provider.BusinessCriteriaProvider;
import io.keel.framework.api.service.provider.NovaServiceProvider;

import de.brasi.nova.taskmanagement.entity.TaskNotification;
import de.brasi.nova.taskmanagement.repository.BaseTaskNotificationRepository;
import de.brasi.nova.taskmanagement.model.TaskNotificationModels.TaskNotificationModel;
import de.brasi.nova.taskmanagement.model.TaskNotificationModels.TaskNotificationCreateModel;
import de.brasi.nova.taskmanagement.model.TaskNotificationModels.TaskNotificationUpdateModel;

import lombok.NoArgsConstructor;

import de.brasi.nova.taskmanagement.entity.dictionary.NotificationSeverity;
import de.brasi.nova.taskmanagement.entity.dictionary.NotificationType;

/** Auto-generiert: BusinessCriteriaProvider-Sammlung für TaskNotification. */
public class BaseTaskNotificationProviders<T extends TaskNotificationModel> {

    public BaseTaskNotificationProviders() {}

    /** Provider: GetTaskNotificationsBySeverityIdProvider → ruft BaseTaskNotificationRepository.findTaskNotificationsBySeverityId(...) */
    @NoArgsConstructor
    public static class GetTaskNotificationsBySeverityIdProvider
            implements BusinessCriteriaProvider<CollectionModel<TaskNotificationModel>> {

        @Override
        public CollectionModel<TaskNotificationModel> execute(NovaServiceProvider services, Object[] args) throws Exception {
            UUID notificationSeverityUuid = (UUID) args[0];

            IdMappingService idMapping = services.getBean(IdMappingService.class);
            BaseTaskNotificationRepository repo = services.getRepository(BaseTaskNotificationRepository.class);
            @SuppressWarnings("unchecked")
            NovaAssembler<TaskNotificationModel, TaskNotification, TaskNotificationCreateModel, TaskNotificationUpdateModel> assembler =
                services.getAssembler(TaskNotificationModel.class, TaskNotification.class, TaskNotificationCreateModel.class, TaskNotificationUpdateModel.class);
           

            Long notificationSeverityId = idMapping.resolveUuidToId(notificationSeverityUuid.toString(), NotificationSeverity.class, null);
            List<TaskNotification> entities = repo.findTaskNotificationsBySeverityId(notificationSeverityId);

            CollectionModel<TaskNotificationModel> model = assembler.toCollectionModel(entities);
            return model;
        }
    }
    /** Provider: GetTaskNotificationsByTypeIdProvider → ruft BaseTaskNotificationRepository.findTaskNotificationsByTypeId(...) */
    @NoArgsConstructor
    public static class GetTaskNotificationsByTypeIdProvider
            implements BusinessCriteriaProvider<CollectionModel<TaskNotificationModel>> {

        @Override
        public CollectionModel<TaskNotificationModel> execute(NovaServiceProvider services, Object[] args) throws Exception {
            UUID notificationTypeUuid = (UUID) args[0];

            IdMappingService idMapping = services.getBean(IdMappingService.class);
            BaseTaskNotificationRepository repo = services.getRepository(BaseTaskNotificationRepository.class);
            @SuppressWarnings("unchecked")
            NovaAssembler<TaskNotificationModel, TaskNotification, TaskNotificationCreateModel, TaskNotificationUpdateModel> assembler =
                services.getAssembler(TaskNotificationModel.class, TaskNotification.class, TaskNotificationCreateModel.class, TaskNotificationUpdateModel.class);
           

            Long notificationTypeId = idMapping.resolveUuidToId(notificationTypeUuid.toString(), NotificationType.class, null);
            List<TaskNotification> entities = repo.findTaskNotificationsByTypeId(notificationTypeId);

            CollectionModel<TaskNotificationModel> model = assembler.toCollectionModel(entities);
            return model;
        }
    }

    // <NOVA_CLI_CUSTOM_CODE_START SECTION="Methods">
    
    // <NOVA_CLI_CUSTOM_CODE_END SECTION="Methods">
}
