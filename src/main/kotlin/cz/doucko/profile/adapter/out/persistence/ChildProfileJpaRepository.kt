package cz.doucko.profile.adapter.out.persistence

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface ChildProfileJpaRepository : JpaRepository<ChildProfileEntity, Int> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ChildProfileEntity p where p.id = :id")
    fun findForUpdate(id: Int): ChildProfileEntity?
}
