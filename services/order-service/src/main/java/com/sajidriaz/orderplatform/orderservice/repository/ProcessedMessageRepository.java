package com.sajidriaz.orderplatform.orderservice.repository;

import com.sajidriaz.orderplatform.orderservice.entity.ProcessedMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedMessageRepository
        extends JpaRepository<ProcessedMessageEntity, ProcessedMessageEntity.Key>
{
}
