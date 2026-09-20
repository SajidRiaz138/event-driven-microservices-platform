package com.sajidriaz.orderplatform.orderservice.repository;

import com.sajidriaz.orderplatform.orderservice.entity.IdempotencyKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IdempotencyKeyRepository
        extends JpaRepository<IdempotencyKeyEntity, IdempotencyKeyEntity.Key>
{
}
