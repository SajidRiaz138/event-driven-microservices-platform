package com.sajidriaz.orderplatform.paymentservice.repository;

import com.sajidriaz.orderplatform.paymentservice.entity.ProcessedMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedMessageRepository
        extends JpaRepository<ProcessedMessageEntity, ProcessedMessageEntity.Key>
{
}
