package com.sep490.vsa.feature.user;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserAuthProviderRepository extends JpaRepository<UserAuthProvider, Long> {

    @EntityGraph(attributePaths = "user")
    Optional<UserAuthProvider> findByProviderAndProviderUserId(String provider, String providerUserId);

    boolean existsByUserAndProvider(User user, String provider);
}
