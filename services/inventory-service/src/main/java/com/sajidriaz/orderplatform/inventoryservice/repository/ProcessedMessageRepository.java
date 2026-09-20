package com.sajidriaz.orderplatform.inventoryservice.repository;

import com.sajidriaz.orderplatform.inventoryservice.entity.ProcessedMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedMessageRepository
        extends JpaRepository<ProcessedMessageEntity, ProcessedMessageEntity.Key> {
}
