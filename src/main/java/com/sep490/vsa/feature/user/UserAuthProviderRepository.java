package com.sep490.vsa.feature.user;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserAuthProviderRepository extends JpaRepository<UserAuthProvider, Long> {

    @EntityGraph(attributePaths = "user")
    Optional<UserAuthProvider> findByProviderAndProviderUserId(String provider, String providerUserId);

    boolean existsByUserAndProvider(User user, String provider);

    @Modifying
    @Query("delete from UserAuthProvider p where p.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
